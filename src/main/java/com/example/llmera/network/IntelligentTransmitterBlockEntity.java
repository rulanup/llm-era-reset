package com.example.llmera.network;

import com.example.llmera.LLMEra;
import com.example.llmera.crypto.EnvelopeCodec;
import com.example.llmera.crypto.PlaintextCodec;
import com.example.llmera.llm.ChatEngine;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

/**
 * Stores the LLM endpoint, model name, API key, and system prompt. Also caches
 * the latest conversation input/response so the conversation screen can re-render
 * after closing/reopening the menu.
 */
public class IntelligentTransmitterBlockEntity extends BlockEntity implements MenuProvider {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String TAG_MODEL_URL = "ModelUrl";
    public static final String TAG_API_KEY = "ApiKey";
    public static final String TAG_MODEL_NAME = "ModelName";
    public static final String TAG_AI_NAME = "AiName";
    public static final String TAG_SYSTEM_PROMPT = "SystemPrompt";
    public static final String TAG_LAST_MODEL_LIST = "LastModelList";
    public static final String TAG_LAST_INPUT = "LastConversationInput";
    public static final String TAG_LAST_RESPONSE = "LastConversationResponse";
    public static final String TAG_DRAFT = "ConversationDraft";
    public static final String TAG_HISTORY = "ConversationHistory";
    public static final String TAG_HISTORY_ROLE = "Role";
    public static final String TAG_HISTORY_CONTENT = "Content";
    public static final String TAG_HISTORY_TOOL_CALLS = "ToolCalls";
    public static final String TAG_HISTORY_TOOL_CALL_ID = "ToolCallId";
    public static final String TAG_HISTORY_REASONING = "Reasoning";
    public static final String TAG_TRUNCATE_ENABLED = "TruncateEnabled";
    public static final String TAG_UNLIMITED_TRUNCATION = "UnlimitedTruncation";
    public static final String TAG_SHOW_REASONING = "ShowReasoning";
    public static final String TAG_EXPAND_REASONING = "ExpandReasoning";
    public static final String TAG_VIEW_TRUNCATE_ENABLED = "ViewTruncateEnabled";
    public static final String TAG_SHOW_TOOLS = "ShowTools";
    /** Wire-only key for the transient pending question. Not persisted
     *  to disk (it loses meaning across server restarts), but it IS
     *  included in getUpdateTag so the client receives it within the
     *  BlockUpdate packet that 'question' triggers server-side. */
    public static final String TAG_PENDING_QUESTION = "PendingQuestion";
    public static final String TAG_PENDING_PROMPT = "PendingPrompt";
    public static final String TAG_PENDING_OPTIONS = "PendingOptions";
    public static final int MAX_HISTORY_TURNS = 30; // ~60 messages
    /** Hard ceiling on serialized history size to keep NBT chunk writes bounded.
     *  We measure bytes (UTF-8), not character count, because Chinese tool
     *  results inflate the JSON well past naive length(). */
    public static final int MAX_HISTORY_BYTES = 48 * 1024;
    private static final int MAX_SYNC_STRING_BYTES = 24 * 1024;
    private static final String SYNC_TRUNCATION_SUFFIX = "\n[truncated for block entity sync]";

    private static final EnvelopeCodec CODEC = new PlaintextCodec();
    private static final String DEFAULT_AI_NAME = "AI Assistant";

    private String modelUrl = "";
    private String apiKey = "";
    private String modelName = "";
    private String aiName = DEFAULT_AI_NAME;
    private String systemPrompt = "";
    private String lastModelList = "";
    private String lastConversationInput = "";
    private String lastConversationResponse = "";
    private String conversationDraft = "";
    /** When true (default), server-side truncates assistant reasoning
     *  (from the head) and tool result strings (from the head) before
     *  shipping them in the next LLM request. When false, the full text
     *  is sent — useful for very long tool outputs the model needs
     *  verbatim. Toggled at runtime by the /truncate slash command. */
    private boolean truncateEnabled = true;
    private boolean unlimitedTruncation = false;
    /** Whether the chat history shows the [AI 思考] block at all.
     *  Toggled by the /thinking slash command; default true so first-time
     *  users see the reasoning flow. */
    private boolean showReasoning = true;
    /** Whether the rendered reasoning block is the full text or just the
     *  first line with a "折叠" marker. Toggled by /expand; default false
     *  (collapsed) so the history doesn't balloon from 4 KB traces. */
    private boolean expandReasoning = false;
    /** When true (default), the client screen applies its per-turn char
     *  caps during rendering (wrapInto: 600 chars/30 lines, wrapIntoFull:
     *  5000 chars). When false, wrapInto is uncapped and wrapIntoFull uses
     *  a higher 10000-char ceiling — full enough for deep traces while
     *  still preventing pathological 200 KB blocks from freezing render.
     *  Toggled by the /viewtrunc slash command; persisted in NBT and
     *  synced to the client via the normal BlockUpdate path. */
    private boolean viewTruncateEnabled = true;
    /** Whether the chat history shows tool-call annotations ("tool: name(args)"
     *  yellow lines) and tool-result blocks ("[tool result]" green/red lines).
     *  Toggled by the /tools slash command; default true so first-time users
     *  see the agent's tool usage. When false, only the assistant's text and
     *  reasoning are rendered — tool calls still execute server-side, they're
     *  just hidden from the display. */
    private boolean showTools = true;
    /** Set by the 'question' tool to signal the agent loop should end
     *  after this turn's tool results are processed, so the next LLM
     *  call only starts when the user sends a reply. Cleared at the
     *  start of every fresh user prompt. */
    private volatile boolean awaitingUserAnswer = false;
    /** Pending question to surface as a floating picker above the chat
     *  input. Set by the 'question' tool, cleared when the user picks
     *  an option (or dismisses with Esc). Volatile + transient — not
     *  persisted in NBT because the question becomes meaningless
     *  across server restarts; if the world reloads mid-question, the
     *  user just sees the history text form instead. */
    public record PendingQuestion(String prompt, java.util.List<String> options, int seq) {}
    private volatile PendingQuestion pendingQuestion = null;
    private final java.util.List<StoredTurn> history = new java.util.ArrayList<>();
    /**
     * Per-BE agent loop stop signal. Set by the Stop button (or the 'finish'
     * tool, indirectly) and observed by the LLM worker thread via the LLM
     * engine. Volatile is enough — only one writer at a time.
     */
    private volatile boolean agentStopRequested = false;
    /** True while the LLM is between calls in an agent run. Used by the UI
     *  to show / hide the Stop button. */
    private volatile boolean agentRunning = false;
    /** Live incremental text delta from the LLM stream (current assistant
     *  turn, before it's committed to history on stream end). Volatile —
     *  written by the client netty handler, read by the screen renderer. */
    private volatile String streamingText = "";
    /** Live incremental reasoning delta from the LLM stream. */
    private volatile String streamingReasoning = "";
    /** Monotonic counter the screen uses to detect "new streaming turn started"
     *  vs "more chunks of the current turn". Increments on every stream-start
     *  / on every flush so the renderer can distinguish. */
    private volatile int streamingSeq = 0;
    private volatile int streamingRevision = 0;

    protected IntelligentTransmitterBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public EnvelopeCodec codec() { return CODEC; }

    public String getModelUrl() { return modelUrl; }
    public String getApiKey() { return apiKey; }
    public String getModelName() { return modelName; }
    public String getAiName() { return aiName; }
    public String getSystemPrompt() { return systemPrompt; }
    public String getLastModelList() { return lastModelList; }
    public String getLastConversationInput() { return lastConversationInput; }
    public String getLastConversationResponse() { return lastConversationResponse; }
    public String getConversationDraft() { return conversationDraft; }
    public boolean isTruncateEnabled() { return truncateEnabled; }
    public boolean isUnlimitedTruncation() { return unlimitedTruncation; }
    public void setTruncateEnabled(boolean v) {
        if (this.truncateEnabled == v) return;
        this.truncateEnabled = v;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
    public boolean isShowReasoning() { return showReasoning; }
    public void setShowReasoning(boolean v) {
        if (this.showReasoning == v) return;
        this.showReasoning = v;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
    public boolean isExpandReasoning() { return expandReasoning; }
    public void setExpandReasoning(boolean v) {
        if (this.expandReasoning == v) return;
        this.expandReasoning = v;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
    public boolean isViewTruncateEnabled() { return viewTruncateEnabled; }
    public void setViewTruncateEnabled(boolean v) {
        if (this.viewTruncateEnabled == v) return;
        this.viewTruncateEnabled = v;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
    public boolean isShowTools() { return showTools; }
    public void setShowTools(boolean v) {
        if (this.showTools == v) return;
        this.showTools = v;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
    public boolean isAwaitingUserAnswer() { return awaitingUserAnswer; }
    public void setAwaitingUserAnswer(boolean v) {
        this.awaitingUserAnswer = v;
        // Don't trigger setChanged/sendBlockUpdated here: the screen
        // already reflects the question as a slash-log line + history
        // entry. A block-update on every state flip would just spam
        // chunk packets.
    }
    public PendingQuestion getPendingQuestion() { return pendingQuestion; }
    public void setPendingQuestion(PendingQuestion q) {
        this.pendingQuestion = q;
        // Force a BlockUpdate so the client side BE actually receives
        // the new PendingQuestion. setChanged alone would only mark
        // the chunk dirty; on a long-running world the sync packet
        // might be coalesced and the floating picker on the client
        // would never appear. sendBlockUpdated pushes the update
        // immediately, which is what we want — the user just hit a
        // question and the picker should pop up this frame.
        if (level != null && !level.isClientSide) {
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /** Read-only view of the rolling conversation history. */
    public java.util.List<StoredTurn> getHistory() { return java.util.Collections.unmodifiableList(history); }

    /** Append a turn to the persistent history, then cap it. Triggers setChanged. */
    public void appendHistory(StoredTurn turn) {
        history.add(clampStoredTurnForSync(turn));
        // Two-layer cap: by entry count and by serialized byte size. The byte
        // cap matters for prompts like "build me a house" that would otherwise
        //  push hundreds of place tool results into history.
        while (history.size() > MAX_HISTORY_TURNS) {
            history.remove(0);
        }
        while (historySizeBytes() > MAX_HISTORY_BYTES && !history.isEmpty()) {
            history.remove(0);
        }
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    private int historySizeBytes() {
        int total = 0;
        for (StoredTurn t : history) {
            if (t.content() != null) total += t.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (t.reasoning() != null) total += t.reasoning().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (t.toolCallsJson() != null) total += t.toolCallsJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        return total;
    }

    private static StoredTurn clampStoredTurnForSync(StoredTurn turn) {
        if (turn == null) {
            return StoredTurn.assistantText("");
        }
        return new StoredTurn(
                turn.role(),
                clampStringForSync(turn.content()),
                clampStringForSync(turn.toolCallId()),
                clampStringForSync(turn.toolCallsJson()),
                clampStringForSync(turn.reasoning()));
    }

    private static void putSyncString(CompoundTag tag, String key, String value) {
        tag.putString(key, clampStringForSync(value));
    }

    private static String clampStringForSync(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= MAX_SYNC_STRING_BYTES) {
            return value;
        }
        byte[] suffixBytes = SYNC_TRUNCATION_SUFFIX.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int budget = Math.max(0, MAX_SYNC_STRING_BYTES - suffixBytes.length);
        int end = 0;
        int used = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            String chunk = new String(Character.toChars(codePoint));
            int chunkBytes = chunk.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (used + chunkBytes > budget) {
                break;
            }
            used += chunkBytes;
            end += Character.charCount(codePoint);
        }
        return value.substring(0, end) + SYNC_TRUNCATION_SUFFIX;
    }

    public void clearHistory() {
        history.clear();
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /** Client requests the server stop the running agent loop on this BE. */
    public void requestAgentStop() {
        agentStopRequested = true;
    }

    public boolean isAgentStopRequested() {
        return agentStopRequested;
    }

    public void clearAgentStop() {
        agentStopRequested = false;
    }

    public boolean isAgentRunning() { return agentRunning; }
    public void setAgentRunning(boolean v) {
        this.agentRunning = v;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /** Live streaming text accessor. The set is on the client (netty
     *  thread) and read by the screen render. No setChanged / no
     *  BlockUpdate — we want fast local updates without syncing the
     *  whole NBT to the server / other clients. */
    public String getStreamingText() { return streamingText; }
    public String getStreamingReasoning() { return streamingReasoning; }
    public int getStreamingSeq() { return streamingSeq; }
    public int getStreamingRevision() { return streamingRevision; }
    public void applyStreamingDelta(String textDelta, String reasoningDelta, int seq) {
        if (textDelta != null && !textDelta.isEmpty()) {
            this.streamingText = this.streamingText + textDelta;
        }
        if (reasoningDelta != null && !reasoningDelta.isEmpty()) {
            this.streamingReasoning = this.streamingReasoning + reasoningDelta;
        }
        this.streamingSeq = seq;
        this.streamingRevision++;
    }
    public void resetStreaming(int seq) {
        this.streamingText = "";
        this.streamingReasoning = "";
        this.streamingSeq = seq;
        this.streamingRevision++;
    }

    /**
     * One persistent turn. {@code toolCallsJson} is the pre-serialized
     * OpenAI tool_calls array (only set on assistant turns with tool calls).
     * {@code toolCallId} is the id of the tool message (only set on tool turns).
     * {@code reasoning} is the model's chain-of-thought text (only on assistant
     * turns that came from a thinking-capable model). The current LLM Era
     * v1 does not pass this back to the LLM in subsequent requests — it is
     * display-only, since most providers do not echo reasoning into a
     * follow-up call.
     */
    public record StoredTurn(String role, String content, String toolCallId, String toolCallsJson, String reasoning) {
        public static StoredTurn user(String text) { return new StoredTurn("user", text, null, null, null); }
        public static StoredTurn assistantText(String text) { return new StoredTurn("assistant", text, null, null, null); }
        public static StoredTurn assistantTextWithReasoning(String text, String reasoning) {
            return new StoredTurn("assistant", text, null, null, reasoning);
        }
        public static StoredTurn assistantToolCalls(String text, String toolCallsJson) {
            return new StoredTurn("assistant", text, null, toolCallsJson, null);
        }
        public static StoredTurn assistantToolCallsWithReasoning(String text, String toolCallsJson, String reasoning) {
            return new StoredTurn("assistant", text, null, toolCallsJson, reasoning);
        }
        public static StoredTurn toolResult(String toolCallId, String text) {
            return new StoredTurn("tool", text, toolCallId, null, null);
        }
    }

    public String getNetworkId() {
        return worldPosition.getX() + "," + worldPosition.getY() + "," + worldPosition.getZ();
    }

    /** Mirror non-sensitive config for display on network clients such as executors. */
    public void mirrorDisplayConfigFrom(IntelligentTransmitterBlockEntity source) {
        if (source == null || source == this) {
            return;
        }
        this.modelUrl = source.modelUrl;
        this.modelName = source.modelName;
        this.aiName = source.aiName;
        this.systemPrompt = source.systemPrompt;
        this.lastModelList = source.lastModelList;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /** v1 has no Blaze Burner detector. Always offline. */
    public boolean isOnline() { return false; }
    public boolean hasBlazeBurner() { return false; }

    /**
     * Used by the chat engine to hand the plain key to LlmClient. Returns the
     * empty string on codec error or unset key.
     */
    public String decryptedApiKey() {
        if (apiKey.isEmpty()) return "";
        try {
            return CODEC.decrypt(apiKey);
        } catch (RuntimeException e) {
            LOGGER.warn("[{}] apiKey decryption failed at {}", LLMEra.MODID, worldPosition);
            return null;
        }
    }

    /** Fingerprint for UI display. SHA-256 of the plain key, first 4 bytes hex. */
    public String fingerprint() {
        return fingerprintOf(decryptedApiKey() == null ? "" : decryptedApiKey());
    }

    /**
     * Applies a config update sent by the client (UpdateBlockConfigPayload).
     * Only the keys present in {@code tag} are written. {@code ApiKey} is run
     * through the envelope codec before storage.
     */
    public void applyClientData(CompoundTag tag) {
        if (tag.contains(TAG_MODEL_URL)) modelUrl = tag.getString(TAG_MODEL_URL).trim();
        if (tag.contains(TAG_API_KEY)) {
            String raw = tag.getString(TAG_API_KEY);
            apiKey = raw.isEmpty() ? "" : CODEC.encrypt(raw);
        }
        if (tag.contains(TAG_MODEL_NAME)) modelName = tag.getString(TAG_MODEL_NAME).trim();
        if (tag.contains(TAG_AI_NAME)) {
            aiName = tag.getString(TAG_AI_NAME).trim();
            if (aiName.isEmpty()) aiName = DEFAULT_AI_NAME;
        }
        if (tag.contains(TAG_SYSTEM_PROMPT)) systemPrompt = tag.getString(TAG_SYSTEM_PROMPT);
        if (tag.contains(TAG_DRAFT)) conversationDraft = tag.getString(TAG_DRAFT);
        // Boolean toggles go through the setters so the immediate
        // sendBlockUpdated fires and the client's BE mirror gets the
        // new value within the same network round-trip — without that
        // the next /truncate (etc.) would still see the stale value
        // and the toggle would appear to do nothing.
        if (tag.contains(TAG_TRUNCATE_ENABLED)) {
            setTruncateEnabled(tag.getBoolean(TAG_TRUNCATE_ENABLED));
        }
        if (tag.contains(TAG_SHOW_REASONING)) {
            setShowReasoning(tag.getBoolean(TAG_SHOW_REASONING));
        }
        if (tag.contains(TAG_EXPAND_REASONING)) {
            setExpandReasoning(tag.getBoolean(TAG_EXPAND_REASONING));
        }
        if (tag.contains(TAG_VIEW_TRUNCATE_ENABLED)) {
            setViewTruncateEnabled(tag.getBoolean(TAG_VIEW_TRUNCATE_ENABLED));
        }
        if (tag.contains(TAG_SHOW_TOOLS)) {
            setShowTools(tag.getBoolean(TAG_SHOW_TOOLS));
        }
        setChanged();
    }

    /** Called by the server when an LLM call finishes. */
    public void setLastConversationResponse(String text) {
        this.lastConversationResponse = clampStringForSync(text == null ? "" : text);
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /** Update the list of models returned by GET /v1/models. Comma-separated. */
    public void setLastModelList(String csv) {
        this.lastModelList = csv == null ? "" : csv;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void clearConversation() {
        this.lastConversationInput = "";
        this.lastConversationResponse = "";
        this.conversationDraft = "";
        clearHistory();
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /** Async chat send. Returns immediately; result lands in lastConversationResponse.
     *  The {@code initiator} is the player who sent the prompt and is used as
     *  the target of streaming delta packets so only the right screen
     *  re-renders. May be null on the server's own console / script path,
     *  in which case streaming packets are skipped. */
    public void sendConversationAsync(String prompt, ServerPlayer initiator) {
        this.lastConversationInput = prompt == null ? "" : prompt.trim();
        this.conversationDraft = "";
        if (lastConversationInput.isBlank()) {
            setLastConversationResponse("(empty prompt)");
            return;
        }
        setLastConversationResponse("Thinking...");
        ChatEngine.respondWithHistory(this, lastConversationInput, initiator);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.putString(TAG_MODEL_URL, modelUrl);
        tag.putString(TAG_API_KEY, apiKey);
        tag.putString(TAG_MODEL_NAME, modelName);
        tag.putString(TAG_AI_NAME, aiName);
        tag.putString(TAG_SYSTEM_PROMPT, systemPrompt);
        tag.putString(TAG_LAST_MODEL_LIST, lastModelList);
        putSyncString(tag, TAG_LAST_INPUT, lastConversationInput);
        putSyncString(tag, TAG_LAST_RESPONSE, lastConversationResponse);
        putSyncString(tag, TAG_DRAFT, conversationDraft);
        tag.putBoolean(TAG_TRUNCATE_ENABLED, truncateEnabled);
        tag.putBoolean(TAG_UNLIMITED_TRUNCATION, unlimitedTruncation);
        tag.putBoolean(TAG_SHOW_REASONING, showReasoning);
        tag.putBoolean(TAG_EXPAND_REASONING, expandReasoning);
        tag.putBoolean(TAG_VIEW_TRUNCATE_ENABLED, viewTruncateEnabled);
        tag.putBoolean(TAG_SHOW_TOOLS, showTools);

        net.minecraft.nbt.ListTag historyList = new net.minecraft.nbt.ListTag();
        for (StoredTurn turn : history) {
            CompoundTag tn = new CompoundTag();
            tn.putString(TAG_HISTORY_ROLE, turn.role());
            putSyncString(tn, TAG_HISTORY_CONTENT, turn.content() == null ? "" : turn.content());
            if (turn.toolCallId() != null) putSyncString(tn, TAG_HISTORY_TOOL_CALL_ID, turn.toolCallId());
            if (turn.toolCallsJson() != null) putSyncString(tn, TAG_HISTORY_TOOL_CALLS, turn.toolCallsJson());
            if (turn.reasoning() != null && !turn.reasoning().isEmpty()) {
                putSyncString(tn, TAG_HISTORY_REASONING, turn.reasoning());
            }
            historyList.add(tn);
        }
        tag.put(TAG_HISTORY, historyList);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        modelUrl = tag.getString(TAG_MODEL_URL);
        apiKey = tag.getString(TAG_API_KEY);
        modelName = tag.getString(TAG_MODEL_NAME);
        aiName = tag.getString(TAG_AI_NAME);
        if (aiName.isBlank()) aiName = DEFAULT_AI_NAME;
        systemPrompt = tag.getString(TAG_SYSTEM_PROMPT);
        lastModelList = tag.getString(TAG_LAST_MODEL_LIST);
        lastConversationInput = tag.getString(TAG_LAST_INPUT);
        lastConversationResponse = tag.getString(TAG_LAST_RESPONSE);
        conversationDraft = tag.getString(TAG_DRAFT);
        // Default to true when the field is missing so a freshly loaded
        // pre-feature save doesn't suddenly disable truncation.
        truncateEnabled = tag.contains(TAG_TRUNCATE_ENABLED)
                ? tag.getBoolean(TAG_TRUNCATE_ENABLED) : true;
        unlimitedTruncation = tag.contains(TAG_UNLIMITED_TRUNCATION)
                ? tag.getBoolean(TAG_UNLIMITED_TRUNCATION) : false;
        showReasoning = tag.contains(TAG_SHOW_REASONING)
                ? tag.getBoolean(TAG_SHOW_REASONING) : true;
        expandReasoning = tag.contains(TAG_EXPAND_REASONING)
                ? tag.getBoolean(TAG_EXPAND_REASONING) : false;
        viewTruncateEnabled = tag.contains(TAG_VIEW_TRUNCATE_ENABLED)
                ? tag.getBoolean(TAG_VIEW_TRUNCATE_ENABLED) : true;
        showTools = tag.contains(TAG_SHOW_TOOLS)
                ? tag.getBoolean(TAG_SHOW_TOOLS) : true;

        history.clear();
        if (tag.contains(TAG_HISTORY)) {
            net.minecraft.nbt.ListTag historyList = tag.getList(TAG_HISTORY, 10);
            for (int i = 0; i < historyList.size(); i++) {
                CompoundTag tn = historyList.getCompound(i);
                history.add(new StoredTurn(
                        tn.getString(TAG_HISTORY_ROLE),
                        tn.getString(TAG_HISTORY_CONTENT),
                        tn.contains(TAG_HISTORY_TOOL_CALL_ID) ? tn.getString(TAG_HISTORY_TOOL_CALL_ID) : null,
                        tn.contains(TAG_HISTORY_TOOL_CALLS) ? tn.getString(TAG_HISTORY_TOOL_CALLS) : null,
                        tn.contains(TAG_HISTORY_REASONING) ? tn.getString(TAG_HISTORY_REASONING) : null));
            }
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, provider);
        // Overlay the transient pending question so the client
        // receives it as part of the BlockUpdate packet. saveAdditional
        // deliberately skips this field (it isn't NBT-persisted), so
        // we add it here at the wire-format layer only.
        PendingQuestion pq = pendingQuestion;
        if (pq != null) {
            putSyncString(tag, TAG_PENDING_PROMPT, pq.prompt());
            net.minecraft.nbt.ListTag opts = new net.minecraft.nbt.ListTag();
            for (String o : pq.options()) {
                opts.add(net.minecraft.nbt.StringTag.valueOf(clampStringForSync(o)));
            }
            tag.put(TAG_PENDING_OPTIONS, opts);
            tag.putBoolean(TAG_PENDING_QUESTION, true);
        }
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection conn, ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider provider) {
        // The base no-op super call would leave the client-side copy of
        // this BE holding the original (pre-BlockUpdate) field values,
        // so /truncate and friends would toggle server-side correctly
        // but the client mirror would stay stale. Read the packet's NBT
        // through loadAdditional so client fields reflect what the
        // server just persisted.
        super.onDataPacket(conn, pkt, provider);
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            loadAdditional(tag, provider);
            // Pick up the wire-only pending question. saveAdditional
            // doesn't touch it; we set it directly here. The seq field
            // is the current render revision so the screen can detect
            // a change and refresh the picker highlight.
            if (tag.getBoolean(TAG_PENDING_QUESTION)) {
                String prompt = tag.getString(TAG_PENDING_PROMPT);
                java.util.List<String> options = new java.util.ArrayList<>();
                if (tag.contains(TAG_PENDING_OPTIONS)) {
                    for (net.minecraft.nbt.Tag t : tag.getList(TAG_PENDING_OPTIONS, 8)) {
                        options.add(t.getAsString());
                    }
                }
                this.pendingQuestion = new PendingQuestion(prompt,
                        java.util.Collections.unmodifiableList(options), 0);
            } else {
                // Server sent an update with no pending question —
                // clear ours too. Done in respondWithHistory too, but
                // this covers the "user clicked Esc / picked an
                // option" path where the server's null propagates.
                this.pendingQuestion = null;
            }
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.llmera.intelligent_transmitter");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
        return new IntelligentTransmitterMenu(containerId, playerInv, worldPosition, false);
    }

    public static String fingerprintOf(String key) {
        if (key == null || key.isEmpty()) return "";
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}

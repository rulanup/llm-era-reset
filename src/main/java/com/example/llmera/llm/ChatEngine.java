package com.example.llmera.llm;

import com.example.llmapi.api.ApiResult;
import com.example.llmapi.api.BlockApi;
import com.example.llmera.LLMEra;
import com.example.llmera.config.LLMEraConfig;
import com.example.llmera.network.IntelligentTransmitterBlockEntity;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import com.example.llmera.packet.ModPackets;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Drives the LLM call + tool-call loop on the server.
 *
 * <p>v1 advertises three tools:
 * <ul>
 *   <li>{@code noop} — echoes its {@code text} argument (protocol smoke test)</li>
 *   <li>{@code place} — calls {@code BlockApi.place} at the given coordinates</li>
 *   <li>{@code read_block} — calls {@code BlockApi.readNbt} on the given block entity</li>
 * </ul>
 * Tools run on the main thread via {@code server.execute}. Loop capped at 3 rounds.
 */
public final class ChatEngine {
    private static final Gson GSON = new Gson();

    /** Hard cap on tool calls executed per LLM turn. Anything above this is
     *  silently dropped from the history and the LLM is told why. Keeps
     *  memory bounded for prompts like "build me a house" that would
     *  otherwise emit hundreds of place tool calls. */
    private static final int MAX_TOOLS_PER_TURN = 10;
    private static final int MAX_TOOL_RESULT_CHARS = 256;
    private static final int MAX_REASONING_CHARS = 4000;

    private static final ToolDefinition NOOP_TOOL = new ToolDefinition(
            "noop",
            "Echoes the 'text' argument back. v1 placeholder used to verify the tool_call protocol.",
            Map.of(
                    "text", Map.of("type", "string", "description", "text to echo")
            )
    );

    private static final ToolDefinition PLACE_TOOL = new ToolDefinition(
            "place",
            "Place a SOLID block at integer coordinates (x, y, z) in the transmitter's dimension. "
                    + "x/y/z are required; block is a block id like 'minecraft:stone' (NOT 'minecraft:air' "
                    + "— to remove a block use break_block instead). "
                    + "replace (default true) controls whether non-air target cells may be overwritten.",
            Map.of(
                    "x", Map.of("type", "integer", "description", "x coordinate"),
                    "y", Map.of("type", "integer", "description", "y coordinate"),
                    "z", Map.of("type", "integer", "description", "z coordinate"),
                    "block", Map.of("type", "string", "description", "block id, e.g. minecraft:stone"),
                    "replace", Map.of("type", "boolean", "description", "overwrite non-air (default true)")
            )
    );

    private static final ToolDefinition READ_BLOCK_TOOL = new ToolDefinition(
            "read_block",
            "Read the block at integer coordinates (x, y, z). Returns block id and (if present) NBT as SNBT. "
                    + "Use this before placing or breaking to confirm the target cell.",
            Map.of(
                    "x", Map.of("type", "integer", "description", "x coordinate"),
                    "y", Map.of("type", "integer", "description", "y coordinate"),
                    "z", Map.of("type", "integer", "description", "z coordinate")
            )
    );

    /** Edit the block-entity NBT at integer coordinates. The 'mode' arg
     *  selects the merge strategy (write = overwrite the value at 'path',
     *  merge = recursive merge into the existing compound, replace =
     *  replace the whole BE tag). 'path' uses Minecraft's /data path
     *  syntax (empty = the root compound). 'value' is an SNBT snippet
     *  matching the type at the path (a quoted string, a number, a
     *  compound body in {...}, etc.). Runs on the main server thread,
     *  no chunk force-load.
     *
     *  REMOVED: write_block_nbt was pulled in an earlier rev because
     *  the underlying NbtPatch path-API silently corrupts container
     *  arrays on path=Items[N].Count-style writes (replace semantics
     *  on a single index sub-element destroys the rest of the list).
     *  Fixing it properly needs a full SNBT modify command (read,
     *  edit, write the parent) which the existing read_block +
     *  /data style ops cover better. For now the mod ships without
     *  block-entity NBT writes; the LLM can only read. */
    // (intentionally empty — see comment above)

    private static final ToolDefinition BREAK_BLOCK_TOOL = new ToolDefinition(
            "break_block",
            "Remove the block at integer coordinates (x, y, z) in the transmitter's dimension, "
                    + "leaving air. Use this to clear a cell. Coordinates are absolute world coordinates.",
            Map.of(
                    "x", Map.of("type", "integer", "description", "x coordinate"),
                    "y", Map.of("type", "integer", "description", "y coordinate"),
                    "z", Map.of("type", "integer", "description", "z coordinate")
            )
    );

    /**
     * Agent-loop terminator. The model calls this when it has completed the
     * user's task (or has decided the task is impossible). Calling finish ends
     * the agent loop; without it, the loop continues until {@code maxAgentTurns}.
     */
    private static final ToolDefinition FINISH_TOOL = new ToolDefinition(
            "finish",
            "Call this when you have completed the user's task or have decided the task "
                    + "is impossible. Provide a short summary in 'reason' so the user can "
                    + "see what you accomplished. The agent loop ends on this call.",
            Map.of(
                    "reason", Map.of("type", "string", "description", "short summary of what you did")
            )
    );

    /** Ask the user a question and pause the agent loop until they reply.
     *  Use this whenever the task is ambiguous, a key design choice
     *  could go several ways, or you need the user to pick between
     *  options you can't infer from context. 'prompt' is the actual
     *  question (keep it short). 'options' is an optional list of
     *  suggested answers — an implicit 'other' option (free-form text
     *  input) is ALWAYS appended server-side, so you do not need to
     *  list it yourself. The agent loop ends on this call: the next
     *  LLM turn only starts once the user sends a message. */
    private static final ToolDefinition QUESTION_TOOL = new ToolDefinition(
            "question",
            "Pause and ask the user a question. The agent loop ends; the next LLM turn "
                    + "starts only after the user types a reply and hits Enter. Use this for "
                    + "ambiguous tasks, design choices, or whenever you'd otherwise guess. "
                    + "'prompt' is the question itself. 'options' is an OPTIONAL list of "
                    + "suggested replies; an 'other' (free-form) option is always added for you, "
                    + "so do not list it explicitly.",
            Map.of(
                    "prompt", Map.of("type", "string",
                            "description", "the question to ask the user. Plain text, one or two sentences."),
                    "options", Map.of("type", "array", "items", Map.of("type", "string"),
                            "description", "optional list of suggested answers the user can pick from. "
                                    + "Each entry should be a short, distinct choice. Omit the field to "
                                    + "let the user type a free-form answer. 'other' is added for you; "
                                    + "do not list it.")
            )
    );

    /** Look up a registered Minecraft item/block by ID via JEI (Just
     *  Enough Items) reflection. Returns the JEI-known display name,
     *  owning mod id, and a short description (registry name, item
     *  class, basic tags). Use this when the model needs to verify a
     *  block is real, get its localized name, or learn which mod it
     *  comes from. The call is reflection-only: the mod has no hard
     *  dependency on JEI, so if JEI is missing the tool result just
     *  says so and the LLM moves on. */
    private static final ToolDefinition JEI_LOOKUP_TOOL = new ToolDefinition(
            "jei_lookup",
            "Look up a registered item/block by its Minecraft ID (e.g. 'minecraft:stone' or "
                    + "'create:mechanical_piston') via the Just Enough Items mod. Returns the "
                    + "display name, owning mod, and a few registry facts. The lookup is via "
                    + "reflection, so it works whether or not JEI is installed — if JEI is "
                    + "missing, the result is an error message instead of a crash.",
            Map.of(
                    "block_id", Map.of("type", "string",
                            "description", "the registry id to look up, in 'namespace:path' form. "
                                    + "Example: 'minecraft:oak_planks', 'create:mechanical_piston'. "
                                    + "For block items prefer the item id (which JEI registers).")
            )
    );

    /** Search the public web on the server. Backed by three providers:
     *  Bing's HTML endpoint (default, reachable from CN), DuckDuckGo's
     *  HTML endpoint (no key), and Wikipedia's OpenSearch API (last-resort
     *  fallback or 'wikipedia' source). The call is fire-and-forget from
     *  the LLM's point of view: it submits the query, parses the result
     *  page, and returns a small JSON envelope with up to ~8
     *  title/url/snippet rows. Use this when the LLM needs facts the
     *  model itself can't reliably produce (recipes, real-world prices,
     *  current mods, etc.). */
    private static final ToolDefinition WEB_SEARCH_TOOL = new ToolDefinition(
            "web_search",
            "Search the public web and return up to 8 result rows (title, url, snippet). "
                    + "Useful when the user asks a question the model can't answer from its "
                    + "training data alone (e.g. 'what is the latest Mekanism version?', 'price of X'). "
                    + "Backed by Bing, DuckDuckGo and Wikipedia — no API key needed. 'source' is "
                    + "'auto' (Bing, fallback to DDG then Wikipedia), 'bing', 'duckduckgo', or "
                    + "'wikipedia'. 'max_results' (1..8) defaults to 5.",
            Map.of(
                    "query", Map.of("type", "string",
                            "description", "the search query. Plain text, no URL encoding needed."),
                    "source", Map.of("type", "string",
                            "description", "optional. 'auto' (default) tries Bing then DuckDuckGo then "
                                    + "Wikipedia. 'bing' / 'duckduckgo' / 'wikipedia' force one provider."),
                    "max_results", Map.of("type", "integer",
                            "description", "optional. 1..8, default 5. Higher counts return more rows "
                                    + "but inflate the tool-result string and may exceed the "
                                    + "truncation cap.")
            )
    );

    /**
     * JSON Schema fragment for a single place entry. OpenAI function-calling
     * accepts nested object schemas via the standard JSON Schema keywords
     * (type / properties / required). We share the snippet between
     * place_many's array items and break_many's array items.
     */
    private static final Map<String, Object> PLACE_ITEM_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "x", Map.of("type", "integer", "description", "absolute x coordinate"),
                    "y", Map.of("type", "integer", "description", "absolute y coordinate"),
                    "z", Map.of("type", "integer", "description", "absolute z coordinate"),
                    "block", Map.of("type", "string", "description", "block id, e.g. minecraft:stone"),
                    "replace", Map.of("type", "boolean", "description", "overwrite non-air (default true)")
            ),
            "required", List.of("x", "y", "z", "block")
    );

    private static final Map<String, Object> BREAK_ITEM_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "x", Map.of("type", "integer", "description", "absolute x coordinate"),
                    "y", Map.of("type", "integer", "description", "absolute y coordinate"),
                    "z", Map.of("type", "integer", "description", "absolute z coordinate")
            ),
            "required", List.of("x", "y", "z")
    );

    /**
     * Batch place tool. Lets the model place up to MAX_BATCH_SIZE blocks in a
     * single round-trip, instead of one tool call per block. Without this
     * the LLM is rate-limited to MAX_TOOLS_PER_TURN = 10 per turn, so even
     * a small 5x5 house takes many turns to build.
     */
    private static final ToolDefinition PLACE_MANY_TOOL = new ToolDefinition(
            "place_many",
            "Place multiple SOLID blocks in a single call. PREFER THIS over 'place' when "
                    + "you need to set several blocks (e.g. building a wall, floor, or full "
                    + "structure). Pass an array 'blocks' of {x,y,z,block,replace?}; each entry "
                    + "becomes one BlockApi.place. Max 64 entries per call. Coordinates are "
                    + "absolute world coordinates in the transmitter's dimension. Do NOT include "
                    + "'minecraft:air' here — use break_many to clear cells.",
            Map.of(
                    "blocks", Map.of(
                            "type", "array",
                            "description", "list of {x,y,z,block,replace?} entries to place",
                            "items", PLACE_ITEM_SCHEMA,
                            "minItems", 1
                    )
            )
    );

    /**
     * Batch break tool. Mirrors place_many but for removal. Up to 64 cells
     * per call, all become air.
     */
    private static final ToolDefinition BREAK_MANY_TOOL = new ToolDefinition(
            "break_many",
            "Remove (set to air) multiple blocks in a single call. PREFER THIS over "
                    + "'break_block' when clearing several cells. Pass an array 'blocks' of "
                    + "{x,y,z} entries. Max 64 entries per call. Coordinates are absolute "
                    + "world coordinates in the transmitter's dimension.",
            Map.of(
                    "blocks", Map.of(
                            "type", "array",
                            "description", "list of {x,y,z} entries to clear",
                            "items", BREAK_ITEM_SCHEMA,
                            "minItems", 1
                    )
            )
    );

    /** Cap per batch tool call. Big enough for a small house (5x5 walls = 60 blocks). */
    private static final int MAX_BATCH_SIZE = 64;

    private static final List<ToolDefinition> TOOLS = List.of(
            NOOP_TOOL, READ_BLOCK_TOOL, PLACE_TOOL, BREAK_BLOCK_TOOL,
            PLACE_MANY_TOOL, BREAK_MANY_TOOL, QUESTION_TOOL, JEI_LOOKUP_TOOL,
            WEB_SEARCH_TOOL, FINISH_TOOL);

    /** Public read-only view of the tool list. Used by the mod's startup
     *  dump and by other callers that want to enumerate what the LLM can see. */
    public static List<ToolDefinition> advertisedTools() {
        return java.util.Collections.unmodifiableList(TOOLS);
    }

    /** Marker exception thrown by {@link StreamingSink#checkStop} when the
     *  user hits /stop mid-stream. We use a checked-style RuntimeException
     *  (not a checked exception) because the StreamListener interface is
     *  pre-1.8 with no throws clause, and the LlmClient stream() method
     *  wraps everything in a RuntimeException anyway. The downstream
     *  whenComplete handler checks for this class to suppress the
     *  generic "(error) ..." surface. */
    public static final class AgentStopException extends RuntimeException {
        public AgentStopException() { super("agent stopped by user"); }
    }

    public static void respond(IntelligentTransmitterBlockEntity t, String userText) {
        respondWithHistory(t, userText, null);
    }

    /**
     * Like {@link #respond} but reads the persistent history from the block
     * entity so the LLM sees the rolling conversation. After each LLM
     * response, the assistant turn and any tool results are appended back to
     * the block entity's history (capped at MAX_HISTORY_TURNS).
     */
    public static void respondWithHistory(IntelligentTransmitterBlockEntity t, String userText, ServerPlayer initiator) {
        // Reset the agent-loop stop flag at the start of every user request.
        t.clearAgentStop();
        // Clear any 'awaiting user answer' from a previous 'question' tool
        // call — we're getting a fresh user message now, so the loop can
        // re-enter the recursive runLoop path. Also clear the pending
        // question picker so the floating card disappears once the user
        // has answered (even if the answer came from a free-text send,
        // not a picker selection). We push a fresh BlockUpdate after
        // this so the client's BE mirror sees the null promptly.
        t.setAwaitingUserAnswer(false);
        t.setPendingQuestion(null);
        t.setAgentRunning(true);
        respondInternal(t, userText, initiator);
    }

    private static void respondInternal(IntelligentTransmitterBlockEntity t, String userText, ServerPlayer initiator) {
        String devKey = LLMEraConfig.DEV_API_KEY.get();
        String endpoint;
        String modelName;
        String apiKey;
        if (devKey != null && !devKey.isBlank()) {
            apiKey = devKey;
            endpoint = LLMEraConfig.DEV_ENDPOINT.get();
            modelName = LLMEraConfig.DEV_MODEL_NAME.get();
        } else {
            apiKey = t.decryptedApiKey();
            if (apiKey == null || apiKey.isEmpty()) {
                LLMEra.LOGGER.warn("[{}] ChatEngine turn aborted: no api key set on transmitter {}",
                        LLMEra.MODID, t.getBlockPos());
                t.setLastConversationResponse("(no api key set)");
                return;
            }
            endpoint = t.getModelUrl();
            modelName = t.getModelName();
        }
        if (endpoint == null || endpoint.isEmpty() || modelName == null || modelName.isEmpty()) {
            LLMEra.LOGGER.warn("[{}] ChatEngine turn aborted: endpoint or model empty (endpoint='{}' model='{}')",
                    LLMEra.MODID, endpoint, modelName);
            t.setLastConversationResponse("(endpoint or model not set)");
            return;
        }
        String systemPrompt = t.getSystemPrompt().isEmpty()
                ? LLMEraConfig.DEFAULT_SYSTEM_PROMPT.get()
                : t.getSystemPrompt();
        int timeout = LLMEraConfig.REQUEST_TIMEOUT_SECONDS.get();

        // Build the request payload: system + BE history + new user turn.
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        for (IntelligentTransmitterBlockEntity.StoredTurn turn : t.getHistory()) {
            if ("user".equals(turn.role())) {
                messages.add(ChatMessage.user(turn.content()));
            } else if ("assistant".equals(turn.role())) {
                if (turn.toolCallsJson() != null && !turn.toolCallsJson().isEmpty()) {
                    messages.add(ChatMessage.assistantWithToolCalls(turn.content(), turn.toolCallsJson()));
                } else {
                    messages.add(ChatMessage.assistant(turn.content()));
                }
            } else if ("tool".equals(turn.role())) {
                messages.add(ChatMessage.tool(turn.toolCallId(), turn.content()));
            }
        }
        // Append the user turn to BOTH the in-flight request AND the
        // persistent history. Without this the history would only ever
        // contain assistant / tool turns and the player's input would never
        // appear in the chat view.
        messages.add(ChatMessage.user(userText));
        t.appendHistory(IntelligentTransmitterBlockEntity.StoredTurn.user(userText));

        runLoop(t, apiKey, endpoint, modelName, messages, TOOLS, timeout, LLMEraConfig.MAX_AGENT_TURNS.get(), initiator);
    }

    private static final java.util.concurrent.atomic.AtomicInteger STREAM_SEQ_GEN = new java.util.concurrent.atomic.AtomicInteger(0);
    /** Flush thresholds for the streaming sink. Sends a packet to the
     *  client either when the buffered text+reasoning delta reaches
     *  {@link #FLUSH_CHARS} characters or when
     *  {@link #FLUSH_INTERVAL_MS} have elapsed since the last flush. */
    private static final int FLUSH_CHARS = 50;
    private static final long FLUSH_INTERVAL_MS = 100L;

    /** Buffers LLM stream deltas and sends a {@link ModPackets.StreamDeltaPayload}
     *  to the originating player. Threaded-safe because LlmClient.stream()
     *  calls onContent / onReasoning from the async HTTP thread. */
    static final class StreamingSink implements LlmClient.StreamListener {
        private final ServerPlayer player;
        private final IntelligentTransmitterBlockEntity t;
        private final int seq;
        private final BlockPos pos;
        private final StringBuilder textBuf = new StringBuilder();
        private final StringBuilder reasoningBuf = new StringBuilder();
        private volatile long lastFlushMs = System.currentTimeMillis();
        private final java.util.concurrent.atomic.AtomicBoolean scheduled = new java.util.concurrent.atomic.AtomicBoolean(false);
        private volatile boolean finished = false;

        StreamingSink(ServerPlayer player, IntelligentTransmitterBlockEntity t, int seq) {
            this.player = player;
            this.t = t;
            this.seq = seq;
            this.pos = t.getBlockPos();
        }

        @Override public void onContent(String chunk) {
            if (chunk == null || chunk.isEmpty()) return;
            checkStop();
            textBuf.append(chunk);
            maybeFlush(false);
        }

        @Override public void onReasoning(String chunk) {
            if (chunk == null || chunk.isEmpty()) return;
            checkStop();
            reasoningBuf.append(chunk);
            maybeFlush(false);
        }

        /** Abort the in-flight stream when the user has hit /stop. The
         *  HTTP body is closed by HttpClient when the iterator is
         *  abandoned, so this also kills the upstream request and stops
         *  burning tokens. The exception propagates back through
         *  LlmClient.stream() and surfaces in runLoop's whenComplete,
         *  which already has a stop-handling branch. */
        private void checkStop() {
            if (t.isAgentStopRequested()) {
                throw new AgentStopException();
            }
        }

        private void maybeFlush(boolean force) {
            int pending = textBuf.length() + reasoningBuf.length();
            long since = System.currentTimeMillis() - lastFlushMs;
            if (!force && pending < FLUSH_CHARS && since < FLUSH_INTERVAL_MS) {
                return;
            }
            if (player == null) {
                // No client to send to (script / console path). Drop buffer.
                textBuf.setLength(0);
                reasoningBuf.setLength(0);
                lastFlushMs = System.currentTimeMillis();
                return;
            }
            String tDelta = textBuf.toString();
            String rDelta = reasoningBuf.toString();
            textBuf.setLength(0);
            reasoningBuf.setLength(0);
            lastFlushMs = System.currentTimeMillis();
            PacketDistributor.sendToPlayer(player, new ModPackets.StreamDeltaPayload(pos, seq, tDelta, rDelta));
        }

        /** Called once on stream end. Sends any remaining buffer as a final
         *  {@link ModPackets.StreamDeltaPayload} plus a
         *  {@link ModPackets.StreamEndPayload} to mark the turn done. */
        void finish() {
            finished = true;
            maybeFlush(true);
            if (player != null) {
                // The final accumulated text lives in the BE's streamingText
                // (kept in sync by the deltas) and the just-shipped deltas
                // cover anything still buffered at stream-end. StreamEnd
                // here is mostly a signal for the client to clear the live
                // streaming state.
                PacketDistributor.sendToPlayer(player, new ModPackets.StreamEndPayload(pos, seq, "", ""));
            }
        }

        int seq() { return seq; }
    }

    private static void runLoop(IntelligentTransmitterBlockEntity t, String apiKey, String endpoint, String modelName,
                                 List<ChatMessage> messages, List<ToolDefinition> tools, int timeout, int roundsLeft,
                                 ServerPlayer initiator) {
        if (roundsLeft <= 0) {
            t.setLastConversationResponse("(agent loop limit reached)");
            t.setAgentRunning(false);
            return;
        }
        if (t.isAgentStopRequested()) {
            // Stop was requested (client clicked Stop, or the previous
            // turn hit an unrecoverable error). Don't make another LLM call.
            t.setAgentRunning(false);
            return;
        }
        int streamSeq = STREAM_SEQ_GEN.incrementAndGet();
        StreamingSink sink = new StreamingSink(initiator, t, streamSeq);
        new LlmClient().stream(endpoint, modelName, apiKey, messages, tools, timeout, LLMEraConfig.THINKING_BUDGET.get(), sink)
                .whenComplete((resp, err) -> {
                    MinecraftServer srv = t.getLevel() instanceof ServerLevel sl ? sl.getServer() : null;
                    if (srv == null) {
                        // No main thread we can use. Best-effort: still notify
                        // the client that the stream is over (the client
                        // renderer is what users see, not the BE fields) and
                        // surface a friendly error. Same /stop handling
                        // as the happy path so the abort doesn't end up
                        // looking like a server crash.
                        try { sink.finish(); } catch (Exception ignored) {}
                        if (err instanceof AgentStopException
                                || (err != null && err.getCause() instanceof AgentStopException)) {
                            t.setLastConversationResponse("[已停止]");
                        } else {
                            t.setLastConversationResponse("(server unavailable)");
                        }
                        t.setAgentRunning(false);
                        return;
                    }
                    srv.execute(() -> {
                        // try-finally guarantees the client always sees a
                        // StreamEnd even if the BE work throws. The screen
                        // relies on receiving an end marker to clear the
                        // "live" streaming line and fall back to history.
                        try {
                        if (err != null) {
                            // A user-driven /stop abort throws through
                            // the StreamListener and surfaces here.
                            // Don't paint it as an error in the chat
                            // history — record a short note and exit
                            // cleanly.
                            if (err instanceof AgentStopException
                                    || (err.getCause() instanceof AgentStopException)) {
                                t.setLastConversationResponse("[已停止]");
                                t.setAgentRunning(false);
                                return;
                            }
                            LLMEra.LOGGER.warn(
                                    "[{}] LLM call failed: {}",
                                    LLMEra.MODID, err.getMessage());
                            t.setLastConversationResponse("(error) " + err.getMessage());
                            t.setAgentRunning(false);
                            return;
                        }
                        if (!resp.hasToolCalls()) {
                            // No tool calls and the model didn't call finish. The agent
                            // loop is over (model produced its final answer). Persist the
                            // assistant turn and surface the reply.
                            String truncatedReasoning = truncateReasoning(resp.reasoning(), t);
                            IntelligentTransmitterBlockEntity.StoredTurn finalTurn = !truncatedReasoning.isEmpty()
                                    ? IntelligentTransmitterBlockEntity.StoredTurn
                                            .assistantTextWithReasoning(resp.text(), truncatedReasoning)
                                    : IntelligentTransmitterBlockEntity.StoredTurn.assistantText(resp.text());
                            t.appendHistory(finalTurn);
                            t.setLastConversationResponse(resp.text());
                            t.setAgentRunning(false);
                            return;
                        }
                        // Cap tool calls per turn. Anything above MAX_TOOLS_PER_TURN
                        // is dropped from the in-flight request and the history; the
                        // LLM is told via a synthetic tool result for each dropped call
                        // so the next turn self-corrects.
                        java.util.List<ToolInvocation> calls = resp.toolCalls();
                        java.util.List<ToolInvocation> accepted = calls.size() > MAX_TOOLS_PER_TURN
                                ? calls.subList(0, MAX_TOOLS_PER_TURN)
                                : calls;
                        java.util.List<ToolInvocation> dropped = calls.size() > MAX_TOOLS_PER_TURN
                                ? calls.subList(MAX_TOOLS_PER_TURN, calls.size())
                                : java.util.List.of();
                        // Build the assistant turn with tool_calls in the exact shape
                        // OpenAI expects: [{id, type:"function", function:{name, arguments:str}}].
                        LlmResponse trimmedResp = new LlmResponse(resp.text(), truncateReasoning(resp.reasoning(), t), accepted);
                        // If the model called 'finish' on this turn and didn't
                        // write any text, surface the finish reason as the
                        // visible assistant message so it shows up in the UI.
                        if (trimmedResp.text() == null || trimmedResp.text().isBlank()) {
                            for (ToolInvocation inv : trimmedResp.toolCalls()) {
                                if ("finish".equals(inv.name())) {
                                    Object reason = inv.arguments().get("reason");
                                    if (reason != null && !reason.toString().isBlank()) {
                                        trimmedResp = new LlmResponse("[finish] " + reason,
                                                trimmedResp.reasoning(), trimmedResp.toolCalls());
                                    }
                                    break;
                                }
                            }
                        }
                        ChatMessage assistant = assistantToolCallMessage(trimmedResp);
                        String tcJson = gson().toJson(serializeToolCalls(trimmedResp));
                        IntelligentTransmitterBlockEntity.StoredTurn assistantTurn = !trimmedResp.hasReasoning()
                                ? toStoredTurn(assistant)
                                : IntelligentTransmitterBlockEntity.StoredTurn
                                        .assistantToolCallsWithReasoning(trimmedResp.text(), tcJson, trimmedResp.reasoning());
                        t.appendHistory(assistantTurn);
                        // Add to in-flight messages for the next round.
                        messages.add(assistant);
                        for (ToolInvocation inv : accepted) {
                            String result = truncateToolResult(executeTool(t, inv), t);
                            ChatMessage toolMsg = ChatMessage.tool(inv.id(), result);
                            t.appendHistory(toStoredTurn(toolMsg));
                            messages.add(toolMsg);
                        }
                        // Inject synthetic replies for dropped tool calls so the LLM
                        // understands its batch was truncated. Each dropped call
                        // gets a placeholder response; we DO NOT persist these to
                        // history (they would explode storage).
                        for (ToolInvocation droppedInv : dropped) {
                            String placeholder = "{\"error\":\"dropped: per-turn tool cap of "
                                    + MAX_TOOLS_PER_TURN + " reached; please call this tool again\"}";
                            messages.add(ChatMessage.tool(droppedInv.id(), truncateToolResult(placeholder, t)));
                        }
                        // Re-check the stop flag in case Stop was clicked while we
                        // were waiting for the LLM. The flag is also set by the
                        // 'finish' tool above so a finish-only turn exits cleanly.
                        if (t.isAgentStopRequested()) {
                            t.setAgentRunning(false);
                            return;
                        }
                        runLoop(t, apiKey, endpoint, modelName, messages, tools, timeout, roundsLeft - 1, initiator);
                        } finally {
                            // Always close out the stream so the client
                            // renderer's "live" line goes away, even on
                            // error or stop.
                            try { sink.finish(); } catch (Exception ignored) {}
                        }
                    });
                });
    }

    private static com.google.gson.Gson gson() {
        return new com.google.gson.Gson();
    }

    private static java.util.List<java.util.Map<String, Object>> serializeToolCalls(LlmResponse resp) {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (ToolInvocation inv : resp.toolCalls()) {
            java.util.Map<String, Object> fn = new java.util.LinkedHashMap<>();
            fn.put("name", inv.name() == null ? "" : inv.name());
            fn.put("arguments", gson().toJson(inv.arguments()));
            java.util.Map<String, Object> tc = new java.util.LinkedHashMap<>();
            tc.put("id", inv.id() == null ? "" : inv.id());
            tc.put("type", "function");
            tc.put("function", fn);
            out.add(tc);
        }
        return out;
    }

    /** Convert a transient ChatMessage into the NBT-persistent StoredTurn. */
    private static IntelligentTransmitterBlockEntity.StoredTurn toStoredTurn(ChatMessage m) {
        return switch (m.role()) {
            case "user" -> IntelligentTransmitterBlockEntity.StoredTurn.user(m.content());
            case "tool" -> IntelligentTransmitterBlockEntity.StoredTurn.toolResult(m.toolCallId(), m.content());
            default -> // assistant
                m.toolCallsJson() == null || m.toolCallsJson().isEmpty()
                        ? IntelligentTransmitterBlockEntity.StoredTurn.assistantText(m.content())
                        : IntelligentTransmitterBlockEntity.StoredTurn.assistantToolCalls(m.content(), m.toolCallsJson());
        };
    }

    private static String executeTool(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        // Anthropic models occasionally wrap their tool calls in a synthetic
        // "multi_tool_use.parallel" router. The arguments carry a
        // "tool_uses" array of {recipient_name, parameters} entries. We
        // unwrap and dispatch each entry to its real handler, then return
        // a combined JSON result so the LLM can read both outcomes.
        if ("multi_tool_use.parallel".equals(inv.name())) {
            return runMultiToolUseParallel(t, inv);
        }
        return switch (inv.name()) {
            case "noop" -> runNoop(inv);
            case "place" -> runPlace(t, inv);
            case "read_block" -> runReadBlock(t, inv);
            case "break_block" -> runBreakBlock(t, inv);
            case "place_many" -> runPlaceMany(t, inv);
            case "break_many" -> runBreakMany(t, inv);
            case "question" -> runQuestion(t, inv);
            case "jei_lookup" -> runJeiLookup(inv);
            case "web_search" -> runWebSearch(inv);
            case "finish" -> runFinish(t, inv);
            default -> errorJson("unknown tool: " + inv.name()
                    + ". Available tools: noop, read_block, place, place_many, break_block, break_many, question, jei_lookup, web_search, finish. "
                    + "Do not wrap calls in multi_tool_use.parallel — emit tool_calls in parallel as separate entries.");
        };
    }

    /**
     * Handle Anthropic's synthetic multi-tool-use router. The shape is:
     * <pre>
     *   arguments = {
     *     "tool_uses": [
     *       { "recipient_name": "functions.place_many", "parameters": "{...}" },
     *       { "recipient_name": "functions.read_block", "parameters": "{...}" }
     *     ]
     *   }
     * </pre>
     * We strip the "functions." prefix and dispatch each entry.
     */
    private static String runMultiToolUseParallel(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> children = coerceBlocksArg(inv.arguments().get("tool_uses"));
        if (children.isEmpty()) {
            return errorJson("multi_tool_use.parallel: empty tool_uses array");
        }
        com.google.gson.JsonArray out = new com.google.gson.JsonArray();
        for (int i = 0; i < children.size(); i++) {
            java.util.Map<String, Object> child = children.get(i);
            String recipient = String.valueOf(child.get("recipient_name"));
            // Anthropic uses "functions.<name>"; OpenAI passes plain "name".
            String realName = recipient.startsWith("functions.")
                    ? recipient.substring("functions.".length())
                    : recipient;
            // Parameters may be a JSON string or a map. Normalize to a map of
            // string -> object so the inner handlers can read it.
            java.util.Map<String, Object> innerArgs = new java.util.HashMap<>();
            Object paramsRaw = child.get("parameters");
            if (paramsRaw instanceof String s && !s.isBlank()) {
                try {
                    com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(s);
                    if (el.isJsonObject()) {
                        innerArgs = new com.google.gson.Gson().fromJson(el, java.util.Map.class);
                    }
                } catch (Exception ignored) { }
            } else if (paramsRaw instanceof java.util.Map) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> casted = (java.util.Map<String, Object>) paramsRaw;
                innerArgs = casted;
            }
            ToolInvocation sub = new ToolInvocation("parallel-" + i, realName, innerArgs);
            com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
            entry.addProperty("recipient_name", recipient);
            entry.addProperty("ok", true);
            entry.addProperty("output", executeTool(t, sub));
            out.add(entry);
        }
        com.google.gson.JsonObject wrap = new com.google.gson.JsonObject();
        wrap.addProperty("executed", children.size());
        wrap.add("results", out);
        return GSON.toJson(wrap);
    }

    /**
     * Coerce the {@code blocks} tool argument into a list of maps. OpenAI
     * delivers tool arguments as a JSON-encoded string, so the value at
     * inv.arguments().get("blocks") is a String. Some client libraries
     * (or our future Kotlin port) may pre-parse it to a List. Handle both.
     */
    private static java.util.List<java.util.Map<String, Object>> coerceBlocksArg(Object raw) {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        if (raw == null) return out;
        if (raw instanceof java.util.List<?> list) {
            for (Object e : list) {
                if (e instanceof java.util.Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> casted = (java.util.Map<String, Object>) m;
                    out.add(casted);
                }
            }
            return out;
        }
        String s = raw.toString();
        try {
            com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(s);
            if (el.isJsonArray()) {
                for (com.google.gson.JsonElement child : el.getAsJsonArray()) {
                    if (child.isJsonObject()) {
                        out.add(new com.google.gson.Gson().fromJson(child, java.util.Map.class));
                    }
                }
            }
        } catch (Exception e) {
            // fall through, returns empty list
        }
        return out;
    }

    private static String runPlaceMany(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        if (!(t.getLevel() instanceof ServerLevel level)) {
            return errorJson("place_many: transmitter level is not a ServerLevel");
        }
        java.util.List<java.util.Map<String, Object>> blocks = coerceBlocksArg(inv.arguments().get("blocks"));
        if (blocks.isEmpty()) {
            return errorJson("place_many: 'blocks' array is empty or unparseable; pass at least 1 entry");
        }
        if (blocks.size() > MAX_BATCH_SIZE) {
            // Truncate rather than fail: the LLM can re-call with the remainder.
            blocks = blocks.subList(0, MAX_BATCH_SIZE);
        }
        com.google.gson.JsonArray results = new com.google.gson.JsonArray();
        int ok = 0, fail = 0;
        for (java.util.Map<String, Object> entry : blocks) {
            Integer x = asInt(entry.get("x"));
            Integer y = asInt(entry.get("y"));
            Integer z = asInt(entry.get("z"));
            Object blockObj = entry.get("block");
            String block = blockObj == null ? null : blockObj.toString();
            com.google.gson.JsonObject r = new com.google.gson.JsonObject();
            r.addProperty("x", x);
            r.addProperty("y", y);
            r.addProperty("z", z);
            if (x == null || y == null || z == null || block == null || block.isBlank()) {
                r.addProperty("ok", false);
                r.addProperty("code", "BAD_REQUEST");
                r.addProperty("message", "x, y, z, block are required");
                fail++;
            } else {
                level.getChunk(x >> 4, z >> 4);
                boolean replace = !entry.containsKey("replace") || Boolean.TRUE.equals(entry.get("replace"));
                ApiResult<Void> ar = BlockApi.place(level, new BlockPos(x, y, z), block, java.util.Map.of(), "", replace);
                r.addProperty("ok", ar.isOk());
                r.addProperty("code", String.valueOf(ar.code()));
                if (ar.message() != null) r.addProperty("message", ar.message());
                if (ar.isOk()) ok++; else fail++;
            }
            results.add(r);
        }
        com.google.gson.JsonObject out = new com.google.gson.JsonObject();
        out.addProperty("count", blocks.size());
        out.addProperty("ok", ok);
        out.addProperty("failed", fail);
        out.add("results", results);
        return GSON.toJson(out);
    }

    private static String runBreakMany(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        if (!(t.getLevel() instanceof ServerLevel level)) {
            return errorJson("break_many: transmitter level is not a ServerLevel");
        }
        java.util.List<java.util.Map<String, Object>> blocks = coerceBlocksArg(inv.arguments().get("blocks"));
        if (blocks.isEmpty()) {
            return errorJson("break_many: 'blocks' array is empty or unparseable");
        }
        if (blocks.size() > MAX_BATCH_SIZE) {
            blocks = blocks.subList(0, MAX_BATCH_SIZE);
        }
        com.google.gson.JsonArray results = new com.google.gson.JsonArray();
        int ok = 0, fail = 0;
        for (java.util.Map<String, Object> entry : blocks) {
            Integer x = asInt(entry.get("x"));
            Integer y = asInt(entry.get("y"));
            Integer z = asInt(entry.get("z"));
            com.google.gson.JsonObject r = new com.google.gson.JsonObject();
            r.addProperty("x", x);
            r.addProperty("y", y);
            r.addProperty("z", z);
            if (x == null || y == null || z == null) {
                r.addProperty("ok", false);
                r.addProperty("code", "BAD_REQUEST");
                r.addProperty("message", "x, y, z are required");
                fail++;
            } else {
                level.getChunk(x >> 4, z >> 4);
                ApiResult<Void> ar = BlockApi.breakBlock(level, new BlockPos(x, y, z));
                r.addProperty("ok", ar.isOk());
                r.addProperty("code", String.valueOf(ar.code()));
                if (ar.message() != null) r.addProperty("message", ar.message());
                if (ar.isOk()) ok++; else fail++;
            }
            results.add(r);
        }
        com.google.gson.JsonObject out = new com.google.gson.JsonObject();
        out.addProperty("count", blocks.size());
        out.addProperty("ok", ok);
        out.addProperty("failed", fail);
        out.add("results", results);
        return GSON.toJson(out);
    }

    private static String runBreakBlock(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        Integer x = asInt(inv.arguments().get("x"));
        Integer y = asInt(inv.arguments().get("y"));
        Integer z = asInt(inv.arguments().get("z"));
        if (x == null || y == null || z == null) {
            return errorJson("break_block: x, y, z are required integers");
        }
        if (!(t.getLevel() instanceof ServerLevel level)) {
            return errorJson("break_block: transmitter level is not a ServerLevel");
        }
        level.getChunk(x >> 4, z >> 4);
        BlockPos pos = new BlockPos(x, y, z);
        ApiResult<Void> r = BlockApi.breakBlock(level, pos);
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("ok", r.isOk());
        o.addProperty("code", String.valueOf(r.code()));
        if (r.message() != null) o.addProperty("message", r.message());
        if (r.isOk()) {
            o.addProperty("now", "minecraft:air");
        }
        return GSON.toJson(o);
    }

    /**
     * The agent loop terminator. The LLM calls this when it has decided the
     * task is done. We surface the reason text into the conversation and
     * mark the loop as done so runLoop() does not recurse.
     */
    private static String runFinish(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        String reason = String.valueOf(inv.arguments().getOrDefault("reason", "(no reason)"));
        t.requestAgentStop(); // reuses the stop flag — both paths exit the loop
        t.setAgentRunning(false);
        JsonObject o = new JsonObject();
        o.addProperty("finished", true);
        o.addProperty("reason", reason);
        return GSON.toJson(o);
    }

    private static String runQuestion(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        Object promptRaw = inv.arguments().get("prompt");
        String prompt = promptRaw == null ? "(empty question)" : promptRaw.toString().trim();
        if (prompt.isEmpty()) {
            prompt = "(empty question)";
        }
        // Build the user-side text that the model will see as the next
        // user message. We keep the in-history copy short — just
        // "[模型提问] <prompt>" — so the chat panel doesn't repeat the
        // option list that the floating picker is already showing.
        // The full option list lives in BE.getPendingQuestion(); the
        // history entry is just there so the LLM's next turn sees a
        // marker for the question and so the user can scroll back to
        // see what was asked.
        String userText = "[模型提问] " + prompt;
        java.util.List<String> optionsList = new java.util.ArrayList<>();
        Object optsRaw = inv.arguments().get("options");
        if (optsRaw instanceof java.util.List<?> opts && !opts.isEmpty()) {
            for (Object o : opts) {
                if (o == null) continue;
                optionsList.add(o.toString());
            }
        }
        // The implicit "other" option is always present so the user
        // is never trapped into a multiple-choice pick — they can
        // type anything. The LLM-side model has been told (in the
        // tool description) that the user is free to type, but
        // listing "other" as a visible bullet makes that obvious
        // on screen as well.
        optionsList.add("other");
        // Persist the question as a user turn in history so the LLM
        // sees a "[模型提问] ..." marker on the next turn and the user
        // can scroll back to see what was asked. We strip the option
        // list — the floating picker above the input shows them, the
        // history doesn't need to.
        IntelligentTransmitterBlockEntity.StoredTurn q = IntelligentTransmitterBlockEntity.StoredTurn.user(userText);
        t.appendHistory(q);
        // Mark awaiting so the runLoop post-tool dispatch path exits
        // instead of recursing into another LLM call. The loop is
        // also shut off via setAgentRunning(false) so the Stop button
        // goes back to "Send". The next runLoop only fires when the
        // user sends a new message — which is exactly what 'question'
        // is supposed to wait for.
        t.setAwaitingUserAnswer(true);
        t.requestAgentStop();
        t.setAgentRunning(false);
        // Store the structured question so the client can render a
        // floating picker above the chat input. The options are kept
        // verbatim (with the implicit 'other' appended) so the picker
        // shows the same list the user just saw in chat history.
        t.setPendingQuestion(new IntelligentTransmitterBlockEntity.PendingQuestion(
                prompt, java.util.Collections.unmodifiableList(optionsList), 0));
        // Surface a friendly placeholder in the BE response field so
        // the screen's lastConversationResponse reads as the question
        // (rather than stale "Thinking..." text). The actual question
        // is in the history; the BE response is just for transient
        // status.
        t.setLastConversationResponse("[等待回答] " + prompt);
        JsonObject o = new JsonObject();
        o.addProperty("asked", true);
        o.addProperty("prompt", prompt);
        return GSON.toJson(o);
    }

    /** No-op: kept for source compat with earlier revisions. The real stop
     *  state lives on the block entity now. */
    public static void requestStop() { }

    private static String runNoop(ToolInvocation inv) {
        String text = String.valueOf(inv.arguments().getOrDefault("text", ""));
        JsonObject o = new JsonObject();
        o.addProperty("echoed", text);
        return GSON.toJson(o);
    }

    private static String runPlace(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        Integer x = asInt(inv.arguments().get("x"));
        Integer y = asInt(inv.arguments().get("y"));
        Integer z = asInt(inv.arguments().get("z"));
        if (x == null || y == null || z == null) {
            return errorJson("place: x, y, z are required integers");
        }
        String block = asString(inv.arguments().get("block"));
        if (block == null || block.isBlank()) {
            return errorJson("place: block id is required (e.g. 'minecraft:stone')");
        }
        boolean replace = !inv.arguments().containsKey("replace") || Boolean.TRUE.equals(inv.arguments().get("replace"));

        if (!(t.getLevel() instanceof ServerLevel level)) {
            return errorJson("place: transmitter level is not a ServerLevel");
        }
        // Force-load the chunk so BlockApi doesn't return CONFLICT for unloaded areas.
        level.getChunk(x >> 4, z >> 4);

        BlockPos pos = new BlockPos(x, y, z);
        java.util.Map<String, String> stateProps = java.util.Collections.emptyMap();
        ApiResult<Void> r = BlockApi.place(level, pos, block, stateProps, "", replace);
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("block", block);
        o.addProperty("ok", r.isOk());
        o.addProperty("code", String.valueOf(r.code()));
        if (r.message() != null) o.addProperty("message", r.message());
        if (r.isOk()) {
            BlockState st = level.getBlockState(pos);
            o.addProperty("placed", st.getBlock().builtInRegistryHolder().key().location().toString());
        }
        return GSON.toJson(o);
    }

    private static String runReadBlock(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        Integer x = asInt(inv.arguments().get("x"));
        Integer y = asInt(inv.arguments().get("y"));
        Integer z = asInt(inv.arguments().get("z"));
        if (x == null || y == null || z == null) {
            return errorJson("read_block: x, y, z are required integers");
        }
        if (!(t.getLevel() instanceof ServerLevel level)) {
            return errorJson("read_block: transmitter level is not a ServerLevel");
        }
        level.getChunk(x >> 4, z >> 4);
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("block", state.getBlock().builtInRegistryHolder().key().location().toString());
        ApiResult<String> nbt = BlockApi.readNbt(level, pos);
        if (nbt.isOk() && nbt.data() != null && !nbt.data().isEmpty()) {
            o.addProperty("nbt", nbt.data());
        } else {
            o.addProperty("nbt", "");
        }
        return GSON.toJson(o);
    }

    /** Reflective lookup of an item/block by id via JEI. The call chain is:
     *  {@code Internal.getJeiRuntime().getIngredientManager().getAllIngredients(ITEM_STACK_TYPE)}
     *  → scan for an item whose registry name matches {@code blockId}, then
     *  {@code getIngredientHelper(item).getDisplayName()} / {@code .getModId()} / etc.
     *  Every step uses {@code Class.forName} / {@code getMethod} so the mod
     *  has no compile-time dependency on JEI; if JEI is missing or the
     *  runtime is not yet ready, the tool result is a friendly error. */
    private static String runJeiLookup(ToolInvocation inv) {
        Object blockIdRaw = inv.arguments().get("block_id");
        if (blockIdRaw == null) {
            return errorJson("jei_lookup: 'block_id' is required (e.g. 'minecraft:stone')");
        }
        String blockId = blockIdRaw.toString().trim();
        if (blockId.isEmpty()) {
            return errorJson("jei_lookup: 'block_id' is required (e.g. 'minecraft:stone')");
        }
        JsonObject out = new JsonObject();
        out.addProperty("block_id", blockId);
        try {
            // 1. Find JEI's runtime accessor class.
            Class<?> internalCls = Class.forName("mezz.jei.common.Internal");
            java.lang.reflect.Method getRuntime = internalCls.getMethod("getJeiRuntime");
            Object runtime = getRuntime.invoke(null);
            if (runtime == null) {
                return errorJson("jei_lookup: JEI runtime not yet available (try again in a few seconds after JEI finishes loading)");
            }
            // 2. runtime.getIngredientManager() returns IIngredientManager.
            Class<?> runtimeCls = runtime.getClass();
            java.lang.reflect.Method getIM = runtimeCls.getMethod("getIngredientManager");
            Object ingMgr = getIM.invoke(runtime);
            if (ingMgr == null) {
                return errorJson("jei_lookup: JEI ingredient manager is null (JEI not ready)");
            }
            // 3. VanillaTypes.ITEM_STACK is a static IIngredientType<ItemStack> field.
            Class<?> vanillaTypesCls = Class.forName("mezz.jei.api.constants.VanillaTypes");
            java.lang.reflect.Field itemField = vanillaTypesCls.getField("ITEM_STACK");
            Object itemType = itemField.get(null);
            // 4. IIngredientManager.getAllIngredients(IIngredientType<V>) → Collection<V>.
            //    The runtime value 'itemType' is an IIngredientType<ItemStack>
            //    instance, but the concrete class may be an anonymous
            //    inner of VanillaTypes (vanillaTypes$1). The method is
            //    declared on the IIngredientType interface, so look it
            //    up by the interface class — that way we don't depend on
            //    whatever anonymous shim JEI ships.
            Class<?> iIngTypeCls = Class.forName("mezz.jei.api.ingredients.IIngredientType");
            java.lang.reflect.Method getAll = ingMgr.getClass().getMethod("getAllIngredients", iIngTypeCls);
            @SuppressWarnings("unchecked")
            java.util.Collection<Object> allItems = (java.util.Collection<Object>) getAll.invoke(ingMgr, itemType);
            // 5. Walk the collection looking for a match on the registry id.
            //    `item` is an ItemStack; its item's registry name is what we compare.
            Object match = null;
            for (Object stack : allItems) {
                if (stack == null) continue;
                // ItemStack.getItem().builtInRegistryHolder().key().location().toString()
                java.lang.reflect.Method getItem = stack.getClass().getMethod("getItem");
                Object item = getItem.invoke(stack);
                if (item == null) continue;
                java.lang.reflect.Method getHolder = item.getClass().getMethod("builtInRegistryHolder");
                Object holder = getHolder.invoke(item);
                if (holder == null) continue;
                // ResourceKey.location() then toString()
                java.lang.reflect.Method keyMethod = holder.getClass().getMethod("key");
                Object key = keyMethod.invoke(holder);
                if (key == null) continue;
                java.lang.reflect.Method locMethod = key.getClass().getMethod("location");
                Object loc = locMethod.invoke(key);
                if (loc == null) continue;
                String idStr = loc.toString();
                if (idStr.equals(blockId)) {
                    match = stack;
                    break;
                }
            }
            if (match == null) {
                out.addProperty("ok", false);
                out.addProperty("error", "no JEI-registered item found with id '" + blockId + "'");
                return GSON.toJson(out);
            }
            // 6. IIngredientManager.getIngredientHelper(item) → IIngredientHelper<ItemStack>.
            //    Two overloads exist after erasure: getIngredientHelper(V)
            //    with V erased to Object, and getIngredientHelper(IIngredientType)
            //    with the interface type preserved. We pick the (V)
            //    variant by excluding methods that take the IIngredientType
            //    interface (the runtime check "param is the IIngredientType
            //    class" is more reliable than "param is not an interface",
            //    since the V variant's parameter is just Object).
            Class<?> iIngTypeClsForHelper = iIngTypeCls;
            java.lang.reflect.Method getHelper = null;
            for (java.lang.reflect.Method m : ingMgr.getClass().getMethods()) {
                if (!m.getName().equals("getIngredientHelper")) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 1) continue;
                if (params[0] == iIngTypeClsForHelper) continue; // skip the (IIngredientType) overload
                getHelper = m;
                break;
            }
            if (getHelper == null) {
                return errorJson("jei_lookup: IIngredientManager.getIngredientHelper(V) not found by reflection");
            }
            Object helper = getHelper.invoke(ingMgr, match);
            if (helper == null) {
                return errorJson("jei_lookup: IIngredientHelper is null for '" + blockId + "'");
            }
            // 7. Pull a few representative methods off the helper. JEI's
            //    IIngredientHelper API exposes: getDisplayName(V),
            //    getModId(V), getResourceLocation(V), getUid(V),
            //    getCreativeTabInfo(...), getTagInfo(...). We grab the
            //    first three — they answer the LLM's most common
            //    questions without flooding the result.
            out.addProperty("ok", true);
            callStringIfPresent(helper, "getDisplayName", out, "display_name", match);
            callStringIfPresent(helper, "getModId", out, "mod_id", match);
            callStringIfPresent(helper, "getResourceLocation", out, "resource_location", match);
            callStringIfPresent(helper, "getUid", out, "jei_uid", match);
            return GSON.toJson(out);
        } catch (ClassNotFoundException e) {
            return errorJson("jei_lookup: JEI mod is not installed on this client (missing class "
                    + e.getMessage() + ")");
        } catch (java.lang.reflect.InvocationTargetException e) {
            // JEI's Preconditions.checkState on getJeiRuntime() throws
            // IllegalStateException when the runtime hasn't been built
            // yet. Surface that as a friendly retry hint rather than a
            // generic Java stack trace.
            Throwable cause = e.getCause();
            if (cause instanceof IllegalStateException) {
                return errorJson("jei_lookup: JEI not ready (" + cause.getMessage()
                        + "). Try again in a few seconds.");
            }
            return errorJson("jei_lookup: JEI reflection failed: "
                    + (cause == null ? e.getMessage() : cause.getMessage()));
        } catch (Exception e) {
            return errorJson("jei_lookup: reflection failure: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    /** Public web search. Two providers, both anonymous (no API key
     *  needed). Synchronous fetch via java.net.http.HttpClient; runs
     *  on whatever thread the LLM worker uses. The result is a small
     *  JSON envelope: source, query, and a list of up to N rows
     *  {title, url, snippet}. The LLM gets this string as the
     *  tool-message in its next request and can quote / paraphrase
     *  from the snippets directly. */
    private static String runWebSearch(ToolInvocation inv) {
        Object qRaw = inv.arguments().get("query");
        if (qRaw == null) {
            return errorJson("web_search: 'query' is required");
        }
        String query = qRaw.toString().trim();
        if (query.isEmpty()) {
            return errorJson("web_search: 'query' is required");
        }
        String source = inv.arguments().get("source") == null
                ? "auto" : inv.arguments().get("source").toString().toLowerCase(java.util.Locale.ROOT);
        Integer maxN = asInt(inv.arguments().get("max_results"));
        if (maxN == null) maxN = 5;
        if (maxN < 1) maxN = 1;
        if (maxN > 8) maxN = 8;

        java.util.List<JsonObject> results = new java.util.ArrayList<>();
        String usedSource = null;
        try {
            switch (source) {
                case "bing" -> {
                    results = searchBing(query, maxN);
                    usedSource = "bing";
                }
                case "duckduckgo" -> {
                    results = searchDuckDuckGo(query, maxN);
                    usedSource = "duckduckgo";
                }
                case "wikipedia" -> {
                    results = searchWikipedia(query, maxN);
                    usedSource = "wikipedia";
                }
                case "auto" -> {
                    // Bing first: it's reachable from networks where DDG
                    // and Wikipedia are blocked (e.g. CN). DDG is the
                    // second try for networks that can reach it but where
                    // Bing is the one blocked; Wikipedia is the
                    // last-resort fallback (strict, short snippets).
                    try {
                        results = searchBing(query, maxN);
                        usedSource = "bing";
                    } catch (Exception bingFail) {
                        try {
                            results = searchDuckDuckGo(query, maxN);
                            usedSource = "duckduckgo";
                        } catch (Exception ddgFail) {
                            // DDG often rate-limits or HTML-shifts; fall
                            // back to the Wikipedia OpenSearch endpoint
                            // which is much more stable.
                            results = searchWikipedia(query, maxN);
                            usedSource = "wikipedia";
                        }
                    }
                }
                default -> {
                    return errorJson("web_search: source must be 'auto', 'bing', 'duckduckgo', or 'wikipedia' (got '"
                            + source + "')");
                }
            }
        } catch (Exception e) {
            return errorJson("web_search: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        JsonObject out = new JsonObject();
        out.addProperty("source", usedSource);
        out.addProperty("query", query);
        JsonArray arr = new JsonArray();
        for (JsonObject r : results) arr.add(r);
        out.add("results", arr);
        out.addProperty("count", results.size());
        return GSON.toJson(out);
    }

    /** Hit Bing's HTML endpoint and pull the organic result rows. Uses
     *  cn.bing.com which is reachable from networks where DuckDuckGo and
     *  Wikipedia are blocked (e.g. CN). The page is server-rendered HTML;
     *  we split on the {@code <li class="b_algo">} result blocks and pull
     *  {title, href, snippet} from each. Bing wraps organic URLs in a
     *  {@code /ck/a?...&u=a1<urlsafe-base64>} redirect — we decode that so
     *  the LLM gets the real destination URL, not a Bing redirect link. */
    private static java.util.List<JsonObject> searchBing(String query, int max) throws Exception {
        String url = "https://cn.bing.com/search?q=" + java.net.URLEncoder.encode(query,
                        java.nio.charset.StandardCharsets.UTF_8)
                + "&count=" + Math.max(max, 10) + "&setlang=en-US";
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0 (compatible; LLM-Era-Mod/1.0; +https://example.com)")
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET()
                .build();
        java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(8))
                .build()
                .send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Bing HTTP " + resp.statusCode());
        }
        String html = resp.body();
        if (html == null || html.isEmpty()) {
            throw new RuntimeException("Bing returned empty body");
        }
        if (html.contains("captcha") || html.contains("challenge-form")) {
            throw new RuntimeException("Bing returned a captcha page");
        }
        java.util.List<JsonObject> rows = new java.util.ArrayList<>();
        // Each organic result lives in <li class="b_algo">...<h2><a href>title</a>
        // ...</h2>...<p ...>snippet</p>...</li>. We split on the block opener so
        // the title + first <p> in each block are guaranteed to belong together.
        String[] blocks = html.split("<li class=\"b_algo\">");
        java.util.regex.Pattern h2a = java.util.regex.Pattern.compile(
                "<h2>[\\s\\S]*?<a[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Pattern pcap = java.util.regex.Pattern.compile(
                "<p[^>]*>([\\s\\S]*?)</p>",
                java.util.regex.Pattern.DOTALL);
        // blocks[0] is the preamble before the first result block; skip it.
        for (int i = 1; i < blocks.length && rows.size() < max; i++) {
            String block = blocks[i];
            java.util.regex.Matcher tm = h2a.matcher(block);
            if (!tm.find()) continue;
            String href = tm.group(1);
            String title = stripHtml(tm.group(2));
            String snippet = "";
            java.util.regex.Matcher sm = pcap.matcher(block);
            if (sm.find()) snippet = stripHtml(sm.group(1));
            if (title.isEmpty() && snippet.isEmpty()) continue;
            JsonObject row = new JsonObject();
            row.addProperty("title", title);
            row.addProperty("url", decodeBingRedirect(href));
            row.addProperty("snippet", snippet);
            rows.add(row);
        }
        return rows;
    }

    /** Bing wraps organic result URLs in a {@code /ck/a?...&u=a1<urlsafe-b64>}
     *  redirect. The {@code u} value is URL-safe base64 of the real
     *  destination, prefixed with a short salt (typically {@code a1}). We
     *  decode the whole thing and slice from the first {@code http} so any
     *  salt length is tolerated. Returns the raw href untouched if it isn't
     *  a Bing redirect or decoding fails. */
    private static String decodeBingRedirect(String href) {
        int uIdx = href.indexOf("u=");
        if (uIdx < 0) return href;
        // Only treat /ck/a links as redirects; ordinary hrefs that happen to
        // contain "u=" somewhere are left alone.
        if (!href.contains("/ck/a")) return href;
        String uVal = href.substring(uIdx + 2);
        int amp = uVal.indexOf('&');
        if (amp >= 0) uVal = uVal.substring(0, amp);
        try {
            uVal = java.net.URLDecoder.decode(uVal, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignore) {
            return href;
        }
        String b64 = uVal.replace('-', '+').replace('_', '/');
        int pad = (4 - b64.length() % 4) % 4;
        if (pad > 0) b64 = b64 + "====".substring(0, pad);
        try {
            byte[] decoded = java.util.Base64.getDecoder().decode(b64);
            String s = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            int httpIdx = s.indexOf("http");
            if (httpIdx >= 0) return s.substring(httpIdx);
        } catch (IllegalArgumentException ignore) {
            return href;
        }
        return href;
    }

    /** Hit DuckDuckGo's HTML endpoint and pull the result rows. The
     *  page is server-rendered HTML; we extract {title, href, snippet}
     *  by walking the result-anchor + result-snippet CSS classes.
     *  DDG occasionally returns a captcha page when traffic is high —
     *  we detect that and throw, so 'auto' can fall back to Wikipedia. */
    private static java.util.List<JsonObject> searchDuckDuckGo(String query, int max) throws Exception {
        // DDG HTML form. POST keeps the query in the body so URLs in
        // the request log don't expose what the user asked the LLM.
        String body = "q=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)
                + "&kl="; // region-free
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("https://html.duckduckgo.com/html/"))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0 (compatible; LLM-Era-Mod/1.0; +https://example.com)")
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build();
        java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(8))
                .build()
                .send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("DDG HTTP " + resp.statusCode());
        }
        String html = resp.body();
        if (html.contains("captcha") || html.contains("challenge-form")) {
            throw new RuntimeException("DDG returned a captcha page");
        }
        java.util.List<JsonObject> rows = new java.util.ArrayList<>();
        // Use a small regex sweep over the well-known DDG result
        // anchor class. Each result block has:
        //   <a class="result__a" href="...">title</a>
        //   <a class="result__snippet">snippet body...</a>
        java.util.regex.Pattern titlePat = java.util.regex.Pattern.compile(
                "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Pattern snipPat = java.util.regex.Pattern.compile(
                "<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>",
                java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher tm = titlePat.matcher(html);
        java.util.regex.Matcher sm = snipPat.matcher(html);
        int idx = 0;
        while (tm.find() && rows.size() < max) {
            String url = tm.group(1);
            String title = stripHtml(tm.group(2));
            String snippet = "";
            // Snippet patterns and title patterns alternate, so find
            // the next snippet after this title position. The matcher
            // is global so .find() advances in lockstep.
            int searchFrom = sm.regionStart();
            if (sm.find(searchFrom)) {
                snippet = stripHtml(sm.group(1));
            }
            JsonObject row = new JsonObject();
            row.addProperty("title", title);
            row.addProperty("url", url);
            row.addProperty("snippet", snippet);
            rows.add(row);
            idx++;
        }
        return rows;
    }

    /** Wikipedia OpenSearch endpoint. Returns a JSON array:
     *  [query, [titles], [snippets], [urls]]. We zip the three
     *  lists into our {title, url, snippet} rows. Wikipedia is a
     *  strict fallback: the snippets are short, so this is best for
     *  "what is X?" type queries. */
    private static java.util.List<JsonObject> searchWikipedia(String query, int max) throws Exception {
        String url = "https://en.wikipedia.org/w/api.php?action=opensearch&limit=" + max
                + "&format=json&search=" + java.net.URLEncoder.encode(query,
                        java.nio.charset.StandardCharsets.UTF_8);
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("User-Agent", "LLM-Era-Mod/1.0 (web search fallback)")
                .header("Accept", "application/json")
                .GET()
                .build();
        java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(8))
                .build()
                .send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Wikipedia HTTP " + resp.statusCode());
        }
        JsonArray arr = JsonParser.parseString(resp.body()).getAsJsonArray();
        // OpenSearch layout: [query, [titles...], [descriptions...], [urls...]]
        if (arr.size() < 4) return java.util.Collections.emptyList();
        JsonArray titles = arr.get(1).getAsJsonArray();
        JsonArray descs = arr.get(2).getAsJsonArray();
        JsonArray urls = arr.get(3).getAsJsonArray();
        java.util.List<JsonObject> rows = new java.util.ArrayList<>();
        int n = Math.min(max, Math.min(titles.size(), Math.min(descs.size(), urls.size())));
        for (int i = 0; i < n; i++) {
            JsonObject row = new JsonObject();
            row.addProperty("title", titles.get(i).getAsString());
            row.addProperty("url", urls.get(i).getAsString());
            row.addProperty("snippet", stripHtml(descs.get(i).getAsString()));
            rows.add(row);
        }
        return rows;
    }

    /** Drop tags & decode the few entities we expect to see in DDG /
     *  Wikipedia markup. We don't need a full HTML parser here — the
     *  result snippets are short and the markup is consistent. */
    private static String stripHtml(String s) {
        if (s == null) return "";
        String out = s.replaceAll("<[^>]+>", " ");
        out = out.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
        return out.replaceAll("\\s+", " ").trim();
    }

    /** Reflection helper: call a no-arg-or-one-V method on `target` and
     *  record the result under `key` in the JSON output if it returns
     *  a String. Silently skips methods that don't exist or throw — JEI
     *  versions vary in which methods IIngredientHelper exposes, and
     *  the LLM would rather get partial info than nothing. */
    private static void callStringIfPresent(Object target, String method, JsonObject out,
                                            String key, Object arg) {
        try {
            Class<?>[] argTypes = arg == null ? new Class<?>[0] : new Class<?>[]{arg.getClass()};
            java.lang.reflect.Method m = null;
            // try exact type, then walk superclasses / interfaces.
            Class<?> cursor = arg == null ? Object.class : arg.getClass();
            while (cursor != null) {
                try {
                    m = target.getClass().getMethod(method, cursor);
                    break;
                } catch (NoSuchMethodException ignored) {
                    cursor = cursor.getSuperclass();
                }
            }
            if (m == null) return;
            Object res = arg == null ? m.invoke(target) : m.invoke(target, arg);
            if (res != null) out.addProperty(key, res.toString());
        } catch (Exception ignored) {
            // Skip quietly — partial info is better than a tool failure.
        }
    }

    private static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static String errorJson(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        return GSON.toJson(o);
    }

    /**
     * Cap assistant reasoning before forwarding. Thinking-capable models
     * emit multi-KB reasoning per turn; we keep the TAIL of the trace
     * (the most recent reasoning) because the conclusion of the chain
     * is what most influences the next LLM step. The prefix is
     * discarded and a marker is prepended so the model sees it's a
     * partial view. Truncation can be turned off per-transmitter with
     * the {@code /truncate} slash command (BE flag).
     */
    private static String truncateReasoning(String s, IntelligentTransmitterBlockEntity t) {
        if (s == null) return "";
        if (t != null && !t.isTruncateEnabled()) return s;
        if (s.length() <= MAX_REASONING_CHARS) return s;
        int drop = s.length() - MAX_REASONING_CHARS;
        return "[reasoning truncated, " + drop + " chars dropped from the head]\n"
                + s.substring(s.length() - MAX_REASONING_CHARS);
    }

    /**
     * Cap tool result payloads before persisting / forwarding. A single
     * place result is short but a read_block on a complex container can be
     * a few KB. We keep the TAIL (the end of the output, which is where
     * the meaningful state tends to land) and drop the prefix. Marker is
     * prepended so the model knows the data was clipped. Honored only
     * when truncation is enabled on the BE (see /truncate).
     */
    private static String truncateToolResult(String s, IntelligentTransmitterBlockEntity t) {
        if (s == null) return "";
        if (t != null && !t.isTruncateEnabled()) return s;
        if (s.length() <= MAX_TOOL_RESULT_CHARS) return s;
        int drop = s.length() - MAX_TOOL_RESULT_CHARS;
        return "[truncated, " + drop + " chars dropped from the head]…"
                + s.substring(s.length() - MAX_TOOL_RESULT_CHARS);
    }

    /**
     * Build an assistant message that carries both the visible text and the
     * structured {@code tool_calls} array. OpenAI requires tool_call_id on
     * the follow-up role=tool messages, and the LLM can only know which id to
     * echo if the assistant turn is reconstructed faithfully.
     */
    private static ChatMessage assistantToolCallMessage(LlmResponse resp) {
        com.google.gson.JsonArray tcs = new com.google.gson.JsonArray();
        for (ToolInvocation inv : resp.toolCalls()) {
            com.google.gson.JsonObject tc = new com.google.gson.JsonObject();
            tc.addProperty("id", inv.id() == null ? "" : inv.id());
            tc.addProperty("type", "function");
            com.google.gson.JsonObject fn = new com.google.gson.JsonObject();
            fn.addProperty("name", inv.name() == null ? "" : inv.name());
            // arguments MUST be a JSON-encoded string, not a nested object.
            fn.addProperty("arguments", GSON.toJson(inv.arguments()));
            tc.add("function", fn);
            tcs.add(tc);
        }
        return ChatMessage.assistantWithToolCalls(resp.text(), GSON.toJson(tcs));
    }
}

package com.example.llmera.client;

import com.example.llmera.LLMEra;
import com.example.llmera.network.IntelligentTransmitterBlockEntity;
import com.example.llmera.network.IntelligentTransmitterMenu;
import com.example.llmera.network.LlmConfiguratorBlockEntity;
import com.example.llmera.packet.ModPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TransmitterScreen extends AbstractContainerScreen<IntelligentTransmitterMenu> {
    private static final int CONVERSATION_VISIBLE_LINES = 10;
    private static final int CONVERSATION_LINE_HEIGHT = 11;

    private EditBox firstField;
    private EditBox secondField;
    private EditBox thirdField;
    private EditBox fourthField;
    private EditBox fifthField;
    private int conversationScroll;
    private int conversationMaxScroll;
    /** True until the conversation view has been painted at least once,
     *  so we can snap the scroll to the bottom on first open. */
    private boolean initialScrollPending = true;
    /** Monotonic counter from the BE — bumped every time a streaming delta
     *  packet is applied. We use it to auto-pin the scroll to the bottom
     *  while text is still being typed in by the LLM. Once it stops
     *  advancing, the player's manual scroll position takes over. */
    private int lastSeenStreamingRevision = -1;
    /** Tracks the BE's history size between renders. When it grows (new turn
     *  arrived from the server) we re-snap to the bottom. */
    private int lastSeenHistorySize = -1;
    /** Toggle: apply the front-truncation cap to assistant reasoning and
     *  tool result strings before they go into the LLM request. When
     *  false, the full text is sent unchanged (useful for long tool
     *  outputs the model needs verbatim, e.g. multi-kilobyte read_block
     *  payloads). The actual state lives in the BE
     *  ({@code IntelligentTransmitterBlockEntity.truncateEnabled}), which
     *  is also persisted in NBT. Toggled at runtime by the
     *  {@code /truncate} slash command. */
    // (No screen-side field — read straight from the BE every time.)
    private Component statusMessage = Component.empty();
    private boolean pendingConfirmClear = false;

    /** Slash-command palette state. {@code slashOpen} is set true while the
     *  chat input starts with {@code /} and contains no whitespace — the
     *  renderer then draws a floating menu above the input. {@code slashSelected}
     *  is the currently highlighted index. The menu is a chat-style
     *  shortcut: type {@code /}, see available commands, Up/Down to
     *  navigate, Enter or Tab to commit, Esc to dismiss. */
    private boolean slashOpen = false;
    private int slashSelected = 0;
    private String slashQuery = "";
    /** Question picker state. When the BE has a non-null
     *  {@code pendingQuestion}, the floating picker is shown above the
     *  chat input and routes keyboard input through it instead of the
     *  EditBox / slash menu / model picker. */
    private int questionSelected = 0;
    /** When the user has selected the "other" option, they get a
     *  free-form text box inside the picker; this flag tells the
     *  renderer (and the key handler) to switch to edit mode. */
    private boolean questionFreeForm = false;

    /** Available slash commands. Add a new entry here and the menu
     *  auto-picks it up. The action is invoked on commit; the menu
     *  itself doesn't clear the input — the action is responsible for
     *  that (so it can also write back partial input like
     *  {@code /model xxx}). */
    private record SlashCommand(String name, String description, java.util.function.Consumer<TransmitterScreen> action) {}

    /** Client-only log of slash-command output. Not persisted; vanishes
     *  when the screen closes. Rendered in the history view as
     *  {@code [system] <message>} lines, oldest at top, newest at the
     *  bottom (right above the streaming tail). Each entry is plain text —
     *  the renderer adds the {@code [system]} prefix and color. */
    private final List<String> slashLog = new ArrayList<>();
    /** Cap so an over-eager user can't blow up the rendered line count
     *  with a long /help output. Older entries are dropped FIFO. */
    private static final int SLASH_LOG_MAX = 20;
    private List<SlashCommand> slashCommands() {
        List<SlashCommand> cmds = new ArrayList<>();
        cmds.add(new SlashCommand("/new", tr("screen.llmera.command.new").getString(), s -> s.runSlashNew()));
        cmds.add(new SlashCommand("/thinking", tr("screen.llmera.command.thinking").getString(),
                s -> s.runSlashThinking()));
        cmds.add(new SlashCommand("/model", tr("screen.llmera.command.model").getString(), s -> s.runSlashModel()));
        cmds.add(new SlashCommand("/truncate", tr("screen.llmera.command.truncate").getString(),
                s -> s.runSlashTruncate()));
        cmds.add(new SlashCommand("/viewtrunc", tr("screen.llmera.command.viewtrunc").getString(),
                s -> s.runSlashViewTrunc()));
        cmds.add(new SlashCommand("/tools", tr("screen.llmera.command.tools").getString(),
                s -> s.runSlashTools()));
        cmds.add(new SlashCommand("/expand", tr("screen.llmera.command.expand").getString(),
                s -> s.runSlashExpand()));
        cmds.add(new SlashCommand("/stop", tr("screen.llmera.command.stop").getString(), s -> s.runSlashStop()));
        cmds.add(new SlashCommand("/help", tr("screen.llmera.command.help").getString(), s -> s.runSlashHelp()));
        return cmds;
    }
    private List<SlashCommand> slashFiltered() {
        String q = slashQuery.toLowerCase(Locale.ROOT);
        List<SlashCommand> all = slashCommands();
        List<SlashCommand> out = new ArrayList<>();
        for (SlashCommand c : all) {
            if (c.name().toLowerCase(Locale.ROOT).startsWith(q)) out.add(c);
        }
        return out;
    }

    public TransmitterScreen(IntelligentTransmitterMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 356;
        this.imageHeight = 250;
    }

    @Override
    protected void init() {
        super.init();
        int x = this.leftPos + 12;
        int y = this.topPos;
        IntelligentTransmitterBlockEntity t = te();
        if (t == null) return;

        if (menu.conversationMode) {
            initConversation(x, y, t);
            if (modelPickerOffset >= 0 && t.getLastModelList() != null
                    && !t.getLastModelList().isEmpty()
                    && !t.getLastModelList().startsWith("ERR")) {
                renderModelPicker(x, y, t);
            }
        } else {
            initTransmitter(x, y, t);
        }
    }

    private void renderModelPicker(int x, int y, IntelligentTransmitterBlockEntity t) {
        // Anchor in the top-right corner, BELOW the New chat button (y+24) so
        // the picker does not overlap the Models button, the New chat button,
        // or the chat history / chat input. Picker geometry: width 150, height
        // up to (visible rows + header) px.
        int pickX = this.leftPos + imageWidth - 162;
        int pickY = y + 50;
        int pickW = 150;
        // Header bar with a "close" button to dismiss.
        addRenderableWidget(Button.builder(
                        Component.literal("≡ " + tr("screen.llmera.label.pick_model").getString())
                                .withStyle(s -> s.withColor(0xFFFFFFFF)),
                        b -> closeModelPicker())
                .pos(pickX, pickY).size(pickW, 14).build());

        String[] models = t.getLastModelList().split(",");
        int total = models.length;
        int visible = Math.min(MODEL_PICKER_VISIBLE, total);
        int maxScroll = Math.max(0, total - MODEL_PICKER_VISIBLE);
        // Clamp scroll/selected in case the BE pushed a fresh (shorter) list
        // while the picker was open. Keeps the math from drawing a row
        // past the end of the array.
        if (modelPickerScroll > maxScroll) modelPickerScroll = maxScroll;
        if (modelPickerSelected < 0) modelPickerSelected = 0;
        if (modelPickerSelected >= total) modelPickerSelected = total - 1;
        int rowY = pickY + 16;
        for (int i = 0; i < visible; i++) {
            int modelIdx = modelPickerScroll + i;
            String name = models[modelIdx].trim();
            if (name.isEmpty()) continue;
            boolean isHighlighted = (modelIdx == modelPickerSelected);
            int textColor = isHighlighted ? 0xFFFFD080 : 0xFFD9E2F2;
            addRenderableWidget(Button.builder(
                    Component.literal(name).withStyle(s -> s.withColor(textColor)),
                    btn -> selectModel(name))
                    .pos(pickX, rowY).size(pickW, 14).build());
            rowY += MODEL_PICKER_ROW_H;
        }
    }

    private void selectModel(String name) {
        CompoundTag data = new CompoundTag();
        data.putString("ModelName", name);
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new ModPackets.UpdateBlockConfigPayload(menu.pos, data));
        statusMessage = Component.literal("Model: " + name);
        closeModelPicker();
    }

    /** Painted on top of the picker buttons every frame. Handles the bits
     *  the {@code addRenderableWidget} path can't do on its own: a
     *  translucent highlight under the currently selected row and a
     *  scrollbar track + thumb on the right edge of the picker. */
    private void renderModelPickerOverlay(GuiGraphics g) {
        if (modelPickerOffset < 0) return;
        IntelligentTransmitterBlockEntity t = te();
        if (t == null) return;
        String list = t.getLastModelList();
        if (list == null || list.isEmpty() || list.startsWith("ERR")) return;
        String[] models = list.split(",");
        int total = models.length;
        int visible = Math.min(MODEL_PICKER_VISIBLE, total);
        int maxScroll = Math.max(0, total - MODEL_PICKER_VISIBLE);
        int pickX = this.leftPos + imageWidth - 162;
        int pickY = this.topPos + 50 + 16;
        int pickW = 150;
        // Highlight band for the keyboard-selected row.
        int selRel = modelPickerSelected - modelPickerScroll;
        if (selRel >= 0 && selRel < visible) {
            int rowY = pickY + selRel * MODEL_PICKER_ROW_H;
            g.fill(pickX + 1, rowY, pickX + pickW - 1, rowY + MODEL_PICKER_ROW_H, 0x55B58CFF);
        }
        // Scrollbar only when there's overflow.
        if (maxScroll > 0) {
            int trackX = pickX + pickW + 1;
            int trackY = pickY;
            int trackH = visible * MODEL_PICKER_ROW_H;
            g.fill(trackX, trackY, trackX + 1, trackY + trackH, 0x665B6B82);
            int thumbH = Math.max(8, trackH * MODEL_PICKER_VISIBLE / total);
            int thumbY = trackY + (trackH - thumbH) * modelPickerScroll / maxScroll;
            g.fill(trackX, thumbY, trackX + 1, thumbY + thumbH, 0xFF9FB6D8);
        }
    }

    private void initTransmitter(int x, int y, IntelligentTransmitterBlockEntity t) {
        firstField = addField(x, y + 34, 146, t.getModelUrl(), 512);
        firstField.setHint(tr("screen.llmera.hint.model_url"));
        secondField = addField(x, y + 64, 146, "", 512); // never re-display API key
        secondField.setHint(tr("screen.llmera.hint.api_key"));
        thirdField = addField(x, y + 94, 146, t.getModelName(), 128);
        thirdField.setHint(tr("screen.llmera.hint.model_name"));
        fourthField = addField(x, y + 124, 146, t.getAiName(), 64);
        fourthField.setHint(tr("screen.llmera.hint.ai_name"));
        fifthField = addField(x, y + 154, 146, t.getSystemPrompt(), 1024);
        fifthField.setHint(tr("screen.llmera.hint.system_prompt"));
        addRenderableWidget(Button.builder(tr("screen.llmera.button.save"), b -> saveTransmitter(false))
                .pos(x, y + 184).size(70, 20).build());
        if (!(t instanceof LlmConfiguratorBlockEntity)) {
            addRenderableWidget(Button.builder(tr("screen.llmera.button.enter_conversation"), b -> openConversation())
                    .pos(x + 76, y + 184).size(86, 20).build());
        }
        // Note: "拉取模型" used to be a button here, but it's now a slash
        // command in the chat input (/model). The button was redundant
        // once we added the slash menu — the menu gives the same action
        // and a discoverable list of every other command too.
    }

    private void initConversation(int x, int y, IntelligentTransmitterBlockEntity t) {
        // When the model picker is open, suppress all other conversation
        // buttons so the picker takes over the right side of the panel.
        if (modelPickerOffset >= 0) {
        firstField = addField(x, y + 210, t.isAgentRunning() ? 256 : 320, t.getConversationDraft(), 1024);
        firstField.setHint(tr("screen.llmera.hint.chat_input"));
            return;
        }
        if (pendingConfirmClear) {
            addRenderableWidget(Button.builder(tr("screen.llmera.button.confirm_yes"), b -> clearConversation())
                    .pos(x + 250, y + 24).size(70, 20).build());
            addRenderableWidget(Button.builder(tr("screen.llmera.button.confirm_no"), b -> {
                pendingConfirmClear = false;
                rebuildWidgets();
            }).pos(x + 250, y + 184).size(70, 20).build());
        } else {
            // Note: "new conversation" used to be a button here, but it's now
            // a slash command in the chat input (/new). Keeps the toolbar
            // uncluttered and matches what users expect from a chat UI.
            addRenderableWidget(Button.builder(tr("screen.llmera.button.close"), b -> openConfig())
                    .pos(x + 250, y + 184).size(70, 20).build());
        }
        // Note: "拉取模型" used to be a button here. It's now the /model
        // slash command in the chat input — same action, plus the slash
        // menu is a natural place to discover /thinking /truncate /help
        // alongside it.
        // Note: the "show reasoning" toggle used to live here as a button.
        // It's now a slash command in the chat input (/thinking). Keeps the
        // toolbar focused on actions that mutate server state.
        firstField = addField(x, y + 210, 256, t.getConversationDraft(), 1024);
        // While the agent loop is running, replace the Send button with a Stop
        // button. The server publishes agentRunning state via the BE update
        // While the agent loop is running, show a Stop button on the right
        // of the chat input. When idle, the input is wide enough to span the
        // full row — press Enter (or the on-screen prompt's key) to send.
        // A "Send" button is intentionally NOT drawn so the chat row stays
        // a single tidy text field; chat-style UX with Enter-to-send.
        if (t.isAgentRunning()) {
            addRenderableWidget(Button.builder(tr("screen.llmera.button.stop"), b -> stopAgent())
                    .pos(x + 262, y + 210).size(58, 20).build());
        }
    }

    private void stopAgent() {
        CompoundTag data = new CompoundTag();
        data.putBoolean("StopAgent", true);
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new ModPackets.UpdateBlockConfigPayload(menu.pos, data));
        statusMessage = tr("screen.llmera.status.agent_stop_sent");
    }

    private EditBox addField(int x, int y, int width, String value, int maxLength) {
        EditBox field = new EditBox(this.font, x, y, width, 18, Component.empty());
        field.setMaxLength(maxLength);
        field.setValue(value == null ? "" : value);
        addRenderableWidget(field);
        return field;
    }

    private IntelligentTransmitterBlockEntity te() {
        return menu.te(Minecraft.getInstance().player);
    }

    private void saveTransmitter(boolean unused) {
        CompoundTag data = new CompoundTag();
        data.putString("ModelUrl", firstField.getValue());
        data.putString("ApiKey", secondField.getValue());
        data.putString("ModelName", thirdField.getValue());
        data.putString("AiName", fourthField.getValue());
        data.putString("SystemPrompt", fifthField.getValue());
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new ModPackets.UpdateBlockConfigPayload(menu.pos, data));
        statusMessage = tr("screen.llmera.status.transmitter_saved");
    }

    private void openConversation() {
        Minecraft.getInstance().setScreen(new TransmitterScreen(
                new IntelligentTransmitterMenu(0, Minecraft.getInstance().player.getInventory(),
                        menu.pos, true),
                Minecraft.getInstance().player.getInventory(),
                tr("screen.llmera.transmitter.conversation")));
    }

    private void openConfig() {
        Minecraft.getInstance().setScreen(new TransmitterScreen(
                new IntelligentTransmitterMenu(0, Minecraft.getInstance().player.getInventory(),
                        menu.pos, false),
                Minecraft.getInstance().player.getInventory(),
                tr("screen.llmera.transmitter.config")));
    }

    private void sendChat() {
        String text = firstField.getValue();
        if (text == null) return;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return;
        // Slash commands: keep the chat input itself the only place to
        // trigger /new and /thinking so the toolbar can stay minimal. The
        // command is consumed (cleared from the input) and the matching
        // local action runs; no packet is sent for /thinking since the
        // toggle is a client-side rendering preference.
        if ("/new".equalsIgnoreCase(trimmed)) {
            clearConversation();
            firstField.setValue("");
            return;
        }
        if ("/thinking".equalsIgnoreCase(trimmed)) {
            runSlashThinking();
            return;
        }
        if ("/expand".equalsIgnoreCase(trimmed)) {
            runSlashExpand();
            return;
        }
        if ("/truncate".equalsIgnoreCase(trimmed)) {
            runSlashTruncate();
            return;
        }
        if ("/viewtrunc".equalsIgnoreCase(trimmed)) {
            runSlashViewTrunc();
            return;
        }
        if ("/tools".equalsIgnoreCase(trimmed)) {
            runSlashTools();
            return;
        }
        if ("/stop".equalsIgnoreCase(trimmed)) {
            runSlashStop();
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putString("ChatPrompt", trimmed);
        data.putString("ConversationDraft", "");
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new ModPackets.UpdateBlockConfigPayload(menu.pos, data));
        firstField.setValue("");
        statusMessage = tr("screen.llmera.status.chat_sent");
        // Snap the view to the bottom on the next frame so the new turn is
        // visible immediately. (Without this, the LLM response would push
        // the scroll position forward only if the user happened to already
        // be at the bottom.)
        initialScrollPending = true;
    }

    /** Re-evaluate the slash menu state from the current chat input. Called
     *  every render frame and after each key event, so any mutation
     *  (typing a character, deleting, paste, mouse-clearing the field) is
     *  picked up without needing to wire a content listener on the
     *  EditBox. */
    private void updateSlashState() {
        if (firstField == null || !menu.conversationMode) {
            slashOpen = false;
            return;
        }
        String v = firstField.getValue();
        if (v == null) v = "";
        if (v.startsWith("/") && v.indexOf(' ') < 0 && v.indexOf('\n') < 0) {
            slashOpen = true;
            slashQuery = v;
            int max = slashFiltered().size();
            if (max == 0) {
                slashOpen = false; // no match — hide menu
            } else if (slashSelected >= max) {
                slashSelected = 0;
            }
        } else {
            slashOpen = false;
            slashQuery = "";
        }
    }

    /** Pick the currently highlighted slash command and run its action. */
    private void commitSlashSelection() {
        List<SlashCommand> filtered = slashFiltered();
        if (filtered.isEmpty()) return;
        SlashCommand chosen = filtered.get(Math.max(0, Math.min(slashSelected, filtered.size() - 1)));
        chosen.action().accept(this);
        // The action is responsible for clearing firstField when needed.
        // Reset palette state so the menu doesn't pop back open after.
        slashOpen = false;
        slashSelected = 0;
        slashQuery = "";
    }

    private void runSlashNew() {
        clearConversation();
        firstField.setValue("");
    }
    private void runSlashThinking() {
        // Read the current value straight from the BE so the toggle
        // reflects what was actually persisted (including the case where
        // the user just opened the screen and the BE already had a
        // stored value from a previous session). The BE is the source
        // of truth; the screen no longer keeps a mirror field.
        IntelligentTransmitterBlockEntity t = te();
        boolean newVal = !(t == null || t.isShowReasoning());
        statusMessage = newVal
                ? tr("screen.llmera.status.reasoning_on")
                : tr("screen.llmera.status.reasoning_off");
        pushSlashLog(newVal ? "思考显示: 开启" : "思考显示: 关闭");
        // Persist via the BE so closing the screen / reloading the chunk
        // / relogging keeps the user's preference. Mirrors /truncate.
        CompoundTag data = new CompoundTag();
        data.putBoolean("ShowReasoning", newVal);
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new ModPackets.UpdateBlockConfigPayload(menu.pos, data));
        firstField.setValue("");
    }
    private void runSlashHelp() {
        // Drop a multi-line block into the slash log so the user can read
        // the full help at a glance, then snap the scroll to the bottom so
        // it's visible without manual scrolling.
        pushSlashLog("可用指令:");
        for (SlashCommand c : slashCommands()) {
            pushSlashLog("  " + c.name() + "  -  " + c.description());
        }
        firstField.setValue("");
        initialScrollPending = true;
    }
    private void runSlashModel() {
        // Same behavior as the old "拉取模型" button: kick off a server
        // fetch and, if a cached list is already there, open the picker
        // immediately so the user can scroll the menu.
        fetchModels();
        IntelligentTransmitterBlockEntity t = te();
        if (t != null) {
            String list = t.getLastModelList();
            if (list != null && !list.isEmpty() && !list.startsWith("ERR")) {
                openModelPicker();
            }
        }
        pushSlashLog("正在拉取模型列表...");
        firstField.setValue("");
    }
    private void runSlashStop() {
        // Same as the Stop button — fires the StopAgent packet so the
        // server-side loop exits at the next "between turns" check.
        // No-op-ish if the agent isn't running, but the server tolerates
        // that and we still clear the input box for the user.
        stopAgent();
        pushSlashLog("已请求停止 agent");
        firstField.setValue("");
    }

    private void runSlashExpand() {
        // Persisted client-side toggle — see runSlashThinking for the
        // BE-as-source-of-truth reasoning. The BE always stores the
        // full reasoning text; we just decide whether to render every
        // line of it or collapse to the first.
        IntelligentTransmitterBlockEntity t = te();
        boolean newVal = !(t == null || t.isExpandReasoning());
        statusMessage = newVal
                ? tr("screen.llmera.status.expand_on")
                : tr("screen.llmera.status.expand_off");
        pushSlashLog(newVal ? "思考过程: 全部展开" : "思考过程: 折叠");
        CompoundTag data = new CompoundTag();
        data.putBoolean("ExpandReasoning", newVal);
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new ModPackets.UpdateBlockConfigPayload(menu.pos, data));
        firstField.setValue("");
    }

    private void runSlashTruncate() {
        // Read current value from the BE so the screen has no mirror
        // state. The BE is the source of truth, persisted in NBT.
        IntelligentTransmitterBlockEntity t = te();
        boolean newVal = !(t == null || t.isTruncateEnabled());
        statusMessage = newVal
                ? tr("screen.llmera.status.truncate_on")
                : tr("screen.llmera.status.truncate_off");
        pushSlashLog(newVal ? "截断已开启 (前面优先)" : "截断已关闭 (发送完整内容)");
        CompoundTag data = new CompoundTag();
        data.putBoolean("TruncateEnabled", newVal);
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new ModPackets.UpdateBlockConfigPayload(menu.pos, data));
        firstField.setValue("");
    }

    private void runSlashViewTrunc() {
        // Toggles the BE's viewTruncateEnabled flag (persisted in NBT,
        // synced to client via BlockUpdate). Controls whether wrapInto /
        // wrapIntoFull apply their per-turn char caps during rendering.
        // When off, wrapInto is uncapped and wrapIntoFull uses a 10000-char
        // ceiling instead of 5000 — full enough for deep traces while
        // still preventing pathological blocks from freezing render.
        IntelligentTransmitterBlockEntity t = te();
        boolean newVal = !(t == null || t.isViewTruncateEnabled());
        statusMessage = newVal
                ? tr("screen.llmera.status.viewtrunc_on")
                : tr("screen.llmera.status.viewtrunc_off");
        pushSlashLog(newVal ? "显示截断已开启" : "显示截断已关闭 (完整渲染, 上限1万字符)");
        CompoundTag data = new CompoundTag();
        data.putBoolean("ViewTruncateEnabled", newVal);
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new ModPackets.UpdateBlockConfigPayload(menu.pos, data));
        firstField.setValue("");
    }

    private void runSlashTools() {
        // Toggles the BE's showTools flag (persisted in NBT, synced to
        // client via BlockUpdate). When off, tool-call annotations
        // ("tool: name(args)" yellow lines) and tool-result blocks
        // ("[tool result]" lines) are hidden from the chat history.
        // Tool calls still execute server-side — only the display is
        // affected.
        IntelligentTransmitterBlockEntity t = te();
        boolean newVal = !(t == null || t.isShowTools());
        statusMessage = newVal
                ? tr("screen.llmera.status.tools_on")
                : tr("screen.llmera.status.tools_off");
        pushSlashLog(newVal ? "工具调用显示已开启" : "工具调用显示已关闭");
        CompoundTag data = new CompoundTag();
        data.putBoolean("ShowTools", newVal);
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new ModPackets.UpdateBlockConfigPayload(menu.pos, data));
        firstField.setValue("");
    }

    /** Append a single line to the slash log. Older entries fall off the
     *  tail at {@link #SLASH_LOG_MAX} to keep the rendered history bounded. */
    private void pushSlashLog(String line) {
        slashLog.add(line);
        while (slashLog.size() > SLASH_LOG_MAX) {
            slashLog.remove(0);
        }
    }

    private void clearConversation() {
        CompoundTag data = new CompoundTag();
        data.putBoolean("ClearConversation", true);
        data.putString("ConversationDraft", "");
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new ModPackets.UpdateBlockConfigPayload(menu.pos, data));
        firstField.setValue("");
        conversationScroll = 0;
        pendingConfirmClear = false;
        statusMessage = tr("screen.llmera.status.chat_cleared");
        pushSlashLog("新对话已开始");
    }

    private void fetchModels() {
        CompoundTag data = new CompoundTag();
        data.putBoolean("FetchModels", true);
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new ModPackets.UpdateBlockConfigPayload(menu.pos, data));
        statusMessage = tr("screen.llmera.status.fetch_models_sent");
    }

    /**
     * Stack the model list under the Models button as plain clickable rows.
     * Each row is a small button; selecting one writes back ModelName via
     * applyClientData. Capped at 12 entries to keep the screen tidy.
     */
    private int modelPickerOffset = -1;
    /** Scroll offset of the model picker. Each row is 15 px tall and the
     *  picker window shows up to {@link #MODEL_PICKER_VISIBLE} rows at a
     *  time, so this is the index of the topmost visible row. Bumped by
     *  keyboard navigation (PageUp/PageDown/Up/Down) and the mouse scroll
     *  wheel when the picker has focus. */
    private int modelPickerScroll = 0;
    /** Row index currently highlighted by keyboard navigation. Mouse clicks
     *  still select immediately; the highlight is purely a visual
     *  affordance so the user can see where Enter will land. */
    private int modelPickerSelected = 0;
    /** How many model rows the picker window shows at once. Tuned to fit
     *  the chat history viewport without overlapping the streaming tail
     *  or the chat input row. */
    private static final int MODEL_PICKER_VISIBLE = 7;
    /** Px per row in the picker. Kept in sync with the button height
     *  used in {@link #renderModelPicker}. */
    private static final int MODEL_PICKER_ROW_H = 15;

    private void openModelPicker() {
        modelPickerOffset = 0;
        modelPickerScroll = 0;
        modelPickerSelected = 0;
        rebuildWidgets();
    }

    private void closeModelPicker() {
        modelPickerOffset = -1;
        modelPickerScroll = 0;
        modelPickerSelected = 0;
        rebuildWidgets();
    }

    /** Centralized picker mutation that also rebuilds the widget tree.
     *  All places that change {@code modelPickerScroll} or
     *  {@code modelPickerSelected} must go through this so the row
     *  buttons get re-added with the new visible window. The overlay
     *  (highlight + scrollbar) paints every frame regardless, but the
     *  actual clickable buttons only exist after a rebuild. */
    private void setPickerOffset(int scroll, int selected) {
        boolean changed = scroll != modelPickerScroll || selected != modelPickerSelected;
        modelPickerScroll = scroll;
        modelPickerSelected = selected;
        if (changed) rebuildWidgets();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // Picker wheel wins over history wheel when the picker is open.
        // Both panels can be visible at once, so disambiguate by which
        // panel sits under the cursor; if the cursor is anywhere over
        // the picker, route the wheel to the picker.
        if (modelPickerOffset >= 0 && mouseInsideModelPicker(mouseX, mouseY)) {
            int total = pickerModelCount();
            if (total > 0) {
                int maxScroll = Math.max(0, total - MODEL_PICKER_VISIBLE);
                int newScroll = modelPickerScroll;
                if (scrollY > 0) newScroll = Math.max(0, newScroll - 1);
                else if (scrollY < 0) newScroll = Math.min(maxScroll, newScroll + 1);
                setPickerOffset(newScroll, modelPickerSelected);
                return true;
            }
        }
        if (menu.conversationMode && conversationMaxScroll > 0) {
            int previous = conversationScroll;
            if (scrollY > 0) conversationScroll--;
            else if (scrollY < 0) conversationScroll++;
            conversationScroll = clampScroll(conversationScroll);
            if (previous != conversationScroll) return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** True when the cursor sits over the model picker's panel rect. Used
     *  to route mouse-wheel events to the picker even though the chat
     *  history scrolls under the same screen. */
    private boolean mouseInsideModelPicker(double mx, double my) {
        if (modelPickerOffset < 0) return false;
        IntelligentTransmitterBlockEntity t = te();
        if (t == null) return false;
        String list = t.getLastModelList();
        if (list == null || list.isEmpty() || list.startsWith("ERR")) return false;
        int total = list.split(",").length;
        int visible = Math.min(MODEL_PICKER_VISIBLE, total);
        int pickX = this.leftPos + imageWidth - 162;
        int pickY = this.topPos + 50;
        int pickW = 150;
        int pickH = 16 + visible * MODEL_PICKER_ROW_H;
        return mx >= pickX && mx < pickX + pickW && my >= pickY && my < pickY + pickH;
    }

    /** Current model count from the BE. Returns 0 if the BE is missing or
     *  has not pushed a list yet. */
    private int pickerModelCount() {
        IntelligentTransmitterBlockEntity t = te();
        if (t == null) return 0;
        String list = t.getLastModelList();
        if (list == null || list.isEmpty() || list.startsWith("ERR")) return 0;
        return list.split(",").length;
    }

    /** Route keyboard events to the question picker when one is open.
     *  Owns Up/Down/Enter/Esc, plus number keys (1..9) for fast
     *  selection, plus the "other" option which switches the chat
     *  input into free-form mode. Returns true if the key was
     *  consumed (so the EditBox and other handlers don't see it). */
    private boolean handleQuestionPickerKey(int keyCode, int scanCode, int modifiers,
                                            IntelligentTransmitterBlockEntity.PendingQuestion q) {
        int n = q.options().size();
        // Esc dismisses without answering — same as the user ignoring
        // the question and typing a free-form message into the chat
        // input. We only clear the picker if the user is actually
        // typing text; if they press Esc with no text, leave the
        // question up so they can still pick.
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            questionFreeForm = false;
            // Dismiss: clear BE pending, drop the picker. The user
            // can then type into the chat input; that send will be
            // routed to the agent as a normal user reply.
            IntelligentTransmitterBlockEntity t = te();
            if (t != null) t.setPendingQuestion(null);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP) {
            if (questionFreeForm) {
                // In free-form mode the EditBox owns arrow keys for
                // caret movement; fall through and let it handle.
                return false;
            }
            questionSelected = Math.max(0, questionSelected - 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            if (questionFreeForm) return false;
            questionSelected = Math.min(n - 1, questionSelected + 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (questionFreeForm) return false; // EditBox Enter = send
            commitQuestionSelection(q);
            return true;
        }
        // Number keys 1..9 jump straight to that option, matching the
        // question.tsx reference. Only the first nine options are
        // addressable; later options still need arrow keys.
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9) {
            if (questionFreeForm) return false;
            int idx = (keyCode - GLFW.GLFW_KEY_1);
            if (idx < n) {
                questionSelected = idx;
                commitQuestionSelection(q);
            }
            return true;
        }
        // Any other key (a printable char) in non-free-form mode:
        // switch to free-form so the user can type their own answer
        // without first navigating to "other". Helpful when the
        // options list is wrong / missing what they want.
        if (!questionFreeForm
                && keyCode != GLFW.GLFW_KEY_LEFT && keyCode != GLFW.GLFW_KEY_RIGHT
                && keyCode != GLFW.GLFW_KEY_HOME && keyCode != GLFW.GLFW_KEY_END
                && keyCode != GLFW.GLFW_KEY_TAB) {
            // Treat printable keys as "type a free-form answer".
            // We dispatch to the EditBox (return false) so the
            // character lands there; we also flip questionFreeForm
            // so the next render shows the option list with the
            // "other" row pre-highlighted.
            if (isPrintableKey(keyCode)) {
                questionFreeForm = true;
                questionSelected = n - 1; // assume last is "other"
            }
        }
        return false;
    }

    /** True for keys that produce a single character when combined
     *  with no modifier. Used by the question picker to decide
     *  whether a key press should drop the user into free-form mode. */
    private static boolean isPrintableKey(int keyCode) {
        // GLFW printable key range: 0x20 (space) to 0x7E (~) is
        // roughly ASCII printable. Anything else (function keys,
        // arrow keys, modifier keys) should not start free-form.
        return keyCode >= 0x20 && keyCode <= 0x7E;
    }

    /** Commit the currently highlighted option. If the option is the
     *  implicit 'other' entry, switch the chat input into free-form
     *  mode and let the user type an answer; otherwise the option
     *  text is sent as the user's reply to the LLM. */
    private void commitQuestionSelection(IntelligentTransmitterBlockEntity.PendingQuestion q) {
        if (questionSelected < 0 || questionSelected >= q.options().size()) return;
        String opt = q.options().get(questionSelected);
        if ("other".equals(opt)) {
            // Drop into free-form: focus the chat input, leave the
            // picker visible so the user can still see the question.
            questionFreeForm = true;
            // Focus the EditBox so the next keystroke lands there.
            if (firstField != null) {
                setFocused(firstField);
            }
            return;
        }
        // Concrete option: send it directly as the user reply.
        IntelligentTransmitterBlockEntity t = te();
        if (t == null) return;
        CompoundTag data = new CompoundTag();
        data.putString("ChatPrompt", opt);
        data.putString("ConversationDraft", "");
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new ModPackets.UpdateBlockConfigPayload(menu.pos, data));
        // Clear the picker; the user has answered.
        t.setPendingQuestion(null);
        questionFreeForm = false;
        questionSelected = 0;
        // Clear the input box on screen — the reply was sent.
        firstField.setValue("");
        statusMessage = tr("screen.llmera.status.chat_sent");
        initialScrollPending = true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Question picker gets first claim on keyboard when a 'question'
        // tool call is awaiting. It owns Up/Down/Enter/Esc entirely,
        // so the model picker / slash menu / EditBox don't see them.
        if (menu.conversationMode) {
            IntelligentTransmitterBlockEntity qt = te();
            if (qt != null && qt.getPendingQuestion() != null) {
                return handleQuestionPickerKey(keyCode, scanCode, modifiers, qt.getPendingQuestion());
            }
        }
        // Model picker gets first claim on arrow keys / PageUp / PageDown /
        // Enter / Esc when it's open. Done before the EditBox branch so
        // typing in the chat input doesn't intercept the picker arrows.
        if (menu.conversationMode && modelPickerOffset >= 0) {
            int total = pickerModelCount();
            if (total > 0) {
                int maxScroll = Math.max(0, total - MODEL_PICKER_VISIBLE);
                if (keyCode == GLFW.GLFW_KEY_UP) {
                    int newSel = Math.max(0, modelPickerSelected - 1);
                    int newScroll = modelPickerScroll;
                    if (newSel < newScroll) newScroll = newSel;
                    setPickerOffset(newScroll, newSel);
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_DOWN) {
                    int newSel = Math.min(total - 1, modelPickerSelected + 1);
                    int newScroll = modelPickerScroll;
                    if (newSel >= newScroll + MODEL_PICKER_VISIBLE) {
                        newScroll = newSel - MODEL_PICKER_VISIBLE + 1;
                    }
                    setPickerOffset(newScroll, newSel);
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
                    int newSel = Math.max(0, modelPickerSelected - MODEL_PICKER_VISIBLE);
                    setPickerOffset(Math.max(0, newSel), newSel);
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
                    int newSel = Math.min(total - 1, modelPickerSelected + MODEL_PICKER_VISIBLE);
                    int newScroll = Math.min(maxScroll, Math.max(0, newSel - MODEL_PICKER_VISIBLE + 1));
                    setPickerOffset(newScroll, newSel);
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_HOME) {
                    setPickerOffset(0, 0);
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_END) {
                    setPickerOffset(maxScroll, total - 1);
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                    IntelligentTransmitterBlockEntity t = te();
                    if (t != null) {
                        String[] models = t.getLastModelList().split(",");
                        if (modelPickerSelected >= 0 && modelPickerSelected < models.length) {
                            String name = models[modelPickerSelected].trim();
                            if (!name.isEmpty()) {
                                selectModel(name);
                                return true;
                            }
                        }
                    }
                }
                if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                    closeModelPicker();
                    return true;
                }
            }
        }
        GuiEventListener focused = getFocused();
        if (focused instanceof EditBox editBox && editBox.isFocused()) {
            // Slash menu navigation has priority over the EditBox when the
            // palette is open. Esc dismisses the palette without losing
            // focus; Up/Down/Tab cycle the highlight; Enter commits the
            // highlighted entry. This mirrors the menu interactions in
            // vanilla chat command suggestions.
            if (menu.conversationMode && slashOpen) {
                List<SlashCommand> filtered = slashFiltered();
                if (keyCode == GLFW.GLFW_KEY_UP) {
                    if (filtered.isEmpty()) slashSelected = 0;
                    else slashSelected = (slashSelected - 1 + filtered.size()) % filtered.size();
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_DOWN) {
                    if (filtered.isEmpty()) slashSelected = 0;
                    else slashSelected = (slashSelected + 1) % filtered.size();
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_TAB) {
                    // Autocomplete — fill the input with the highlighted
                    // command name and a trailing space. Lets the user
                    // start typing a regular message right after.
                    if (!filtered.isEmpty()) {
                        SlashCommand c = filtered.get(Math.max(0, Math.min(slashSelected, filtered.size() - 1)));
                        firstField.setValue(c.name() + " ");
                    }
                    slashOpen = false;
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                    slashOpen = false;
                    return true;
                }
            }
            if (menu.conversationMode && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
                if (slashOpen) {
                    commitSlashSelection();
                } else {
                    sendChat();
                }
                return true;
            }
            if (keyCode != GLFW.GLFW_KEY_ESCAPE) {
                editBox.keyPressed(keyCode, scanCode, modifiers);
                updateSlashState();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (menu.conversationMode && firstField != null) {
            CompoundTag data = new CompoundTag();
            data.putString("ConversationDraft", firstField.getValue());
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new ModPackets.UpdateBlockConfigPayload(menu.pos, data));
        }
        super.onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // If a new turn arrived since the last frame, snap to the bottom.
        // We detect this by comparing the BE history size across frames.
        if (menu.conversationMode) {
            IntelligentTransmitterBlockEntity t = te();
            int current = t == null ? 0 : t.getHistory().size();
            if (lastSeenHistorySize >= 0 && current > lastSeenHistorySize) {
                initialScrollPending = true;
            }
            lastSeenHistorySize = current;
            // While a streaming delta is still being applied (BE revision
            // counter keeps advancing), keep the viewport pinned to the
            // bottom so the user sees text appearing in real time. This
            // matches what chat UIs do on every other platform.
            if (t != null) {
                int rev = t.getStreamingRevision();
                if (lastSeenStreamingRevision >= 0 && rev > lastSeenStreamingRevision) {
                    initialScrollPending = true;
                }
                lastSeenStreamingRevision = rev;
            }
        }
        super.render(g, mouseX, mouseY, partialTick);
        renderText(g);
        // Slash palette state must be refreshed after super.render so any
        // input changes the EditBox applied this frame are picked up before
        // we draw the menu. Cheap; just a String check.
        if (menu.conversationMode) updateSlashState();
        if (slashOpen) renderSlashMenu(g);
        // Picker overlay sits above the buttons (rebuilt in rebuildWidgets)
        // so the highlight + scrollbar stay aligned with whatever the
        // most recent rebuild produced. Same trick as renderSlashMenu.
        if (modelPickerOffset >= 0) renderModelPickerOverlay(g);
        // Question picker floats above the chat input when a 'question'
        // tool call is awaiting. Sits below the slash menu so a /command
        // typed in the input still shows over the question card.
        IntelligentTransmitterBlockEntity qt = te();
        if (qt != null && qt.getPendingQuestion() != null) {
            renderQuestionPicker(g, qt.getPendingQuestion());
        }
        renderTooltip(g, mouseX, mouseY);
    }

    /** Paints the floating question picker above the chat input. Layout
     *  follows the slash menu style: 1px blue frame, translucent dark
     *  background, amber accents for the active row. Sits flush against
     *  the top edge of the chat input so the user immediately sees the
     *  card as part of the chat UI. */
    private void renderQuestionPicker(GuiGraphics g,
                                      IntelligentTransmitterBlockEntity.PendingQuestion q) {
        int x = firstField.getX();
        int y = firstField.getY();
        int w = firstField.getWidth();
        int rowH = 12;
        // Frame: 1px blue border around the whole card, dark fill.
        int padTop = 4;
        int padBot = 4;
        int headerH = rowH + 2;        // prompt line
        int hintH = rowH;              // "↑↓ 选择  Enter 提交  Esc 关闭"
        int bodyH = q.options().size() * rowH;
        int totalH = padTop + headerH + bodyH + hintH + padBot;
        // Sits right above the input box. y is the top of the input,
        // so subtract totalH (plus a 1px gap) to make the bottom edge
        // of the card sit just above the input.
        int cardY = y - totalH - 1;
        // Card background.
        g.fill(x, cardY, x + w, cardY + totalH, 0xEE1A2030);
        g.fill(x, cardY, x + w, cardY + 1, 0xFF6EA8FE);
        g.fill(x, cardY + totalH - 1, x + w, cardY + totalH, 0xFF6EA8FE);
        g.fill(x, cardY, x + 1, cardY + totalH, 0xFF6EA8FE);
        g.fill(x + w - 1, cardY, x + w, cardY + totalH, 0xFF6EA8FE);
        // Header: [Question] <prompt> (amber)
        g.drawString(font, "[Question] " + q.prompt(), x + 4, cardY + padTop, 0xFFFFD080, false);
        // Body: numbered options
        int rowY = cardY + padTop + headerH;
        for (int i = 0; i < q.options().size(); i++) {
            String opt = q.options().get(i);
            String label;
            if ("other".equals(opt)) {
                label = (i + 1) + ". 自由输入 (other)";
            } else {
                label = (i + 1) + ". " + opt;
            }
            int textColor;
            if (i == questionSelected) {
                g.fill(x + 1, rowY, x + w - 1, rowY + rowH, 0x55B58CFF);
                textColor = questionFreeForm && "other".equals(opt) ? 0xFFFFD080 : 0xFFFFFFFF;
            } else {
                textColor = 0xFFCFE0CF;
            }
            g.drawString(font, label, x + 6, rowY + 1, textColor, false);
            rowY += rowH;
        }
        // Footer hint: keyboard shortcuts
        String hint = "↑↓ 选择  Enter 提交  Esc 关闭";
        g.drawString(font, hint, x + 4, cardY + padTop + headerH + bodyH + 1, 0xFF8FA8D8, false);
    }

    /** Draws the floating slash-command palette above the chat input. The
     *  panel background uses a translucent dark fill so the chat scrollback
     *  shows through; the highlighted row uses the same accent amber as
     *  the AI's streaming caret so it reads as the same UI family. */
    private void renderSlashMenu(GuiGraphics g) {
        List<SlashCommand> filtered = slashFiltered();
        if (filtered.isEmpty()) return;
        int x = firstField.getX();
        int y = firstField.getY() - filtered.size() * 12 - 4;
        int w = firstField.getWidth();
        int rowH = 12;
        // Soft shadow + background.
        g.fill(x - 1, y - 1, x + w + 1, y + filtered.size() * rowH + 1, 0xAA000000);
        g.fill(x, y, x + w, y + filtered.size() * rowH, 0xEE1A2030);
        g.fill(x, y, x + w, y + 1, 0xFF6EA8FE);
        g.fill(x, y + filtered.size() * rowH - 1, x + w, y + filtered.size() * rowH, 0xFF6EA8FE);
        g.fill(x, y, x + 1, y + filtered.size() * rowH, 0xFF6EA8FE);
        g.fill(x + w - 1, y, x + w, y + filtered.size() * rowH, 0xFF6EA8FE);
        for (int i = 0; i < filtered.size(); i++) {
            SlashCommand c = filtered.get(i);
            int rowY = y + i * rowH;
            if (i == slashSelected) {
                g.fill(x + 1, rowY, x + w - 1, rowY + rowH, 0x55B58CFF);
            }
            int nameColor = i == slashSelected ? 0xFFFFD080 : 0xFFE6EEF7;
            g.drawString(font, c.name(), x + 4, rowY + 2, nameColor, false);
            int descW = font.width(c.description());
            int descX = x + w - descW - 4;
            g.drawString(font, c.description(), descX, rowY + 2, 0xFF8FA8D8, false);
        }
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        // dark background
        g.fill(x, y, x + imageWidth, y + imageHeight, 0xEE10151C);
        // blue frame
        g.fill(x, y, x + imageWidth, y + 1, 0xFF6EA8FE);
        g.fill(x, y + imageHeight - 1, x + imageWidth, y + imageHeight, 0xFF6EA8FE);
        g.fill(x, y, x + 1, y + imageHeight, 0xFF6EA8FE);
        g.fill(x + imageWidth - 1, y, x + imageWidth, y + imageHeight, 0xFF6EA8FE);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // All labels are drawn in renderText() to control exact placement.
    }

    private void renderText(GuiGraphics g) {
        int x = this.leftPos + 12;
        int y = this.topPos;
        int color = 0xE8EEF7;
        g.drawString(font, titleForScreen(), x, y + 8, color, false);
        if (modelPickerOffset >= 0 && menu.conversationMode) {
            // The picker overlay is in charge of this view; show only a hint
            // line and a "click a model to select" prompt so the chat history
            // and chat input don't get visually clobbered.
            IntelligentTransmitterBlockEntity t = te();
            if (t != null && t.getLastModelList() != null && !t.getLastModelList().isEmpty()) {
                g.drawString(font,
                        tr("screen.llmera.label.pick_model_hint", String.valueOf(t.getModelName())),
                        x, y + 28, 0xFFAAFFAA, false);
            }
            return;
        }
        if (!statusMessage.getString().isBlank()) {
            g.drawString(font, statusMessage, x, y + 232, 0x9BE89B, false);
        }
        IntelligentTransmitterBlockEntity t = te();
        if (t == null) {
            g.drawString(font, tr("screen.llmera.error.block_entity_missing"), x, y + 28, 0xFF7777, false);
            return;
        }
        if (menu.conversationMode) renderConversationText(g, x, y, color, t);
        else renderTransmitterText(g, x, y, color, t);
    }

    private void renderConversationText(GuiGraphics g, int x, int y, int color, IntelligentTransmitterBlockEntity t) {
        boolean online = t.isOnline();
        g.drawString(font, online ? tr("screen.llmera.label.blaze_online") : tr("screen.llmera.label.blaze_offline"),
                x, y + 28, online ? 0x9BE89B : 0xFFAA66, false);
        g.drawString(font, tr("screen.llmera.label.model_name_value", blankValue(t.getModelName())),
                x, y + 42, color, false);
        if (pendingConfirmClear) {
            g.drawString(font, tr("screen.llmera.label.confirm_clear_hint"),
                    x, y + 62, 0xFFFFAA66, false);
        } else {
            g.drawString(font, tr("screen.llmera.label.ai_response"), x, y + 62, color, false);
        }
        renderHistory(g, t, x, y + 78, imageWidth - 36);
        g.drawString(font, tr("screen.llmera.label.chat_input"), x, y + 198, color, false);
        // "Thinking..." status in the top-right corner, right-aligned. It
        // used to sit at y+78 (overlapping the history viewport) then at
        // y+232 (overlapping the chat input). The top-right corner is empty
        // space next to the title and never overlaps any content.
        String last = blankValue(t.getLastConversationResponse());
        if (last.equals("Thinking...")) {
            int tw = font.width(last);
            g.drawString(font, last, x + imageWidth - tw - 8, y + 8, 0x9BE89B, false);
        }
    }

    /**
     * Walk the full history. Each turn gets a colored marker line; assistant
     * turns with tool_calls get a "tool: name(args)" annotation line in
     * yellow; tool results get a short result summary in green/red.
     */
    /** Hard cap on the number of formatted lines we materialize per render.
     *  The chat viewport shows 10 lines at a time, so anything past ~120 lines
     *  is invisible — building more would just waste heap. */
    private static final int MAX_FORMATTED_LINES = 120;
    /** Cap for the expanded-reasoning view. When the user has run
     *  {@code /expand}, we let one assistant turn's reasoning take up to
     *  this many formatted lines (and 5000 chars via wrapIntoFull's own
     *  cap) so a 4 KB deepseek-r1 trace fits in one history entry. Past
     *  this we still draw a "more" marker so the chat history doesn't
     *  explode out from under the screen geometry. */
    private static final int MAX_FORMATTED_LINES_EXPANDED = 200;
    /** Per-reasoning char cap used by {@link #wrapIntoFull} when expanded.
     *  5000 chars is roughly 50 lines of typical monospace, enough for a
     *  full deepseek-r1 thinking trace while still bounding memory. */
    private static final int MAX_REASONING_CHARS_EXPANDED = 5000;

    private void renderHistory(GuiGraphics g, IntelligentTransmitterBlockEntity t, int x, int y, int width) {
        List<FormattedLine> lines = new ArrayList<>();
        var history = t.getHistory();
        // Don't bail out early when history is empty — slash command logs
        // and the live streaming tail still need to render. The "empty"
        // hint only shows when there's nothing else to show.
        boolean streamingLive = t.getStreamingRevision() >= 0
                && (!t.getStreamingText().isEmpty() || !t.getStreamingReasoning().isEmpty());
        if (history.isEmpty() && slashLog.isEmpty() && !streamingLive) {
            g.drawString(font, tr("screen.llmera.conversation.empty").getString(),
                    x, y, 0xAAAAAA, false);
            return;
        }
        // Walk the history in FORWARD order (oldest first), so the formatted
        // list mirrors the natural chat order: old turns at the top, newest
        // at the bottom. We stop once we have enough lines for the viewport.
        // When the user has /expand enabled, raise the budget to the
        // expanded cap so a long thinking trace doesn't get folded back
        // into "⋯ (more, ...)" the moment history reaches 120 lines.
        int budget = t.isExpandReasoning() ? MAX_FORMATTED_LINES_EXPANDED : MAX_FORMATTED_LINES;
        int firstRenderedIdx = -1;
        for (int idx = 0; idx < history.size() && budget > 0; idx++) {
            if (firstRenderedIdx < 0) firstRenderedIdx = idx;
            IntelligentTransmitterBlockEntity.StoredTurn turn = history.get(idx);
            switch (turn.role()) {
                case "user" -> {
                    if (budget < 2) { budget = 0; break; }
                    String body = turn.content() == null ? "" : turn.content();
                    // "[模型提问] ..." is what the 'question' tool wrote
                    // into history. Visually it should read as the AI
                    // asking the user, not as the user typing a message.
                    if (body.startsWith("[模型提问] ")) {
                        lines.add(new FormattedLine("[模型提问]", 0xFFFFD080));
                        int beforeQ = lines.size();
                        wrapInto(lines, body.substring("[模型提问] ".length()), 0xFFE6EEF7, width);
                        budget -= (lines.size() - beforeQ);
                    } else {
                        lines.add(new FormattedLine("[" + playerName() + "]", 0xFFAAFFAA));
                        int beforeU = lines.size();
                        wrapInto(lines, body, 0xFFCFE0CF, width);
                        budget -= (lines.size() - beforeU);
                    }
                    lines.add(new FormattedLine("", 0xFFFFFF));
                }
                case "assistant" -> {
                    int before = lines.size();
                    if (t.isShowReasoning() && turn.reasoning() != null && !turn.reasoning().isEmpty()) {
                        String r = turn.reasoning();
                        lines.add(new FormattedLine("[" + blankValue(t.getAiName()) + " 思考]", 0xFFB58CFF));
                        if (t.isExpandReasoning()) {
                            // When expanding, strip the server-side
                            // truncation prefix. The model still sees
                            // the marker (so /truncate controls the
                            // upstream payload), but the user gets the
                            // original reasoning tail in full without
                            // a "[reasoning truncated, ... chars dropped
                            // from the head]" line above it.
                            String clean = stripTruncationPrefix(r);
                            // /expand is explicit consent to see the
                            // whole thing. wrapIntoFull has NO caps
                            // (no per-turn char limit, no per-turn line
                            // limit) so the user really does see all of
                            // it. The remaining safety net is budget:
                            // the outer renderHistory loop has a 120-line
                            // budget per history walk, and one expanded
                            // reasoning can eat the whole thing. That's
                            // intentional — the user asked for it. OOM
                            // from extremely long reasoning is mitigated
                            // by the stripTruncationPrefix above
                            // (truncated prefixes are dropped) and the
                            // fact that the BE itself caps reasoning at
                            // MAX_REASONING_CHARS when /truncate is on.
                            int beforeExpand = lines.size();
                            wrapIntoFull(lines, clean, 0xFFD9C0FF, width);
                            budget -= (lines.size() - beforeExpand);
                        } else {
                            String first = firstLineOf(r);
                            if (first.length() > 80) first = first.substring(0, 80) + "…";
                            wrapInto(lines, first.isEmpty() ? "(空)" : first, 0xFFD9C0FF, width);
                            if (r.indexOf('\n') >= 0) {
                                lines.add(new FormattedLine("  ⋯ (折叠, " + lineCount(r) + " 行) ⋯",
                                        0xFFB58CFF));
                            }
                        }
                    }
                    if (turn.toolCallsJson() != null && !turn.toolCallsJson().isEmpty()) {
                        if (!turn.content().isBlank()) {
                            lines.add(new FormattedLine("[" + blankValue(t.getAiName()) + "]", 0xFFAAAAFF));
                            wrapInto(lines, turn.content(), 0xFFD9E2F2, width);
                        }
                        if (toolsVisible()) {
                            for (String ann : renderToolCallAnnotations(turn.toolCallsJson())) {
                                if (budget <= 0) break;
                                lines.add(new FormattedLine(ann, 0xFFFFD24A));
                                budget--;
                            }
                        }
                    } else {
                        lines.add(new FormattedLine("[" + blankValue(t.getAiName()) + "]", 0xFFAAAAFF));
                        wrapInto(lines, turn.content() == null ? "" : turn.content(), 0xFFD9E2F2, width);
                    }
                    lines.add(new FormattedLine("", 0xFFFFFF));
                    budget -= (lines.size() - before) + 1;
                    if (budget <= 0) { idx = -1; break; }
                }
                case "tool" -> {
                    // When /tools is off, skip tool-result blocks entirely.
                    // The call still ran server-side; we just don't render it.
                    if (!toolsVisible()) continue;
                    if (budget < 2) { budget = 0; break; }
                    String label = "[tool result]";
                    int color = turn.content() != null && turn.content().contains("\"ok\":true")
                            ? 0xFF9BE89B : 0xFFFF8888;
                    int before = lines.size();
                    // Render the label as its own short line (font.width reports
                    // the real pixel cost), then wrap the content against the
                    // REMAINING width. This avoids the bug where the
                    // pre-concatenated "[tool result] " + content blew past
                    // `width` because shorten() measured in characters, not
                    // pixels.
                    lines.add(new FormattedLine(label, color));
                    budget--;
                    int remaining = Math.max(40, width - font.width(label) - 6);
                    wrapInto(lines, turn.content() == null ? "" : turn.content(), color, remaining);
                    budget -= (lines.size() - before);
                }
            }
        }
        // If we walked past the cap, prepend a marker so the user knows older
        // turns are folded (they live in the BE history but were skipped to
        // keep the formatted list bounded).
        if (firstRenderedIdx > 0) {
            lines.add(0, new FormattedLine("  ⋯ (前 " + firstRenderedIdx + " 条对话已折叠, 向上滚动查看) ⋯", 0xFF888888));
        }
        if (budget <= 0) {
            // We stopped before consuming the full history. Prepend a single
            // marker line so the user knows there's more by scrolling up.
            lines.add(new FormattedLine("  ⋯ (更早的对话已折叠, 滚动查看) ⋯", 0xFF888888));
        }
        // Slash command output. Renders as [system] lines in a fixed slot
        // between the regular history and the live streaming tail, so the
        // order is: history → system log → live typing. The [system] tag
        // is bright yellow to read as a system message, not a user or AI
        // turn. Each entry is one line, so wrapInto is only needed for
        // long help blocks (splitting on \n is a courtesy for /help's
        // multi-line output).
        if (!slashLog.isEmpty()) {
            for (String entry : slashLog) {
                for (String sub : entry.split("\n")) {
                    if (sub.startsWith("  ")) {
                        // continuation line (e.g. /help's body) — keep the
                        // indent and use the system body color.
                        lines.add(new FormattedLine(sub, 0xFFB0BCD4));
                    } else {
                        lines.add(new FormattedLine("[system] " + sub, 0xFFFFD080));
                    }
                }
            }
        }
        // Live streaming tail. While a turn is in flight, the BE accumulates
        // partial text deltas sent from the server; we render them as a
        // fresh "[AI Assistant]" block that grows in real time. On stream
        // end the server appends the final StoredTurn to history and
        // resets the BE's streamingText, so this block vanishes and the
        // history line takes over.
        if (t.getStreamingRevision() >= 0 && (!t.getStreamingText().isEmpty() || !t.getStreamingReasoning().isEmpty())) {
            lines.add(new FormattedLine("[" + aiName() + "]", 0xFFFFD080));
            if (t.isShowReasoning() && !t.getStreamingReasoning().isEmpty()) {
                lines.add(new FormattedLine("  思考:", 0xFF8FA8D8));
                // Strip the server-side truncation prefix from the live
                // buffer so the user doesn't see a stray
                // "[reasoning truncated, ...]" line while the model is
                // still streaming. Also: when /expand is on, route
                // through wrapIntoFull (5000 char cap, no fold
                // markers) so long thinking reads as one continuous
                // block during streaming — same as the history view.
                String cleanLive = stripTruncationPrefix(t.getStreamingReasoning());
                if (t.isExpandReasoning()) {
                    wrapIntoFull(lines, cleanLive, 0xFF8FA8D8, width);
                } else {
                    wrapInto(lines, cleanLive, 0xFF8FA8D8, width);
                }
            }
            if (!t.getStreamingText().isEmpty()) {
                wrapInto(lines, t.getStreamingText(), 0xFFE6EEF7, width);
            }
            // Blinking caret so the user knows text is still arriving.
            int caretTick = (int) (System.currentTimeMillis() / 400L) % 2;
            lines.add(new FormattedLine(caretTick == 0 ? "▍" : " ", 0xFFB0B0B0));
        }
        // Scroll viewport: imageHeight - 78 - 12 leaves about 110 px for history; 10 lines fit.
        renderScrollableHistory(g, lines, x, y, width);
    }

    /** Returns a list of one-line annotations for a tool_calls JSON array. */
    private List<String> renderToolCallAnnotations(String toolCallsJson) {
        List<String> out = new ArrayList<>();
        try {
            com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(toolCallsJson).getAsJsonArray();
            for (var el : arr) {
                var tc = el.getAsJsonObject();
                String name = tc.has("function") && tc.getAsJsonObject("function").has("name")
                        ? tc.getAsJsonObject("function").get("name").getAsString() : "?";
                // 'question' is special: it doesn't surface as a
                // tool-call annotation in the chat panel because the
                // floating picker above the input already shows the
                // full option list. Showing the JSON dump here would
                // just be visual noise.
                if ("question".equals(name)) continue;
                String args = tc.has("function") && tc.getAsJsonObject("function").has("arguments")
                        ? tc.getAsJsonObject("function").get("arguments").getAsString() : "{}";
                out.add("  ↳ " + name + "(" + shorten(args, 80) + ")");
            }
        } catch (Exception e) {
            out.add("  ↳ (parse error)");
        }
        return out;
    }

    /** Read the view-truncate flag from the BE. Defaults to true when
     *  the BE is unavailable (e.g. screen opened before sync). */
    private boolean viewTruncOn() {
        IntelligentTransmitterBlockEntity t = te();
        return t == null || t.isViewTruncateEnabled();
    }

    /** Read the show-tools flag from the BE. Defaults to true when the
     *  BE is unavailable. When false, tool-call annotations and tool
     *  result blocks are hidden from the rendered history. */
    private boolean toolsVisible() {
        IntelligentTransmitterBlockEntity t = te();
        return t == null || t.isShowTools();
    }

    /** Full-fidelity wrap. Like {@link #wrapInto} but with NO per-turn
     *  char or line cap — every line of the input is rendered, even if
     *  the result is 200 lines. Used by the /expand view for reasoning,
     *  where the user has explicitly asked to see everything and accepts
     *  the perf cost. OOM in this code path is the user's responsibility
     *  to manage: they can /expand off again. */
    private void wrapIntoFull(List<FormattedLine> sink, String text, int color, int width) {
        if (text == null || text.isEmpty()) return;
        // Cap depends on /viewtrunc: 5000 chars (default) or 10000 when
        // the user has turned display truncation off — still a safety
        // net against pathological 200 KB blocks, but generous enough
        // for the deepest reasoning traces.
        int cap = viewTruncOn() ? MAX_REASONING_CHARS_EXPANDED : 10000;
        if (text.length() > cap) {
            int drop = text.length() - cap;
            text = text.substring(0, cap)
                    + "\n[truncated, " + drop + " chars dropped from the tail]";
        }
        int idx = 0;
        while (idx < text.length()) {
            int nl = text.indexOf('\n', idx);
            int end = nl < 0 ? text.length() : nl;
            int seg = idx;
            while (seg < end) {
                int fit = seg;
                int curW = 0;
                while (fit < end) {
                    int cp = text.codePointAt(fit);
                    int charLen = Character.charCount(cp);
                    int chW = font.width(new String(Character.toChars(cp)));
                    if (curW + chW > width) {
                        break;
                    }
                    curW += chW;
                    fit += charLen;
                    if (curW >= width) {
                        break;
                    }
                }
                if (fit == seg) {
                    fit = seg + Character.charCount(text.codePointAt(seg));
                }
                sink.add(new FormattedLine(text.substring(seg, fit), color));
                seg = fit;
            }
            idx = end + 1; // skip the newline (or one past end)
        }
    }

    private void wrapInto(List<FormattedLine> sink, String text, int color, int width) {
        if (text == null || text.isEmpty()) return;
        // Hard cap on per-turn work. Skipped when /viewtrunc is off.
        final int MAX_CHARS_PER_TURN = 600;
        final int MAX_LINES_PER_TURN = 30;
        if (viewTruncOn() && text.length() > MAX_CHARS_PER_TURN) {
            text = text.substring(0, MAX_CHARS_PER_TURN) + " …[truncated " + (text.length() - MAX_CHARS_PER_TURN) + " chars]";
        }
        // Split on explicit newlines first.
        int idx = 0;
        int linesAdded = 0;
        int lineCap = viewTruncOn() ? MAX_LINES_PER_TURN : Integer.MAX_VALUE;
        outer:
        while (idx < text.length() && linesAdded < lineCap) {
            int nl = text.indexOf('\n', idx);
            int end = nl < 0 ? text.length() : nl;
            // Walk character-by-character, measuring INCREMENTAL width so we
            // know exactly when a line is full. CJK chars count as 2 UTF-16
            // units; we step by code point.
            int seg = idx;
            while (seg < end) {
                if (linesAdded >= lineCap) break outer;
                int fit = seg;
                int curW = 0;
                while (fit < end) {
                    int cp = text.codePointAt(fit);
                    int charLen = Character.charCount(cp);
                    int chW = font.width(new String(Character.toChars(cp)));
                    if (curW + chW > width) {
                        // next char overflows -> break here
                        break;
                    }
                    curW += chW;
                    fit += charLen;
                    if (curW >= width) {
                        break; // filled the line
                    }
                }
                if (fit == seg) {
                    // Pathological: a single code point wider than the line.
                    // Emit it alone so we always make forward progress.
                    fit = seg + Character.charCount(text.codePointAt(seg));
                }
                sink.add(new FormattedLine(text.substring(seg, fit), color));
                linesAdded++;
                seg = fit;
            }
            idx = end + 1; // skip the newline
        }
        if (viewTruncOn() && idx <= text.length() && linesAdded >= lineCap && idx < text.length()) {
            sink.add(new FormattedLine("  ⋯ (more, " + (text.length() - idx) + " chars) ⋯", 0xFF888888));
        }
    }

    private String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String firstLineOf(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    /** Strip the server-side truncation marker the LLM-side
     *  {@code ChatEngine.truncateReasoning} prepends when an upstream
     *  trace was too long to keep whole. The marker looks like
     *  {@code [reasoning truncated, 1234 chars dropped from the head]\n}
     *  followed by the surviving tail. We drop everything up to and
     *  including the first newline so the expanded view reads as the
     *  natural reasoning, not a debug log. If the marker isn't there
     *  (e.g. /truncate is off) the string passes through untouched. */
    private static String stripTruncationPrefix(String s) {
        if (s == null) return "";
        if (s.startsWith("[reasoning truncated, ")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) return s.substring(nl + 1);
        }
        return s;
    }

    private static int lineCount(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    private record FormattedLine(String text, int color) {}

    private void renderScrollableHistory(GuiGraphics g, List<FormattedLine> lines, int x, int y, int width) {
        // Render into a 10-line viewport.
        final int maxLines = 10;
        int totalLines = lines.size();
        conversationMaxScroll = Math.max(0, totalLines - maxLines);
        // On the first paint after opening the screen, snap to the bottom so
        // the latest messages are visible. After that, honor the player's
        // manual scroll position.
        if (initialScrollPending) {
            // Forward-order list: lines[0] = oldest, lines[last] = newest.
            // scroll = max means "pin to the bottom" so the latest message
            // is visible.
            conversationScroll = conversationMaxScroll;
            initialScrollPending = false;
        } else {
            conversationScroll = clampScroll(conversationScroll);
        }
        int lineH = 11;
        for (int i = 0; i < maxLines && i + conversationScroll < totalLines; i++) {
            FormattedLine ln = lines.get(i + conversationScroll);
            g.drawString(font, ln.text(), x, y + i * lineH, ln.color(), false);
        }
        if (conversationMaxScroll > 0) {
            int trackX = x + width + 5;
            int trackHeight = maxLines * lineH - 1;
            int thumbHeight = Math.max(12, trackHeight * maxLines / totalLines);
            int thumbY = y + (trackHeight - thumbHeight) * conversationScroll / conversationMaxScroll;
            g.fill(trackX, y, trackX + 2, y + trackHeight, 0x665B6B82);
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, 0xFF9FB6D8);
        }
    }

    private void renderTransmitterText(GuiGraphics g, int x, int y, int color, IntelligentTransmitterBlockEntity t) {
        g.drawString(font, tr("screen.llmera.label.model_url"), x, y + 24, color, false);
        g.drawString(font, tr("screen.llmera.label.api_key"), x, y + 54, color, false);
        g.drawString(font, tr("screen.llmera.label.model_name"), x, y + 84, color, false);
        g.drawString(font, tr("screen.llmera.label.ai_name"), x, y + 114, color, false);
        g.drawString(font, tr("screen.llmera.label.system_prompt"), x, y + 144, color, false);

        int infoX = x + 164;
        g.drawString(font, tr("screen.llmera.label.network_id", t.getNetworkId()),
                infoX, y + 34, 0xAFC8FF, false);
        boolean online = t.isOnline();
        g.drawString(font, online ? tr("screen.llmera.label.blaze_online") : tr("screen.llmera.label.blaze_offline"),
                infoX, y + 48, online ? 0x9BE89B : 0xFFAA66, false);
        g.drawString(font, tr("screen.llmera.label.model_list"), infoX, y + 64, color, false);
        g.drawString(font, trim(blankValue(t.getLastModelList()), 30), infoX, y + 78, 0xD9E2F2, false);
        g.drawString(font, tr("screen.llmera.label.fingerprint_prefix",
                        t.fingerprint().isEmpty() ? "-" : t.fingerprint()),
                infoX, y + 92, 0xAFC8FF, false);
        g.drawString(font, tr("screen.llmera.label.bound_entries"), infoX, y + 108, color, false);
        // v1 has no tool/skill bindings.
        g.drawString(font, tr("screen.llmera.label.bound_none_v1"), infoX, y + 124, 0x888888, false);
    }

    private void renderScrollableConversation(GuiGraphics g, Component text, int x, int y, int width, int color) {
        // Deprecated: kept as a no-op stub. The conversation view now uses
        // renderHistory() which walks t.getHistory() and shows tool_call
        // annotations per assistant turn.
    }

    private int clampScroll(int scroll) {
        return Math.max(0, Math.min(scroll, conversationMaxScroll));
    }

    private Component titleForScreen() {
        return menu.conversationMode
                ? tr("screen.llmera.transmitter.conversation")
                : tr("screen.llmera.title.transmitter");
    }

    private static String blankValue(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String playerName() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player == null ? "Player" : minecraft.player.getName().getString();
    }

    /** Display name of the AI for the current transmitter, or a sensible
     *  default if the BE hasn't been populated yet (e.g. right after
     *  opening the screen for the first time). */
    private String aiName() {
        var t = te();
        String n = t == null ? null : t.getAiName();
        if (n == null || n.isBlank()) n = "AI Assistant";
        return n;
    }

    private static String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value == null ? "" : value;
        return value.substring(0, maxLength - 3) + "...";
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable(key, args);
    }
}

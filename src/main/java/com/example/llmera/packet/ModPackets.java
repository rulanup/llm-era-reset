package com.example.llmera.packet;

import com.example.llmera.LLMEra;
import com.example.llmera.network.IntelligentTransmitterBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Single bidirectional payload for all transmitter updates. The {@code data}
 * CompoundTag carries arbitrary string/bool keys understood by
 * {@link IntelligentTransmitterBlockEntity#applyClientData(CompoundTag)} or by
 * the chat/save dispatch logic in {@link #handle}.
 */
@EventBusSubscriber(modid = LLMEra.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModPackets {
    private ModPackets() {}

    public record UpdateBlockConfigPayload(BlockPos pos, CompoundTag data) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<UpdateBlockConfigPayload> TYPE =
                new CustomPacketPayload.Type<>(LLMEra.id("update_block_config"));
        public static final StreamCodec<ByteBuf, UpdateBlockConfigPayload> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, UpdateBlockConfigPayload::pos,
                ByteBufCodecs.COMPOUND_TAG, UpdateBlockConfigPayload::data,
                UpdateBlockConfigPayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Server → client streaming delta. Sends only the chunks that arrived
     * since the last flush, not the full accumulated text. {@code seq} is
     * the stream turn id (incremented on every new assistant turn) and
     * lets the client discard stale chunks from a previous turn after a
     * Stop / new prompt. Text deltas are UTF-8 strings; both fields may
     * be empty (e.g. reasoning-only flush, or text-only flush).
     */
    public record StreamDeltaPayload(BlockPos pos, int seq, String textDelta, String reasoningDelta) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<StreamDeltaPayload> TYPE =
                new CustomPacketPayload.Type<>(LLMEra.id("stream_delta"));
        public static final StreamCodec<ByteBuf, StreamDeltaPayload> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, StreamDeltaPayload::pos,
                ByteBufCodecs.VAR_INT, StreamDeltaPayload::seq,
                ByteBufCodecs.STRING_UTF8, StreamDeltaPayload::textDelta,
                ByteBufCodecs.STRING_UTF8, StreamDeltaPayload::reasoningDelta,
                StreamDeltaPayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Server → client stream-end marker. Sends the final accumulated text
     *  + reasoning (same fields as a delta, but the client handler treats
     *  this as "this turn is over; commit to history / clear streaming
     *  state"). */
    public record StreamEndPayload(BlockPos pos, int seq, String finalText, String finalReasoning) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<StreamEndPayload> TYPE =
                new CustomPacketPayload.Type<>(LLMEra.id("stream_end"));
        public static final StreamCodec<ByteBuf, StreamEndPayload> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, StreamEndPayload::pos,
                ByteBufCodecs.VAR_INT, StreamEndPayload::seq,
                ByteBufCodecs.STRING_UTF8, StreamEndPayload::finalText,
                ByteBufCodecs.STRING_UTF8, StreamEndPayload::finalReasoning,
                StreamEndPayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");
        r.playBidirectional(
                UpdateBlockConfigPayload.TYPE, UpdateBlockConfigPayload.STREAM_CODEC,
                ModPackets::handle);
        r.playToClient(
                StreamDeltaPayload.TYPE, StreamDeltaPayload.STREAM_CODEC,
                ModPackets::handleStreamDelta);
        r.playToClient(
                StreamEndPayload.TYPE, StreamEndPayload.STREAM_CODEC,
                ModPackets::handleStreamEnd);
    }

    private static void handle(UpdateBlockConfigPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
            if (!(sp.level().getBlockEntity(payload.pos()) instanceof IntelligentTransmitterBlockEntity t)) {
                LLMEra.LOGGER.warn("[{}] payload for unknown block entity at {}", LLMEra.MODID, payload.pos());
                return;
            }
            CompoundTag data = payload.data();

            // Chat send
            if (data.contains("ChatPrompt")) {
                String prompt = data.getString("ChatPrompt");
                t.sendConversationAsync(prompt, sp);
                return;
            }
            // New chat
            if (data.contains("ClearConversation") && data.getBoolean("ClearConversation")) {
                t.clearConversation();
                return;
            }
            // Stop the running agent loop on this BE. Client clicks "Stop"
            // while an LLM is mid-flight or between turns.
            if (data.contains("StopAgent") && data.getBoolean("StopAgent")) {
                t.requestAgentStop();
                t.setAgentRunning(false);
                return;
            }
            // Fetch model list from the LLM endpoint
            if (data.contains("FetchModels") && data.getBoolean("FetchModels")) {
                String ep = t.getModelUrl();
                String key = t.decryptedApiKey();
                if (ep == null || ep.isEmpty()) {
                    t.setLastModelList("ERR endpoint not set");
                    return;
                }
                int timeout = com.example.llmera.config.LLMEraConfig.REQUEST_TIMEOUT_SECONDS.get();
                com.example.llmera.llm.ModelLister lister = new com.example.llmera.llm.ModelLister();
                String csv = lister.fetch(ep, key == null ? "" : key, timeout);
                t.setLastModelList(csv);
                return;
            }
            // Save config
            if (data.contains("ModelUrl") || data.contains("ApiKey") || data.contains("ModelName")
                    || data.contains("AiName") || data.contains("SystemPrompt") || data.contains("ConversationDraft")
                    || data.contains("TruncateEnabled")
                    || data.contains("ShowReasoning")
                    || data.contains("ExpandReasoning")) {
                t.applyClientData(data);
            }
        });
    }

    private static void handleStreamDelta(StreamDeltaPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player().level().getBlockEntity(payload.pos()) instanceof IntelligentTransmitterBlockEntity t)) return;
            // First packet of a new turn (or packet from a turn the client
            // hadn't heard about yet, e.g. the screen was opened mid-stream):
            // reset the streaming buffers to the new turn id so this packet
            // is treated as the start, not as a stale leftover.
            if (payload.seq() != t.getStreamingSeq()) {
                t.resetStreaming(payload.seq());
            }
            t.applyStreamingDelta(payload.textDelta(), payload.reasoningDelta(), payload.seq());
        });
    }

    private static void handleStreamEnd(StreamEndPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player().level().getBlockEntity(payload.pos()) instanceof IntelligentTransmitterBlockEntity t)) return;
            // Same staleness rule as deltas: if the end packet is for a
            // different turn than what we currently show, just adopt the
            // new id and clear — don't apply (the deltas already brought
            // the text up to date).
            if (payload.seq() != t.getStreamingSeq()) {
                t.resetStreaming(payload.seq());
                return;
            }
            // Apply the final delta so the buffer holds the complete text
            // (catches anything still in the server-side buffer that didn't
            // make it into a per-flush packet). Then commit to history.
            t.applyStreamingDelta(payload.finalText(), payload.finalReasoning(), payload.seq());
            // The actual history append happens in ChatEngine — the server
            // owns history state and the final StoredTurn is already written
            // by the time it sends StreamEnd. We just clear the streaming
            // buffers on the client so the renderer falls back to the
            // regular history view.
            t.resetStreaming(payload.seq());
        });
    }
}

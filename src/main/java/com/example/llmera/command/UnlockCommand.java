package com.example.llmera.command;

import com.example.llmera.LLMEra;
import com.example.llmera.network.IntelligentTransmitterBlockEntity;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@EventBusSubscriber(modid = LLMEra.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class UnlockCommand {
    private UnlockCommand() {}

    /**
     * v1 stub: holds an in-memory marker that "unlocks" v1 plaintext envelope.
     * Real private-key handling arrives in v2 alongside RsaAesCodec.
     * The marker is per-server process; restarting the server re-locks.
     */
    private static final AtomicReference<String> UNLOCK_TOKEN = new AtomicReference<>(null);

    public static boolean isUnlocked() {
        return UNLOCK_TOKEN.get() != null;
    }

    public static String currentFingerprint() {
        String t = UNLOCK_TOKEN.get();
        return t == null ? "<locked>" : IntelligentTransmitterBlockEntity.fingerprintOf(t);
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("llmera")
                .then(Commands.literal("unlock")
                        .then(Commands.argument("token", StringArgumentType.greedyString())
                                .executes(UnlockCommand::unlock)))
                .then(Commands.literal("lock").executes(UnlockCommand::lock))
                .then(Commands.literal("status").executes(UnlockCommand::status))
        );
    }

    private static int unlock(CommandContext<CommandSourceStack> ctx) {
        String token = StringArgumentType.getString(ctx, "token");
        UNLOCK_TOKEN.set(token);
        ctx.getSource().sendSystemMessage(Component.literal(
                "[llmera] unlock token accepted (fingerprint=" + currentFingerprint() + ")"));
        return 1;
    }

    private static int lock(CommandContext<CommandSourceStack> ctx) {
        boolean was = isUnlocked();
        UNLOCK_TOKEN.set(null);
        ctx.getSource().sendSystemMessage(Component.literal(
                was ? "[llmera] locked" : "[llmera] already locked"));
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSystemMessage(Component.literal(
                "[llmera] codec=v1-plaintext unlocked=" + isUnlocked()
                        + " token-fingerprint=" + currentFingerprint()));
        return 1;
    }
}

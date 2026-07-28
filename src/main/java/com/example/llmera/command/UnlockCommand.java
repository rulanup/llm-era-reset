package com.example.llmera.command;

import com.example.llmera.LLMEra;
import com.example.llmera.network.IntelligentTransmitterBlockEntity;
import com.example.llmera.network.SkillBoardBlockEntity;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

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
                .then(Commands.literal("skill")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("set")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .then(Commands.argument("description", StringArgumentType.string())
                                                        .then(Commands.argument("steps", StringArgumentType.greedyString())
                                                                .executes(UnlockCommand::setSkill))))))
                        .then(Commands.literal("clear")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(UnlockCommand::clearSkill)))
                        .then(Commands.literal("status")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(UnlockCommand::skillStatus)))
                ));
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

    private static int setSkill(CommandContext<CommandSourceStack> ctx) {
        SkillBoardBlockEntity board = skillBoard(ctx);
        if (board == null) return 0;
        String steps = StringArgumentType.getString(ctx, "steps");
        board.setSkill(StringArgumentType.getString(ctx, "name"),
                StringArgumentType.getString(ctx, "description"),
                java.util.Arrays.asList(steps.split("\\|", -1)), true);
        ctx.getSource().sendSuccess(() -> Component.literal("[llmera] skill written to "
                + board.getBlockPos() + ": " + board.getSkillName()), true);
        return 1;
    }

    private static int clearSkill(CommandContext<CommandSourceStack> ctx) {
        SkillBoardBlockEntity board = skillBoard(ctx);
        if (board == null) return 0;
        board.clearSkill();
        ctx.getSource().sendSuccess(() -> Component.literal("[llmera] skill cleared at " + board.getBlockPos()), true);
        return 1;
    }

    private static int skillStatus(CommandContext<CommandSourceStack> ctx) {
        SkillBoardBlockEntity board = skillBoard(ctx);
        if (board == null) return 0;
        ctx.getSource().sendSuccess(() -> Component.literal("[llmera] skill=" + board.getSkillName()
                + " enabled=" + board.isEnabled() + " steps=" + board.getSteps().size()), false);
        return 1;
    }

    private static SkillBoardBlockEntity skillBoard(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) return null;
        BlockPos pos;
        try {
            pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        } catch (CommandSyntaxException e) {
            ctx.getSource().sendFailure(Component.literal("[llmera] invalid or unloaded skill board position"));
            return null;
        }
        if (level.getBlockEntity(pos) instanceof SkillBoardBlockEntity board) return board;
        ctx.getSource().sendFailure(Component.literal("[llmera] no skill board at " + pos));
        return null;
    }
}

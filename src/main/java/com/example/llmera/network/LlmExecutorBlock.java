package com.example.llmera.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

public class LlmExecutorBlock extends Block implements EntityBlock, CodeNetworkMember {
    private final LlmToolMode mode;

    public LlmExecutorBlock(LlmToolMode mode) {
        super(Properties.of().mapColor(MapColor.COLOR_GREEN).strength(2.0f));
        this.mode = mode;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LlmExecutorBlockEntity(pos, state);
    }

    public LlmToolMode mode() {
        return mode;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof LlmExecutorBlockEntity t) {
            if (level instanceof ServerLevel serverLevel) {
                CodeNetworkScanner.NetworkView view = CodeNetworkScanner.scan(serverLevel, pos);
                if (view.configurator() != null) {
                    t.mirrorDisplayConfigFrom(view.configurator());
                }
            }
            player.openMenu(t, buf -> {
                buf.writeBlockPos(pos);
                buf.writeBoolean(true);
            });
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }
}

package com.example.llmera.network;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

public class IntelligentTransmitterBlock extends Block implements EntityBlock {
    public IntelligentTransmitterBlock() {
        super(Properties.of().mapColor(MapColor.METAL).strength(2.0f));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new IntelligentTransmitterBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                                BlockHitResult hitResult) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof IntelligentTransmitterBlockEntity t) {
            // Open the configuration view by default. Right-click again on the
            // chat button in the screen to flip into conversation mode.
            player.openMenu(t, buf -> {
                buf.writeBlockPos(pos);
                buf.writeBoolean(false);
            });
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }
}

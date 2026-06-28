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

public class LlmConfiguratorBlock extends Block implements EntityBlock, CodeNetworkMember {
    public LlmConfiguratorBlock() {
        super(Properties.of().mapColor(MapColor.COLOR_BLUE).strength(2.0f));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LlmConfiguratorBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof LlmConfiguratorBlockEntity t) {
            player.openMenu(t, buf -> {
                buf.writeBlockPos(pos);
                buf.writeBoolean(false);
            });
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }
}

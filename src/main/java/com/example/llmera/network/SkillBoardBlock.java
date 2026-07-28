package com.example.llmera.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

public final class SkillBoardBlock extends Block implements EntityBlock, CodeNetworkMember {
    public SkillBoardBlock() {
        super(Properties.of().mapColor(MapColor.COLOR_GREEN).strength(2.0f));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SkillBoardBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof SkillBoardBlockEntity board) {
            String name = board.getSkillName().isBlank() ? "<empty>" : board.getSkillName();
            player.displayClientMessage(Component.literal("Skill Board: " + name
                    + " (" + board.getSteps().size() + " steps, enabled=" + board.isEnabled() + ")"), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}

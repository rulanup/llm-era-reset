package com.example.llmera.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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

public class Ae2NetworkBridgeBlock extends Block implements EntityBlock, CodeNetworkMember {
    public Ae2NetworkBridgeBlock() {
        super(Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(2.5f));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Ae2NetworkBridgeBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
            CodeNetworkScanner.NetworkView view = CodeNetworkScanner.scan(serverLevel, pos);
            int bridgeCount = view.ae2Bridges().size();
            player.displayClientMessage(Component.literal("AE2 Network Bridge: "
                    + bridgeCount + " bridge(s) on code network"), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}

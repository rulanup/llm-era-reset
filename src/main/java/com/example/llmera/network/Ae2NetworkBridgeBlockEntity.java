package com.example.llmera.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class Ae2NetworkBridgeBlockEntity extends BlockEntity implements CodeNetworkMember {
    public Ae2NetworkBridgeBlockEntity(BlockPos pos, BlockState state) {
        super(LLMEraBlockEntities.AE2_NETWORK_BRIDGE.get(), pos, state);
    }

    public Component getDisplayName() {
        return Component.translatable("block.llmera.ae2_network_bridge");
    }
}

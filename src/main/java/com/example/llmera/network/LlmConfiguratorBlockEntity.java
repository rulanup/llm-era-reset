package com.example.llmera.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

public class LlmConfiguratorBlockEntity extends IntelligentTransmitterBlockEntity implements CodeNetworkMember {
    public LlmConfiguratorBlockEntity(BlockPos pos, BlockState state) {
        super(LLMEraBlockEntities.LLM_CONFIGURATOR.get(), pos, state);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.llmera.llm_configurator");
    }
}

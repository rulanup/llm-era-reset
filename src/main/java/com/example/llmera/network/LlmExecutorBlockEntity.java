package com.example.llmera.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

public class LlmExecutorBlockEntity extends IntelligentTransmitterBlockEntity implements CodeNetworkMember {
    public LlmExecutorBlockEntity(BlockPos pos, BlockState state) {
        super(LLMEraBlockEntities.LLM_EXECUTOR.get(), pos, state);
    }

    public LlmToolMode getMode() {
        if (getBlockState().getBlock() instanceof LlmExecutorBlock executorBlock) {
            return executorBlock.mode();
        }
        return LlmToolMode.CHAT;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.llmera.llm_" + getMode().getSerializedName() + "_executor");
    }
}

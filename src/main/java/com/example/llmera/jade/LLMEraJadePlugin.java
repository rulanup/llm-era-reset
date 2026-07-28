package com.example.llmera.jade;

import com.example.llmera.network.CodeCableBlock;
import com.example.llmera.network.CodeNetworkControllerBlock;
import com.example.llmera.network.CodeNetworkControllerBlockEntity;
import com.example.llmera.network.LlmConfiguratorBlock;
import com.example.llmera.network.LlmConfiguratorBlockEntity;
import com.example.llmera.network.LlmExecutorBlock;
import com.example.llmera.network.LlmExecutorBlockEntity;
import com.example.llmera.network.SkillBoardBlock;
import com.example.llmera.network.SkillBoardBlockEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

@WailaPlugin
public final class LLMEraJadePlugin implements IWailaPlugin {
    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(NetworkStatusProvider.INSTANCE, CodeNetworkControllerBlockEntity.class);
        registration.registerBlockDataProvider(NetworkStatusProvider.INSTANCE, CodeCableBlock.class);
        registration.registerBlockDataProvider(NetworkStatusProvider.INSTANCE, LlmConfiguratorBlockEntity.class);
        registration.registerBlockDataProvider(NetworkStatusProvider.INSTANCE, LlmExecutorBlockEntity.class);
        registration.registerBlockDataProvider(NetworkStatusProvider.INSTANCE, SkillBoardBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(NetworkStatusProvider.INSTANCE, CodeNetworkControllerBlock.class);
        registration.registerBlockComponent(NetworkStatusProvider.INSTANCE, CodeCableBlock.class);
        registration.registerBlockComponent(NetworkStatusProvider.INSTANCE, LlmConfiguratorBlock.class);
        registration.registerBlockComponent(NetworkStatusProvider.INSTANCE, LlmExecutorBlock.class);
        registration.registerBlockComponent(NetworkStatusProvider.INSTANCE, SkillBoardBlock.class);
    }
}

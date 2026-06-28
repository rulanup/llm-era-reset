package com.example.llmera.network;

import com.example.llmera.LLMEra;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class LLMEraBlockEntities {
    private LLMEraBlockEntities() {}

    public static final DeferredRegister<BlockEntityType<?>> REGISTRY =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, LLMEra.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<IntelligentTransmitterBlockEntity>> TRANSMITTER =
            REGISTRY.register("intelligent_transmitter", () ->
                    BlockEntityType.Builder.of(
                            IntelligentTransmitterBlockEntity::new,
                            LLMEra.TRANSMITTER_BLOCK.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CodeNetworkControllerBlockEntity>> CODE_NETWORK_CONTROLLER =
            REGISTRY.register("code_network_controller", () ->
                    BlockEntityType.Builder.of(
                            CodeNetworkControllerBlockEntity::new,
                            LLMEra.CODE_NETWORK_CONTROLLER_BLOCK.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LlmConfiguratorBlockEntity>> LLM_CONFIGURATOR =
            REGISTRY.register("llm_configurator", () ->
                    BlockEntityType.Builder.of(
                            LlmConfiguratorBlockEntity::new,
                            LLMEra.LLM_CONFIGURATOR_BLOCK.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<Ae2NetworkBridgeBlockEntity>> AE2_NETWORK_BRIDGE =
            REGISTRY.register("ae2_network_bridge", () ->
                    BlockEntityType.Builder.of(
                            Ae2NetworkBridgeBlockEntity::new,
                            LLMEra.AE2_NETWORK_BRIDGE_BLOCK.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LlmExecutorBlockEntity>> LLM_EXECUTOR =
            REGISTRY.register("llm_executor", () ->
                    BlockEntityType.Builder.of(
                            LlmExecutorBlockEntity::new,
                            LLMEra.LLM_BUILD_EXECUTOR_BLOCK.get(),
                            LLMEra.LLM_PLAN_EXECUTOR_BLOCK.get(),
                            LLMEra.LLM_CHAT_EXECUTOR_BLOCK.get()).build(null));
}

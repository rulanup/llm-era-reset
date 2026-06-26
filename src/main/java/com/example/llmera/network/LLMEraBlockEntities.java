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
}

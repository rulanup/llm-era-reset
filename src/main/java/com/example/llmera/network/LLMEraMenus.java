package com.example.llmera.network;

import com.example.llmera.LLMEra;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class LLMEraMenus {
    private LLMEraMenus() {}

    public static final DeferredRegister<MenuType<?>> REGISTRY =
            DeferredRegister.create(BuiltInRegistries.MENU, LLMEra.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<IntelligentTransmitterMenu>> TRANSMITTER =
            REGISTRY.register("intelligent_transmitter",
                    () -> IMenuTypeExtension.create(IntelligentTransmitterMenu::clientFromBuffer));
}

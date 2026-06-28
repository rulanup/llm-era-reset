package com.example.llmera.network;

import com.example.llmera.LLMEra;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

@EventBusSubscriber(modid = LLMEra.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class LLMEraCapabilities {
    private LLMEraCapabilities() {
    }

    @SubscribeEvent
    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                LLMEraBlockEntities.CODE_NETWORK_CONTROLLER.get(),
                (controller, side) -> controller.energyStorage());
    }
}

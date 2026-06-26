package com.example.llmera.client;

import com.example.llmera.LLMEra;
import com.example.llmera.network.IntelligentTransmitterMenu;
import com.example.llmera.network.LLMEraMenus;
import net.minecraft.client.gui.screens.MenuScreens;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = LLMEra.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientSetup {
    private ClientSetup() {}

    @SubscribeEvent
    public static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(LLMEraMenus.TRANSMITTER.get(), TransmitterScreen::new);
    }
}

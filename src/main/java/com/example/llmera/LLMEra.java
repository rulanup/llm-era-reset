package com.example.llmera;

import com.example.llmera.config.LLMEraConfig;
import com.example.llmera.network.IntelligentTransmitterBlock;
import com.example.llmera.network.LLMEraBlockEntities;
import com.example.llmera.network.LLMEraMenus;
import com.mojang.logging.LogUtils;
import net.minecraft.world.item.BlockItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(LLMEra.MODID)
public class LLMEra {

    public static final String MODID = "llmera";
    public static final String VERSION = "0.1";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static net.minecraft.resources.ResourceLocation id(String path) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    public static final DeferredBlock<IntelligentTransmitterBlock> TRANSMITTER_BLOCK =
            BLOCKS.register("intelligent_transmitter", IntelligentTransmitterBlock::new);
    public static final DeferredItem<BlockItem> TRANSMITTER_ITEM =
            ITEMS.registerSimpleBlockItem("intelligent_transmitter", TRANSMITTER_BLOCK);

    public LLMEra(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(
                net.neoforged.fml.config.ModConfig.Type.SERVER,
                LLMEraConfig.SPEC, "llmera-server.toml");
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        LLMEraBlockEntities.REGISTRY.register(modEventBus);
        LLMEraMenus.REGISTRY.register(modEventBus);
    }
}

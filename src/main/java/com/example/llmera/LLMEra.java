package com.example.llmera;

import com.example.llmera.config.LLMEraConfig;
import com.example.llmera.network.Ae2NetworkBridgeBlock;
import com.example.llmera.network.CodeCableBlock;
import com.example.llmera.network.CodeNetworkControllerBlock;
import com.example.llmera.network.IntelligentTransmitterBlock;
import com.example.llmera.network.LlmConfiguratorBlock;
import com.example.llmera.network.LlmExecutorBlock;
import com.example.llmera.network.LLMEraBlockEntities;
import com.example.llmera.network.LLMEraMenus;
import com.mojang.logging.LogUtils;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
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
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredBlock<IntelligentTransmitterBlock> TRANSMITTER_BLOCK =
            BLOCKS.register("intelligent_transmitter", IntelligentTransmitterBlock::new);
    public static final DeferredItem<BlockItem> TRANSMITTER_ITEM =
            ITEMS.registerSimpleBlockItem("intelligent_transmitter", TRANSMITTER_BLOCK);
    public static final DeferredBlock<CodeNetworkControllerBlock> CODE_NETWORK_CONTROLLER_BLOCK =
            BLOCKS.register("code_network_controller", CodeNetworkControllerBlock::new);
    public static final DeferredItem<BlockItem> CODE_NETWORK_CONTROLLER_ITEM =
            ITEMS.registerSimpleBlockItem("code_network_controller", CODE_NETWORK_CONTROLLER_BLOCK);
    public static final DeferredBlock<CodeCableBlock> CODE_CABLE_BLOCK =
            BLOCKS.register("code_cable", CodeCableBlock::new);
    public static final DeferredItem<BlockItem> CODE_CABLE_ITEM =
            ITEMS.registerSimpleBlockItem("code_cable", CODE_CABLE_BLOCK);
    public static final DeferredBlock<LlmConfiguratorBlock> LLM_CONFIGURATOR_BLOCK =
            BLOCKS.register("llm_configurator", LlmConfiguratorBlock::new);
    public static final DeferredItem<BlockItem> LLM_CONFIGURATOR_ITEM =
            ITEMS.registerSimpleBlockItem("llm_configurator", LLM_CONFIGURATOR_BLOCK);
    public static final DeferredBlock<Ae2NetworkBridgeBlock> AE2_NETWORK_BRIDGE_BLOCK =
            BLOCKS.register("ae2_network_bridge", Ae2NetworkBridgeBlock::new);
    public static final DeferredItem<BlockItem> AE2_NETWORK_BRIDGE_ITEM =
            ITEMS.registerSimpleBlockItem("ae2_network_bridge", AE2_NETWORK_BRIDGE_BLOCK);
    public static final DeferredBlock<LlmExecutorBlock> LLM_BUILD_EXECUTOR_BLOCK =
            BLOCKS.register("llm_build_executor", () -> new LlmExecutorBlock(com.example.llmera.network.LlmToolMode.BUILD));
    public static final DeferredItem<BlockItem> LLM_BUILD_EXECUTOR_ITEM =
            ITEMS.registerSimpleBlockItem("llm_build_executor", LLM_BUILD_EXECUTOR_BLOCK);
    public static final DeferredBlock<LlmExecutorBlock> LLM_PLAN_EXECUTOR_BLOCK =
            BLOCKS.register("llm_plan_executor", () -> new LlmExecutorBlock(com.example.llmera.network.LlmToolMode.PLAN));
    public static final DeferredItem<BlockItem> LLM_PLAN_EXECUTOR_ITEM =
            ITEMS.registerSimpleBlockItem("llm_plan_executor", LLM_PLAN_EXECUTOR_BLOCK);
    public static final DeferredBlock<LlmExecutorBlock> LLM_CHAT_EXECUTOR_BLOCK =
            BLOCKS.register("llm_chat_executor", () -> new LlmExecutorBlock(com.example.llmera.network.LlmToolMode.CHAT));
    public static final DeferredItem<BlockItem> LLM_CHAT_EXECUTOR_ITEM =
            ITEMS.registerSimpleBlockItem("llm_chat_executor", LLM_CHAT_EXECUTOR_BLOCK);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB =
            CREATIVE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.llmera"))
                    .icon(() -> new ItemStack(CODE_NETWORK_CONTROLLER_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(CODE_NETWORK_CONTROLLER_ITEM.get());
                        output.accept(CODE_CABLE_ITEM.get());
                        output.accept(LLM_CONFIGURATOR_ITEM.get());
                        output.accept(AE2_NETWORK_BRIDGE_ITEM.get());
                        output.accept(LLM_BUILD_EXECUTOR_ITEM.get());
                        output.accept(LLM_PLAN_EXECUTOR_ITEM.get());
                        output.accept(LLM_CHAT_EXECUTOR_ITEM.get());
                        output.accept(TRANSMITTER_ITEM.get());
                    })
                    .build());

    public LLMEra(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(
                net.neoforged.fml.config.ModConfig.Type.SERVER,
                LLMEraConfig.SPEC, "llmera-server.toml");
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
        LLMEraBlockEntities.REGISTRY.register(modEventBus);
        LLMEraMenus.REGISTRY.register(modEventBus);
    }
}

package com.example.llmera.jade;

import com.example.llmera.network.CodeCableBlock;
import com.example.llmera.network.CodeNetworkMember;
import com.example.llmera.network.CodeNetworkControllerBlockEntity;
import com.example.llmera.network.CodeNetworkScanner;
import com.example.llmera.network.IntelligentTransmitterBlockEntity;
import com.example.llmera.network.LlmExecutorBlockEntity;
import com.example.llmera.network.SkillBoardBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.theme.IThemeHelper;

public enum NetworkStatusProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("llmera", "network_status");
    private static final String DATA_KEY = ID.toString();

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        if (!(accessor.getLevel() instanceof ServerLevel level)) {
            return;
        }

        CodeNetworkScanner.NetworkView view = CodeNetworkScanner.scan(level, accessor.getPosition());
        CompoundTag status = new CompoundTag();
        boolean controller = accessor.getBlockEntity() instanceof CodeNetworkControllerBlockEntity;
        boolean cable = accessor.getBlock() instanceof CodeCableBlock;
        boolean endpoint = !controller && !cable;

        status.putBoolean("ControllerPresent", view.controller() != null);
        status.putBoolean("Powered", view.powered());
        status.putInt("ChannelUsed", view.channelUsed());
        status.putInt("ChannelCapacity", view.channelCapacity());
        status.putBoolean("MemberHasChannel", endpoint && view.memberHasChannel(accessor.getPosition()));
        status.putInt("ChannelDemand", endpoint ? channelDemand(accessor) : 0);
        status.putInt("ChannelProblems", view.channelProblems().size());
        status.putInt("Nodes", view.nodes());
        status.putBoolean("Truncated", view.truncated());

        if (view.controller() != null) {
            status.putInt("ControllerEnergy", view.controller().getEnergyStored());
            status.putInt("ControllerEnergyCapacity", view.controller().getMaxEnergyStored());
        }
        if (cable) {
            status.putInt("CableUsed", view.cableUsed(accessor.getPosition()));
            status.putInt("CableCapacity", view.cableCapacity(accessor.getPosition()));
        }

        if (accessor.getBlockEntity() instanceof IntelligentTransmitterBlockEntity transmitter) {
            boolean configured = !transmitter.getModelUrl().isBlank()
                    && !transmitter.getModelName().isBlank();
            status.putBoolean("ConfigReady", configured);
            if (!transmitter.getModelName().isBlank()) {
                status.putString("ModelName", compact(transmitter.getModelName(), 64));
            }
        }
        if (accessor.getBlockEntity() instanceof LlmExecutorBlockEntity executor) {
            status.putString("ExecutorMode", executor.getMode().getSerializedName());
            status.putBoolean("AgentRunning", executor.isAgentRunning());
        }
        if (accessor.getBlockEntity() instanceof SkillBoardBlockEntity board) {
            status.putBoolean("HasSkill", !board.getSkillName().isBlank());
            status.putString("SkillName", compact(board.getSkillName(), 64));
            status.putBoolean("SkillEnabled", board.isEnabled());
            status.putInt("SkillSteps", board.getSteps().size());
        }
        data.put(DATA_KEY, status);
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag data = accessor.getServerData();
        if (!data.contains(DATA_KEY)) {
            return;
        }
        CompoundTag status = data.getCompound(DATA_KEY);

        IThemeHelper theme = IThemeHelper.get();
        Component networkState;
        if (!status.getBoolean("ControllerPresent")) {
            networkState = theme.danger(Component.translatable("tooltip.llmera.jade.no_controller"));
        } else if (!status.getBoolean("Powered")) {
            networkState = theme.danger(Component.translatable("tooltip.llmera.jade.no_power"));
        } else {
            networkState = theme.success(Component.translatable("tooltip.llmera.jade.online"));
        }
        tooltip.add(Component.translatable("tooltip.llmera.jade.network", networkState));

        if (status.getBoolean("ControllerPresent")) {
            tooltip.add(Component.translatable("tooltip.llmera.jade.power",
                    theme.info(status.getInt("ControllerEnergy")),
                    theme.info(status.getInt("ControllerEnergyCapacity"))));
        }
        tooltip.add(Component.translatable("tooltip.llmera.jade.code_channels",
                theme.info(status.getInt("ChannelUsed")), theme.info(status.getInt("ChannelCapacity"))));

        if (accessor.getBlock() instanceof CodeCableBlock) {
            tooltip.add(Component.translatable("tooltip.llmera.jade.cable_load",
                    theme.info(status.getInt("CableUsed")), theme.info(status.getInt("CableCapacity"))));
        } else if (status.getBoolean("MemberHasChannel") || status.getInt("ChannelDemand") > 0) {
            int assigned = status.getBoolean("MemberHasChannel") ? status.getInt("ChannelDemand") : 0;
            Component assignment = status.getBoolean("MemberHasChannel")
                    ? theme.success(Component.translatable("tooltip.llmera.jade.assigned"))
                    : theme.danger(Component.translatable("tooltip.llmera.jade.no_channel"));
            tooltip.add(Component.translatable("tooltip.llmera.jade.endpoint_channel", assignment));
            tooltip.add(Component.translatable("tooltip.llmera.jade.endpoint_demand",
                    theme.info(assigned), theme.info(status.getInt("ChannelDemand"))));
        }

        if (status.contains("ConfigReady")) {
            Component configState = status.getBoolean("ConfigReady")
                    ? theme.success(Component.translatable("tooltip.llmera.jade.configured"))
                    : theme.danger(Component.translatable("tooltip.llmera.jade.not_configured"));
            tooltip.add(Component.translatable("tooltip.llmera.jade.config", configState));
            if (status.contains("ModelName")) {
                tooltip.add(Component.translatable("tooltip.llmera.jade.model",
                        theme.info(status.getString("ModelName"))));
            }
        }

        if (status.contains("ExecutorMode")) {
            Component runState = status.getBoolean("AgentRunning")
                    ? theme.warning(Component.translatable("tooltip.llmera.jade.running"))
                    : theme.info(Component.translatable("tooltip.llmera.jade.idle"));
            tooltip.add(Component.translatable("tooltip.llmera.jade.executor",
                    Component.translatable("tooltip.llmera.jade.mode." + status.getString("ExecutorMode")),
                    runState));
        }

        if (status.contains("HasSkill")) {
            Component skillState = status.getBoolean("HasSkill")
                    ? (status.getBoolean("SkillEnabled")
                    ? theme.success(Component.translatable("tooltip.llmera.jade.enabled"))
                    : theme.warning(Component.translatable("tooltip.llmera.jade.disabled")))
                    : theme.danger(Component.translatable("tooltip.llmera.jade.no_skill"));
            tooltip.add(Component.translatable("tooltip.llmera.jade.skill",
                    status.getString("SkillName"), skillState, theme.info(status.getInt("SkillSteps"))));
        }

        tooltip.add(Component.translatable("tooltip.llmera.jade.nodes", theme.info(status.getInt("Nodes"))));
        if (status.getInt("ChannelProblems") > 0) {
            tooltip.add(Component.translatable("tooltip.llmera.jade.channel_problems",
                    theme.warning(status.getInt("ChannelProblems"))));
        }
        if (status.getBoolean("Truncated")) {
            tooltip.add(theme.warning(Component.translatable("tooltip.llmera.jade.scan_truncated")));
        }
    }

    @Override
    public ResourceLocation getUid() {
        return ID;
    }

    private static int channelDemand(BlockAccessor accessor) {
        if (accessor.getBlockEntity() instanceof CodeNetworkMember member) {
            return Math.max(1, member.channelDemand());
        }
        if (accessor.getBlock() instanceof CodeNetworkMember member) {
            return Math.max(1, member.channelDemand());
        }
        return 1;
    }

    private static String compact(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength - 3) + "...";
    }
}

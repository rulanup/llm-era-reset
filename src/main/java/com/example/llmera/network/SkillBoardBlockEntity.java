package com.example.llmera.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public final class SkillBoardBlockEntity extends BlockEntity implements CodeNetworkMember {
    private static final int MAX_TEXT = 2048;
    private String skillName = "";
    private String description = "";
    private final List<String> steps = new ArrayList<>();
    private boolean enabled = true;

    public SkillBoardBlockEntity(BlockPos pos, BlockState state) {
        super(LLMEraBlockEntities.SKILL_BOARD.get(), pos, state);
    }

    public String getSkillName() { return skillName; }
    public String getDescription() { return description; }
    public List<String> getSteps() { return List.copyOf(steps); }
    public boolean isEnabled() { return enabled; }

    public void setSkill(String name, String description, List<String> steps, boolean enabled) {
        this.skillName = clamp(name);
        this.description = clamp(description);
        this.steps.clear();
        for (String step : steps) {
            String value = clamp(step);
            if (!value.isBlank()) this.steps.add(value);
            if (this.steps.size() >= 64) break;
        }
        this.enabled = enabled;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void clearSkill() {
        setSkill("", "", List.of(), false);
    }

    public Component getDisplayName() {
        return Component.translatable("block.llmera.skill_board");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.putString("SkillName", skillName);
        tag.putString("Description", description);
        tag.putString("Steps", String.join("|", steps));
        tag.putBoolean("Enabled", enabled);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        skillName = clamp(tag.getString("SkillName"));
        description = clamp(tag.getString("Description"));
        steps.clear();
        String rawSteps = tag.getString("Steps");
        if (!rawSteps.isBlank()) {
            for (String step : rawSteps.split("\\|", -1)) {
                String value = clamp(step);
                if (!value.isBlank()) steps.add(value);
                if (steps.size() >= 64) break;
            }
        }
        enabled = !tag.contains("Enabled") || tag.getBoolean("Enabled");
    }

    private static String clamp(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= MAX_TEXT ? trimmed : trimmed.substring(0, MAX_TEXT);
    }
}

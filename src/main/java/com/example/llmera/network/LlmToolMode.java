package com.example.llmera.network;

import net.minecraft.util.StringRepresentable;

public enum LlmToolMode implements StringRepresentable {
    BUILD("build"),
    PLAN("plan"),
    CHAT("chat");

    private final String name;

    LlmToolMode(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

}

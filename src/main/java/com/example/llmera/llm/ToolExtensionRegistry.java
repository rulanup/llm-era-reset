package com.example.llmera.llm;

import com.example.llmera.network.IntelligentTransmitterBlockEntity;
import com.example.llmera.network.LlmToolMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolExtensionRegistry {
    private static final Map<String, ToolExtension.Tool> TOOLS = new LinkedHashMap<>();
    private static final List<String> PROMPT_GUIDANCE = new ArrayList<>();

    private ToolExtensionRegistry() {
    }

    public static synchronized void register(ToolExtension extension) {
        for (ToolExtension.Tool tool : extension.tools()) {
            String name = tool.definition().name();
            if (TOOLS.putIfAbsent(name, tool) != null) {
                throw new IllegalStateException("tool already registered: " + name);
            }
        }
        if (!extension.systemPromptGuidance().isBlank()) {
            PROMPT_GUIDANCE.add(extension.systemPromptGuidance());
        }
    }

    public static synchronized List<ToolDefinition> definitionsFor(LlmToolMode mode) {
        return TOOLS.values().stream()
                .filter(tool -> tool.modes().contains(mode))
                .map(ToolExtension.Tool::definition)
                .toList();
    }

    public static synchronized boolean isAllowed(String name, LlmToolMode mode) {
        ToolExtension.Tool tool = TOOLS.get(name);
        return tool != null && tool.modes().contains(mode);
    }

    public static synchronized String execute(IntelligentTransmitterBlockEntity transmitter,
                                              ToolInvocation invocation) {
        ToolExtension.Tool tool = TOOLS.get(invocation.name());
        return tool == null ? null : tool.executor().execute(transmitter, invocation);
    }

    public static synchronized String systemPromptGuidance() {
        return String.join("\n\n", PROMPT_GUIDANCE);
    }
}

package com.example.llmera.llm;

import com.example.llmera.network.IntelligentTransmitterBlockEntity;
import com.example.llmera.network.LlmToolMode;

import java.util.List;
import java.util.Set;

public record ToolExtension(List<Tool> tools, String systemPromptGuidance) {
    public ToolExtension {
        tools = List.copyOf(tools);
        systemPromptGuidance = systemPromptGuidance == null ? "" : systemPromptGuidance;
    }

    public record Tool(ToolDefinition definition, Set<LlmToolMode> modes, Executor executor) {
        public Tool {
            modes = Set.copyOf(modes);
        }
    }

    @FunctionalInterface
    public interface Executor {
        String execute(IntelligentTransmitterBlockEntity transmitter, ToolInvocation invocation);
    }
}

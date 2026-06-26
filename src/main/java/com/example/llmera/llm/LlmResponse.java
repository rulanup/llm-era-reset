package com.example.llmera.llm;

import java.util.List;

/** Result of a single LlmClient.call invocation. */
public record LlmResponse(String text, String reasoning, List<ToolInvocation> toolCalls) {
    public boolean hasToolCalls() { return toolCalls != null && !toolCalls.isEmpty(); }
    public boolean hasReasoning() { return reasoning != null && !reasoning.isEmpty(); }
}

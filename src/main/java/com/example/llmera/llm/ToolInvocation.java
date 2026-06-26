package com.example.llmera.llm;

import java.util.List;
import java.util.Map;

public record ToolInvocation(String id, String name, Map<String, Object> arguments) {}

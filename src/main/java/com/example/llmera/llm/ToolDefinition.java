package com.example.llmera.llm;

import java.util.List;
import java.util.Map;

public record ToolDefinition(String name, String description, Map<String, Object> parameters) {}

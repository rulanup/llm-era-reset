package com.example.llmera.llm;

/**
 * One entry in the LLM conversation history.
 *
 * <p>For the common role=system / role=user / role=tool cases only
 * {@link #role()} and {@link #content()} are populated. For the
 * role=assistant turn that contains {@code tool_calls} we additionally carry
 * a pre-serialized {@code tool_calls} JSON array in {@link #toolCallsJson()};
 * the LlmClient writes it through verbatim because the OpenAI schema for that
 * field is non-trivial ({@code [{id, type, function:{name, arguments:string}}]}).
 *
 * <p>For the role=tool reply, {@link #toolCallId()} must echo the id of the
 * assistant turn's matching tool_call.
 */
public record ChatMessage(String role, String content, String toolCallId, String toolCallsJson) {
    public static ChatMessage system(String c) { return new ChatMessage("system", c, null, null); }
    public static ChatMessage user(String c) { return new ChatMessage("user", c, null, null); }
    public static ChatMessage assistant(String c) { return new ChatMessage("assistant", c, null, null); }
    public static ChatMessage tool(String id, String c) { return new ChatMessage("tool", c, id, null); }

    /** Internal: assistant turn that announces one or more tool calls. */
    public static ChatMessage assistantWithToolCalls(String text, String toolCallsJson) {
        return new ChatMessage("assistant", text == null ? "" : text, null, toolCallsJson);
    }
}

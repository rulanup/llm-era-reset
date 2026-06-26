package com.example.llmera.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class LLMEraConfig {
    private LLMEraConfig() {}

    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.ConfigValue<String> DEFAULT_SYSTEM_PROMPT;
    public static final ModConfigSpec.IntValue REQUEST_TIMEOUT_SECONDS;
    public static final ModConfigSpec.IntValue MAX_CALLS_PER_SECOND;
    public static final ModConfigSpec.IntValue MAX_AGENT_TURNS;
    public static final ModConfigSpec.IntValue THINKING_BUDGET;
    public static final ModConfigSpec.ConfigValue<String> CODEC;
    public static final ModConfigSpec.ConfigValue<String> PUBLIC_KEY_PATH;
    public static final ModConfigSpec.ConfigValue<String> DEV_API_KEY;
    public static final ModConfigSpec.ConfigValue<String> DEV_ENDPOINT;
    public static final ModConfigSpec.ConfigValue<String> DEV_MODEL_NAME;

    static {
        BUILDER.push("llm");

        DEFAULT_SYSTEM_PROMPT = BUILDER
                .comment("Default system prompt used when a transmitter has none set. "
                        + "The placeholder below nudges the model toward the right tool-call workflow "
                        + "(read_block before place / break; place is for solid blocks, not air) "
                        + "and tells it to call 'finish' when the task is done.")
                .define("defaultSystemPrompt",
                        "You are a helpful in-game assistant driving a Minecraft world through tool calls.\n"
                                + "Available tools:\n"
                                + "  - read_block (inspect one cell)\n"
                                + "  - place (set ONE solid block)\n"
                                + "  - place_many (set UP TO 64 blocks in a single call — PREFER this for walls, "
                                + "floors, or any multi-block structure)\n"
                                + "  - break_block (clear one cell to air)\n"
                                + "  - break_many (clear up to 64 cells in a single call — PREFER for clearing areas)\n"
                                + "  - finish (end the agent loop with a short summary)\n"
                                + "  - noop (echo)\n"
                                + "Workflow: 1) read_block to confirm the target, 2) call place_many / break_many "
                                + "with a 'blocks' array (NEVER one-at-a-time when building), "
                                + "3) call finish when done.\n"
                                + "Coordinates are absolute world coordinates in the transmitter's dimension. "
                                + "Do NOT use place with 'minecraft:air' — use break_block or break_many to clear cells.\n"
                                + "When a tool returns ok=false on some entries, read the 'message' field and explain "
                                + "the failure to the user.\n"
                                + "If you call finish without finishing the user's task, that is a failure — only call "
                                + "finish when the work is genuinely complete (or the task is impossible).");

        REQUEST_TIMEOUT_SECONDS = BUILDER
                .comment("HTTP request timeout for the LLM endpoint, in seconds.")
                .defineInRange("requestTimeoutSeconds", 90, 1, 600);

        MAX_CALLS_PER_SECOND = BUILDER
                .comment("Per-transmitter rate limit. Excess calls return immediately.")
                .defineInRange("maxCallsPerSecond", 5, 1, 100);

        MAX_AGENT_TURNS = BUILDER
                .comment("Hard ceiling on agent-loop turns per user request. Each turn is one "
                        + "LLM call (with up to 10 tool calls). Higher = longer agent runs, more "
                        + "token spend. The model can end the loop early by calling the 'finish' tool.")
                .defineInRange("maxAgentTurns", 20, 1, 200);

        THINKING_BUDGET = BUILDER
                .comment("Reasoning budget_tokens sent to thinking-capable models (DeepSeek R1, "
                        + "Claude 3.7 thinking, Gemini thinking, etc.) via OpenRouter's reasoning "
                        + "field and Anthropic's thinking field. 0 = omit the hint and let the "
                        + "provider choose a default (often the model will still think). 2048 is a "
                        + "safe starting value; raise for harder problems, lower to save tokens.")
                .defineInRange("thinkingBudget", 2048, 0, 32768);

        BUILDER.pop();
        BUILDER.push("crypto");

        CODEC = BUILDER
                .comment("Envelope codec for apiKey storage. 'plaintext' (v1 dev) or 'rsa-aes' (v2).")
                .define("codec", "plaintext");

        PUBLIC_KEY_PATH = BUILDER
                .comment("Path to RSA public key PEM. Only used when codec='rsa-aes'.")
                .define("publicKeyPath", "config/llmera-public.pem");

        BUILDER.pop();
        BUILDER.push("dev");

        DEV_API_KEY = BUILDER
                .comment("Fixed API key used by all transmitters in v1 dev mode. "
                        + "When non-empty, overrides the per-block apiKey set via the UI.")
                .define("devApiKey", "");

        DEV_ENDPOINT = BUILDER
                .comment("Fixed LLM endpoint used when devApiKey is set.")
                .define("devEndpoint", "https://openrouter.ai/api/v1");

        DEV_MODEL_NAME = BUILDER
                .comment("Fixed model name used when devApiKey is set.")
                .define("devModelName", "openai/gpt-4o-mini");

        BUILDER.pop();
        SPEC = BUILDER.build();
    }
}

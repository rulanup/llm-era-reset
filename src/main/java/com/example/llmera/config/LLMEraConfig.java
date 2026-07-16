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
    public static final ModConfigSpec.BooleanValue DEBUG_AGENT_TOOLS;
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
                                + "finish when the work is genuinely complete (or the task is impossible).\n"
                                + "\n"
                                + "Tool routing hints (synthesis / recipe / item questions):\n"
                                + "  - For 'how do I craft X', 'what is the recipe for X', 'what materials make X', "
                                + "or any other synthesis question, call emi_lookup (or jei_lookup) with block_id = X. "
                                + "The tool returns the materials and the recipe directly in the 'recipes' field of the "
                                + "result — do NOT then call mcmod_search / web_search for the same item, those are "
                                + "for mod metadata only. The tool's 'include_recipes' and 'include_uses' default to "
                                + "true, so a single call already gives you OUTPUT recipes, INPUT recipes, materials, "
                                + "and tags when 'include_tags' is also set — no follow-up 'what about the raw materials' "
                                + "query is needed.\n"
                                + "  - For 'what is the mod id of X' or 'is X added by mod Y' or 'is X in MC 1.20.1', "
                                + "use mcmod_search / mcmod_fetch (MC百科).\n"
                                + "  - For non-MC questions the user has explicitly opted in to, use web_search / web_fetch.\n"
                                + "\n"
                                + "AE2 pattern (样板) routing — this is the most common source of wasted web calls:\n"
                                + "  - When the user asks to write / create / fill an AE2 pattern (样板) for an item, "
                                + "do NOT call web_search / web_fetch / mcmod_search. The web has no per-recipe "
                                + "AE2 pattern data (inputs, outputs, slot layout) — wikis and mod pages only describe "
                                + "the high-level 'this machine processes X into Y'. The only authoritative source "
                                + "is the local mod's own recipe registry, which is reachable via emi_lookup / jei_lookup.\n"
                                + "  - Step 1: call emi_lookup or jei_lookup with block_id = the item the user wants "
                                + "to automate. Read the 'recipes' array — for AE2 the relevant categories are usually "
                                + "'ae2:inscriber' (刻印机, 制造电路板 / 硅 / 处理器等) and 'ae2:charger' (充能器, "
                                + "制造充能赛特斯石英、水晶等). For modded patterns, look for the machine's own "
                                + "category (e.g. 'thermal:press', 'immersive_engineering:metal_press').\n"
                                + "  - Step 2: classify the recipe. AE2 has THREE pattern types, you MUST pick the "
                                + "right one before calling ae2_pattern_write:\n"
                                + "      * 'crafting' (合成样板) — only for vanilla workbench / 3x3 grid recipes, where "
                                + "the output is a single item formed from up to 9 inputs on a grid. If the recipe is "
                                + "NOT a 3x3 grid shape, it is NOT a crafting pattern. Examples that are NOT crafting "
                                + "patterns: any recipe processed by a machine (pulverizer, inscriber, sawmill, smelter, "
                                + "etc.) — even if the input is a single item — those are 'processing' patterns.\n"
                                + "      * 'processing' (处理样板) — recipes handled by an AE2-attached machine "
                                + "(inscriber / charger / pulverizer / sawmill / smelter / etc.) OR by any modded "
                + "machine the user names. DEFAULT choice when in doubt. Takes primary input + optional "
                + "secondary input (e.g. fluid / additional item) and yields the machine's output.\n"
                                + "      * 'smithing' (锻造样板, 1.20+) — only for the vanilla smithing table "
                + "(template + base + addition). Rarely needed.\n"
                                + "  - Step 3: build the inputs array from the recipe. emi_lookup's recipe entry has "
                                + "the inputs as an ingredient list — map each to {item_id, count} (count is 0 for "
                                + "placeholder / no-input slots in crafting, 1+ for actual inputs in processing). "
                                + "For processing patterns the primary input goes in slot 0 and any secondary "
                                + "input (e.g. fluid bucket, additional reagent) goes in slot 1; for crafting "
                                + "patterns the order is top-left to bottom-right of the 3x3 grid.\n"
                                + "  - Step 4: call ae2_pattern_write (single) or ae2_pattern_write_many (batch) with "
                                + "the chosen pattern_type, the inputs array, and the output. For batch writes you can "
                                + "submit up to 16 specs in one call with per-spec overrides for replace_existing and "
                                + "pattern_type — atomic transaction with dry-run preview when dry_run=true.\n"
                                + "  - Common mistake to avoid: a 'shapeless' 1-input → 1-output recipe is still a "
                                + "PROCESSING pattern if it goes through a machine (pulverizer: cobblestone → gravel, "
                                + "sawmill: log → planks, etc.), NOT a crafting pattern. Workbench patterns are "
                                + "almost always 3x3 grid + multiple inputs OR a shaped arrangement.\n"
                                + "\n"
                                + "Cache and retry policy (the runtime enforces this — don't fight it):\n"
                                + "  - Results from emi_lookup / jei_lookup / mcmod_* / web_* for the same args are "
                                + "replayed from a per-turn cache. If you've already queried an item, USE that result "
                                + "rather than re-asking with a slightly different id (e.g. with/without namespace).\n"
                                + "  - If 3 tool calls in a row return an error, the runtime will inject a system "
                                + "nudge asking you to switch tools or call 'question' to ask the user. Follow the "
                                + "nudge: don't keep retrying the same failing call.\n");

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

        DEBUG_AGENT_TOOLS = BUILDER
                .comment("When true, every agent tool call logs: tool name, full arguments, "
                        + "result preview (first 400 chars), duration, and any exception with "
                        + "stack trace. Also logs the runLoop entry/exit, LLM stream start/end, "
                        + "and the internal flow of ae2_pattern_write. Recommended for diagnosing "
                        + "stuck agent loops; safe to leave on in production since the log volume "
                        + "per turn is small.")
                .define("debugAgentTools", true);

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

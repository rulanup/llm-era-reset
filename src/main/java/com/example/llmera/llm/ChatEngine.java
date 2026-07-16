package com.example.llmera.llm;

import com.example.llmapi.api.ApiResult;
import com.example.llmapi.api.BlockApi;
import com.example.llmera.LLMEra;
import com.example.llmera.config.LLMEraConfig;
import com.example.llmera.network.Ae2NetworkBridgeBlockEntity;
import com.example.llmera.network.CodeNetworkScanner;
import com.example.llmera.network.IntelligentTransmitterBlockEntity;
import com.example.llmera.network.LlmExecutorBlockEntity;
import com.example.llmera.network.LlmToolMode;
import com.example.llmapi.websearch.WebFetchTool;
import com.example.llmapi.websearch.WebSearchTool;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import com.example.llmera.packet.ModPackets;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Drives the LLM call + tool-call loop on the server.
 *
 * <p>v1 advertises three tools:
 * <ul>
 *   <li>{@code noop} — echoes its {@code text} argument (protocol smoke test)</li>
 *   <li>{@code place} — calls {@code BlockApi.place} at the given coordinates</li>
 *   <li>{@code read_block} — calls {@code BlockApi.readNbt} on the given block entity</li>
 * </ul>
 * Tools run on the main thread via {@code server.execute}. Loop capped at 3 rounds.
 */
public final class ChatEngine {
    private static final Gson GSON = new Gson();

    /** Hard cap on tool calls executed per LLM turn. Anything above this is
     *  silently dropped from the history and the LLM is told why. Keeps
     *  memory bounded for prompts like "build me a house" that would
     *  otherwise emit hundreds of place tool calls. */
    private static final int MAX_TOOLS_PER_TURN = 10;
    private static final int MAX_TOOL_RESULT_CHARS = 24000;
    /** Consecutive tool-call failures (anything returning `{"error":...}`
     *  or `{"ok":false,...}`) before the agent loop injects a synthetic
     *  user message telling the model to stop retrying, ask the user, or
     *  finish. Lower = faster intervention but more chat noise on
     *  transient failures. 3 has been a good middle ground for JEI/EMI
     *  misconfigs and bad block_id typos. */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final int MAX_REASONING_CHARS = 4000;

    private static final ToolDefinition NOOP_TOOL = new ToolDefinition(
            "noop",
            "Echoes the 'text' argument back. v1 placeholder used to verify the tool_call protocol.",
            Map.of(
                    "text", Map.of("type", "string", "description", "text to echo")
            )
    );

    private static final ToolDefinition PLACE_TOOL = new ToolDefinition(
            "place",
            "Place a SOLID block at integer coordinates (x, y, z) in the transmitter's dimension. "
                    + "x/y/z are required; block is a block id like 'minecraft:stone' (NOT 'minecraft:air' "
                    + "— to remove a block use break_block instead). "
                    + "replace (default true) controls whether non-air target cells may be overwritten.",
            Map.of(
                    "x", Map.of("type", "integer", "description", "x coordinate"),
                    "y", Map.of("type", "integer", "description", "y coordinate"),
                    "z", Map.of("type", "integer", "description", "z coordinate"),
                    "block", Map.of("type", "string", "description", "block id, e.g. minecraft:stone"),
                    "replace", Map.of("type", "boolean", "description", "overwrite non-air (default true)")
            )
    );

    private static final ToolDefinition READ_BLOCK_TOOL = new ToolDefinition(
            "read_block",
            "Read the block at integer coordinates (x, y, z). Returns block id and (if present) NBT as SNBT. "
                    + "Use this before placing or breaking to confirm the target cell.",
            Map.of(
                    "x", Map.of("type", "integer", "description", "x coordinate"),
                    "y", Map.of("type", "integer", "description", "y coordinate"),
                    "z", Map.of("type", "integer", "description", "z coordinate")
            )
    );

    /** Edit the block-entity NBT at integer coordinates. The 'mode' arg
     *  selects the merge strategy (write = overwrite the value at 'path',
     *  merge = recursive merge into the existing compound, replace =
     *  replace the whole BE tag). 'path' uses Minecraft's /data path
     *  syntax (empty = the root compound). 'value' is an SNBT snippet
     *  matching the type at the path (a quoted string, a number, a
     *  compound body in {...}, etc.). Runs on the main server thread,
     *  no chunk force-load.
     *
     *  REMOVED: write_block_nbt was pulled in an earlier rev because
     *  the underlying NbtPatch path-API silently corrupts container
     *  arrays on path=Items[N].Count-style writes (replace semantics
     *  on a single index sub-element destroys the rest of the list).
     *  Fixing it properly needs a full SNBT modify command (read,
     *  edit, write the parent) which the existing read_block +
     *  /data style ops cover better. For now the mod ships without
     *  block-entity NBT writes; the LLM can only read. */
    // (intentionally empty — see comment above)

    private static final ToolDefinition BREAK_BLOCK_TOOL = new ToolDefinition(
            "break_block",
            "Remove the block at integer coordinates (x, y, z) in the transmitter's dimension, "
                    + "leaving air. Use this to clear a cell. Coordinates are absolute world coordinates.",
            Map.of(
                    "x", Map.of("type", "integer", "description", "x coordinate"),
                    "y", Map.of("type", "integer", "description", "y coordinate"),
                    "z", Map.of("type", "integer", "description", "z coordinate")
            )
    );

    /**
     * Agent-loop terminator. The model calls this when it has completed the
     * user's task (or has decided the task is impossible). Calling finish ends
     * the agent loop; without it, the loop continues until {@code maxAgentTurns}.
     */
    private static final ToolDefinition FINISH_TOOL = new ToolDefinition(
            "finish",
            "Call this when you have completed the user's task or have decided the task "
                    + "is impossible. Provide a short summary in 'reason' so the user can "
                    + "see what you accomplished. The agent loop ends on this call.",
            Map.of(
                    "reason", Map.of("type", "string", "description", "short summary of what you did")
            )
    );

    /** Ask the user a question and pause the agent loop until they reply.
     *  Use this whenever the task is ambiguous, a key design choice
     *  could go several ways, or you need the user to pick between
     *  options you can't infer from context. 'prompt' is the actual
     *  question (keep it short). 'options' is an optional list of
     *  suggested answers — an implicit 'other' option (free-form text
     *  input) is ALWAYS appended server-side, so you do not need to
     *  list it yourself. The agent loop ends on this call: the next
     *  LLM turn only starts once the user sends a message. */
    private static final ToolDefinition QUESTION_TOOL = new ToolDefinition(
            "question",
            "Pause and ask the user a question. The agent loop ends; the next LLM turn "
                    + "starts only after the user types a reply and hits Enter. Use this for "
                    + "ambiguous tasks, design choices, or whenever you'd otherwise guess. "
                    + "'prompt' is the question itself. 'options' is an OPTIONAL list of "
                    + "suggested replies; an 'other' (free-form) option is always added for you, "
                    + "so do not list it explicitly.",
            Map.of(
                    "prompt", Map.of("type", "string",
                            "description", "the question to ask the user. Plain text, one or two sentences."),
                    "options", Map.of("type", "array", "items", Map.of("type", "string"),
                            "description", "optional list of suggested answers the user can pick from. "
                                    + "Each entry should be a short, distinct choice. Omit the field to "
                                    + "let the user type a free-form answer. 'other' is added for you; "
                                    + "do not list it.")
            )
    );

    /** Look up a registered Minecraft item/block by ID via JEI (Just
     *  Enough Items) reflection. Returns the JEI-known display name,
     *  owning mod id, and a short description (registry name, item
     *  class, basic tags). Use this when the model needs to verify a
     *  block is real, get its localized name, or learn which mod it
     *  comes from. The call is reflection-only: the mod has no hard
     *  dependency on JEI, so if JEI is missing the tool result just
     *  says so and the LLM moves on. */
    private static final ToolDefinition JEI_LOOKUP_TOOL = new ToolDefinition(
            "jei_lookup",
            "Look up a registered item/block by its Minecraft ID (e.g. 'minecraft:stone' or "
                    + "'create:mechanical_piston') via the Just Enough Items mod. Returns the "
                    + "display name, owning mod, and a few registry facts. The lookup is via "
                    + "reflection, so it works whether or not JEI is installed — if JEI is "
                    + "missing, the result is an error message instead of a crash. "
                    + "By default returns recipes that PRODUCE the item ('include_recipes' default true) "
                    + "and recipes that CONSUME the item ('include_uses' default true), because the "
                    + "primary use of this tool is answering crafting/recipe questions — the materials "
                    + "needed are already in those recipes, so there is no need to do a separate "
                    + "mcmod_search / web_search for the same item. Pass 'include_recipes=false' / "
                    + "'include_uses=false' if the user only wants the id+name. Pass 'include_tags=true' "
                    + "for the tag list (e.g. minecraft:planks, c:ingots).",
            Map.of(
                    "block_id", Map.of("type", "string",
                            "description", "the registry id to look up, in 'namespace:path' form. "
                                    + "Example: 'minecraft:oak_planks', 'create:mechanical_piston'. "
                                    + "For block items prefer the item id (which JEI registers)."),
                    "include_recipes", Map.of("type", "boolean",
                            "description", "optional. If true (default), return recipes that produce this item "
                                    + "(as output). Pass false to skip and just get id+name+mod."),
                    "include_uses", Map.of("type", "boolean",
                            "description", "optional. If true (default), return recipes that consume this item "
                                    + "(as input). Pass false to skip and just get id+name+mod."),
                    "include_tags", Map.of("type", "boolean",
                            "description", "optional. If true, return the list of tags this item "
                                    + "belongs to (e.g. 'minecraft:planks', 'c:ingots'). Default false."),
                    "data_value", Map.of("type", "integer",
                            "description", "optional legacy item Data Value / damage. Use this when a JEI entry "
                                    + "is distinguished by commands like '/give @p mod:item 64 2'.")
            )
    );

    private static final ToolDefinition JEI_NAME_LOOKUP_TOOL = new ToolDefinition(
            "jei_name_lookup",
            "Search JEI items by displayed item name instead of registry id. "
                    + "Use this when the user gives a Chinese/localized/common item name and the model needs the "
                    + "Minecraft registry id. Returns matching registry ids, display names, and mod ids. "
                    + "Reflection-only: if JEI is not installed or not ready, returns an error JSON instead of crashing.",
            Map.of(
                    "item_name", Map.of("type", "string",
                            "description", "displayed item name or partial name, e.g. '铁锭', 'oak plank', '样板'"),
                    "max_results", Map.of("type", "integer",
                            "description", "optional. Maximum matches to return, 1..50. Default 20."),
                    "data_value", Map.of("type", "integer",
                            "description", "optional legacy item Data Value / damage filter."),
                    "count", Map.of("type", "integer",
                            "description", "optional count for generated /give examples. Default 64."),
                    "target", Map.of("type", "string",
                            "description", "optional target selector/player for generated /give examples. Default @p.")
            )
    );

    /** EMI counterpart of {@link #JEI_LOOKUP_TOOL}. Behavior is identical;
     *  only the underlying mod is different. The mod is detected at runtime
     *  via {@code net.neoforged.fml.ModList.get().isLoaded("emi")}. The
     *  two JEI tools and these two EMI tools are exposed MUTUALLY EXCLUSIVE
     *  by {@link #recipeLookupTools()} — only one pair is ever advertised
     *  to the LLM, and EMI wins if both mods are installed. */
    private static final ToolDefinition EMI_LOOKUP_TOOL = new ToolDefinition(
            "emi_lookup",
            "Look up a registered item/block by its Minecraft ID (e.g. 'minecraft:stone' or "
                    + "'create:mechanical_piston') via the EMI mod (formerly known as Roughly Enough Items) "
                    + "via reflection. Returns the display name, owning mod, and a few registry facts. "
                    + "The lookup is via reflection, so it works whether or not EMI is installed — if EMI is "
                    + "missing, the result is an error message instead of a crash. "
                    + "By default returns recipes that PRODUCE the item ('include_recipes' default true) "
                    + "and recipes that CONSUME the item ('include_uses' default true), because the "
                    + "primary use of this tool is answering crafting/recipe questions — the materials "
                    + "needed are already in those recipes, so there is no need to do a separate "
                    + "mcmod_search / web_search for the same item. Pass 'include_recipes=false' / "
                    + "'include_uses=false' if the user only wants the id+name. Pass 'include_tags=true' "
                    + "for the tag list (e.g. minecraft:planks, c:ingots).",
            Map.of(
                    "block_id", Map.of("type", "string",
                            "description", "the registry id to look up, in 'namespace:path' form. "
                                    + "Example: 'minecraft:oak_planks', 'create:mechanical_piston'. "
                                    + "For block items prefer the item id (which EMI registers)."),
                    "include_recipes", Map.of("type", "boolean",
                            "description", "optional. If true (default), return recipes that produce this item "
                                    + "(as output). Pass false to skip and just get id+name+mod."),
                    "include_uses", Map.of("type", "boolean",
                            "description", "optional. If true (default), return recipes that consume this item "
                                    + "(as input). Pass false to skip and just get id+name+mod."),
                    "include_tags", Map.of("type", "boolean",
                            "description", "optional. If true, return the list of tags this item "
                                    + "belongs to (e.g. 'minecraft:planks', 'c:ingots'). Default false."),
                    "data_value", Map.of("type", "integer",
                            "description", "optional legacy item Data Value / damage. Use this when an EMI entry "
                                    + "is distinguished by commands like '/give @p mod:item 64 2'.")
            )
    );

    private static final ToolDefinition EMI_NAME_LOOKUP_TOOL = new ToolDefinition(
            "emi_name_lookup",
            "Search EMI items by displayed item name instead of registry id. "
                    + "Use this when the user gives a Chinese/localized/common item name and the model needs the "
                    + "Minecraft registry id. Returns matching registry ids, display names, and mod ids. "
                    + "Reflection-only: if EMI is not installed or not ready, returns an error JSON instead of crashing.",
            Map.of(
                    "item_name", Map.of("type", "string",
                            "description", "displayed item name or partial name, e.g. '铁锭', 'oak plank', '样板'"),
                    "max_results", Map.of("type", "integer",
                            "description", "optional. Maximum matches to return, 1..50. Default 20."),
                    "data_value", Map.of("type", "integer",
                            "description", "optional legacy item Data Value / damage filter."),
                    "count", Map.of("type", "integer",
                            "description", "optional count for generated /give examples. Default 64."),
                    "target", Map.of("type", "string",
                            "description", "optional target selector/player for generated /give examples. Default @p.")
            )
    );

    private static final ToolDefinition AE2_PATTERN_LOOKUP_TOOL = new ToolDefinition(
            "ae2_pattern_lookup",
            "Query encoded patterns currently visible to an AE2 pattern access terminal. "
                    + "By default, uses the ae2_network_bridge blocks connected to this executor's code network. "
                    + "Optional x/y/z can point at an AE2 network node, cable, controller, terminal, or pattern provider "
                    + "for manual debugging. "
                    + "The tool reflects AE2's IGrid -> PatternContainer flow, so the mod has no hard dependency on AE2. "
                    + "It is read-only and returns pattern provider groups, slots, encoded pattern items, inputs, and outputs. "
                    + "If no AE2 grid can be resolved, it falls back to reading a single pattern provider at the coordinates.",
            Map.of(
                    "x", Map.of("type", "integer", "description", "optional x coordinate of an AE2 network block"),
                    "y", Map.of("type", "integer", "description", "optional y coordinate of an AE2 network block"),
                    "z", Map.of("type", "integer", "description", "optional z coordinate of an AE2 network block"),
                    "include_details", Map.of("type", "boolean",
                            "description", "optional. Decode pattern inputs and outputs. Default true."),
                    "include_hidden", Map.of("type", "boolean",
                            "description", "optional. Include pattern containers hidden from the pattern access terminal. Default false.")
            )
    );

    private static final ToolDefinition AE2_PATTERN_WRITE_TOOL = new ToolDefinition(
            "ae2_pattern_write",
            "Encode and install an AE2 encoded pattern through the ae2_network_bridge connected to this code network. "
                    + "This is a write tool for build executors only. For crafting patterns, pass recipe_id from JEI "
                    + "or pass crafting_grid; if only output_item_id is given and multiple recipes produce it, the tool "
                    + "returns candidate recipe ids instead of guessing. The tool uses the same server recipe layout JEI "
                    + "shows for normal crafting recipes, encodes the AE2 pattern reflectively, then installs it into a "
                    + "matching Pattern Access Terminal container: crafting patterns go to molecular-assembler targets, "
                    + "processing patterns go to non-molecular pattern providers. If a compatible pattern with the same "
                    + "primary output already exists, it is replaced by default; otherwise the first compatible empty slot "
                    + "is used.",
            Map.ofEntries(
                    Map.entry("pattern_type", Map.of("type", "string",
                            "description", "crafting or processing. Default crafting.")),
                    Map.entry("recipe_id", Map.of("type", "string",
                            "description", "optional JEI/server recipe id for crafting, e.g. minecraft:oak_planks")),
                    Map.entry("output_item_id", Map.of("type", "string",
                            "description", "crafting output item id, or processing primary output item id")),
                    Map.entry("crafting_grid", Map.of("type", "array",
                            "description", "optional 9-slot JEI crafting grid. Each entry is an item id, empty/null, "
                                    + "or {item_id, amount, data_value}. Used when recipe_id is missing or a JEI "
                                    + "transient layout must be preserved.")),
                    Map.entry("inputs", Map.of("type", "array",
                            "description", "processing inputs as [{item_id, amount, data_value}], only for pattern_type=processing")),
                    Map.entry("outputs", Map.of("type", "array",
                            "description", "processing outputs as [{item_id, amount, data_value}], first output is primary")),
                    Map.entry("allow_substitutes", Map.of("type", "boolean",
                            "description", "crafting pattern item substitution flag. Default false.")),
                    Map.entry("allow_fluid_substitutes", Map.of("type", "boolean",
                            "description", "crafting pattern fluid substitution flag. Default false.")),
                    Map.entry("replace_existing", Map.of("type", "boolean",
                            "description", "replace an existing compatible pattern with the same primary output. Default true.")),
                    Map.entry("target_machine_item_id", Map.of("type", "string",
                            "description", "optional machine/block item id learned from JEI, e.g. minecraft:furnace "
                                    + "or create:mechanical_press. The tool prefers pattern providers whose terminal "
                                    + "group icon matches this machine.")),
                    Map.entry("target_group_name", Map.of("type", "string",
                            "description", "optional lowercase/partial terminal group name to match the target machine "
                                    + "when JEI gives a machine name instead of an item id.")),
                    Map.entry("container_index", Map.of("type", "integer",
                            "description", "optional target container index from ae2_pattern_lookup")),
                    Map.entry("slot", Map.of("type", "integer",
                            "description", "optional exact slot in target container; refuses to overwrite a different pattern")),
                    Map.entry("x", Map.of("type", "integer", "description", "optional manual AE2 anchor x")),
                    Map.entry("y", Map.of("type", "integer", "description", "optional manual AE2 anchor y")),
                    Map.entry("z", Map.of("type", "integer", "description", "optional manual AE2 anchor z"))
            )
    );

    /**
     * JSON Schema fragment for one entry in ae2_pattern_write_many's "patterns"
     * array. Mirrors ae2_pattern_write's argument shape so the LLM can build
     * each entry by copy-pasting the single-call argument map. Declared
     * before {@link #AE2_PATTERN_WRITE_MANY_TOOL} to avoid an illegal forward
     * reference when the tool definition references it.
     */
    private static final Map<String, Object> AE2_PATTERN_WRITE_ITEM_SCHEMA = Map.ofEntries(
            Map.entry("type", "object"),
            Map.entry("description", "one AE2 pattern spec; shape matches ae2_pattern_write's arguments"),
            Map.entry("properties", Map.ofEntries(
                    Map.entry("pattern_type", Map.of("type", "string",
                            "description", "crafting or processing. Default crafting.")),
                    Map.entry("recipe_id", Map.of("type", "string",
                            "description", "optional JEI recipe id for crafting")),
                    Map.entry("output_item_id", Map.of("type", "string",
                            "description", "crafting output item id, or processing primary output")),
                    Map.entry("crafting_grid", Map.of("type", "array",
                            "description", "optional 9-slot JEI crafting grid for crafting patterns")),
                    Map.entry("inputs", Map.of("type", "array",
                            "description", "processing inputs (only for pattern_type=processing)")),
                    Map.entry("outputs", Map.of("type", "array",
                            "description", "processing outputs (only for pattern_type=processing, first = primary)")),
                    Map.entry("allow_substitutes", Map.of("type", "boolean",
                            "description", "per-spec override of the global allow_substitutes flag")),
                    Map.entry("allow_fluid_substitutes", Map.of("type", "boolean",
                            "description", "per-spec override of the global allow_fluid_substitutes flag")),
                    Map.entry("replace_existing", Map.of("type", "boolean",
                            "description", "per-spec override of the global replace_existing policy")),
                    Map.entry("container_index", Map.of("type", "integer",
                            "description", "per-spec override of the target container index")),
                    Map.entry("slot", Map.of("type", "integer",
                            "description", "per-spec override of the target slot; refuses to overwrite a different pattern"))
            ))
    );

    /**
     * Batch counterpart of {@link #AE2_PATTERN_WRITE_TOOL}. Encodes and installs
     * 1..16 patterns in a single round-trip using a shared network anchor
     * and pre-scanned PatternContainer list. Per-spec overrides let the LLM
     * mix crafting + processing, target different containers, and override the
     * global replace_existing policy per pattern.
     *
     * <p>Two transactional modes:
     * <ul>
     *   <li>{@code atomic: false} (default) — best-effort: successful patterns
     *       stay written, failures are reported per index. Useful for
     *       incremental "fill what you can" work.</li>
     *   <li>{@code atomic: true} — full-or-nothing: any failure rolls back
     *       every successful write via a pre-write slot snapshot. Useful when
     *       the LLM has a complete plan and wants to commit or abort as a unit.</li>
     * </ul>
     *
     * <p>{@code dry_run: true} runs the full encode + target-choose path but
     * skips the actual slot write; response is a "would-write" preview.
     */
    private static final ToolDefinition AE2_PATTERN_WRITE_MANY_TOOL = new ToolDefinition(
            "ae2_pattern_write_many",
            "Encode and install up to 16 AE2 encoded patterns in a single call. Mirrors "
                    + "ae2_pattern_write but takes a 'patterns' array; each entry is the same shape as "
                    + "ae2_pattern_write's arguments (pattern_type, recipe_id, output_item_id, crafting_grid, "
                    + "inputs, outputs, allow_substitutes, allow_fluid_substitutes, replace_existing, "
                    + "container_index, slot). The x/y/z anchor, target_machine_item_id, target_group_name, "
                    + "and the default replace_existing / allow_substitutes / allow_fluid_substitutes are "
                    + "shared across the batch (top-level). Per-spec 'replace_existing' overrides the top "
                    + "level. PREFER THIS over ae2_pattern_write when installing more than one pattern at "
                    + "once (e.g. an entire Create machine chain). 'atomic: true' makes the batch "
                    + "all-or-nothing — any failure rolls back every successful write. 'dry_run: true' "
                    + "previews what would be written without actually writing.",
            Map.ofEntries(
                    Map.entry("patterns", Map.of(
                            "type", "array",
                            "description", "list of pattern specs. Each entry is a self-contained ae2_pattern_write "
                                    + "argument map (pattern_type, recipe_id or output_item_id, crafting_grid or "
                                    + "inputs+outputs, optional replace_existing / container_index / slot).",
                            "minItems", 1,
                            "maxItems", 16,
                            "items", AE2_PATTERN_WRITE_ITEM_SCHEMA
                    )),
                    Map.entry("atomic", Map.of("type", "boolean",
                            "description", "if true, any failure rolls back every successful write in this call. "
                                    + "Default false (best-effort).")),
                    Map.entry("dry_run", Map.of("type", "boolean",
                            "description", "if true, run the full encode + target-choose path but skip the actual "
                                    + "slot write. Returns the would-be targets. Default false.")),
                    Map.entry("replace_existing", Map.of("type", "boolean",
                            "description", "default replace_existing policy for specs that don't override it. Default true.")),
                    Map.entry("allow_substitutes", Map.of("type", "boolean",
                            "description", "default crafting item-substitution flag. Default false.")),
                    Map.entry("allow_fluid_substitutes", Map.of("type", "boolean",
                            "description", "default crafting fluid-substitution flag. Default false.")),
                    Map.entry("target_machine_item_id", Map.of("type", "string",
                            "description", "shared machine item id hint for target container selection (e.g. "
                                    + "create:mechanical_press). Per-spec container_index still wins.")),
                    Map.entry("target_group_name", Map.of("type", "string",
                            "description", "shared terminal group name hint for target container selection.")),
                    Map.entry("x", Map.of("type", "integer", "description", "shared AE2 network anchor x")),
                    Map.entry("y", Map.of("type", "integer", "description", "shared AE2 network anchor y")),
                    Map.entry("z", Map.of("type", "integer", "description", "shared AE2 network anchor z"))
            )
    );

    /**
     * JSON Schema fragment for a single place entry. OpenAI function-calling
     * accepts nested object schemas via the standard JSON Schema keywords
     * (type / properties / required). We share the snippet between
     * place_many's array items and break_many's array items.
     */
    private static final Map<String, Object> PLACE_ITEM_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "x", Map.of("type", "integer", "description", "absolute x coordinate"),
                    "y", Map.of("type", "integer", "description", "absolute y coordinate"),
                    "z", Map.of("type", "integer", "description", "absolute z coordinate"),
                    "block", Map.of("type", "string", "description", "block id, e.g. minecraft:stone"),
                    "replace", Map.of("type", "boolean", "description", "overwrite non-air (default true)")
            ),
            "required", List.of("x", "y", "z", "block")
    );

    private static final Map<String, Object> BREAK_ITEM_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "x", Map.of("type", "integer", "description", "absolute x coordinate"),
                    "y", Map.of("type", "integer", "description", "absolute y coordinate"),
                    "z", Map.of("type", "integer", "description", "absolute z coordinate")
            ),
            "required", List.of("x", "y", "z")
    );

    /**
     * Batch place tool. Lets the model place up to MAX_BATCH_SIZE blocks in a
     * single round-trip, instead of one tool call per block. Without this
     * the LLM is rate-limited to MAX_TOOLS_PER_TURN = 10 per turn, so even
     * a small 5x5 house takes many turns to build.
     */
    private static final ToolDefinition PLACE_MANY_TOOL = new ToolDefinition(
            "place_many",
            "Place multiple SOLID blocks in a single call. PREFER THIS over 'place' when "
                    + "you need to set several blocks (e.g. building a wall, floor, or full "
                    + "structure). Pass an array 'blocks' of {x,y,z,block,replace?}; each entry "
                    + "becomes one BlockApi.place. Max 64 entries per call. Coordinates are "
                    + "absolute world coordinates in the transmitter's dimension. Do NOT include "
                    + "'minecraft:air' here — use break_many to clear cells.",
            Map.of(
                    "blocks", Map.of(
                            "type", "array",
                            "description", "list of {x,y,z,block,replace?} entries to place",
                            "items", PLACE_ITEM_SCHEMA,
                            "minItems", 1
                    )
            )
    );

    /**
     * Batch break tool. Mirrors place_many but for removal. Up to 64 cells
     * per call, all become air.
     */
    private static final ToolDefinition BREAK_MANY_TOOL = new ToolDefinition(
            "break_many",
            "Remove (set to air) multiple blocks in a single call. PREFER THIS over "
                    + "'break_block' when clearing several cells. Pass an array 'blocks' of "
                    + "{x,y,z} entries. Max 64 entries per call. Coordinates are absolute "
                    + "world coordinates in the transmitter's dimension.",
            Map.of(
                    "blocks", Map.of(
                            "type", "array",
                            "description", "list of {x,y,z} entries to clear",
                            "items", BREAK_ITEM_SCHEMA,
                            "minItems", 1
                    )
            )
    );

    /**
     * Default web search: MC百科 (Chinese Minecraft mod wiki) only.
     * This is the right tool for almost every MC-related question
     * (mod names, recipes, version compatibility, item details, mod
     * lists). It is the only web search tool exposed by default —
     * general-web search is kept behind a separate tool that is only
     * added to the advertised tool list when the user explicitly asks
     * for it (see {@link #userWantsGeneralWebSearch}).
     */
    private static final ToolDefinition MCMOD_SEARCH_TOOL = new ToolDefinition(
            "mcmod_search",
            "Search MC百科 (mcmod.cn) — the Chinese Minecraft mod wiki — for "
                    + "mod metadata, mod descriptions, version compatibility, and mod "
                    + "lists. For CRAFTING / RECIPE / 'how do I make X' questions, "
                    + "PREFER emi_lookup (or jei_lookup) instead — the recipe and the "
                    + "materials are returned directly by that tool, and going through "
                    + "mcmod adds an extra hop with no extra info. Use this tool only "
                    + "when the user is asking about a mod itself (description, version, "
                    + "supported MC versions, compatible mods) or wants lore/text. Goes "
                    + "through the configured proxy to bypass captcha and is much more "
                    + "reliable for MC content than generic search engines. 'query' is "
                    + "required. For general web questions unrelated to Minecraft use "
                    + "web_search (only available when the user explicitly opts in).",
            Map.of(
                    "query", Map.of("type", "string", "description", "搜索词 / search keywords"),
                    "max_results", Map.of("type", "integer",
                            "description", "可选，最大返回条数 1..20，默认 10")
            )
    );

    /**
     * Fetch a specific MC百科 page and extract its content. Two modes:
     * 'mcmod_item' (one item by numeric id) or 'mcmod_list' (item list
     * for a mod by class id + page). This is the MC百科 counterpart of
     * web_fetch and is exposed by default. Goes through the configured
     * proxy to bypass captcha.
     */
    private static final ToolDefinition MCMOD_FETCH_TOOL = new ToolDefinition(
            "mcmod_fetch",
            "Fetch a specific MC百科 (mcmod.cn) page. Two modes: 'mcmod_item' "
                    + "(fetch one item page; pass 'item_id', the numeric id from an "
                    + "mcmod_search result link) or 'mcmod_list' (fetch the item "
                    + "list for a mod; pass 'class_id' and optional 'page'). For "
                    + "CRAFTING / RECIPE details prefer emi_lookup (or jei_lookup) "
                    + "instead — those tools return the materials directly without an "
                    + "extra page fetch. Use this tool for mod metadata, version info, "
                    + "or lore that emi_lookup / jei_lookup don't cover. Goes through "
                    + "the configured proxy to bypass captcha. For general non-MC "
                    + "pages use web_fetch (only available when the user explicitly "
                    + "opts in).",
            Map.of(
                    "mode", Map.of("type", "string",
                            "description", "mcmod_item | mcmod_list"),
                    "item_id", Map.of("type", "string",
                            "description", "mcmod_item 模式：MC百科 物品 ID（数字）"),
                    "class_id", Map.of("type", "string",
                            "description", "mcmod_list 模式：MC百科 模组 class ID（数字）"),
                    "page", Map.of("type", "integer",
                            "description", "mcmod_list 模式：页码，默认 1")
            )
    );

    /**
     * General web search. NOT exposed by default. Only added to the
     * advertised tool list when the user's message explicitly asks for
     * a non-mcmod web search (bing / duckduckgo / generic web query).
     * This keeps the agent on the high-quality MC百科 path for the
     * default "build me a thing in Minecraft" workflow and stops it
     * from accidentally paying bing for every fact lookup.
     */
    private static final ToolDefinition WEB_SEARCH_TOOL = new ToolDefinition(
            "web_search",
            "Search the public web via Bing or DuckDuckGo. This tool is NOT "
                    + "available for ordinary Minecraft tasks — the user must have "
                    + "explicitly asked for a generic web search (e.g. '用bing搜' / "
                    + "'必应搜索' / 'search the web' / 'duckduckgo'). "
                    + "For anything Minecraft-related prefer mcmod_search, which is "
                    + "far more accurate for MC content. 'query' is required. 'engine' "
                    + "(optional) forces 'bing' | 'duckduckgo' | 'auto' (default 'auto' "
                    + "= bing first, then duckduckgo).",
            Map.of(
                    "query", Map.of("type", "string", "description", "搜索词 / search keywords"),
                    "engine", Map.of("type", "string",
                            "description", "可选：bing | duckduckgo | auto，默认 auto"),
                    "max_results", Map.of("type", "integer",
                            "description", "可选，最大返回条数 1..20，默认 10")
            )
    );

    /**
     * Fetch an arbitrary non-MC百科 page. NOT exposed by default. Only
     * added to the advertised tool list when the user has explicitly
     * asked for general web search. For MC百科 pages use mcmod_fetch.
     */
    private static final ToolDefinition WEB_FETCH_TOOL = new ToolDefinition(
            "web_fetch",
            "Fetch an arbitrary non-MC web page and extract its <title> + plain "
                    + "text body. This tool is NOT available for ordinary Minecraft "
                    + "tasks — it is only present when the user has explicitly asked "
                    + "for a generic web lookup. For MC百科 pages use mcmod_fetch. "
                    + "Pass a full 'url' that starts with http:// or https://.",
            Map.of(
                    "url", Map.of("type", "string",
                            "description", "完整 URL，必须以 http:// 或 https:// 开头")
            )
    );

    /** Cap per batch tool call. Big enough for a small house (5x5 walls = 60 blocks). */
    private static final int MAX_BATCH_SIZE = 64;

    /**
     * General web search tools. These are added to the advertised
     * tool list on top of the base MC百科 tools when the user's
     * current message explicitly opts in to a non-mcmod web search
     * (see {@link #userWantsGeneralWebSearch}). Kept as a separate
     * constant so other call sites (e.g. a future per-user toggle)
     * can reuse the same allowlist.
     */
    /** Cache for the dynamically-resolved recipe-lookup tool pair. The
     *  mod-load set is stable for the lifetime of a JVM (mods only change
     *  on world reload, which discards the classloader and refreshes
     *  statics), so we compute once and never re-evaluate. The pair is
     *  picked by {@link #recipeLookupTools()}: EMI wins if both are
     *  installed, otherwise whichever of EMI/JEI is installed, otherwise
     *  the empty list. */
    private static final java.util.concurrent.atomic.AtomicReference<java.util.List<ToolDefinition>> RECIPE_LOOKUP_TOOLS_CACHE =
            new java.util.concurrent.atomic.AtomicReference<>();

    private static final List<ToolDefinition> GENERAL_WEB_TOOLS = List.of(
            WEB_SEARCH_TOOL, WEB_FETCH_TOOL);

    private static final List<ToolDefinition> TOOLS = List.of(
            NOOP_TOOL, READ_BLOCK_TOOL, PLACE_TOOL, BREAK_BLOCK_TOOL,
            PLACE_MANY_TOOL, BREAK_MANY_TOOL, QUESTION_TOOL,
            AE2_PATTERN_LOOKUP_TOOL, AE2_PATTERN_WRITE_TOOL, AE2_PATTERN_WRITE_MANY_TOOL,
            MCMOD_SEARCH_TOOL, MCMOD_FETCH_TOOL, FINISH_TOOL);
    private static final List<ToolDefinition> PLAN_TOOLS = List.of(
            NOOP_TOOL, READ_BLOCK_TOOL, QUESTION_TOOL,
            AE2_PATTERN_LOOKUP_TOOL, MCMOD_SEARCH_TOOL, MCMOD_FETCH_TOOL, FINISH_TOOL);
    private static final List<ToolDefinition> CHAT_TOOLS = List.of();

    /** Pick the recipe-lookup tool pair that should be advertised for the
     *  current mod set. EMI wins when both are installed (per spec). Returns
     *  an empty list if neither is installed so the LLM doesn't even see
     *  the tools. Cached on first call. */
    private static List<ToolDefinition> recipeLookupTools() {
        java.util.List<ToolDefinition> cached = RECIPE_LOOKUP_TOOLS_CACHE.get();
        if (cached != null) {
            return cached;
        }
        boolean emi = net.neoforged.fml.ModList.get().isLoaded("emi");
        boolean jei = !emi && net.neoforged.fml.ModList.get().isLoaded("jei");
        java.util.List<ToolDefinition> resolved;
        if (emi) {
            resolved = List.of(EMI_LOOKUP_TOOL, EMI_NAME_LOOKUP_TOOL);
        } else if (jei) {
            resolved = List.of(JEI_LOOKUP_TOOL, JEI_NAME_LOOKUP_TOOL);
        } else {
            resolved = List.of();
        }
        java.util.List<ToolDefinition> imm = java.util.Collections.unmodifiableList(resolved);
        RECIPE_LOOKUP_TOOLS_CACHE.compareAndSet(null, imm);
        return imm;
    }

    /**
     * Heuristic for "the user explicitly asked for a general web
     * search". Triggers when the current user message mentions
     * bing/必应/duckduckgo/ddg or asks for a non-MC web lookup
     * (web search / online search / 联网搜 etc.). Conservative on
     * purpose — bare "搜索" / "查一下" is intentionally NOT a
     * trigger, because for MC questions mcmod_search is the right
     * tool and we don't want to leak the general-web path.
     */
    static boolean userWantsGeneralWebSearch(String userText) {
        if (userText == null || userText.isBlank()) return false;
        String t = userText.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("bing") || t.contains("必应")) return true;
        if (t.contains("duckduckgo") || t.contains(" ddg ") || t.contains("ddg搜索")
                || t.contains("ddg 搜") || t.startsWith("ddg ") || t.endsWith(" ddg")
                || t.contains("用ddg")) return true;
        if (t.contains("web search") || t.contains("web_search")
                || t.contains("search the web") || t.contains("online search")) return true;
        if (userText.contains("联网搜索") || userText.contains("网络搜索")
                || userText.contains("上网搜") || userText.contains("在线搜索")) return true;
        if (userText.contains("搜索引擎") || userText.contains("搜引擎")) return true;
        return false;
    }
    private static final int REQUEST_ENERGY_COST = 100;
    private static final int TOOL_ENERGY_COST = 20;

    /** Public read-only view of the tool list. Used by the mod's startup
     *  dump and by other callers that want to enumerate what the LLM can see. */
    public static List<ToolDefinition> advertisedTools() {
        return java.util.Collections.unmodifiableList(TOOLS);
    }

    private record RuntimeConfig(String apiKey, String endpoint, String modelName, String systemPrompt,
                                 List<ToolDefinition> tools) {
    }

    /** Marker exception thrown by {@link StreamingSink#checkStop} when the
     *  user hits /stop mid-stream. We use a checked-style RuntimeException
     *  (not a checked exception) because the StreamListener interface is
     *  pre-1.8 with no throws clause, and the LlmClient stream() method
     *  wraps everything in a RuntimeException anyway. The downstream
     *  whenComplete handler checks for this class to suppress the
     *  generic "(error) ..." surface. */
    public static final class AgentStopException extends RuntimeException {
        public AgentStopException() { super("agent stopped by user"); }
    }

    public static void respond(IntelligentTransmitterBlockEntity t, String userText) {
        respondWithHistory(t, userText, null);
    }

    /**
     * Like {@link #respond} but reads the persistent history from the block
     * entity so the LLM sees the rolling conversation. After each LLM
     * response, the assistant turn and any tool results are appended back to
     * the block entity's history (capped at MAX_HISTORY_TURNS).
     */
    public static void respondWithHistory(IntelligentTransmitterBlockEntity t, String userText, ServerPlayer initiator) {
        // Reset the agent-loop stop flag at the start of every user request.
        t.clearAgentStop();
        // Clear any 'awaiting user answer' from a previous 'question' tool
        // call — we're getting a fresh user message now, so the loop can
        // re-enter the recursive runLoop path. Also clear the pending
        // question picker so the floating card disappears once the user
        // has answered (even if the answer came from a free-text send,
        // not a picker selection). We push a fresh BlockUpdate after
        // this so the client's BE mirror sees the null promptly.
        t.setAwaitingUserAnswer(false);
        t.setPendingQuestion(null);
        t.setAgentRunning(true);
        respondInternal(t, userText, initiator);
    }

    private static void respondInternal(IntelligentTransmitterBlockEntity t, String userText, ServerPlayer initiator) {
        RuntimeConfig runtime = resolveRuntimeConfig(t, userText);
        if (runtime == null) {
            return;
        }
        int timeout = LLMEraConfig.REQUEST_TIMEOUT_SECONDS.get();

        // Build the request payload: system + BE history + new user turn.
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(runtime.systemPrompt()));
        for (IntelligentTransmitterBlockEntity.StoredTurn turn : t.getHistory()) {
            if ("user".equals(turn.role())) {
                messages.add(ChatMessage.user(turn.content()));
            } else if ("assistant".equals(turn.role())) {
                if (turn.toolCallsJson() != null && !turn.toolCallsJson().isEmpty()) {
                    messages.add(ChatMessage.assistantWithToolCalls(turn.content(), turn.toolCallsJson()));
                } else {
                    messages.add(ChatMessage.assistant(turn.content()));
                }
            } else if ("tool".equals(turn.role())) {
                messages.add(ChatMessage.tool(turn.toolCallId(), turn.content()));
            }
        }
        // Append the user turn to BOTH the in-flight request AND the
        // persistent history. Without this the history would only ever
        // contain assistant / tool turns and the player's input would never
        // appear in the chat view.
        messages.add(ChatMessage.user(userText));
        t.appendHistory(IntelligentTransmitterBlockEntity.StoredTurn.user(userText));
        agentDebug("user turn START: model='{}' maxTurns={} msgPreview={}",
                runtime.modelName(), LLMEraConfig.MAX_AGENT_TURNS.get(), previewForLog(userText, 200));

        runLoop(t, runtime.apiKey(), runtime.endpoint(), runtime.modelName(), messages,
                runtime.tools(), timeout, LLMEraConfig.MAX_AGENT_TURNS.get(), initiator,
                new java.util.HashMap<>(), 0);
    }

    private static RuntimeConfig resolveRuntimeConfig(IntelligentTransmitterBlockEntity t, String userText) {
        String devKey = LLMEraConfig.DEV_API_KEY.get();
        LlmToolMode mode = t instanceof LlmExecutorBlockEntity executor ? executor.getMode() : LlmToolMode.BUILD;
        IntelligentTransmitterBlockEntity configSource = t;

        if (t instanceof LlmExecutorBlockEntity) {
            if (!(t.getLevel() instanceof ServerLevel serverLevel)) {
                t.setLastConversationResponse("(executor is not on a server level)");
                return null;
            }
            CodeNetworkScanner.NetworkView view = CodeNetworkScanner.scan(serverLevel, t.getBlockPos());
            if (view.controller() == null) {
                t.setLastConversationResponse("(no code network controller connected)");
                return null;
            }
            if (!view.powered()) {
                t.setLastConversationResponse("(code network controller has no power)");
                return null;
            }
            if (view.configurator() == null) {
                t.setLastConversationResponse("(no LLM configurator connected)");
                return null;
            }
            if (!view.controller().consumeEnergy(REQUEST_ENERGY_COST)) {
                t.setLastConversationResponse("(code network controller is out of power)");
                return null;
            }
            configSource = view.configurator();
        }

        String endpoint;
        String modelName;
        String apiKey;
        if (devKey != null && !devKey.isBlank()) {
            apiKey = devKey;
            endpoint = LLMEraConfig.DEV_ENDPOINT.get();
            modelName = LLMEraConfig.DEV_MODEL_NAME.get();
        } else {
            apiKey = configSource.decryptedApiKey();
            if (apiKey == null || apiKey.isEmpty()) {
                LLMEra.LOGGER.warn("[{}] ChatEngine turn aborted: no api key set at {}",
                        LLMEra.MODID, configSource.getBlockPos());
                t.setLastConversationResponse("(no api key set)");
                return null;
            }
            endpoint = configSource.getModelUrl();
            modelName = configSource.getModelName();
        }
        if (endpoint == null || endpoint.isEmpty() || modelName == null || modelName.isEmpty()) {
            LLMEra.LOGGER.warn("[{}] ChatEngine turn aborted: endpoint or model empty (endpoint='{}' model='{}')",
                    LLMEra.MODID, endpoint, modelName);
            t.setLastConversationResponse("(endpoint or model not set)");
            return null;
        }
        String systemPrompt = configSource.getSystemPrompt().isEmpty()
                ? LLMEraConfig.DEFAULT_SYSTEM_PROMPT.get()
                : configSource.getSystemPrompt();
        return new RuntimeConfig(apiKey, endpoint, modelName, systemPrompt, toolsForMode(mode, userText));
    }

    private static List<ToolDefinition> toolsForMode(LlmToolMode mode, String userText) {
        List<ToolDefinition> base = switch (mode) {
            case BUILD -> TOOLS;
            case PLAN -> PLAN_TOOLS;
            case CHAT -> CHAT_TOOLS;
        };
        java.util.List<ToolDefinition> withRecipe = new java.util.ArrayList<>(base);
        for (ToolDefinition td : recipeLookupTools()) {
            if (!withRecipe.contains(td)) {
                withRecipe.add(td);
            }
        }
        if (userWantsGeneralWebSearch(userText)) {
            for (ToolDefinition td : GENERAL_WEB_TOOLS) {
                if (!withRecipe.contains(td)) {
                    withRecipe.add(td);
                }
            }
        }
        return java.util.Collections.unmodifiableList(withRecipe);
    }

    private static final java.util.concurrent.atomic.AtomicInteger STREAM_SEQ_GEN = new java.util.concurrent.atomic.AtomicInteger(0);
    /** Flush thresholds for the streaming sink. Sends a packet to the
     *  client either when the buffered text+reasoning delta reaches
     *  {@link #FLUSH_CHARS} characters or when
     *  {@link #FLUSH_INTERVAL_MS} have elapsed since the last flush. */
    private static final int FLUSH_CHARS = 50;
    private static final long FLUSH_INTERVAL_MS = 100L;

    /** Buffers LLM stream deltas and sends a {@link ModPackets.StreamDeltaPayload}
     *  to the originating player. Threaded-safe because LlmClient.stream()
     *  calls onContent / onReasoning from the async HTTP thread. */
    static final class StreamingSink implements LlmClient.StreamListener {
        private final ServerPlayer player;
        private final IntelligentTransmitterBlockEntity t;
        private final int seq;
        private final BlockPos pos;
        private final StringBuilder textBuf = new StringBuilder();
        private final StringBuilder reasoningBuf = new StringBuilder();
        private volatile long lastFlushMs = System.currentTimeMillis();
        private final java.util.concurrent.atomic.AtomicBoolean scheduled = new java.util.concurrent.atomic.AtomicBoolean(false);
        private volatile boolean finished = false;

        StreamingSink(ServerPlayer player, IntelligentTransmitterBlockEntity t, int seq) {
            this.player = player;
            this.t = t;
            this.seq = seq;
            this.pos = t.getBlockPos();
        }

        @Override public void onContent(String chunk) {
            if (chunk == null || chunk.isEmpty()) return;
            checkStop();
            textBuf.append(chunk);
            maybeFlush(false);
        }

        @Override public void onReasoning(String chunk) {
            if (chunk == null || chunk.isEmpty()) return;
            checkStop();
            reasoningBuf.append(chunk);
            maybeFlush(false);
        }

        /** Abort the in-flight stream when the user has hit /stop. The
         *  HTTP body is closed by HttpClient when the iterator is
         *  abandoned, so this also kills the upstream request and stops
         *  burning tokens. The exception propagates back through
         *  LlmClient.stream() and surfaces in runLoop's whenComplete,
         *  which already has a stop-handling branch. */
        private void checkStop() {
            if (t.isAgentStopRequested()) {
                throw new AgentStopException();
            }
        }

        private void maybeFlush(boolean force) {
            int pending = textBuf.length() + reasoningBuf.length();
            long since = System.currentTimeMillis() - lastFlushMs;
            if (!force && pending < FLUSH_CHARS && since < FLUSH_INTERVAL_MS) {
                return;
            }
            if (player == null) {
                // No client to send to (script / console path). Drop buffer.
                textBuf.setLength(0);
                reasoningBuf.setLength(0);
                lastFlushMs = System.currentTimeMillis();
                return;
            }
            String tDelta = textBuf.toString();
            String rDelta = reasoningBuf.toString();
            textBuf.setLength(0);
            reasoningBuf.setLength(0);
            lastFlushMs = System.currentTimeMillis();
            PacketDistributor.sendToPlayer(player, new ModPackets.StreamDeltaPayload(pos, seq, tDelta, rDelta));
        }

        /** Called once on stream end. Sends any remaining buffer as a final
         *  {@link ModPackets.StreamDeltaPayload} plus a
         *  {@link ModPackets.StreamEndPayload} to mark the turn done. */
        void finish() {
            finished = true;
            maybeFlush(true);
            if (player != null) {
                // The final accumulated text lives in the BE's streamingText
                // (kept in sync by the deltas) and the just-shipped deltas
                // cover anything still buffered at stream-end. StreamEnd
                // here is mostly a signal for the client to clear the live
                // streaming state.
                PacketDistributor.sendToPlayer(player, new ModPackets.StreamEndPayload(pos, seq, "", ""));
            }
        }

        int seq() { return seq; }
    }

    private static void runLoop(IntelligentTransmitterBlockEntity t, String apiKey, String endpoint, String modelName,
                                 List<ChatMessage> messages, List<ToolDefinition> tools, int timeout, int roundsLeft,
                                 ServerPlayer initiator, java.util.Map<String, String> toolCache, int consecutiveFailures) {
        if (roundsLeft <= 0) {
            agentWarn("runLoop exiting: roundsLeft exhausted (agent loop limit reached)");
            t.setLastConversationResponse("(agent loop limit reached)");
            t.setAgentRunning(false);
            return;
        }
        if (t.isAgentStopRequested()) {
            agentDebug("runLoop exiting: stop requested before turn");
            // Stop was requested (client clicked Stop, or the previous
            // turn hit an unrecoverable error). Don't make another LLM call.
            t.setAgentRunning(false);
            return;
        }
        agentDebug("runLoop BEGIN round={}/{} model='{}' messages={} endpoint='{}'",
                roundsLeft, LLMEraConfig.MAX_AGENT_TURNS.get(), modelName, messages.size(), endpoint);
        int streamSeq = STREAM_SEQ_GEN.incrementAndGet();
        StreamingSink sink = new StreamingSink(initiator, t, streamSeq);
        agentDebug("LLM stream START streamSeq={} model='{}' messages={} tools={} timeout={}s",
                streamSeq, modelName, messages.size(), tools.size(), timeout);
        new LlmClient().stream(endpoint, modelName, apiKey, messages, tools, timeout, LLMEraConfig.THINKING_BUDGET.get(), sink)
                .whenComplete((resp, err) -> {
                    MinecraftServer srv = t.getLevel() instanceof ServerLevel sl ? sl.getServer() : null;
                    if (srv == null) {
                        agentWarn("LLM stream END streamSeq={} BUT no server main thread; aborting turn", streamSeq);
                        // No main thread we can use. Best-effort: still notify
                        // the client that the stream is over (the client
                        // renderer is what users see, not the BE fields) and
                        // surface a friendly error. Same /stop handling
                        // as the happy path so the abort doesn't end up
                        // looking like a server crash.
                        try { sink.finish(); } catch (Exception ignored) {}
                        if (err instanceof AgentStopException
                                || (err != null && err.getCause() instanceof AgentStopException)) {
                            t.setLastConversationResponse("[已停止]");
                        } else {
                            t.setLastConversationResponse("(server unavailable)");
                        }
                        t.setAgentRunning(false);
                        return;
                    }
                    srv.execute(() -> {
                        // try-catch-finally guarantees the client always sees a
                        // StreamEnd even if the BE work throws. The screen
                        // relies on receiving an end marker to clear the
                        // "live" streaming line and fall back to history.
                        // The catch is a backstop: executeTool already wraps
                        // its own throwables, but anything else in this
                        // Runnable (history append, scheduler call, etc.)
                        // could still throw and would otherwise kill the
                        // agent loop without a visible error to the user.
                        try {
                        if (err != null) {
                            agentWarn("LLM stream END streamSeq={} err={}", streamSeq, err.toString());
                            // A user-driven /stop abort throws through
                            // the StreamListener and surfaces here.
                            // Don't paint it as an error in the chat
                            // history — record a short note and exit
                            // cleanly.
                            if (err instanceof AgentStopException
                                    || (err.getCause() instanceof AgentStopException)) {
                                t.setLastConversationResponse("[已停止]");
                                t.setAgentRunning(false);
                                return;
                            }
                            LLMEra.LOGGER.warn(
                                    "[{}] LLM call failed: {}",
                                    LLMEra.MODID, err.getMessage());
                            t.setLastConversationResponse("(error) " + err.getMessage());
                            t.setAgentRunning(false);
                            return;
                        }
                        agentDebug("LLM stream END streamSeq={} textLen={} toolCalls={} reasoningLen={}",
                                streamSeq,
                                resp.text() == null ? 0 : resp.text().length(),
                                resp.toolCalls().size(),
                                resp.reasoning() == null ? 0 : resp.reasoning().length());
                        if (!resp.hasToolCalls()) {
                            agentDebug("LLM produced final answer (no tool calls); agent loop END");
                            // No tool calls and the model didn't call finish. The agent
                            // loop is over (model produced its final answer). Persist the
                            // assistant turn and surface the reply.
                            String truncatedReasoning = truncateReasoning(resp.reasoning(), t);
                            IntelligentTransmitterBlockEntity.StoredTurn finalTurn = !truncatedReasoning.isEmpty()
                                    ? IntelligentTransmitterBlockEntity.StoredTurn
                                            .assistantTextWithReasoning(resp.text(), truncatedReasoning)
                                    : IntelligentTransmitterBlockEntity.StoredTurn.assistantText(resp.text());
                            t.appendHistory(finalTurn);
                            t.setLastConversationResponse(resp.text());
                            t.setAgentRunning(false);
                            return;
                        }
                        // Cap tool calls per turn. Anything above MAX_TOOLS_PER_TURN
                        // is dropped from the in-flight request and the history; the
                        // LLM is told via a synthetic tool result for each dropped call
                        // so the next turn self-corrects.
                        java.util.List<ToolInvocation> calls = resp.toolCalls();
                        java.util.List<ToolInvocation> accepted = calls.size() > MAX_TOOLS_PER_TURN
                                ? calls.subList(0, MAX_TOOLS_PER_TURN)
                                : calls;
                        java.util.List<ToolInvocation> dropped = calls.size() > MAX_TOOLS_PER_TURN
                                ? calls.subList(MAX_TOOLS_PER_TURN, calls.size())
                                : java.util.List.of();
                        // Log the model-decided call set so we can correlate the
                        // LLM's intent with what we actually executed. Without
                        // this we have no easy way to see if the LLM is
                        // repeatedly calling a single tool or oscillating.
                        if (agentDebug()) {
                            StringBuilder sb = new StringBuilder("[");
                            for (int i = 0; i < accepted.size(); i++) {
                                if (i > 0) sb.append(", ");
                                sb.append(accepted.get(i).name());
                            }
                            sb.append(']');
                            agentDebug("LLM requested tool calls: accepted={} dropped={} names={}",
                                    accepted.size(), dropped.size(), sb);
                        }
                        // Build the assistant turn with tool_calls in the exact shape
                        // OpenAI expects: [{id, type:"function", function:{name, arguments:str}}].
                        LlmResponse trimmedResp = new LlmResponse(resp.text(), truncateReasoning(resp.reasoning(), t), accepted);
                        // If the model called 'finish' on this turn and didn't
                        // write any text, surface the finish reason as the
                        // visible assistant message so it shows up in the UI.
                        if (trimmedResp.text() == null || trimmedResp.text().isBlank()) {
                            for (ToolInvocation inv : trimmedResp.toolCalls()) {
                                if ("finish".equals(inv.name())) {
                                    Object reason = inv.arguments().get("reason");
                                    if (reason != null && !reason.toString().isBlank()) {
                                        trimmedResp = new LlmResponse("[finish] " + reason,
                                                trimmedResp.reasoning(), trimmedResp.toolCalls());
                                    }
                                    break;
                                }
                            }
                        }
                        ChatMessage assistant = assistantToolCallMessage(trimmedResp);
                        String tcJson = gson().toJson(serializeToolCalls(trimmedResp));
                        IntelligentTransmitterBlockEntity.StoredTurn assistantTurn = !trimmedResp.hasReasoning()
                                ? toStoredTurn(assistant)
                                : IntelligentTransmitterBlockEntity.StoredTurn
                                        .assistantToolCallsWithReasoning(trimmedResp.text(), tcJson, trimmedResp.reasoning());
                        t.appendHistory(assistantTurn);
                        // Add to in-flight messages for the next round.
                        messages.add(assistant);
                        // The dispatch loop runs inside a Runnable submitted
                        // to the server's main-thread executor. Java
                        // requires that locals captured by the inner
                        // lambdas be effectively final, so we carry
                        // consecutiveFailures in a 1-element holder
                        // instead of trying to mutate a captured int.
                        // Declared in the outer try block (not inside the
                        // for-loop scope) so the recursive runLoop call
                        // below can read the final value.
                        final int[] failures = { consecutiveFailures };
                        for (int i = 0; i < accepted.size(); i++) {
                            ToolInvocation inv = accepted.get(i);
                            // If a previous tool call in this batch (typically
                            // 'finish' or 'question') already requested a stop,
                            // break out of the batch IMMEDIATELY. The recursion
                            // check below would also catch the stop flag, but
                            // executing the remaining tools would waste work
                            // and add stale entries to history. This is the
                            // "completely stop" behavior the user expects from
                            // 'finish' when it's batched with other tools
                            // (e.g. LLM emits [finish, place, break_block] in
                            // one turn — only 'finish' should run).
                            if (t.isAgentStopRequested()) {
                                agentDebug("stop requested mid-batch after tool {} of {}; skipping remaining {} calls",
                                        i, accepted.size(), accepted.size() - i);
                                break;
                            }
                            agentDebug("dispatching tool call id='{}' name='{}' ({} of {})",
                                    inv.id(), inv.name(), i + 1, accepted.size());
                            String result;
                            String cacheKey = toolCacheKey(inv);
                            String cached = toolCache.get(cacheKey);
                            if (cached != null) {
                                // Per-turn cache hit: the LLM has already
                                // received this exact (tool, args) result
                                // earlier in the same user turn. Replaying
                                // it from cache (with a small marker)
                                // saves the work of the actual invocation
                                // and gives the LLM an explicit signal
                                // that the answer is already in the
                                // history — it should USE that answer
                                // rather than asking again or re-issuing
                                // a subtly-different variant.
                                result = cached;
                                agentDebug("tool cache HIT key='{}' -> reuse", cacheKey);
                            } else {
                                result = truncateToolResult(executeTool(t, inv), t);
                                toolCache.put(cacheKey, result);
                            }
                            ChatMessage toolMsg = ChatMessage.tool(inv.id(), result);
                            t.appendHistory(toStoredTurn(toolMsg));
                            messages.add(toolMsg);
                            // Track consecutive failures so a stuck agent
                            // loop (e.g. misconfigured JEI/EMI causing
                            // 'ok:false' on every query, or a bad block_id
                            // that always returns an error) eventually
                            // gets a synthetic nudge to either ask the
                            // user or finish the turn. Anything that
                            // returns a non-error response resets the
                            // counter. Errors come back as `{"error": ...}`
                            // from the tool catch-alls and as
                            // `{"ok": false, ...}` from query tools.
                            if (toolResultIsFailure(result)) {
                                failures[0]++;
                                if (failures[0] >= MAX_CONSECUTIVE_FAILURES) {
                                    final int finalFailureCount = failures[0];
                                    String nudge = "[System: " + finalFailureCount + " consecutive tool "
                                            + "failures on '" + inv.name() + "'. STOP retrying the same "
                                            + "call. Either (a) ask the user a question via the 'question' "
                                            + "tool to clarify intent, or (b) switch to a different tool / "
                                            + "approach, or (c) call 'finish' to end the turn with a brief "
                                            + "explanation of what went wrong. The cache from earlier in this "
                                            + "turn is still available — reuse prior successful lookups "
                                            + "rather than re-querying.]";
                                    agentDebug("consecutive failures reached {}; injecting synthetic nudge", finalFailureCount);
                                    messages.add(ChatMessage.user(nudge));
                                    // Reset so we don't re-fire the nudge
                                    // every recursion. The LLM is supposed
                                    // to course-correct after seeing it; if
                                    // it still fails, we'll fire it again
                                    // after another MAX_CONSECUTIVE_FAILURES
                                    // streak.
                                    failures[0] = 0;
                                }
                            } else {
                                failures[0] = 0;
                            }
                        }
                        // Inject synthetic replies for dropped tool calls so the LLM
                        // understands its batch was truncated. Each dropped call
                        // gets a placeholder response; we DO NOT persist these to
                        // history (they would explode storage).
                        for (ToolInvocation droppedInv : dropped) {
                            String placeholder = "{\"error\":\"dropped: per-turn tool cap of "
                                    + MAX_TOOLS_PER_TURN + " reached; please call this tool again\"}";
                            messages.add(ChatMessage.tool(droppedInv.id(), truncateToolResult(placeholder, t)));
                        }
                        // Surface the LLM's text to the UI as the "last response".
                        // The no-tool-call path above already calls
                        // setLastConversationResponse; this is the tool-call
                        // path counterpart. Without it, the top-right
                        // "Thinking..." indicator stays stale ("Thinking..."
                        // never clears) and the user can't tell from that
                        // indicator alone whether the AI has finished or is
                        // still working. The text is also clamped to
                        // 24 KB by setLastConversationResponse, so very long
                        // responses truncate gracefully (the full text is
                        // still in history, just not in this transient field).
                        t.setLastConversationResponse(trimmedResp.text());
                        // Re-check the stop flag in case Stop was clicked while we
                        // were waiting for the LLM. The flag is also set by the
                        // 'finish' tool above so a finish-only turn exits cleanly.
                        if (t.isAgentStopRequested()) {
                            agentDebug("agent stop requested after tool dispatch; exiting before recursion");
                            t.setAgentRunning(false);
                            return;
                        }
                        agentDebug("runLoop -> recurse (roundsLeft {} -> {})", roundsLeft, roundsLeft - 1);
                        runLoop(t, apiKey, endpoint, modelName, messages, tools, timeout, roundsLeft - 1, initiator,
                                toolCache, failures[0]);
                        } catch (Throwable loopErr) {
                            // Any throwable that escapes the per-tool
                            // catches would otherwise silently kill the
                            // agent loop here (no parent catch around
                            // srv.execute). Surface it as a visible error
                            // and tear down the loop cleanly so the user
                            // can keep chatting.
                            agentWarn("agent loop CRASHED in tool dispatch: {}", loopErr.toString(), loopErr);
                            t.setLastConversationResponse("(error) agent loop crashed: "
                                    + loopErr.getClass().getSimpleName() + ": " + loopErr.getMessage());
                            t.setAgentRunning(false);
                        } finally {
                            // Always close out the stream so the client
                            // renderer's "live" line goes away, even on
                            // error or stop.
                            try { sink.finish(); } catch (Exception ignored) {}
                        }
                    });
                });
    }

    private static com.google.gson.Gson gson() {
        return new com.google.gson.Gson();
    }

    private static java.util.List<java.util.Map<String, Object>> serializeToolCalls(LlmResponse resp) {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (ToolInvocation inv : resp.toolCalls()) {
            java.util.Map<String, Object> fn = new java.util.LinkedHashMap<>();
            fn.put("name", inv.name() == null ? "" : inv.name());
            fn.put("arguments", gson().toJson(inv.arguments()));
            java.util.Map<String, Object> tc = new java.util.LinkedHashMap<>();
            tc.put("id", inv.id() == null ? "" : inv.id());
            tc.put("type", "function");
            tc.put("function", fn);
            out.add(tc);
        }
        return out;
    }

    /** Convert a transient ChatMessage into the NBT-persistent StoredTurn. */
    private static IntelligentTransmitterBlockEntity.StoredTurn toStoredTurn(ChatMessage m) {
        return switch (m.role()) {
            case "user" -> IntelligentTransmitterBlockEntity.StoredTurn.user(m.content());
            case "tool" -> IntelligentTransmitterBlockEntity.StoredTurn.toolResult(m.toolCallId(), m.content());
            default -> // assistant
                m.toolCallsJson() == null || m.toolCallsJson().isEmpty()
                        ? IntelligentTransmitterBlockEntity.StoredTurn.assistantText(m.content())
                        : IntelligentTransmitterBlockEntity.StoredTurn.assistantToolCalls(m.content(), m.toolCallsJson());
        };
    }

    /**
     * Tool dispatcher. MUST NEVER throw — any exception (including {@link Error}
     * such as {@link NoClassDefFoundError} from AE2 reflection, or
     * {@link IllegalStateException} from a stale grid node) is caught and
     * converted into a JSON error string. Otherwise the unhandled throwable
     * would escape the {@code srv.execute} Runnable in {@link #runLoop}, which
     * has no catch block — leading to a silent agent-loop death that looks
     * like a hang to the user.
     */
    private static String executeTool(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        long startNs = System.nanoTime();
        agentDebug("tool BEGIN name='{}' id='{}' args={}",
                inv.name(), inv.id(), inv.arguments());
        try {
            String result = executeToolInner(t, inv);
            long durMs = (System.nanoTime() - startNs) / 1_000_000L;
            agentDebug("tool END   name='{}' id='{}' durMs={} resultPreview={}",
                    inv.name(), inv.id(), durMs, previewForLog(result, 400));
            return result;
        } catch (Throwable th) {
            long durMs = (System.nanoTime() - startNs) / 1_000_000L;
            agentWarn("tool FAIL  name='{}' id='{}' durMs={} ex={}",
                    inv.name(), inv.id(), durMs,
                    th.getClass().getName() + ": " + th.getMessage(), th);
            // We still return a JSON error so the LLM sees a structured reply
            // and can self-correct on its next turn. Without this, an
            // uncaught throwable escapes the srv.execute Runnable, kills the
            // agent loop, and looks like a hang to the user.
            return errorJson(inv.name() + ": unhandled " + th.getClass().getSimpleName()
                    + ": " + th.getMessage());
        }
    }

    private static String executeToolInner(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        // Anthropic models occasionally wrap their tool calls in a synthetic
        // "multi_tool_use.parallel" router. The arguments carry a
        // "tool_uses" array of {recipient_name, parameters} entries. We
        // unwrap and dispatch each entry to its real handler, then return
        // a combined JSON result so the LLM can read both outcomes.
        if ("multi_tool_use.parallel".equals(inv.name())) {
            return runMultiToolUseParallel(t, inv);
        }
        if (!toolAllowedForExecutorMode(t, inv.name())) {
            return errorJson("tool '" + inv.name() + "' is not allowed in "
                    + ((LlmExecutorBlockEntity) t).getMode().getSerializedName() + " mode");
        }
        if (!consumeToolEnergy(t, inv.name())) {
            return errorJson("code network controller is out of power");
        }
        return switch (inv.name()) {
            case "noop" -> runNoop(inv);
            case "place" -> runPlace(t, inv);
            case "read_block" -> runReadBlock(t, inv);
            case "break_block" -> runBreakBlock(t, inv);
            case "place_many" -> runPlaceMany(t, inv);
            case "break_many" -> runBreakMany(t, inv);
            case "question" -> runQuestion(t, inv);
            case "jei_lookup" -> runJeiLookup(inv);
            case "jei_name_lookup" -> runJeiNameLookup(inv);
            case "emi_lookup" -> runEmiLookup(inv);
            case "emi_name_lookup" -> runEmiNameLookup(inv);
            case "ae2_pattern_lookup" -> runAe2PatternLookup(t, inv);
            case "ae2_pattern_write" -> runAe2PatternWrite(t, inv);
            case "ae2_pattern_write_many" -> runAe2PatternWriteMany(t, inv);
            case "mcmod_search" -> runMcmodSearch(inv);
            case "mcmod_fetch" -> runMcmodFetch(inv);
            case "web_search" -> runWebSearch(inv);
            case "web_fetch" -> runWebFetch(inv);
            case "finish" -> runFinish(t, inv);
            default -> errorJson("unknown tool: " + inv.name()
                    + ". Available tools: noop, read_block, place, place_many, break_block, break_many, question, jei_lookup, jei_name_lookup, emi_lookup, emi_name_lookup, ae2_pattern_lookup, ae2_pattern_write, ae2_pattern_write_many, mcmod_search, mcmod_fetch"
                    + advertisedGeneralWebSuffix()
                    + ", finish. "
                    + "Do not wrap calls in multi_tool_use.parallel — emit tool_calls in parallel as separate entries.");
        };
    }

    /** ", web_search, web_fetch" when the current build's advertised tool set
     *  includes the general-web tools, otherwise "". Keeps the unknown-tool
     *  error message in sync with the tool set we actually serve. */
    private static String advertisedGeneralWebSuffix() {
        for (ToolDefinition td : advertisedTools()) {
            if ("web_search".equals(td.name())) return ", web_search, web_fetch";
        }
        return "";
    }

    private static boolean toolAllowedForExecutorMode(IntelligentTransmitterBlockEntity t, String toolName) {
        if (!(t instanceof LlmExecutorBlockEntity executor)) {
            return true;
        }
        return switch (executor.getMode()) {
            case BUILD -> true;
            case PLAN -> "noop".equals(toolName)
                    || "read_block".equals(toolName)
                    || "question".equals(toolName)
                    || "jei_lookup".equals(toolName)
                    || "jei_name_lookup".equals(toolName)
                    || "emi_lookup".equals(toolName)
                    || "emi_name_lookup".equals(toolName)
                    || "ae2_pattern_lookup".equals(toolName)
                    || "mcmod_search".equals(toolName)
                    || "mcmod_fetch".equals(toolName)
                    || "web_search".equals(toolName)
                    || "web_fetch".equals(toolName)
                    || "finish".equals(toolName);
            case CHAT -> false;
        };
    }

    private static boolean consumeToolEnergy(IntelligentTransmitterBlockEntity t, String toolName) {
        if (!(t instanceof LlmExecutorBlockEntity) || "finish".equals(toolName) || "question".equals(toolName)) {
            return true;
        }
        if (!(t.getLevel() instanceof ServerLevel serverLevel)) {
            return false;
        }
        CodeNetworkScanner.NetworkView view = CodeNetworkScanner.scan(serverLevel, t.getBlockPos());
        return view.controller() != null && view.controller().consumeEnergy(TOOL_ENERGY_COST);
    }

    /**
     * Handle Anthropic's synthetic multi-tool-use router. The shape is:
     * <pre>
     *   arguments = {
     *     "tool_uses": [
     *       { "recipient_name": "functions.place_many", "parameters": "{...}" },
     *       { "recipient_name": "functions.read_block", "parameters": "{...}" }
     *     ]
     *   }
     * </pre>
     * We strip the "functions." prefix and dispatch each entry.
     */
    private static String runMultiToolUseParallel(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> children = coerceBlocksArg(inv.arguments().get("tool_uses"));
        agentDebug("multi_tool_use.parallel: unwrapping {} child tool call(s)", children.size());
        if (children.isEmpty()) {
            return errorJson("multi_tool_use.parallel: empty tool_uses array");
        }
        com.google.gson.JsonArray out = new com.google.gson.JsonArray();
        for (int i = 0; i < children.size(); i++) {
            java.util.Map<String, Object> child = children.get(i);
            String recipient = String.valueOf(child.get("recipient_name"));
            // Anthropic uses "functions.<name>"; OpenAI passes plain "name".
            String realName = recipient.startsWith("functions.")
                    ? recipient.substring("functions.".length())
                    : recipient;
            // Parameters may be a JSON string or a map. Normalize to a map of
            // string -> object so the inner handlers can read it.
            java.util.Map<String, Object> innerArgs = new java.util.HashMap<>();
            Object paramsRaw = child.get("parameters");
            if (paramsRaw instanceof String s && !s.isBlank()) {
                try {
                    com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(s);
                    if (el.isJsonObject()) {
                        innerArgs = new com.google.gson.Gson().fromJson(el, java.util.Map.class);
                    }
                } catch (Exception ignored) { }
            } else if (paramsRaw instanceof java.util.Map) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> casted = (java.util.Map<String, Object>) paramsRaw;
                innerArgs = casted;
            }
            ToolInvocation sub = new ToolInvocation("parallel-" + i, realName, innerArgs);
            com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
            entry.addProperty("recipient_name", recipient);
            entry.addProperty("ok", true);
            entry.addProperty("output", executeTool(t, sub));
            out.add(entry);
        }
        com.google.gson.JsonObject wrap = new com.google.gson.JsonObject();
        wrap.addProperty("executed", children.size());
        wrap.add("results", out);
        return GSON.toJson(wrap);
    }

    /**
     * Coerce the {@code blocks} tool argument into a list of maps. OpenAI
     * delivers tool arguments as a JSON-encoded string, so the value at
     * inv.arguments().get("blocks") is a String. Some client libraries
     * (or our future Kotlin port) may pre-parse it to a List. Handle both.
     */
    private static java.util.List<java.util.Map<String, Object>> coerceBlocksArg(Object raw) {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        if (raw == null) return out;
        if (raw instanceof java.util.List<?> list) {
            for (Object e : list) {
                if (e instanceof java.util.Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> casted = (java.util.Map<String, Object>) m;
                    out.add(casted);
                }
            }
            return out;
        }
        String s = raw.toString();
        try {
            com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(s);
            if (el.isJsonArray()) {
                for (com.google.gson.JsonElement child : el.getAsJsonArray()) {
                    if (child.isJsonObject()) {
                        out.add(new com.google.gson.Gson().fromJson(child, java.util.Map.class));
                    }
                }
            }
        } catch (Exception e) {
            // fall through, returns empty list
        }
        return out;
    }

    private static String runPlaceMany(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        if (!(t.getLevel() instanceof ServerLevel level)) {
            return errorJson("place_many: transmitter level is not a ServerLevel");
        }
        java.util.List<java.util.Map<String, Object>> blocks = coerceBlocksArg(inv.arguments().get("blocks"));
        if (blocks.isEmpty()) {
            return errorJson("place_many: 'blocks' array is empty or unparseable; pass at least 1 entry");
        }
        if (blocks.size() > MAX_BATCH_SIZE) {
            // Truncate rather than fail: the LLM can re-call with the remainder.
            blocks = blocks.subList(0, MAX_BATCH_SIZE);
        }
        com.google.gson.JsonArray results = new com.google.gson.JsonArray();
        int ok = 0, fail = 0;
        for (java.util.Map<String, Object> entry : blocks) {
            Integer x = asInt(entry.get("x"));
            Integer y = asInt(entry.get("y"));
            Integer z = asInt(entry.get("z"));
            Object blockObj = entry.get("block");
            String block = blockObj == null ? null : blockObj.toString();
            com.google.gson.JsonObject r = new com.google.gson.JsonObject();
            r.addProperty("x", x);
            r.addProperty("y", y);
            r.addProperty("z", z);
            if (x == null || y == null || z == null || block == null || block.isBlank()) {
                r.addProperty("ok", false);
                r.addProperty("code", "BAD_REQUEST");
                r.addProperty("message", "x, y, z, block are required");
                fail++;
            } else {
                level.getChunk(x >> 4, z >> 4);
                boolean replace = !entry.containsKey("replace") || Boolean.TRUE.equals(entry.get("replace"));
                ApiResult<Void> ar = BlockApi.place(level, new BlockPos(x, y, z), block, java.util.Map.of(), "", replace);
                r.addProperty("ok", ar.isOk());
                r.addProperty("code", String.valueOf(ar.code()));
                if (ar.message() != null) r.addProperty("message", ar.message());
                if (ar.isOk()) ok++; else fail++;
            }
            results.add(r);
        }
        com.google.gson.JsonObject out = new com.google.gson.JsonObject();
        out.addProperty("count", blocks.size());
        out.addProperty("ok", ok);
        out.addProperty("failed", fail);
        out.add("results", results);
        return GSON.toJson(out);
    }

    private static String runBreakMany(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        if (!(t.getLevel() instanceof ServerLevel level)) {
            return errorJson("break_many: transmitter level is not a ServerLevel");
        }
        java.util.List<java.util.Map<String, Object>> blocks = coerceBlocksArg(inv.arguments().get("blocks"));
        if (blocks.isEmpty()) {
            return errorJson("break_many: 'blocks' array is empty or unparseable");
        }
        if (blocks.size() > MAX_BATCH_SIZE) {
            blocks = blocks.subList(0, MAX_BATCH_SIZE);
        }
        com.google.gson.JsonArray results = new com.google.gson.JsonArray();
        int ok = 0, fail = 0;
        for (java.util.Map<String, Object> entry : blocks) {
            Integer x = asInt(entry.get("x"));
            Integer y = asInt(entry.get("y"));
            Integer z = asInt(entry.get("z"));
            com.google.gson.JsonObject r = new com.google.gson.JsonObject();
            r.addProperty("x", x);
            r.addProperty("y", y);
            r.addProperty("z", z);
            if (x == null || y == null || z == null) {
                r.addProperty("ok", false);
                r.addProperty("code", "BAD_REQUEST");
                r.addProperty("message", "x, y, z are required");
                fail++;
            } else {
                level.getChunk(x >> 4, z >> 4);
                ApiResult<Void> ar = BlockApi.breakBlock(level, new BlockPos(x, y, z));
                r.addProperty("ok", ar.isOk());
                r.addProperty("code", String.valueOf(ar.code()));
                if (ar.message() != null) r.addProperty("message", ar.message());
                if (ar.isOk()) ok++; else fail++;
            }
            results.add(r);
        }
        com.google.gson.JsonObject out = new com.google.gson.JsonObject();
        out.addProperty("count", blocks.size());
        out.addProperty("ok", ok);
        out.addProperty("failed", fail);
        out.add("results", results);
        return GSON.toJson(out);
    }

    private static String runBreakBlock(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        Integer x = asInt(inv.arguments().get("x"));
        Integer y = asInt(inv.arguments().get("y"));
        Integer z = asInt(inv.arguments().get("z"));
        if (x == null || y == null || z == null) {
            return errorJson("break_block: x, y, z are required integers");
        }
        if (!(t.getLevel() instanceof ServerLevel level)) {
            return errorJson("break_block: transmitter level is not a ServerLevel");
        }
        level.getChunk(x >> 4, z >> 4);
        BlockPos pos = new BlockPos(x, y, z);
        ApiResult<Void> r = BlockApi.breakBlock(level, pos);
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("ok", r.isOk());
        o.addProperty("code", String.valueOf(r.code()));
        if (r.message() != null) o.addProperty("message", r.message());
        if (r.isOk()) {
            o.addProperty("now", "minecraft:air");
        }
        return GSON.toJson(o);
    }

    /**
     * The agent loop terminator. The LLM calls this when it has decided the
     * task is done. We surface the reason text into the conversation and
     * mark the loop as done so runLoop() does not recurse.
     */
    private static String runFinish(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        String reason = String.valueOf(inv.arguments().getOrDefault("reason", "(no reason)"));
        t.requestAgentStop(); // reuses the stop flag — both paths exit the loop
        t.setAgentRunning(false);
        // Safety net: surface the reason as the "last response" so the
        // top-right "Thinking..." indicator clears even if the runLoop
        // post-dispatch setLastConversationResponse call (which normally
        // fires with the LLM's text) is somehow skipped (e.g. tool
        // exception earlier in the batch, or a future code path that
        // short-circuits). The runLoop also runs the [finish] reason
        // synthesis on the LLM's text, so this is belt-and-suspenders.
        if (reason != null && !reason.isBlank() && !"(no reason)".equals(reason)) {
            t.setLastConversationResponse("[finish] " + reason);
        }
        JsonObject o = new JsonObject();
        o.addProperty("finished", true);
        o.addProperty("reason", reason);
        return GSON.toJson(o);
    }

    private static String runQuestion(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        Object promptRaw = inv.arguments().get("prompt");
        String prompt = promptRaw == null ? "(empty question)" : promptRaw.toString().trim();
        if (prompt.isEmpty()) {
            prompt = "(empty question)";
        }
        // Build the user-side text that the model will see as the next
        // user message. We keep the in-history copy short — just
        // "[模型提问] <prompt>" — so the chat panel doesn't repeat the
        // option list that the floating picker is already showing.
        // The full option list lives in BE.getPendingQuestion(); the
        // history entry is just there so the LLM's next turn sees a
        // marker for the question and so the user can scroll back to
        // see what was asked.
        String userText = "[模型提问] " + prompt;
        java.util.List<String> optionsList = new java.util.ArrayList<>();
        Object optsRaw = inv.arguments().get("options");
        if (optsRaw instanceof java.util.List<?> opts && !opts.isEmpty()) {
            for (Object o : opts) {
                if (o == null) continue;
                optionsList.add(o.toString());
            }
        }
        // The implicit "other" option is always present so the user
        // is never trapped into a multiple-choice pick — they can
        // type anything. The LLM-side model has been told (in the
        // tool description) that the user is free to type, but
        // listing "other" as a visible bullet makes that obvious
        // on screen as well.
        optionsList.add("other");
        // Persist the question as a user turn in history so the LLM
        // sees a "[模型提问] ..." marker on the next turn and the user
        // can scroll back to see what was asked. We strip the option
        // list — the floating picker above the input shows them, the
        // history doesn't need to.
        IntelligentTransmitterBlockEntity.StoredTurn q = IntelligentTransmitterBlockEntity.StoredTurn.user(userText);
        t.appendHistory(q);
        // Mark awaiting so the runLoop post-tool dispatch path exits
        // instead of recursing into another LLM call. The loop is
        // also shut off via setAgentRunning(false) so the Stop button
        // goes back to "Send". The next runLoop only fires when the
        // user sends a new message — which is exactly what 'question'
        // is supposed to wait for.
        t.setAwaitingUserAnswer(true);
        t.requestAgentStop();
        t.setAgentRunning(false);
        // Store the structured question so the client can render a
        // floating picker above the chat input. The options are kept
        // verbatim (with the implicit 'other' appended) so the picker
        // shows the same list the user just saw in chat history.
        t.setPendingQuestion(new IntelligentTransmitterBlockEntity.PendingQuestion(
                prompt, java.util.Collections.unmodifiableList(optionsList), 0));
        // Surface a friendly placeholder in the BE response field so
        // the screen's lastConversationResponse reads as the question
        // (rather than stale "Thinking..." text). The actual question
        // is in the history; the BE response is just for transient
        // status.
        t.setLastConversationResponse("[等待回答] " + prompt);
        JsonObject o = new JsonObject();
        o.addProperty("asked", true);
        o.addProperty("prompt", prompt);
        return GSON.toJson(o);
    }

    /** No-op: kept for source compat with earlier revisions. The real stop
     *  state lives on the block entity now. */
    public static void requestStop() { }

    private static String runMcmodSearch(ToolInvocation inv) {
        String query = asString(inv.arguments().get("query"));
        if (query == null || query.isBlank()) {
            return errorJson("mcmod_search: 'query' is required (e.g. '工业时代2')");
        }
        // mcmod.cn is a Chinese Minecraft mod wiki. Pure-English queries
        // with no obvious MC keyword almost always return zero results,
        // and the agent will then sit in a loop guessing. Detect that
        // case up front and point the LLM at the right tool instead of
        // burning a request and a wall-clock budget on a doomed query.
        if (isClearlyNonMCQuery(query)) {
            return errorJson("mcmod_search: query '" + query
                    + "' looks like a non-MC search (no CJK and no Minecraft / mod "
                    + "keyword detected). mcmod.cn is the Chinese MC mod wiki and "
                    + "will likely return no results here. For non-MC web queries "
                    + "ask the user to opt in to web_search (e.g. '用 bing 搜一下').");
        }
        Integer maxRaw = asInt(inv.arguments().get("max_results"));
        int max = maxRaw == null ? 10 : Math.max(1, Math.min(20, maxRaw));
        try {
            WebSearchTool.SearchResult r = WebSearchTool.search(query, "mcmod", max);
            return GSON.toJson(r.toJson());
        } catch (Throwable t) {
            LLMEra.LOGGER.warn("[{}] mcmod_search threw: {}", LLMEra.MODID, t.toString());
            return errorJson("mcmod_search failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
    }

    /** General web search. Only reachable when the user's current
     *  message explicitly asked for a non-mcmod web lookup (see
     *  {@link #userWantsGeneralWebSearch}). The engine arg is restricted
     *  to the general-web backends — mcmod is intentionally NOT
     *  accepted here; that lives on the separate mcmod_search tool. */
    private static String runWebSearch(ToolInvocation inv) {
        String query = asString(inv.arguments().get("query"));
        if (query == null || query.isBlank()) {
            return errorJson("web_search: 'query' is required (e.g. 'rust async tutorial')");
        }
        String engine = asString(inv.arguments().get("engine"));
        if (engine != null && !engine.isBlank()) {
            String e = engine.toLowerCase(java.util.Locale.ROOT);
            if ("mcmod".equals(e)) {
                return errorJson("web_search: 'engine=mcmod' is not allowed here — use mcmod_search instead");
            }
            if (!"bing".equals(e) && !"duckduckgo".equals(e) && !"auto".equals(e)) {
                return errorJson("web_search: 'engine' must be one of bing | duckduckgo | auto, got: " + engine);
            }
        }
        Integer maxRaw = asInt(inv.arguments().get("max_results"));
        int max = maxRaw == null ? 10 : Math.max(1, Math.min(20, maxRaw));
        try {
            WebSearchTool.SearchResult r = WebSearchTool.search(query, engine, max);
            return GSON.toJson(r.toJson());
        } catch (Throwable t) {
            LLMEra.LOGGER.warn("[{}] web_search threw: {}", LLMEra.MODID, t.toString());
            return errorJson("web_search failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
    }

    private static String runMcmodFetch(ToolInvocation inv) {
        String mode = asString(inv.arguments().get("mode"));
        if (mode == null || mode.isBlank()) mode = "mcmod_item";
        String m = mode.toLowerCase(java.util.Locale.ROOT);
        if (!"mcmod_item".equals(m) && !"mcmod_list".equals(m)) {
            return errorJson("mcmod_fetch: 'mode' must be mcmod_item | mcmod_list, got: " + mode
                    + " (for arbitrary URLs use web_fetch instead)");
        }
        String itemId = asString(inv.arguments().get("item_id"));
        String classId = asString(inv.arguments().get("class_id"));
        Integer page = asInt(inv.arguments().get("page"));
        try {
            WebFetchTool.FetchResult r = WebFetchTool.fetch(m, itemId, classId, page, null);
            return GSON.toJson(r.toJson());
        } catch (Throwable t) {
            LLMEra.LOGGER.warn("[{}] mcmod_fetch threw: {}", LLMEra.MODID, t.toString());
            return errorJson("mcmod_fetch failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
    }

    private static String runWebFetch(ToolInvocation inv) {
        String url = asString(inv.arguments().get("url"));
        try {
            WebFetchTool.FetchResult r = WebFetchTool.fetch("page", null, null, null, url);
            return GSON.toJson(r.toJson());
        } catch (Throwable t) {
            LLMEra.LOGGER.warn("[{}] web_fetch threw: {}", LLMEra.MODID, t.toString());
            return errorJson("web_fetch failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
    }

    private static String runNoop(ToolInvocation inv) {
        String text = String.valueOf(inv.arguments().getOrDefault("text", ""));
        JsonObject o = new JsonObject();
        o.addProperty("echoed", text);
        return GSON.toJson(o);
    }

    private static String runPlace(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        Integer x = asInt(inv.arguments().get("x"));
        Integer y = asInt(inv.arguments().get("y"));
        Integer z = asInt(inv.arguments().get("z"));
        if (x == null || y == null || z == null) {
            return errorJson("place: x, y, z are required integers");
        }
        String block = asString(inv.arguments().get("block"));
        if (block == null || block.isBlank()) {
            return errorJson("place: block id is required (e.g. 'minecraft:stone')");
        }
        boolean replace = !inv.arguments().containsKey("replace") || Boolean.TRUE.equals(inv.arguments().get("replace"));

        if (!(t.getLevel() instanceof ServerLevel level)) {
            return errorJson("place: transmitter level is not a ServerLevel");
        }
        // Force-load the chunk so BlockApi doesn't return CONFLICT for unloaded areas.
        level.getChunk(x >> 4, z >> 4);

        BlockPos pos = new BlockPos(x, y, z);
        java.util.Map<String, String> stateProps = java.util.Collections.emptyMap();
        ApiResult<Void> r = BlockApi.place(level, pos, block, stateProps, "", replace);
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("block", block);
        o.addProperty("ok", r.isOk());
        o.addProperty("code", String.valueOf(r.code()));
        if (r.message() != null) o.addProperty("message", r.message());
        if (r.isOk()) {
            BlockState st = level.getBlockState(pos);
            o.addProperty("placed", st.getBlock().builtInRegistryHolder().key().location().toString());
        }
        return GSON.toJson(o);
    }

    private static String runReadBlock(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        Integer x = asInt(inv.arguments().get("x"));
        Integer y = asInt(inv.arguments().get("y"));
        Integer z = asInt(inv.arguments().get("z"));
        if (x == null || y == null || z == null) {
            return errorJson("read_block: x, y, z are required integers");
        }
        if (!(t.getLevel() instanceof ServerLevel level)) {
            return errorJson("read_block: transmitter level is not a ServerLevel");
        }
        level.getChunk(x >> 4, z >> 4);
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("block", state.getBlock().builtInRegistryHolder().key().location().toString());
        ApiResult<String> nbt = BlockApi.readNbt(level, pos);
        if (nbt.isOk() && nbt.data() != null && !nbt.data().isEmpty()) {
            o.addProperty("nbt", nbt.data());
        } else {
            o.addProperty("nbt", "");
        }
        return GSON.toJson(o);
    }

    private static String runAe2PatternLookup(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        if (!(t.getLevel() instanceof ServerLevel level)) {
            return errorJson("ae2_pattern_lookup: transmitter level is not a ServerLevel");
        }

        boolean includeDetails = !inv.arguments().containsKey("include_details")
                || asBool(inv.arguments().get("include_details"));
        boolean includeHidden = asBool(inv.arguments().get("include_hidden"));
        java.util.LinkedHashSet<BlockPos> anchors = new java.util.LinkedHashSet<>();

        Integer x = asInt(inv.arguments().get("x"));
        Integer y = asInt(inv.arguments().get("y"));
        Integer z = asInt(inv.arguments().get("z"));
        if (x != null && y != null && z != null) {
            anchors.add(new BlockPos(x, y, z));
        }

        CodeNetworkScanner.NetworkView codeView = CodeNetworkScanner.scan(level, t.getBlockPos());
        for (Ae2NetworkBridgeBlockEntity bridge : codeView.ae2Bridges()) {
            anchors.add(bridge.getBlockPos());
        }

        if (anchors.isEmpty()) {
            return errorJson("ae2_pattern_lookup: no AE2 network bridge found on the code network; "
                    + "place an ae2_network_bridge next to an AE2 cable/device and connect it with code cable");
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("tool", "ae2_pattern_lookup");
        out.addProperty("source", "ae2_pattern_access_terminal_reflection");
        out.addProperty("include_hidden", includeHidden);
        out.addProperty("include_details", includeDetails);
        out.addProperty("anchor_count", anchors.size());

        try {
            Class.forName("appeng.api.networking.IGrid");
            Class.forName("appeng.helpers.patternprovider.PatternContainer");
            Class.forName("appeng.api.crafting.PatternDetailsHelper");

            JsonArray networks = new JsonArray();
            JsonArray unresolved = new JsonArray();
            int[] remainingPatterns = new int[]{128};

            for (BlockPos anchor : anchors) {
                Object grid = resolveAe2GridNear(level, anchor);
                if (grid != null) {
                    networks.add(describeAe2GridPatterns(level, anchor, grid, includeDetails, includeHidden,
                            remainingPatterns));
                    continue;
                }

                JsonObject single = describeSingleAe2PatternContainer(level, anchor, includeDetails, includeHidden,
                        remainingPatterns);
                if (single != null) {
                    networks.add(single);
                } else {
                    JsonObject miss = new JsonObject();
                    miss.addProperty("x", anchor.getX());
                    miss.addProperty("y", anchor.getY());
                    miss.addProperty("z", anchor.getZ());
                    miss.addProperty("error", "no AE2 grid or PatternContainer found at/adjacent to anchor");
                    unresolved.add(miss);
                }
            }

            out.add("networks", networks);
            out.add("unresolved_anchors", unresolved);
            out.addProperty("network_count", networks.size());
            out.addProperty("truncated", remainingPatterns[0] <= 0);
            return GSON.toJson(out);
        } catch (ClassNotFoundException e) {
            return errorJson("ae2_pattern_lookup: AE2 is not installed or is not loaded (missing "
                    + e.getMessage() + ")");
        } catch (Exception e) {
            return errorJson("ae2_pattern_lookup: AE2 reflection failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private record EncodedAe2Pattern(ItemStack stack, String patternType, String recipeId, ItemStack primaryOutput,
                                     JsonObject recipeInfo) {
    }

    private record Ae2PatternContainerRef(BlockPos anchor, int index, Object container, Object inventory) {
    }

    private record Ae2PatternWriteTarget(Ae2PatternContainerRef ref, int slot, boolean replacing,
                                         String selectionReason) {
    }

    private static String runAe2PatternWrite(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        long startNs = System.nanoTime();
        if (!(t.getLevel() instanceof ServerLevel level)) {
            agentDebug("ae2_pattern_write: abort — transmitter level is not a ServerLevel");
            return errorJson("ae2_pattern_write: transmitter level is not a ServerLevel");
        }
        agentDebug("ae2_pattern_write: step 1/8 resolve anchors (transmitter pos={})", t.getBlockPos());

        java.util.LinkedHashSet<BlockPos> anchors = new java.util.LinkedHashSet<>();
        Integer x = asInt(inv.arguments().get("x"));
        Integer y = asInt(inv.arguments().get("y"));
        Integer z = asInt(inv.arguments().get("z"));
        if (x != null && y != null && z != null) {
            anchors.add(new BlockPos(x, y, z));
        }

        CodeNetworkScanner.NetworkView codeView = CodeNetworkScanner.scan(level, t.getBlockPos());
        for (Ae2NetworkBridgeBlockEntity bridge : codeView.ae2Bridges()) {
            anchors.add(bridge.getBlockPos());
        }
        if (anchors.isEmpty()) {
            agentDebug("ae2_pattern_write: abort — no AE2 bridge on code network");
            return errorJson("ae2_pattern_write: no AE2 network bridge found on the code network; "
                    + "place an ae2_network_bridge next to an AE2 cable/device and connect it with code cable");
        }
        agentDebug("ae2_pattern_write: step 2/8 found {} bridge anchor(s)", anchors.size());

        try {
            agentDebug("ae2_pattern_write: step 3/8 checking AE2 classes on classpath");
            Class.forName("appeng.api.networking.IGrid");
            Class.forName("appeng.helpers.patternprovider.PatternContainer");
            Class.forName("appeng.api.crafting.PatternDetailsHelper");

            agentDebug("ae2_pattern_write: step 4/8 scanning pattern containers (anchors={}, includeEmpty=false)",
                    anchors.size());
            java.util.List<Ae2PatternContainerRef> containers = collectAe2PatternContainers(level, anchors, false);
            if (containers.isEmpty()) {
                agentDebug("ae2_pattern_write: abort — no visible PatternContainer found from anchors");
                return errorJson("ae2_pattern_write: no visible AE2 PatternContainer found from bridge anchors");
            }
            agentDebug("ae2_pattern_write: found {} candidate container(s)", containers.size());

            String targetMachineItemId = normalizeId(asString(inv.arguments().get("target_machine_item_id")));
            String targetGroupName = asString(inv.arguments().get("target_group_name"));
            boolean replaceExisting = !inv.arguments().containsKey("replace_existing")
                    || asBool(inv.arguments().get("replace_existing"));
            agentDebug("ae2_pattern_write: selection hints machineId='{}' group='{}' replaceExisting={}",
                    targetMachineItemId, targetGroupName, replaceExisting);

            agentDebug("ae2_pattern_write: step 5/8 prepare (encode + choose target) — shared with ae2_pattern_write_many");
            PreparedAe2Pattern prepared = prepareAe2PatternWrite(level, inv, containers,
                    targetMachineItemId, targetGroupName, replaceExisting);
            EncodedAe2Pattern encoded = prepared.encoded();
            agentDebug("ae2_pattern_write: encoded pattern OK recipe='{}' primary={} stackSize={}",
                    encoded.recipeId(), itemId(encoded.primaryOutput()), encoded.stack().getCount());

            if (prepared.target() == null) {
                agentDebug("ae2_pattern_write: abort — prepareAe2PatternWrite returned null target "
                        + "(no empty slot and no existing match)");
                JsonObject out = new JsonObject();
                out.addProperty("ok", false);
                out.addProperty("error", "ae2_pattern_write: no compatible target slot found");
                out.addProperty("pattern_type", encoded.patternType());
                out.addProperty("primary_output", itemId(encoded.primaryOutput()));
                out.add("containers", summarizeAe2PatternWriteContainers(level, containers, targetMachineItemId,
                        targetGroupName, encoded.stack()));
                return GSON.toJson(out);
            }
            Ae2PatternWriteTarget target = prepared.target();
            agentDebug("ae2_pattern_write: target chosen containerIdx={} slot={} replacing={} reason='{}'",
                    target.ref().index(), target.slot(), target.replacing(), target.selectionReason());

            agentDebug("ae2_pattern_write: step 6/8 invoking setItemDirect via reflection "
                    + "on inventory class '{}'", target.ref().inventory().getClass().getName());
            writeAe2PatternSlot(level, target, encoded.stack());
            agentDebug("ae2_pattern_write: write complete, building success response");

            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("tool", "ae2_pattern_write");
            out.addProperty("pattern_type", encoded.patternType());
            out.addProperty("recipe_id", encoded.recipeId() == null ? "" : encoded.recipeId());
            out.addProperty("primary_output", itemId(encoded.primaryOutput()));
            out.addProperty("target_container_index", target.ref().index());
            out.addProperty("target_slot", target.slot());
            out.addProperty("replaced_existing", target.replacing());
            out.addProperty("selection_reason", target.selectionReason());
            out.add("target_group", describeAe2PatternGroup(
                    Class.forName("appeng.helpers.patternprovider.PatternContainer")
                            .getMethod("getTerminalGroup")
                            .invoke(target.ref().container())));
            out.add("encoded_pattern", describeItemStack(encoded.stack()));
            out.add("recipe", encoded.recipeInfo());
            long durMs = (System.nanoTime() - startNs) / 1_000_000L;
            agentDebug("ae2_pattern_write: SUCCESS totalDurMs={}", durMs);
            return GSON.toJson(out);
        } catch (ClassNotFoundException e) {
            agentWarn("ae2_pattern_write: ClassNotFoundException — {}", e.getMessage());
            return errorJson("ae2_pattern_write: AE2 is not installed or is not loaded (missing "
                    + e.getMessage() + ")");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            agentWarn("ae2_pattern_write: AE2 reflection InvocationTargetException cause={}: {}",
                    cause == null ? "<none>" : cause.getClass().getName(),
                    cause == null ? e.getMessage() : cause.getMessage(),
                    cause == null ? e : cause);
            return errorJson("ae2_pattern_write: AE2 reflection failed: "
                    + (cause == null ? e.getClass().getSimpleName() : cause.getClass().getSimpleName())
                    + ": " + (cause == null ? e.getMessage() : cause.getMessage()));
        } catch (Exception e) {
            agentWarn("ae2_pattern_write: caught {}: {}", e.getClass().getName(), e.getMessage(), e);
            return errorJson("ae2_pattern_write: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Batch counterpart of {@link #runAe2PatternWrite}. Encodes and installs
     * 1..{@value #MAX_AE2_PATTERNS_PER_BATCH} patterns in one call, sharing
     * the network anchor resolution + container scan, but iterating the
     * encode + choose-target + write loop per spec. Per-spec overrides
     * ({@code pattern_type}, {@code replace_existing}, {@code container_index},
     * {@code slot}, etc.) take precedence over the top-level defaults.
     *
     * <p>Transaction modes: {@code atomic: false} (default) keeps every
     * successful write even if later specs fail; {@code atomic: true} rolls
     * back every successful write on the first failure. {@code dry_run: true}
     * runs the full encode + target-choose path but skips setItemDirect.
     */
    private static String runAe2PatternWriteMany(IntelligentTransmitterBlockEntity t, ToolInvocation inv) {
        long startNs = System.nanoTime();
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> patterns =
                (java.util.List<java.util.Map<String, Object>>) inv.arguments().get("patterns");
        if (patterns == null || patterns.isEmpty()) {
            return errorJson("ae2_pattern_write_many: 'patterns' array is required and must contain at least 1 entry");
        }
        if (patterns.size() > MAX_AE2_PATTERNS_PER_BATCH) {
            return errorJson("ae2_pattern_write_many: too many patterns: " + patterns.size()
                    + " (max " + MAX_AE2_PATTERNS_PER_BATCH + " per call)");
        }

        boolean atomic = asBool(inv.arguments().get("atomic"));
        boolean dryRun = asBool(inv.arguments().get("dry_run"));
        boolean replaceDefault = !inv.arguments().containsKey("replace_existing")
                || asBool(inv.arguments().get("replace_existing"));
        String targetMachineItemId = normalizeId(asString(inv.arguments().get("target_machine_item_id")));
        String targetGroupName = asString(inv.arguments().get("target_group_name"));
        agentDebug("ae2_pattern_write_many: dry_run={} atomic={} size={} replaceDefault={}",
                dryRun, atomic, patterns.size(), replaceDefault);

        if (!(t.getLevel() instanceof ServerLevel level)) {
            return errorJson("ae2_pattern_write_many: transmitter level is not a ServerLevel");
        }

        java.util.LinkedHashSet<BlockPos> anchors = new java.util.LinkedHashSet<>();
        Integer ax = asInt(inv.arguments().get("x"));
        Integer ay = asInt(inv.arguments().get("y"));
        Integer az = asInt(inv.arguments().get("z"));
        if (ax != null && ay != null && az != null) {
            anchors.add(new BlockPos(ax, ay, az));
        }
        CodeNetworkScanner.NetworkView codeView = CodeNetworkScanner.scan(level, t.getBlockPos());
        for (Ae2NetworkBridgeBlockEntity bridge : codeView.ae2Bridges()) {
            anchors.add(bridge.getBlockPos());
        }
        if (anchors.isEmpty()) {
            return errorJson("ae2_pattern_write_many: no AE2 network bridge found on the code network; "
                    + "place an ae2_network_bridge next to an AE2 cable/device and connect it with code cable");
        }

        try {
            Class.forName("appeng.api.networking.IGrid");
            Class.forName("appeng.helpers.patternprovider.PatternContainer");
            Class.forName("appeng.api.crafting.PatternDetailsHelper");

            java.util.List<Ae2PatternContainerRef> containers =
                    collectAe2PatternContainers(level, anchors, false);
            if (containers.isEmpty()) {
                return errorJson("ae2_pattern_write_many: no visible AE2 PatternContainer found from bridge anchors");
            }
            agentDebug("ae2_pattern_write_many: {} candidate container(s) for the batch", containers.size());

            java.util.List<JsonObject> results = new java.util.ArrayList<>();
            java.util.List<Ae2SlotSnapshot> snapshots = new java.util.ArrayList<>();
            int succeeded = 0;
            int failed = 0;
            int skipped = 0;
            boolean atomicRolledBack = false;
            String firstError = null;

            for (int i = 0; i < patterns.size(); i++) {
                java.util.Map<String, Object> spec = patterns.get(i);
                if (spec == null) spec = java.util.Map.of();
                JsonObject result = new JsonObject();
                result.addProperty("index", i);
                String pType = asString(spec.get("pattern_type"));
                if (pType == null || pType.isBlank()) pType = "crafting";
                result.addProperty("pattern_type", pType.toLowerCase(java.util.Locale.ROOT));
                String recipeId = asString(spec.get("recipe_id"));
                if (recipeId != null) result.addProperty("recipe_id", recipeId);
                String outId = asString(spec.get("output_item_id"));
                if (outId != null) result.addProperty("output_item_id", outId);

                try {
                    ToolInvocation specInv = new ToolInvocation(
                            "spec_" + i, "ae2_pattern_write", spec);
                    boolean specReplace = spec.containsKey("replace_existing")
                            ? asBool(spec.get("replace_existing")) : replaceDefault;
                    PreparedAe2Pattern prepared = prepareAe2PatternWrite(level, specInv, containers,
                            targetMachineItemId, targetGroupName, specReplace);

                    if (prepared.target() == null) {
                        result.addProperty("ok", false);
                        result.addProperty("error", "no compatible target slot found");
                        failed++;
                        if (firstError == null) firstError = "spec[" + i + "]: no compatible target slot found";
                        results.add(result);
                        if (atomic) {
                            rollbackAe2PatternSlots(level, snapshots, results, succeeded);
                            atomicRolledBack = true;
                            for (int j = i + 1; j < patterns.size(); j++) {
                                JsonObject skip = new JsonObject();
                                skip.addProperty("index", j);
                                skip.addProperty("ok", false);
                                skip.addProperty("skipped", true);
                                skip.addProperty("error", "atomic batch rolled back at spec[" + i + "]");
                                results.add(skip);
                                skipped++;
                            }
                            break;
                        }
                        continue;
                    }

                    result.addProperty("ok", true);
                    result.addProperty("container_index", prepared.target().ref().index());
                    result.addProperty("slot", prepared.target().slot());
                    result.addProperty("replaced_existing", prepared.target().replacing());
                    result.addProperty("selection_reason", prepared.target().selectionReason());
                    result.addProperty("primary_output", itemId(prepared.encoded().primaryOutput()));
                    result.addProperty("dry_run", dryRun);

                    if (!dryRun) {
                        Ae2SlotSnapshot snap = snapshotAe2PatternSlot(prepared.target());
                        writeAe2PatternSlot(level, prepared.target(), prepared.encoded().stack());
                        snapshots.add(snap);
                        result.addProperty("written", true);
                    } else {
                        result.addProperty("written", false);
                    }
                    succeeded++;
                    results.add(result);
                } catch (Exception e) {
                    agentWarn("ae2_pattern_write_many: spec[{}] threw: {}", i, e.toString());
                    result.addProperty("ok", false);
                    result.addProperty("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                    results.add(result);
                    failed++;
                    if (firstError == null) firstError = "spec[" + i + "]: " + e.getMessage();
                    if (atomic) {
                        rollbackAe2PatternSlots(level, snapshots, results, succeeded);
                        atomicRolledBack = true;
                        for (int j = i + 1; j < patterns.size(); j++) {
                            JsonObject skip = new JsonObject();
                            skip.addProperty("index", j);
                            skip.addProperty("ok", false);
                            skip.addProperty("skipped", true);
                            skip.addProperty("error", "atomic batch rolled back at spec[" + i + "]");
                            results.add(skip);
                            skipped++;
                        }
                        break;
                    }
                }
            }

            JsonObject out = new JsonObject();
            out.addProperty("ok", failed == 0 && !atomicRolledBack);
            out.addProperty("tool", "ae2_pattern_write_many");
            out.addProperty("dry_run", dryRun);
            out.addProperty("atomic", atomic);
            out.addProperty("atomic_rolled_back", atomicRolledBack);
            JsonArray arr = new JsonArray();
            for (JsonObject r : results) arr.add(r);
            out.add("results", arr);
            JsonObject summary = new JsonObject();
            summary.addProperty("total", patterns.size());
            summary.addProperty("succeeded", succeeded);
            summary.addProperty("failed", failed);
            summary.addProperty("skipped", skipped);
            out.add("summary", summary);
            if (firstError != null) out.addProperty("first_error", firstError);
            long durMs = (System.nanoTime() - startNs) / 1_000_000L;
            agentDebug("ae2_pattern_write_many: done total={} ok={} fail={} skip={} durMs={}",
                    patterns.size(), succeeded, failed, skipped, durMs);
            return GSON.toJson(out);
        } catch (ClassNotFoundException e) {
            agentWarn("ae2_pattern_write_many: ClassNotFoundException — {}", e.getMessage());
            return errorJson("ae2_pattern_write_many: AE2 is not installed or is not loaded (missing "
                    + e.getMessage() + ")");
        } catch (Exception e) {
            agentWarn("ae2_pattern_write_many: caught {}: {}", e.getClass().getName(), e.getMessage(), e);
            return errorJson("ae2_pattern_write_many: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Cap on patterns per ae2_pattern_write_many call. Big enough to fill a
     *  large pattern provider in one round-trip, small enough to keep a
     *  worst-case reflection failure (atomic rollback) bounded. */
    private static final int MAX_AE2_PATTERNS_PER_BATCH = 16;

    /** Pre-write snapshot of one slot in one AE2 PatternContainer inventory.
     *  Captured in atomic mode so the slot can be restored on rollback. The
     *  container reference is kept so the change-marker can be fired after
     *  restore. */
    private record Ae2SlotSnapshot(Object container, Object inventory, int slot, ItemStack original) {}

    /** Result of the "prepare" step: encoded pattern + chosen target slot.
     *  {@code target} may be null when no compatible slot exists; callers
     *  surface this as a per-spec failure in the batch result array. */
    private record PreparedAe2Pattern(EncodedAe2Pattern encoded, Ae2PatternWriteTarget target) {}

    /**
     * Encode a single spec and choose a target slot. Shared between
     * {@link #runAe2PatternWrite} and {@link #runAe2PatternWriteMany} so
     * both paths apply the same recipe resolution + slot-choice rules.
     *
     * @param specInv     synthetic {@link ToolInvocation} carrying the per-spec
     *                    arguments ({@code pattern_type}, {@code recipe_id},
     *                    {@code output_item_id}, {@code crafting_grid} /
     *                    {@code inputs} / {@code outputs}, {@code container_index},
     *                    {@code slot}, etc.)
     * @param containers  pre-scanned list of AE2 PatternContainer refs
     * @param replaceExisting  resolved replace_existing policy (top-level
     *                          default OR per-spec override)
     * @return encoded pattern + target. {@code target} may be null.
     * @throws Exception on bad spec (illegal pattern_type, missing item,
     *                   recipe_id not found, encode reflection failure).
     */
    private static PreparedAe2Pattern prepareAe2PatternWrite(
            ServerLevel level, ToolInvocation specInv,
            java.util.List<Ae2PatternContainerRef> containers,
            String targetMachineItemId, String targetGroupName, boolean replaceExisting) throws Exception {
        String patternType = asString(specInv.arguments().get("pattern_type"));
        if (patternType == null || patternType.isBlank()) {
            patternType = "crafting";
        }
        patternType = patternType.trim().toLowerCase(java.util.Locale.ROOT);
        EncodedAe2Pattern encoded = switch (patternType) {
            case "crafting" -> encodeAe2CraftingPattern(level, specInv);
            case "processing" -> encodeAe2ProcessingPattern(specInv);
            default -> throw new IllegalArgumentException("pattern_type must be crafting or processing, got: " + patternType);
        };
        Integer requestedContainer = asInt(specInv.arguments().get("container_index"));
        Integer requestedSlot = asInt(specInv.arguments().get("slot"));
        Ae2PatternWriteTarget target = chooseAe2PatternTarget(level, containers, encoded,
                targetMachineItemId, targetGroupName, replaceExisting, requestedContainer, requestedSlot);
        return new PreparedAe2Pattern(encoded, target);
    }

    /**
     * Write the encoded pattern into the chosen target slot via reflective
     * {@code setItemDirect}, then mark the owning container changed. Throws
     * on reflection failure (caller decides whether to surface as per-spec
     * error or abort the batch).
     */
    private static void writeAe2PatternSlot(ServerLevel level, Ae2PatternWriteTarget target, ItemStack pattern)
            throws Exception {
        java.lang.reflect.Method setItemDirect = target.ref().inventory().getClass()
                .getMethod("setItemDirect", int.class, ItemStack.class);
        setItemDirect.invoke(target.ref().inventory(), target.slot(), pattern.copy());
        markAe2PatternContainerChanged(level, target.ref().container());
    }

    /** Capture the current contents of a target slot for later rollback. */
    private static Ae2SlotSnapshot snapshotAe2PatternSlot(Ae2PatternWriteTarget target) throws Exception {
        ItemStack original = inventoryStack(target.ref().inventory(), target.slot());
        return new Ae2SlotSnapshot(target.ref().container(), target.ref().inventory(), target.slot(),
                original.copy());
    }

    /**
     * Roll back a batch's successful writes. Restores each snapshot's
     * original stack to the same slot via setItemDirect, then marks the
     * affected containers changed. Also annotates the per-spec results
     * array so the LLM can see which writes were reverted. Failures
     * during restore are logged at warn but do not throw — the partial
     * restore is still better than nothing, and a follow-up ae2_pattern_lookup
     * can confirm the state.
     */
    private static void rollbackAe2PatternSlots(ServerLevel level,
                                                java.util.List<Ae2SlotSnapshot> snapshots,
                                                java.util.List<JsonObject> results,
                                                int previouslySucceeded) {
        if (snapshots.isEmpty()) return;
        agentDebug("rollbackAe2PatternSlots: rolling back {} snapshot(s)", snapshots.size());
        java.util.LinkedHashSet<Object> containers = new java.util.LinkedHashSet<>();
        for (Ae2SlotSnapshot snap : snapshots) {
            try {
                java.lang.reflect.Method setItemDirect = snap.inventory().getClass()
                        .getMethod("setItemDirect", int.class, ItemStack.class);
                setItemDirect.invoke(snap.inventory(), snap.slot(), snap.original().copy());
                containers.add(snap.container());
            } catch (Exception e) {
                agentWarn("rollbackAe2PatternSlots: failed to restore slot {}: {}",
                        snap.slot(), e.toString());
            }
        }
        for (Object container : containers) {
            try {
                markAe2PatternContainerChanged(level, container);
            } catch (Exception e) {
                agentWarn("rollbackAe2PatternSlots: failed to mark container changed: {}", e.toString());
            }
        }
        // Annotate previously-succeeded results as rolled-back so the LLM
        // can tell the difference between "I never wrote this" and "I
        // wrote this and then reverted it".
        int marked = 0;
        for (JsonObject r : results) {
            if (marked >= previouslySucceeded) break;
            if (r.has("ok") && r.get("ok").getAsBoolean()
                    && r.has("written") && r.get("written").getAsBoolean()) {
                r.addProperty("rolled_back", true);
                marked++;
            }
        }
    }

    private static EncodedAe2Pattern encodeAe2ProcessingPattern(ToolInvocation inv) throws Exception {
        java.util.List<java.util.Map<String, Object>> inputArgs = coerceBlocksArg(inv.arguments().get("inputs"));
        java.util.List<java.util.Map<String, Object>> outputArgs = coerceBlocksArg(inv.arguments().get("outputs"));
        if (inputArgs.isEmpty()) {
            throw new IllegalArgumentException("processing inputs must be [{item_id, amount, data_value}]");
        }
        if (outputArgs.isEmpty()) {
            String outputItemId = asString(inv.arguments().get("output_item_id"));
            if (outputItemId != null && !outputItemId.isBlank()) {
                outputArgs = java.util.List.of(java.util.Map.of("item_id", outputItemId, "amount", 1));
            } else {
                throw new IllegalArgumentException("processing outputs or output_item_id is required");
            }
        }

        java.util.List<Object> inputs = new java.util.ArrayList<>();
        for (java.util.Map<String, Object> entry : inputArgs) {
            inputs.add(ae2GenericStackFromItemEntry(entry, "inputs"));
        }
        java.util.List<Object> outputs = new java.util.ArrayList<>();
        for (java.util.Map<String, Object> entry : outputArgs) {
            outputs.add(ae2GenericStackFromItemEntry(entry, "outputs"));
        }

        Class<?> helper = Class.forName("appeng.api.crafting.PatternDetailsHelper");
        @SuppressWarnings("unchecked")
        ItemStack encoded = (ItemStack) helper.getMethod("encodeProcessingPattern", java.util.List.class,
                java.util.List.class).invoke(null, inputs, outputs);

        ItemStack primaryOutput = itemStackFromObject(outputArgs.get(0), "outputs[0]", 1);
        JsonObject recipe = new JsonObject();
        recipe.addProperty("source", "explicit_processing_io");
        recipe.add("inputs", describePatternItemEntries(inputArgs));
        recipe.add("outputs", describePatternItemEntries(outputArgs));
        return new EncodedAe2Pattern(encoded, "processing", null, primaryOutput, recipe);
    }

    private static EncodedAe2Pattern encodeAe2CraftingPattern(ServerLevel level, ToolInvocation inv) throws Exception {
        String recipeIdArg = asString(inv.arguments().get("recipe_id"));
        String outputItemId = normalizeId(asString(inv.arguments().get("output_item_id")));
        ItemStack[] grid = parseCraftingGrid(inv.arguments().get("crafting_grid"));
        RecipeHolder<CraftingRecipe> recipe = null;

        if (recipeIdArg != null && !recipeIdArg.isBlank()) {
            var holder = level.getRecipeManager().byKey(ResourceLocation.parse(recipeIdArg.trim())).orElse(null);
            if (holder == null) {
                throw new IllegalArgumentException("recipe_id not found: " + recipeIdArg);
            }
            if (!(holder.value() instanceof CraftingRecipe)) {
                throw new IllegalArgumentException("recipe_id is not a crafting recipe: " + recipeIdArg);
            }
            @SuppressWarnings("unchecked")
            RecipeHolder<CraftingRecipe> casted = (RecipeHolder<CraftingRecipe>) (RecipeHolder<?>) holder;
            recipe = casted;
            if (isEmptyGrid(grid)) {
                grid = craftingGridFromRecipe(recipe.value());
            }
        } else if (!isEmptyGrid(grid)) {
            CraftingInput input = CraftingInput.of(3, 3, nonNullCraftingGrid(grid));
            recipe = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level).orElse(null);
            if (recipe == null) {
                throw new IllegalArgumentException("no crafting recipe matches crafting_grid");
            }
        } else if (outputItemId != null && !outputItemId.isBlank()) {
            java.util.List<RecipeHolder<CraftingRecipe>> candidates = findCraftingRecipesForOutput(level, outputItemId);
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("no crafting recipe produces " + outputItemId);
            }
            if (candidates.size() > 1) {
                JsonObject err = new JsonObject();
                err.addProperty("output_item_id", outputItemId);
                err.addProperty("candidate_count", candidates.size());
                JsonArray candidateIds = new JsonArray();
                for (RecipeHolder<CraftingRecipe> candidate : candidates) {
                    candidateIds.add(candidate.id().toString());
                }
                err.add("candidate_recipe_ids", candidateIds);
                throw new IllegalArgumentException("multiple recipes produce " + outputItemId
                        + "; call jei_lookup/include_recipes and retry with recipe_id. Candidates: "
                        + GSON.toJson(err));
            }
            recipe = candidates.get(0);
            grid = craftingGridFromRecipe(recipe.value());
        } else {
            throw new IllegalArgumentException("crafting requires recipe_id, crafting_grid, or output_item_id");
        }

        if (isEmptyGrid(grid)) {
            throw new IllegalArgumentException("crafting grid resolved to empty");
        }

        CraftingInput input = CraftingInput.of(3, 3, nonNullCraftingGrid(grid));
        if (!recipe.value().matches(input, level)) {
            throw new IllegalArgumentException("resolved crafting_grid does not match recipe " + recipe.id());
        }
        ItemStack output = recipe.value().assemble(input, level.registryAccess());
        if (output.isEmpty()) {
            output = recipe.value().getResultItem(level.registryAccess()).copy();
        }
        if (outputItemId != null && !outputItemId.isBlank() && !outputItemId.equals(itemId(output))) {
            throw new IllegalArgumentException("recipe " + recipe.id() + " produces " + itemId(output)
                    + ", not requested output " + outputItemId);
        }

        boolean allowSubstitutes = asBool(inv.arguments().get("allow_substitutes"));
        boolean allowFluidSubstitutes = asBool(inv.arguments().get("allow_fluid_substitutes"));
        Class<?> helper = Class.forName("appeng.api.crafting.PatternDetailsHelper");
        ItemStack encoded = (ItemStack) helper.getMethod("encodeCraftingPattern", RecipeHolder.class,
                ItemStack[].class, ItemStack.class, boolean.class, boolean.class)
                .invoke(null, recipe, grid, output, allowSubstitutes, allowFluidSubstitutes);

        JsonObject recipeInfo = new JsonObject();
        recipeInfo.addProperty("source", recipeIdArg == null || recipeIdArg.isBlank()
                ? "server_recipe_lookup_matching_jei_layout" : "recipe_id");
        recipeInfo.addProperty("id", recipe.id().toString());
        recipeInfo.addProperty("output", itemId(output));
        recipeInfo.add("crafting_grid", describeItemStackArray(grid));
        recipeInfo.addProperty("allow_substitutes", allowSubstitutes);
        recipeInfo.addProperty("allow_fluid_substitutes", allowFluidSubstitutes);
        return new EncodedAe2Pattern(encoded, "crafting", recipe.id().toString(), output, recipeInfo);
    }

    private static java.util.List<Ae2PatternContainerRef> collectAe2PatternContainers(ServerLevel level,
                                                                                       java.util.Set<BlockPos> anchors,
                                                                                       boolean includeHidden)
            throws Exception {
        Class<?> gridClass = Class.forName("appeng.api.networking.IGrid");
        Class<?> patternContainerClass = Class.forName("appeng.helpers.patternprovider.PatternContainer");
        java.lang.reflect.Method getMachineClasses = gridClass.getMethod("getMachineClasses");
        java.lang.reflect.Method getActiveMachines = gridClass.getMethod("getActiveMachines", Class.class);
        java.util.List<Ae2PatternContainerRef> out = new java.util.ArrayList<>();
        java.util.Set<Object> seenContainers = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        for (BlockPos anchor : anchors) {
            Object grid = resolveAe2GridNear(level, anchor);
            if (grid != null) {
                Iterable<?> machineClasses = (Iterable<?>) getMachineClasses.invoke(grid);
                for (Object rawMachineClass : machineClasses) {
                    if (!(rawMachineClass instanceof Class<?> machineClass)
                            || !patternContainerClass.isAssignableFrom(machineClass)) {
                        continue;
                    }
                    Iterable<?> activeMachines = (Iterable<?>) getActiveMachines.invoke(grid, machineClass);
                    for (Object container : activeMachines) {
                        if (!seenContainers.add(container)) {
                            continue;
                        }
                        boolean visible = reflectBoolean(patternContainerClass, container, "isVisibleInTerminal",
                                true);
                        if (!visible && !includeHidden) {
                            continue;
                        }
                        Object inventory = patternContainerClass.getMethod("getTerminalPatternInventory")
                                .invoke(container);
                        out.add(new Ae2PatternContainerRef(anchor, out.size(), container, inventory));
                    }
                }
                continue;
            }

            for (BlockPos pos : positionsAtAndAdjacent(anchor)) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be == null || !patternContainerClass.isInstance(be) || !seenContainers.add(be)) {
                    continue;
                }
                boolean visible = reflectBoolean(patternContainerClass, be, "isVisibleInTerminal", true);
                if (!visible && !includeHidden) {
                    continue;
                }
                Object inventory = patternContainerClass.getMethod("getTerminalPatternInventory").invoke(be);
                out.add(new Ae2PatternContainerRef(pos, out.size(), be, inventory));
            }
        }
        return out;
    }

    private static java.util.List<BlockPos> positionsAtAndAdjacent(BlockPos pos) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        out.add(pos);
        for (Direction direction : Direction.values()) {
            out.add(pos.relative(direction));
        }
        return out;
    }

    private static Ae2PatternWriteTarget chooseAe2PatternTarget(Level level,
                                                                java.util.List<Ae2PatternContainerRef> containers,
                                                                EncodedAe2Pattern encoded,
                                                                String targetMachineItemId,
                                                                String targetGroupName,
                                                                boolean replaceExisting,
                                                                Integer requestedContainer,
                                                                Integer requestedSlot) throws Exception {
        long startNs = System.nanoTime();
        boolean molecularPattern = isAe2MolecularAssemblerPattern(level, encoded.stack());
        String primaryOutputId = itemId(encoded.primaryOutput());
        agentDebug("chooseAe2PatternTarget: molecularPattern={} primaryOutputId='{}' containerCount={}",
                molecularPattern, primaryOutputId, containers.size());
        java.util.List<Ae2PatternContainerRef> candidates = new java.util.ArrayList<>();
        for (Ae2PatternContainerRef ref : containers) {
            if (requestedContainer != null && ref.index() != requestedContainer) {
                continue;
            }
            boolean molecularContainer = isAe2MolecularAssemblerContainer(ref.container());
            if (molecularPattern != molecularContainer) {
                continue;
            }
            if ((targetMachineItemId != null || targetGroupName != null)
                    && !ae2ContainerMatchesRequestedTarget(ref.container(), targetMachineItemId, targetGroupName)) {
                continue;
            }
            candidates.add(ref);
        }
        candidates.sort((a, b) -> Integer.compare(
                ae2ContainerMatchScore(b.container(), targetMachineItemId, targetGroupName, molecularPattern),
                ae2ContainerMatchScore(a.container(), targetMachineItemId, targetGroupName, molecularPattern)));
        agentDebug("chooseAe2PatternTarget: filtered to {} candidate container(s) after filter+sort", candidates.size());

        if (requestedSlot != null) {
            agentDebug("chooseAe2PatternTarget: requestedSlot={} — scanning candidates for that slot", requestedSlot);
            for (Ae2PatternContainerRef ref : candidates) {
                int size = inventorySize(ref.inventory());
                if (requestedSlot < 0 || requestedSlot >= size) {
                    continue;
                }
                ItemStack existing = inventoryStack(ref.inventory(), requestedSlot);
                if (existing.isEmpty()) {
                    return new Ae2PatternWriteTarget(ref, requestedSlot, false, "requested_empty_slot");
                }
                String existingOutput = ae2PatternPrimaryOutputId(level, existing);
                if (primaryOutputId.equals(existingOutput) && replaceExisting) {
                    return new Ae2PatternWriteTarget(ref, requestedSlot, true, "requested_matching_output_slot");
                }
                return null;
            }
            return null;
        }

        if (replaceExisting) {
            agentDebug("chooseAe2PatternTarget: replaceExisting=true — searching for existing slot matching primary output");
            for (Ae2PatternContainerRef ref : candidates) {
                int size = inventorySize(ref.inventory());
                for (int slot = 0; slot < size; slot++) {
                    ItemStack existing = inventoryStack(ref.inventory(), slot);
                    if (!existing.isEmpty() && primaryOutputId.equals(ae2PatternPrimaryOutputId(level, existing))) {
                        return new Ae2PatternWriteTarget(ref, slot, true, "existing_matching_primary_output");
                    }
                }
            }
            agentDebug("chooseAe2PatternTarget: no existing-matching slot found, falling back to empty-slot search");
        }

        for (Ae2PatternContainerRef ref : candidates) {
            int size = inventorySize(ref.inventory());
            for (int slot = 0; slot < size; slot++) {
                if (inventoryStack(ref.inventory(), slot).isEmpty()) {
                    String reason = targetMachineItemId != null || targetGroupName != null
                            ? "target_machine_matched_empty_slot"
                            : "ae2_pattern_type_matched_empty_slot";
                    long durMs = (System.nanoTime() - startNs) / 1_000_000L;
                    agentDebug("chooseAe2PatternTarget: empty slot found container={} slot={} durMs={}",
                            ref.index(), slot, durMs);
                    return new Ae2PatternWriteTarget(ref, slot, false, reason);
                }
            }
        }
        agentDebug("chooseAe2PatternTarget: no slot available in any candidate container");
        return null;
    }

    private static boolean ae2ContainerMatchesRequestedTarget(Object container, String targetMachineItemId,
                                                              String targetGroupName) {
        Object group = invokeNoArgQuiet(container, "getTerminalGroup");
        if (group == null) {
            return false;
        }
        String iconId = ae2GroupIconId(group);
        if (targetMachineItemId != null && targetMachineItemId.equals(iconId)) {
            return true;
        }
        String name = ae2GroupName(group);
        return targetGroupName != null && name.toLowerCase(java.util.Locale.ROOT)
                .contains(targetGroupName.toLowerCase(java.util.Locale.ROOT));
    }

    private static int ae2ContainerMatchScore(Object container, String targetMachineItemId, String targetGroupName,
                                              boolean molecularPattern) {
        int score = 0;
        if (isAe2MolecularAssemblerContainer(container) == molecularPattern) {
            score += 100;
        }
        Object group = invokeNoArgQuiet(container, "getTerminalGroup");
        if (group == null) {
            return score;
        }
        String iconId = ae2GroupIconId(group);
        if (targetMachineItemId != null && targetMachineItemId.equals(iconId)) {
            score += 1000;
        }
        String name = ae2GroupName(group);
        if (targetGroupName != null && name.toLowerCase(java.util.Locale.ROOT)
                .contains(targetGroupName.toLowerCase(java.util.Locale.ROOT))) {
            score += 500;
        }
        return score;
    }

    private static JsonArray summarizeAe2PatternWriteContainers(Level level,
                                                                 java.util.List<Ae2PatternContainerRef> containers,
                                                                 String targetMachineItemId,
                                                                 String targetGroupName,
                                                                 ItemStack encodedPattern) throws Exception {
        boolean molecularPattern = isAe2MolecularAssemblerPattern(level, encodedPattern);
        JsonArray out = new JsonArray();
        for (Ae2PatternContainerRef ref : containers) {
            JsonObject entry = new JsonObject();
            entry.addProperty("container_index", ref.index());
            entry.addProperty("inventory_size", inventorySize(ref.inventory()));
            entry.addProperty("empty_slots", countEmptySlots(ref.inventory()));
            entry.addProperty("molecular_assembler_target", isAe2MolecularAssemblerContainer(ref.container()));
            entry.addProperty("match_score", ae2ContainerMatchScore(ref.container(), targetMachineItemId,
                    targetGroupName, molecularPattern));
            Object group = invokeNoArgQuiet(ref.container(), "getTerminalGroup");
            if (group != null) {
                entry.add("group", describeAe2PatternGroup(group));
            }
            out.add(entry);
        }
        return out;
    }

    private static Object ae2GenericStackFromItemEntry(java.util.Map<String, Object> entry, String context)
            throws Exception {
        ItemStack stack = itemStackFromObject(entry, context, 1);
        long amount = asLong(entry.get("amount"), stack.getCount());
        if (amount <= 0) {
            throw new IllegalArgumentException(context + ": amount must be positive");
        }
        Class<?> aeItemKeyClass = Class.forName("appeng.api.stacks.AEItemKey");
        Object key = aeItemKeyClass.getMethod("of", ItemStack.class).invoke(null, stack);
        if (key == null) {
            throw new IllegalArgumentException(context + ": cannot create AE item key for " + itemId(stack));
        }
        Class<?> aeKeyClass = Class.forName("appeng.api.stacks.AEKey");
        Class<?> genericStackClass = Class.forName("appeng.api.stacks.GenericStack");
        return genericStackClass.getConstructor(aeKeyClass, long.class).newInstance(key, amount);
    }

    private static ItemStack itemStackFromObject(Object raw, String context, int defaultCount) {
        if (raw == null) {
            return ItemStack.EMPTY;
        }
        if (raw instanceof String s) {
            if (s.isBlank() || "minecraft:air".equals(s.trim())) {
                return ItemStack.EMPTY;
            }
            return itemStackFromId(s.trim(), defaultCount, null, context);
        }
        if (raw instanceof java.util.Map<?, ?> rawMap) {
            Object idRaw = rawMap.get("item_id");
            if (idRaw == null) {
                idRaw = rawMap.get("id");
            }
            if (idRaw == null) {
                throw new IllegalArgumentException(context + ": item_id is required");
            }
            int count = asInt(rawMap.get("count")) == null
                    ? (asInt(rawMap.get("amount")) == null ? defaultCount : asInt(rawMap.get("amount")))
                    : asInt(rawMap.get("count"));
            Integer dataValue = firstPresentInt(rawMap, "data_value", "data", "metadata", "damage");
            return itemStackFromId(idRaw.toString(), Math.max(1, count), dataValue, context);
        }
        throw new IllegalArgumentException(context + ": expected item id or {item_id, amount, data_value}");
    }

    private static ItemStack itemStackFromId(String id, int count, Integer dataValue, String context) {
        ResourceLocation location = ResourceLocation.parse(id);
        Item item = BuiltInRegistries.ITEM.getOptional(location).orElse(null);
        if (item == null || location.equals(BuiltInRegistries.ITEM.getDefaultKey())) {
            throw new IllegalArgumentException(context + ": unknown item id " + id);
        }
        ItemStack stack = new ItemStack(item, Math.max(1, count));
        if (dataValue != null) {
            if (dataValue < 0) {
                throw new IllegalArgumentException(context + ": data_value must be >= 0");
            }
            stack.setDamageValue(dataValue);
        }
        return stack;
    }

    private static ItemStack[] parseCraftingGrid(Object raw) {
        ItemStack[] grid = emptyCraftingGrid();
        java.util.List<Object> list = coerceListArg(raw);
        if (list.isEmpty()) {
            return grid;
        }
        if (list.size() != 9) {
            throw new IllegalArgumentException("crafting_grid must contain exactly 9 slots");
        }
        for (int i = 0; i < 9; i++) {
            grid[i] = itemStackFromObject(list.get(i), "crafting_grid[" + i + "]", 1);
        }
        return grid;
    }

    private static ItemStack[] emptyCraftingGrid() {
        ItemStack[] grid = new ItemStack[9];
        java.util.Arrays.fill(grid, ItemStack.EMPTY);
        return grid;
    }

    private static boolean isEmptyGrid(ItemStack[] grid) {
        for (ItemStack stack : grid) {
            if (stack != null && !stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Build a 9-slot {@link NonNullList} from a 3x3 crafting grid. {@link
     * NonNullList#of(Object, Object...)} treats the second argument as a single
     * varargs element, so {@code NonNullList.of(ItemStack.EMPTY, grid)} would
     * create a 2-entry list with the whole array as the second element and blow
     * up {@link CraftingInput#of} with a {@link ClassCastException}. This
     * helper materialises the grid into a fixed-size list that the recipe
     * matcher actually expects.
     */
    private static NonNullList<ItemStack> nonNullCraftingGrid(ItemStack[] grid) {
        NonNullList<ItemStack> out = NonNullList.withSize(9, ItemStack.EMPTY);
        if (grid != null) {
            for (int i = 0; i < Math.min(9, grid.length); i++) {
                ItemStack stack = grid[i];
                out.set(i, stack == null ? ItemStack.EMPTY : stack);
            }
        }
        return out;
    }

    private static ItemStack[] craftingGridFromRecipe(Recipe<?> recipe) {
        NonNullList<Ingredient> ingredients = ensure3By3CraftingMatrix(recipe);
        ItemStack[] grid = emptyCraftingGrid();
        for (int i = 0; i < ingredients.size() && i < 9; i++) {
            Ingredient ingredient = ingredients.get(i);
            if (!ingredient.isEmpty() && ingredient.getItems().length > 0) {
                grid[i] = ingredient.getItems()[0].copy();
                grid[i].setCount(1);
            }
        }
        return grid;
    }

    private static NonNullList<Ingredient> ensure3By3CraftingMatrix(Recipe<?> recipe) {
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        NonNullList<Ingredient> expanded = NonNullList.withSize(9, Ingredient.EMPTY);
        if (ingredients.size() > 9) {
            throw new IllegalArgumentException("recipe has more than 9 ingredients");
        }
        if (recipe instanceof ShapedRecipe shapedRecipe) {
            int width = shapedRecipe.getWidth();
            int height = shapedRecipe.getHeight();
            if (width > 3 || height > 3) {
                throw new IllegalArgumentException("recipe is larger than 3x3");
            }
            for (int h = 0; h < height; h++) {
                for (int w = 0; w < width; w++) {
                    expanded.set(w + h * 3, ingredients.get(w + h * width));
                }
            }
        } else {
            for (int i = 0; i < ingredients.size(); i++) {
                expanded.set(i, ingredients.get(i));
            }
        }
        return expanded;
    }

    private static java.util.List<RecipeHolder<CraftingRecipe>> findCraftingRecipesForOutput(ServerLevel level,
                                                                                              String outputItemId) {
        java.util.List<RecipeHolder<CraftingRecipe>> out = new java.util.ArrayList<>();
        for (RecipeHolder<CraftingRecipe> holder : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            ItemStack result = holder.value().getResultItem(level.registryAccess());
            if (!result.isEmpty() && outputItemId.equals(itemId(result))) {
                out.add(holder);
            }
        }
        return out;
    }

    private static java.util.List<Object> coerceListArg(Object raw) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (raw instanceof java.util.List<?> list) {
            out.addAll(list);
            return out;
        }
        try {
            com.google.gson.JsonElement el = JsonParser.parseString(raw.toString());
            if (el.isJsonArray()) {
                for (com.google.gson.JsonElement child : el.getAsJsonArray()) {
                    if (child.isJsonObject()) {
                        out.add(GSON.fromJson(child, java.util.Map.class));
                    } else if (child.isJsonNull()) {
                        out.add(null);
                    } else if (child.isJsonPrimitive()) {
                        out.add(child.getAsString());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static JsonArray describePatternItemEntries(java.util.List<java.util.Map<String, Object>> entries) {
        JsonArray out = new JsonArray();
        for (java.util.Map<String, Object> entry : entries) {
            JsonObject item = new JsonObject();
            item.addProperty("item_id", normalizeId(asString(entry.get("item_id"))));
            item.addProperty("amount", asLong(entry.get("amount"), 1));
            Integer dataValue = firstPresentInt(entry, "data_value", "data", "metadata", "damage");
            if (dataValue != null) {
                item.addProperty("data_value", dataValue);
            }
            out.add(item);
        }
        return out;
    }

    private static JsonArray describeItemStackArray(ItemStack[] stacks) throws Exception {
        JsonArray out = new JsonArray();
        for (int i = 0; i < stacks.length; i++) {
            JsonObject entry = describeItemStack(stacks[i] == null ? ItemStack.EMPTY : stacks[i]);
            entry.addProperty("slot", i);
            out.add(entry);
        }
        return out;
    }

    private static ItemStack inventoryStack(Object inventory, int slot) throws Exception {
        Object rawStack = inventory.getClass().getMethod("getStackInSlot", int.class).invoke(inventory, slot);
        return rawStack instanceof ItemStack stack ? stack : ItemStack.EMPTY;
    }

    private static int countEmptySlots(Object inventory) throws Exception {
        int size = inventorySize(inventory);
        int count = 0;
        for (int slot = 0; slot < size; slot++) {
            if (inventoryStack(inventory, slot).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static boolean isAe2MolecularAssemblerPattern(Level level, ItemStack stack) throws Exception {
        Class<?> helper = Class.forName("appeng.api.crafting.PatternDetailsHelper");
        Object details = helper.getMethod("decodePattern", ItemStack.class, Level.class).invoke(null, stack, level);
        return details != null
                && Class.forName("appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern")
                .isInstance(details);
    }

    private static boolean isAe2MolecularAssemblerContainer(Object container) {
        Object group = invokeNoArgQuiet(container, "getTerminalGroup");
        String iconId = ae2GroupIconId(group);
        return "ae2:molecular_assembler".equals(iconId);
    }

    private static String ae2GroupIconId(Object group) {
        if (group == null) {
            return "";
        }
        Object icon = invokeNoArgQuiet(group, "icon");
        if (icon == null) {
            return "";
        }
        Object id = invokeNoArgQuiet(icon, "getId");
        return id == null ? "" : id.toString();
    }

    private static String ae2GroupName(Object group) {
        if (group == null) {
            return "";
        }
        Object name = invokeNoArgQuiet(group, "name");
        return name == null ? "" : componentText(name);
    }

    private static String ae2PatternPrimaryOutputId(Level level, ItemStack stack) throws Exception {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        Class<?> helper = Class.forName("appeng.api.crafting.PatternDetailsHelper");
        Object details = helper.getMethod("decodePattern", ItemStack.class, Level.class).invoke(null, stack, level);
        if (details == null) {
            return "";
        }
        Class<?> detailsClass = Class.forName("appeng.api.crafting.IPatternDetails");
        Object primaryOutput = detailsClass.getMethod("getPrimaryOutput").invoke(details);
        if (primaryOutput == null) {
            return "";
        }
        Object what = invokeNoArgQuiet(primaryOutput, "what");
        if (what == null) {
            return "";
        }
        Object id = invokeNoArgQuiet(what, "getId");
        return id == null ? "" : id.toString();
    }

    private static void markAe2PatternContainerChanged(Level level, Object container) {
        long startNs = System.nanoTime();
        agentDebug("markAe2PatternContainerChanged: container class='{}'",
                container == null ? "<null>" : container.getClass().getName());
        invokeNoArgQuiet(container, "saveChanges");
        invokeNoArgQuiet(container, "setChanged");
        if (container instanceof BlockEntity be) {
            be.setChanged();
            agentDebug("markAe2PatternContainerChanged: BlockEntity.setChanged() at pos={}", be.getBlockPos());
            level.sendBlockUpdated(be.getBlockPos(), be.getBlockState(), be.getBlockState(), 3);
        }
        long durMs = (System.nanoTime() - startNs) / 1_000_000L;
        agentDebug("markAe2PatternContainerChanged: done durMs={}", durMs);
    }

    private static String normalizeId(String id) {
        if (id == null) {
            return null;
        }
        String trimmed = id.trim();
        return trimmed.isEmpty() ? null : ResourceLocation.parse(trimmed).toString();
    }

    private static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "minecraft:air";
        }
        return stack.getItem().builtInRegistryHolder().key().location().toString();
    }

    private static Object resolveAe2GridNear(ServerLevel level, BlockPos anchor) throws Exception {
        Object grid = resolveAe2GridFromBlockEntity(level.getBlockEntity(anchor));
        if (grid != null) {
            return grid;
        }
        for (Direction direction : Direction.values()) {
            grid = resolveAe2GridFromBlockEntity(level.getBlockEntity(anchor.relative(direction)));
            if (grid != null) {
                return grid;
            }
        }
        return null;
    }

    private static Object resolveAe2GridFromBlockEntity(BlockEntity be) throws Exception {
        if (be == null) {
            return null;
        }

        Object directGrid = invokeNoArg(be, "getGrid");
        if (isAe2Grid(directGrid)) {
            return directGrid;
        }

        Object mainNode = invokeNoArg(be, "getMainNode");
        Object grid = gridFromAe2Node(mainNode);
        if (grid != null) {
            return grid;
        }

        Object gridNode = invokeNoArg(be, "getGridNode");
        grid = gridFromAe2Node(gridNode);
        if (grid != null) {
            return grid;
        }

        for (Direction direction : Direction.values()) {
            Object sideNode = invokeOneArg(be, "getGridNode", Direction.class, direction);
            grid = gridFromAe2Node(sideNode);
            if (grid != null) {
                return grid;
            }
        }

        Object cableBus = invokeNoArg(be, "getCableBus");
        if (cableBus != null) {
            for (Direction direction : Direction.values()) {
                Object sideNode = invokeOneArg(cableBus, "getGridNode", Direction.class, direction);
                grid = gridFromAe2Node(sideNode);
                if (grid != null) {
                    return grid;
                }
            }
        }

        return null;
    }

    private static Object gridFromAe2Node(Object nodeLike) throws Exception {
        if (nodeLike == null) {
            return null;
        }
        Object grid = invokeNoArg(nodeLike, "getGrid");
        if (isAe2Grid(grid)) {
            return grid;
        }
        Object node = invokeNoArg(nodeLike, "getNode");
        if (node != null && node != nodeLike) {
            grid = invokeNoArg(node, "getGrid");
            if (isAe2Grid(grid)) {
                return grid;
            }
        }
        return null;
    }

    private static boolean isAe2Grid(Object grid) throws ClassNotFoundException {
        return grid != null && Class.forName("appeng.api.networking.IGrid").isInstance(grid);
    }

    private static JsonObject describeAe2GridPatterns(Level level, BlockPos anchor, Object grid,
                                                       boolean includeDetails, boolean includeHidden,
                                                       int[] remainingPatterns) throws Exception {
        Class<?> gridClass = Class.forName("appeng.api.networking.IGrid");
        Class<?> patternContainerClass = Class.forName("appeng.helpers.patternprovider.PatternContainer");
        java.lang.reflect.Method getMachineClasses = gridClass.getMethod("getMachineClasses");
        java.lang.reflect.Method getActiveMachines = gridClass.getMethod("getActiveMachines", Class.class);

        JsonObject network = new JsonObject();
        network.addProperty("anchor_x", anchor.getX());
        network.addProperty("anchor_y", anchor.getY());
        network.addProperty("anchor_z", anchor.getZ());
        network.addProperty("grid_class", grid.getClass().getName());
        Object size = invokeNoArg(grid, "size");
        if (size instanceof Number n) {
            network.addProperty("grid_nodes", n.intValue());
        }

        JsonArray containers = new JsonArray();
        int containerCount = 0;
        int visibleSkipped = 0;
        int patternCount = 0;

        Iterable<?> machineClasses = (Iterable<?>) getMachineClasses.invoke(grid);
        for (Object rawMachineClass : machineClasses) {
            if (!(rawMachineClass instanceof Class<?> machineClass)
                    || !patternContainerClass.isAssignableFrom(machineClass)) {
                continue;
            }

            Iterable<?> activeMachines = (Iterable<?>) getActiveMachines.invoke(grid, machineClass);
            for (Object container : activeMachines) {
                containerCount++;
                boolean visible = reflectBoolean(patternContainerClass, container, "isVisibleInTerminal", true);
                if (!visible && !includeHidden) {
                    visibleSkipped++;
                    continue;
                }
                JsonObject containerJson = describeAe2PatternContainer(level, patternContainerClass, container,
                        includeDetails, remainingPatterns);
                containerJson.addProperty("visible_in_pattern_access_terminal", visible);
                patternCount += containerJson.get("pattern_count").getAsInt();
                containers.add(containerJson);
            }
        }

        network.add("containers", containers);
        network.addProperty("container_count", containerCount);
        network.addProperty("visible_container_count", containers.size());
        network.addProperty("hidden_container_skipped", visibleSkipped);
        network.addProperty("pattern_count", patternCount);
        return network;
    }

    private static JsonObject describeSingleAe2PatternContainer(Level level, BlockPos anchor, boolean includeDetails,
                                                                boolean includeHidden, int[] remainingPatterns)
            throws Exception {
        Class<?> patternContainerClass = Class.forName("appeng.helpers.patternprovider.PatternContainer");
        BlockEntity be = level.getBlockEntity(anchor);
        if (be == null || !patternContainerClass.isInstance(be)) {
            return null;
        }
        boolean visible = reflectBoolean(patternContainerClass, be, "isVisibleInTerminal", true);
        if (!visible && !includeHidden) {
            return null;
        }

        JsonObject network = new JsonObject();
        network.addProperty("anchor_x", anchor.getX());
        network.addProperty("anchor_y", anchor.getY());
        network.addProperty("anchor_z", anchor.getZ());
        network.addProperty("source", "single_pattern_container_fallback");

        JsonObject container = describeAe2PatternContainer(level, patternContainerClass, be, includeDetails,
                remainingPatterns);
        container.addProperty("visible_in_pattern_access_terminal", visible);

        JsonArray containers = new JsonArray();
        containers.add(container);
        network.add("containers", containers);
        network.addProperty("container_count", 1);
        network.addProperty("visible_container_count", 1);
        network.addProperty("hidden_container_skipped", 0);
        network.addProperty("pattern_count", container.get("pattern_count").getAsInt());
        return network;
    }

    private static JsonObject describeAe2PatternContainer(Level level, Class<?> patternContainerClass,
                                                          Object container, boolean includeDetails,
                                                          int[] remainingPatterns) throws Exception {
        JsonObject out = new JsonObject();
        out.addProperty("container_class", container.getClass().getName());
        Object sortOrder = patternContainerClass.getMethod("getTerminalSortOrder").invoke(container);
        if (sortOrder instanceof Number n) {
            out.addProperty("sort_order", n.longValue());
        }
        Object group = patternContainerClass.getMethod("getTerminalGroup").invoke(container);
        if (group != null) {
            out.add("group", describeAe2PatternGroup(group));
        }

        Object inventory = patternContainerClass.getMethod("getTerminalPatternInventory").invoke(container);
        JsonArray patterns = new JsonArray();
        int patternCount = addAe2InventoryPatterns(level, inventory, includeDetails, remainingPatterns, patterns);
        out.addProperty("inventory_class", inventory == null ? "" : inventory.getClass().getName());
        out.addProperty("inventory_size", inventorySize(inventory));
        out.addProperty("pattern_count", patternCount);
        out.add("patterns", patterns);
        return out;
    }

    private static JsonObject describeAe2PatternGroup(Object group) {
        JsonObject out = new JsonObject();
        out.addProperty("group_class", group.getClass().getName());
        Object name = invokeNoArgQuiet(group, "name");
        if (name != null) {
            out.addProperty("name", componentText(name));
        }
        Object icon = invokeNoArgQuiet(group, "icon");
        if (icon != null) {
            out.add("icon", describeAe2Key(icon));
        }
        Object tooltip = invokeNoArgQuiet(group, "tooltip");
        if (tooltip instanceof Iterable<?> iterable) {
            JsonArray lines = new JsonArray();
            for (Object line : iterable) {
                lines.add(componentText(line));
            }
            out.add("tooltip", lines);
        }
        return out;
    }

    private static int addAe2InventoryPatterns(Level level, Object inventory, boolean includeDetails,
                                               int[] remainingPatterns, JsonArray out) throws Exception {
        if (inventory == null) {
            return 0;
        }
        java.lang.reflect.Method sizeMethod = inventory.getClass().getMethod("size");
        java.lang.reflect.Method getStack = inventory.getClass().getMethod("getStackInSlot", int.class);
        int size = ((Number) sizeMethod.invoke(inventory)).intValue();
        int patternCount = 0;
        for (int slot = 0; slot < size; slot++) {
            Object rawStack = getStack.invoke(inventory, slot);
            if (!(rawStack instanceof ItemStack stack) || stack.isEmpty()) {
                continue;
            }
            patternCount++;
            if (remainingPatterns[0] <= 0) {
                continue;
            }
            remainingPatterns[0]--;

            JsonObject pattern = describeItemStack(stack);
            pattern.addProperty("slot", slot);
            if (includeDetails) {
                JsonObject details = decodeAe2PatternDetails(level, stack);
                if (details != null) {
                    pattern.add("details", details);
                }
            }
            out.add(pattern);
        }
        return patternCount;
    }

    private static int inventorySize(Object inventory) {
        if (inventory == null) {
            return 0;
        }
        try {
            Object size = inventory.getClass().getMethod("size").invoke(inventory);
            return size instanceof Number n ? n.intValue() : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static JsonObject describeItemStack(ItemStack stack) throws Exception {
        JsonObject out = new JsonObject();
        out.addProperty("item_id", stack.getItem().builtInRegistryHolder().key().location().toString());
        out.addProperty("display_name", stack.getHoverName().getString());
        out.addProperty("count", stack.getCount());
        out.addProperty("data_value", stack.getDamageValue());
        out.addProperty("has_custom_components", !stack.isComponentsPatchEmpty());
        out.addProperty("raw", shortString(stack.toString(), 240));
        try {
            Class<?> helper = Class.forName("appeng.api.crafting.PatternDetailsHelper");
            out.addProperty("encoded_pattern",
                    (Boolean) helper.getMethod("isEncodedPattern", ItemStack.class).invoke(null, stack));
        } catch (Exception e) {
            out.addProperty("encoded_pattern_error", e.getClass().getSimpleName());
        }
        return out;
    }

    private static JsonObject decodeAe2PatternDetails(Level level, ItemStack stack) throws Exception {
        Class<?> helper = Class.forName("appeng.api.crafting.PatternDetailsHelper");
        Object details = helper.getMethod("decodePattern", ItemStack.class, Level.class).invoke(null, stack, level);
        if (details == null) {
            return null;
        }

        Class<?> detailsClass = Class.forName("appeng.api.crafting.IPatternDetails");
        JsonObject out = new JsonObject();
        out.addProperty("details_class", details.getClass().getName());
        Object definition = detailsClass.getMethod("getDefinition").invoke(details);
        if (definition != null) {
            out.add("definition", describeAe2Key(definition));
        }
        Object primaryOutput = detailsClass.getMethod("getPrimaryOutput").invoke(details);
        if (primaryOutput != null) {
            out.add("primary_output", describeGenericStack(primaryOutput));
        }
        Object outputs = detailsClass.getMethod("getOutputs").invoke(details);
        out.add("outputs", describeGenericStackIterable(outputs, 16));
        Object inputs = detailsClass.getMethod("getInputs").invoke(details);
        out.add("inputs", describeAe2PatternInputs(inputs));
        Object supportsExternal = detailsClass.getMethod("supportsPushInputsToExternalInventory").invoke(details);
        if (supportsExternal instanceof Boolean b) {
            out.addProperty("supports_external_inventory", b);
        }
        return out;
    }

    private static JsonArray describeAe2PatternInputs(Object inputs) throws Exception {
        JsonArray out = new JsonArray();
        if (inputs == null || !inputs.getClass().isArray()) {
            return out;
        }
        Class<?> inputClass = Class.forName("appeng.api.crafting.IPatternDetails$IInput");
        java.lang.reflect.Method getMultiplier = inputClass.getMethod("getMultiplier");
        java.lang.reflect.Method getPossibleInputs = inputClass.getMethod("getPossibleInputs");
        int size = Math.min(java.lang.reflect.Array.getLength(inputs), 16);
        for (int i = 0; i < size; i++) {
            Object input = java.lang.reflect.Array.get(inputs, i);
            JsonObject inputJson = new JsonObject();
            inputJson.addProperty("index", i);
            Object multiplier = getMultiplier.invoke(input);
            if (multiplier instanceof Number n) {
                inputJson.addProperty("multiplier", n.longValue());
            }
            Object possibleInputs = getPossibleInputs.invoke(input);
            inputJson.add("possible", describeGenericStackArray(possibleInputs, 16));
            out.add(inputJson);
        }
        return out;
    }

    private static JsonArray describeGenericStackIterable(Object stacks, int limit) {
        JsonArray out = new JsonArray();
        if (stacks instanceof Iterable<?> iterable) {
            int count = 0;
            for (Object stack : iterable) {
                if (count++ >= limit) {
                    break;
                }
                out.add(describeGenericStack(stack));
            }
        }
        return out;
    }

    private static JsonArray describeGenericStackArray(Object stacks, int limit) {
        JsonArray out = new JsonArray();
        if (stacks == null || !stacks.getClass().isArray()) {
            return out;
        }
        int size = Math.min(java.lang.reflect.Array.getLength(stacks), limit);
        for (int i = 0; i < size; i++) {
            out.add(describeGenericStack(java.lang.reflect.Array.get(stacks, i)));
        }
        return out;
    }

    private static JsonObject describeGenericStack(Object stack) {
        JsonObject out = new JsonObject();
        if (stack == null) {
            out.addProperty("raw", "null");
            return out;
        }
        Object amount = invokeNoArgQuiet(stack, "amount");
        if (amount instanceof Number n) {
            out.addProperty("amount", n.longValue());
        }
        Object what = invokeNoArgQuiet(stack, "what");
        if (what != null) {
            out.add("what", describeAe2Key(what));
        }
        out.addProperty("raw", shortString(stack.toString(), 240));
        return out;
    }

    private static JsonObject describeAe2Key(Object key) {
        JsonObject out = new JsonObject();
        out.addProperty("class", key.getClass().getName());
        Object id = invokeNoArgQuiet(key, "getId");
        if (id != null) {
            out.addProperty("id", id.toString());
        }
        Object displayName = invokeNoArgQuiet(key, "getDisplayName");
        if (displayName != null) {
            out.addProperty("display_name", componentText(displayName));
        }
        out.addProperty("raw", shortString(key.toString(), 240));
        return out;
    }

    private static Object invokeNoArg(Object target, String method) throws Exception {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Object invokeOneArg(Object target, String method, Class<?> argType, Object arg) throws Exception {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(method, argType).invoke(target, arg);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Object invokeNoArgQuiet(Object target, String method) {
        try {
            return invokeNoArg(target, method);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean reflectBoolean(Class<?> declaringClass, Object target, String method, boolean fallback) {
        try {
            Object value = declaringClass.getMethod(method).invoke(target);
            return value instanceof Boolean b ? b : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String componentText(Object component) {
        if (component instanceof net.minecraft.network.chat.Component c) {
            return c.getString();
        }
        return shortString(String.valueOf(component), 240);
    }

    private static String shortString(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }

    /** Reflective lookup of an item/block by id via JEI. The call chain is:
     *  {@code Internal.getJeiRuntime().getIngredientManager().getAllIngredients(ITEM_STACK_TYPE)}
     *  → scan for an item whose registry name matches {@code blockId}, then
     *  {@code getIngredientHelper(item).getDisplayName()} / {@code .getModId()} / etc.
     *  Every step uses {@code Class.forName} / {@code getMethod} so the mod
     *  has no compile-time dependency on JEI; if JEI is missing or the
     *  runtime is not yet ready, the tool result is a friendly error. */
    private static String runJeiNameLookup(ToolInvocation inv) {
        String itemName = asString(inv.arguments().get("item_name"));
        if (itemName == null || itemName.isBlank()) {
            return errorJson("jei_name_lookup: 'item_name' is required (e.g. '铁锭' or 'oak plank')");
        }
        String needle = normalizeSearchText(itemName);
        Integer requestedMax = asInt(inv.arguments().get("max_results"));
        int maxResults = requestedMax == null ? 20 : Math.max(1, Math.min(50, requestedMax));
        Integer requestedCount = asInt(inv.arguments().get("count"));
        int giveCount = requestedCount == null ? 64 : Math.max(1, Math.min(64, requestedCount));
        Integer requestedDataValue = firstPresentInt(inv.arguments(), "data_value", "data", "metadata", "damage");
        String target = asString(inv.arguments().get("target"));
        if (target == null || target.isBlank()) {
            target = "@p";
        }

        JsonObject out = new JsonObject();
        out.addProperty("query", itemName);
        out.addProperty("give_count", giveCount);
        out.addProperty("target", target);
        if (requestedDataValue != null) {
            out.addProperty("requested_data_value", requestedDataValue);
        }
        try {
            Class<?> internalCls = Class.forName("mezz.jei.common.Internal");
            Object runtime = internalCls.getMethod("getJeiRuntime").invoke(null);
            if (runtime == null) {
                return errorJson("jei_name_lookup: JEI runtime not yet available");
            }
            Object ingMgr = runtime.getClass().getMethod("getIngredientManager").invoke(runtime);
            if (ingMgr == null) {
                return errorJson("jei_name_lookup: JEI ingredient manager is null");
            }
            Class<?> vanillaTypesCls = Class.forName("mezz.jei.api.constants.VanillaTypes");
            Object itemType = vanillaTypesCls.getField("ITEM_STACK").get(null);
            Class<?> iIngTypeCls = Class.forName("mezz.jei.api.ingredients.IIngredientType");
            java.lang.reflect.Method getAll = ingMgr.getClass().getMethod("getAllIngredients", iIngTypeCls);
            @SuppressWarnings("unchecked")
            java.util.Collection<Object> allItems = (java.util.Collection<Object>) getAll.invoke(ingMgr, itemType);

            JsonArray exact = new JsonArray();
            JsonArray partial = new JsonArray();
            for (Object rawStack : allItems) {
                if (!(rawStack instanceof ItemStack stack) || stack.isEmpty()) {
                    continue;
                }
                if (requestedDataValue != null && stack.getDamageValue() != requestedDataValue) {
                    continue;
                }
                JsonObject match = describeJeiNameMatch(ingMgr, stack, target, giveCount);
                String displayName = match.has("display_name") ? match.get("display_name").getAsString()
                        : stack.getHoverName().getString();
                String normalizedDisplay = normalizeSearchText(displayName);
                String itemId = match.get("item_id").getAsString();
                if (normalizedDisplay.equals(needle) || itemId.equals(itemName.trim())) {
                    exact.add(match);
                } else if (normalizedDisplay.contains(needle) || itemId.contains(needle)) {
                    partial.add(match);
                }
                if (exact.size() + partial.size() >= maxResults * 3) {
                    break;
                }
            }

            JsonArray results = new JsonArray();
            addLimited(results, exact, maxResults);
            addLimited(results, partial, maxResults);
            out.addProperty("ok", true);
            out.addProperty("result_count", results.size());
            out.add("results", results);
            return GSON.toJson(out);
        } catch (ClassNotFoundException e) {
            return errorJson("jei_name_lookup: JEI mod is not installed on this client (missing class "
                    + e.getMessage() + ")");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            return errorJson("jei_name_lookup: JEI reflection failed: "
                    + (cause == null ? e.getMessage() : cause.getMessage()));
        } catch (Exception e) {
            return errorJson("jei_name_lookup: reflection failure: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    private static JsonObject describeJeiNameMatch(Object ingMgr, ItemStack stack, String target, int giveCount) {
        JsonObject out = new JsonObject();
        String itemId = itemId(stack);
        int dataValue = stack.getDamageValue();
        out.addProperty("item_id", itemId);
        out.addProperty("display_name", stack.getHoverName().getString());
        out.addProperty("mod_id", itemId.contains(":") ? itemId.substring(0, itemId.indexOf(':')) : "minecraft");
        out.addProperty("count", stack.getCount());
        out.addProperty("data_value", dataValue);
        out.addProperty("damage", dataValue);
        out.addProperty("has_custom_components", !stack.isComponentsPatchEmpty());
        out.addProperty("raw_stack", shortString(stack.toString(), 360));
        out.addProperty("components_patch", shortString(stack.getComponentsPatch().toString(), 360));
        out.addProperty("give_command", "/give " + target + " " + itemId + " " + giveCount);
        out.addProperty("give_command_legacy_data_value",
                "/give " + target + " " + itemId + " " + giveCount + " " + dataValue);
        if (dataValue != 0) {
            out.addProperty("give_command_with_data_value",
                    "/give " + target + " " + itemId + " " + giveCount + " " + dataValue);
        }

        Object helper = getJeiIngredientHelper(ingMgr, stack);
        if (helper != null) {
            addJeiHelperString(helper, stack, out, "display_name", "getDisplayName");
            addJeiHelperString(helper, stack, out, "resource_location", "getResourceLocation");
            addJeiHelperString(helper, stack, out, "mod_id", "getDisplayModId");
            addJeiHelperString(helper, stack, out, "wildcard_id", "getWildcardId");
            addJeiHelperString(helper, stack, out, "error_info", "getErrorInfo");
            addJeiUid(helper, stack, out, "jei_uid_ingredient", "Ingredient");
            addJeiUid(helper, stack, out, "jei_uid_recipe", "Recipe");
            try {
                Object cheatStack = helper.getClass().getMethod("getCheatItemStack", Object.class)
                        .invoke(helper, stack);
                if (cheatStack instanceof ItemStack cheat && !cheat.isEmpty()) {
                    out.addProperty("cheat_stack_raw", shortString(cheat.toString(), 360));
                    out.addProperty("cheat_data_value", cheat.getDamageValue());
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static Object getJeiIngredientHelper(Object ingMgr, Object ingredient) {
        if (ingMgr == null || ingredient == null) {
            return null;
        }
        for (java.lang.reflect.Method method : ingMgr.getClass().getMethods()) {
            if (!"getIngredientHelper".equals(method.getName()) || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> param = method.getParameterTypes()[0];
            if (!param.isAssignableFrom(ingredient.getClass()) && param != Object.class) {
                continue;
            }
            try {
                return method.invoke(ingMgr, ingredient);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static void addJeiHelperString(Object helper, Object ingredient, JsonObject out, String key,
                                           String methodName) {
        try {
            for (java.lang.reflect.Method method : helper.getClass().getMethods()) {
                if (!methodName.equals(method.getName()) || method.getParameterCount() != 1) {
                    continue;
                }
                Class<?> param = method.getParameterTypes()[0];
                if (!param.isAssignableFrom(ingredient.getClass()) && param != Object.class) {
                    continue;
                }
                Object value = method.invoke(helper, ingredient);
                if (value != null) {
                    out.addProperty(key, value.toString());
                }
                return;
            }
        } catch (Exception ignored) {
        }
    }

    private static void addJeiUid(Object helper, Object ingredient, JsonObject out, String key, String contextName) {
        try {
            Class<?> contextClass = Class.forName("mezz.jei.api.ingredients.subtypes.UidContext");
            Object context = Enum.valueOf((Class<Enum>) contextClass.asSubclass(Enum.class), contextName);
            for (java.lang.reflect.Method method : helper.getClass().getMethods()) {
                if (!("getUniqueId".equals(method.getName()) || "getUid".equals(method.getName()))
                        || method.getParameterCount() != 2) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if ((!params[0].isAssignableFrom(ingredient.getClass()) && params[0] != Object.class)
                        || !params[1].isAssignableFrom(contextClass)) {
                    continue;
                }
                Object value = method.invoke(helper, ingredient, context);
                if (value != null) {
                    out.addProperty(key, value.toString());
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static String normalizeSearchText(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private static void addLimited(JsonArray dst, JsonArray src, int maxResults) {
        for (int i = 0; i < src.size() && dst.size() < maxResults; i++) {
            dst.add(src.get(i));
        }
    }

     private static String runJeiLookup(ToolInvocation inv) {
        Object blockIdRaw = inv.arguments().get("block_id");
        if (blockIdRaw == null) {
            return errorJson("jei_lookup: 'block_id' is required (e.g. 'minecraft:stone')");
        }
        String blockId = blockIdRaw.toString().trim();
        if (blockId.isEmpty()) {
            return errorJson("jei_lookup: 'block_id' is required (e.g. 'minecraft:stone')");
        }
        boolean includeRecipes = asBoolWithDefault(inv.arguments(), "include_recipes", true);
        boolean includeUses = asBoolWithDefault(inv.arguments(), "include_uses", true);
        boolean includeTags = asBoolWithDefault(inv.arguments(), "include_tags", false);
        Integer requestedDataValue = firstPresentInt(inv.arguments(), "data_value", "data", "metadata", "damage");
        JsonObject out = new JsonObject();
        out.addProperty("block_id", blockId);
        if (requestedDataValue != null) {
            out.addProperty("requested_data_value", requestedDataValue);
        }
        try {
            // 1. Find JEI's runtime accessor class.
            Class<?> internalCls = Class.forName("mezz.jei.common.Internal");
            java.lang.reflect.Method getRuntime = internalCls.getMethod("getJeiRuntime");
            Object runtime = getRuntime.invoke(null);
            if (runtime == null) {
                return errorJson("jei_lookup: JEI runtime not yet available (try again in a few seconds after JEI finishes loading)");
            }
            // 2. runtime.getIngredientManager() returns IIngredientManager.
            Class<?> runtimeCls = runtime.getClass();
            java.lang.reflect.Method getIM = runtimeCls.getMethod("getIngredientManager");
            Object ingMgr = getIM.invoke(runtime);
            if (ingMgr == null) {
                return errorJson("jei_lookup: JEI ingredient manager is null (JEI not ready)");
            }
            // 3. VanillaTypes.ITEM_STACK is a static IIngredientType<ItemStack> field.
            Class<?> vanillaTypesCls = Class.forName("mezz.jei.api.constants.VanillaTypes");
            java.lang.reflect.Field itemField = vanillaTypesCls.getField("ITEM_STACK");
            Object itemType = itemField.get(null);
            // 4. IIngredientManager.getAllIngredients(IIngredientType<V>) → Collection<V>.
            Class<?> iIngTypeCls = Class.forName("mezz.jei.api.ingredients.IIngredientType");
            java.lang.reflect.Method getAll = ingMgr.getClass().getMethod("getAllIngredients", iIngTypeCls);
            @SuppressWarnings("unchecked")
            java.util.Collection<Object> allItems = (java.util.Collection<Object>) getAll.invoke(ingMgr, itemType);
            // 5. Walk the collection looking for a match on the registry id.
            ItemStack match = null;
            JsonArray variants = new JsonArray();
            for (Object rawStack : allItems) {
                if (!(rawStack instanceof ItemStack stack) || stack.isEmpty()) {
                    continue;
                }
                String idStr = itemId(stack);
                if (idStr.equals(blockId)) {
                    if (variants.size() < 20) {
                        variants.add(describeJeiNameMatch(ingMgr, stack, "@p", 64));
                    }
                    if (requestedDataValue == null || stack.getDamageValue() == requestedDataValue) {
                        match = stack;
                        if (requestedDataValue != null) {
                            break;
                        }
                    }
                }
            }
            if (match == null) {
                out.addProperty("ok", false);
                out.addProperty("error", requestedDataValue == null
                        ? "no JEI-registered item found with id '" + blockId + "'"
                        : "no JEI-registered item found with id '" + blockId
                        + "' and data_value " + requestedDataValue);
                if (variants.size() > 0) {
                    out.add("variants", variants);
                }
                return GSON.toJson(out);
            }
            // 6. IIngredientManager.getIngredientHelper(item) → IIngredientHelper<ItemStack>.
            Class<?> iIngTypeClsForHelper = iIngTypeCls;
            java.lang.reflect.Method getHelper = null;
            for (java.lang.reflect.Method m : ingMgr.getClass().getMethods()) {
                if (!m.getName().equals("getIngredientHelper")) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 1) continue;
                if (params[0] == iIngTypeClsForHelper) continue;
                getHelper = m;
                break;
            }
            if (getHelper == null) {
                return errorJson("jei_lookup: IIngredientManager.getIngredientHelper(V) not found by reflection");
            }
            Object helper = getHelper.invoke(ingMgr, match);
            if (helper == null) {
                return errorJson("jei_lookup: IIngredientHelper is null for '" + blockId + "'");
            }
            // 7. Pull basic info from the helper.
            out.addProperty("ok", true);
            out.add("stack", describeJeiNameMatch(ingMgr, match, "@p", 64));
            if (variants.size() > 1) {
                out.add("variants", variants);
            }
            callStringIfPresent(helper, "getDisplayName", out, "display_name", match);
            callStringIfPresent(helper, "getModId", out, "mod_id", match);
            callStringIfPresent(helper, "getResourceLocation", out, "resource_location", match);
            callStringIfPresent(helper, "getUid", out, "jei_uid", match);
            // 8. Optional: recipes that produce this item (OUTPUT focus).
            if (includeRecipes) {
                try {
                    JsonArray recipes = collectJeiRecipes(runtime, ingMgr, itemType, match, false);
                    out.add("recipes", recipes);
                    out.addProperty("recipe_count", recipes.size());
                } catch (Exception e) {
                    out.addProperty("recipes_error", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            // 9. Optional: recipes that consume this item (INPUT focus).
            if (includeUses) {
                try {
                    JsonArray uses = collectJeiRecipes(runtime, ingMgr, itemType, match, true);
                    out.add("uses", uses);
                    out.addProperty("use_count", uses.size());
                } catch (Exception e) {
                    out.addProperty("uses_error", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            // 10. Optional: tags this item belongs to.
            if (includeTags) {
                try {
                    JsonArray tags = collectJeiTags(helper, match);
                    out.add("tags", tags);
                    out.addProperty("tag_count", tags.size());
                } catch (Exception e) {
                    out.addProperty("tags_error", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            return GSON.toJson(out);
        } catch (ClassNotFoundException e) {
            return errorJson("jei_lookup: JEI mod is not installed on this client (missing class "
                    + e.getMessage() + ")");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IllegalStateException) {
                return errorJson("jei_lookup: JEI not ready (" + cause.getMessage()
                        + "). Try again in a few seconds.");
            }
            return errorJson("jei_lookup: JEI reflection failed: "
                    + (cause == null ? e.getMessage() : cause.getMessage()));
        } catch (Exception e) {
            return errorJson("jei_lookup: reflection failure: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    /** Reflective lookup of an item/block by id via EMI. The call chain is:
     *  {@code EmiApi.getIndexStacks()} → scan for a stack whose
     *  {@code getId()} matches {@code blockId}, then
     *  {@code getItemStack()} / {@code getName()} / etc. for display facts.
     *  Recipes and tags are pulled from
     *  {@code EmiApi.getRecipeManager().getRecipesByOutput/Input} and
     *  {@code EmiTags.TAGS} respectively.
     *  Every step uses {@code Class.forName} / {@code getMethod} so the
     *  mod has no compile-time dependency on EMI; if EMI is missing or
     *  the runtime is not yet ready, the tool result is a friendly
     *  error. */
    private static String runEmiLookup(ToolInvocation inv) {
        Object blockIdRaw = inv.arguments().get("block_id");
        if (blockIdRaw == null) {
            return errorJson("emi_lookup: 'block_id' is required (e.g. 'minecraft:stone')");
        }
        String blockId = blockIdRaw.toString().trim();
        if (blockId.isEmpty()) {
            return errorJson("emi_lookup: 'block_id' is required (e.g. 'minecraft:stone')");
        }
        boolean includeRecipes = asBoolWithDefault(inv.arguments(), "include_recipes", true);
        boolean includeUses = asBoolWithDefault(inv.arguments(), "include_uses", true);
        boolean includeTags = asBoolWithDefault(inv.arguments(), "include_tags", false);
        Integer requestedDataValue = firstPresentInt(inv.arguments(), "data_value", "data", "metadata", "damage");
        JsonObject out = new JsonObject();
        out.addProperty("block_id", blockId);
        out.addProperty("source", "emi");
        if (requestedDataValue != null) {
            out.addProperty("requested_data_value", requestedDataValue);
        }
        try {
            // 1. Resolve EmiApi and call getIndexStacks() → List<EmiStack>.
            Class<?> emiApiCls = Class.forName("dev.emi.emi.api.EmiApi");
            java.lang.reflect.Method getIndex = emiApiCls.getMethod("getIndexStacks");
            Object indexObj = getIndex.invoke(null);
            if (!(indexObj instanceof java.util.List<?> indexList)) {
                return errorJson("emi_lookup: EmiApi.getIndexStacks() did not return a List");
            }

            // 2. Walk the list looking for a stack whose getId() matches blockId.
            //    We keep a "best" stack (matching id + data_value if specified)
            //    and a "variants" list (matching id, any data_value) capped at 20
            //    so the LLM can disambiguate damage values.
            Object bestStack = null;
            Object bestItemStack = null;
            int bestDataValue = 0;
            int bestAmount = 1;
            String bestName = null;
            String bestId = null;
            JsonArray variants = new JsonArray();
            int variantEmiStacks = 0;
            for (Object emiStack : indexList) {
                if (emiStack == null) continue;
                Object idObj = invokeNoArgQuiet(emiStack, "getId");
                if (!(idObj instanceof net.minecraft.resources.ResourceLocation id)) continue;
                String idStr = id.toString();
                if (!idStr.equals(blockId)) continue;

                Object itemStack = invokeNoArgQuiet(emiStack, "getItemStack");
                if (!(itemStack instanceof ItemStack stack) || stack.isEmpty()) {
                    // Non-vanilla EMI stack (fluid, etc.) — still record the id
                    // so the LLM knows it exists, but we can't easily describe it.
                    variantEmiStacks++;
                    if (variants.size() < 20) {
                        JsonObject v = new JsonObject();
                        v.addProperty("item_id", idStr);
                        v.addProperty("note", "non-vanilla EMI stack (fluid or special)");
                        variants.add(v);
                    }
                    continue;
                }
                int dv = stack.getDamageValue();
                long amount = 1L;
                Object amountObj = invokeNoArgQuiet(emiStack, "getAmount");
                if (amountObj instanceof Number amt) amount = amt.longValue();
                if (variants.size() < 20) {
                    variants.add(describeEmiStack(stack, amount, "@p", 64));
                }
                if (bestStack == null
                        || (requestedDataValue != null && dv == requestedDataValue
                                && bestDataValue != requestedDataValue)) {
                    bestStack = emiStack;
                    bestItemStack = stack;
                    bestDataValue = dv;
                    bestAmount = (int) Math.min(Integer.MAX_VALUE, amount);
                    bestName = stack.getHoverName().getString();
                    bestId = idStr;
                    if (requestedDataValue != null && dv == requestedDataValue) {
                        break;
                    }
                }
            }
            if (bestStack == null) {
                out.addProperty("ok", false);
                out.addProperty("error", requestedDataValue == null
                        ? "no EMI-registered item found with id '" + blockId + "'"
                        : "no EMI-registered item found with id '" + blockId
                                + "' and data_value " + requestedDataValue);
                if (variants.size() > 0) {
                    out.add("variants", variants);
                }
                return GSON.toJson(out);
            }

            // 3. Pull basic info from the matched vanilla ItemStack (mirrors
            //    the JEI path — getItemStack() already returns a real stack).
            out.addProperty("ok", true);
            out.add("stack", describeEmiStack((ItemStack) bestItemStack, bestAmount, "@p", 64));
            if (variants.size() > 1) {
                out.add("variants", variants);
            }
            out.addProperty("display_name", bestName);
            out.addProperty("resource_location", bestId);
            out.addProperty("mod_id", bestId.contains(":")
                    ? bestId.substring(0, bestId.indexOf(':')) : "minecraft");
            out.addProperty("data_value", bestDataValue);
            out.addProperty("emi_amount", bestAmount);

            // 4. Optional: recipes that produce this item (OUTPUT focus).
            if (includeRecipes) {
                try {
                    JsonArray recipes = collectEmiRecipes(emiApiCls, bestStack, false);
                    out.add("recipes", recipes);
                    out.addProperty("recipe_count", recipes.size());
                } catch (Exception e) {
                    out.addProperty("recipes_error", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            // 5. Optional: recipes that consume this item (INPUT focus).
            if (includeUses) {
                try {
                    JsonArray uses = collectEmiRecipes(emiApiCls, bestStack, true);
                    out.add("uses", uses);
                    out.addProperty("use_count", uses.size());
                } catch (Exception e) {
                    out.addProperty("uses_error", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            // 6. Optional: tags this item belongs to.
            if (includeTags) {
                try {
                    JsonArray tags = collectEmiTagsForStack(bestStack);
                    out.add("tags", tags);
                    out.addProperty("tag_count", tags.size());
                } catch (Exception e) {
                    out.addProperty("tags_error", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            return GSON.toJson(out);
        } catch (ClassNotFoundException e) {
            return errorJson("emi_lookup: EMI mod is not installed on this client (missing class "
                    + e.getMessage() + ")");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            return errorJson("emi_lookup: EMI reflection failed: "
                    + (cause == null ? e.getMessage() : cause.getMessage()));
        } catch (Exception e) {
            return errorJson("emi_lookup: reflection failure: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    /** Build a name + /give + components JSON object for one EMI-backed
     *  ItemStack. Mirrors the shape of {@link #describeJeiNameMatch} so the
     *  two tools return consistent results. */
    private static JsonObject describeEmiStack(ItemStack stack, long emiAmount, String target, int giveCount) {
        JsonObject out = new JsonObject();
        String itemId = itemId(stack);
        int dataValue = stack.getDamageValue();
        int count = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, emiAmount));
        out.addProperty("item_id", itemId);
        out.addProperty("display_name", stack.getHoverName().getString());
        out.addProperty("mod_id", itemId.contains(":") ? itemId.substring(0, itemId.indexOf(':')) : "minecraft");
        out.addProperty("count", count);
        out.addProperty("data_value", dataValue);
        out.addProperty("damage", dataValue);
        out.addProperty("has_custom_components", !stack.isComponentsPatchEmpty());
        out.addProperty("raw_stack", shortString(stack.toString(), 360));
        out.addProperty("components_patch", shortString(stack.getComponentsPatch().toString(), 360));
        out.addProperty("give_command", "/give " + target + " " + itemId + " " + giveCount);
        if (dataValue != 0) {
            out.addProperty("give_command_with_data_value",
                    "/give " + target + " " + itemId + " " + giveCount + " " + dataValue);
        }
        return out;
    }

    /** Collect recipes from EMI for a given EmiStack. Uses
     *  {@code EmiApi.getRecipeManager().getRecipesByOutput} for
     *  {@code uses=false}, or {@code getRecipesByInput} for
     *  {@code uses=true}. Each result entry has the category id/title
     *  and a string representation of the recipe. Capped at 5
     *  categories and 20 recipes per category to keep the result
     *  bounded. */
    private static JsonArray collectEmiRecipes(Class<?> emiApiCls, Object emiStack, boolean uses) throws Exception {
        JsonArray out = new JsonArray();
        Object manager = emiApiCls.getMethod("getRecipeManager").invoke(null);
        if (manager == null) return out;
        java.lang.reflect.Method listMethod = uses
                ? manager.getClass().getMethod("getRecipesByInput", Class.forName("dev.emi.emi.api.stack.EmiStack"))
                : manager.getClass().getMethod("getRecipesByOutput", Class.forName("dev.emi.emi.api.stack.EmiStack"));
        @SuppressWarnings("unchecked")
        java.util.List<Object> recipes = (java.util.List<Object>) listMethod.invoke(manager, emiStack);
        if (recipes == null) return out;

        // Group recipes by category id for compact reporting. The per-call
        // final variables `finalCatId` / `finalCatTitle` are needed because
        // the lambda inside computeIfAbsent captures them — Java requires
        // lambda-captured locals to be (effectively) final.
        java.util.Map<String, JsonArray> byCategory = new java.util.LinkedHashMap<>();
        int totalAdded = 0;
        int cap = 5 * 20;
        for (Object recipe : recipes) {
            if (recipe == null || totalAdded >= cap) continue;
            String catId = "?";
            String catTitle = "?";
            try {
                Object category = recipe.getClass().getMethod("getCategory").invoke(recipe);
                if (category != null) {
                    Object cid = invokeNoArgQuiet(category, "getId");
                    if (cid instanceof net.minecraft.resources.ResourceLocation ci) catId = ci.toString();
                    Object title = category.getClass().getMethod("getName").invoke(category);
                    if (title != null) catTitle = title.toString();
                }
            } catch (Exception ignored) {
            }
            String recipeStr;
            try {
                recipeStr = String.valueOf(recipe.getClass().getMethod("toString").invoke(recipe));
            } catch (Exception e) {
                recipeStr = recipe.getClass().getName();
            }
            final String finalCatId = catId;
            final String finalCatTitle = catTitle;
            JsonArray bucket = byCategory.computeIfAbsent(catId + "|" + catTitle, k -> {
                JsonArray a = new JsonArray();
                JsonObject header = new JsonObject();
                header.addProperty("category_id", finalCatId);
                header.addProperty("category_title", finalCatTitle);
                header.add("recipes", a);
                out.add(header);
                return a;
            });
            if (bucket.size() < 20) {
                bucket.add(shortString(recipeStr, 240));
                totalAdded++;
            }
        }
        return out;
    }

    /** Collect tags an EmiStack belongs to by walking
     *  {@code EmiTags.TAGS} and checking membership via
     *  {@code EmiTags.getValues(key)}. Capped at 50 to bound the
     *  result. */
    private static JsonArray collectEmiTagsForStack(Object emiStack) throws Exception {
        JsonArray out = new JsonArray();
        Class<?> emiTagsCls = Class.forName("dev.emi.emi.registry.EmiTags");
        java.lang.reflect.Field tagsField = null;
        for (java.lang.reflect.Field f : emiTagsCls.getFields()) {
            if ("TAGS".equals(f.getName())) {
                tagsField = f;
                break;
            }
        }
        if (tagsField == null) return out;
        @SuppressWarnings("unchecked")
        java.util.List<Object> tags = (java.util.List<Object>) tagsField.get(null);
        if (tags == null) return out;
        Object emiStackKey = invokeNoArgQuiet(emiStack, "getKey");
        if (emiStackKey == null) return out;
        java.lang.reflect.Method getValues = null;
        for (java.lang.reflect.Method m : emiTagsCls.getMethods()) {
            if ("getValues".equals(m.getName()) && m.getParameterCount() == 1) {
                getValues = m;
                break;
            }
        }
        if (getValues == null) return out;
        int cap = 50;
        for (Object tagKey : tags) {
            if (out.size() >= cap) break;
            if (tagKey == null) continue;
            Object idObj = invokeNoArgQuiet(tagKey, "id");
            if (!(idObj instanceof net.minecraft.resources.ResourceLocation tagId)) continue;
            @SuppressWarnings("unchecked")
            java.util.List<Object> values = (java.util.List<Object>) getValues.invoke(emiTagsCls, tagKey);
            if (values == null) continue;
            for (Object v : values) {
                if (v == null) continue;
                Object vKey = invokeNoArgQuiet(v, "getKey");
                if (emiStackKey.equals(vKey)) {
                    out.add(tagId.toString());
                    break;
                }
            }
        }
        return out;
    }

    /** Reflective name-based lookup via EMI. Mirrors
     *  {@link #runJeiNameLookup} structurally: walk
     *  {@code EmiApi.getIndexStacks()}, match against
     *  {@code EmiStack.getName()} and {@code getId()}, and return
     *  exact-then-partial hits. */
    private static String runEmiNameLookup(ToolInvocation inv) {
        String itemName = asString(inv.arguments().get("item_name"));
        if (itemName == null || itemName.isBlank()) {
            return errorJson("emi_name_lookup: 'item_name' is required (e.g. '铁锭' or 'oak plank')");
        }
        String needle = normalizeSearchText(itemName);
        Integer requestedMax = asInt(inv.arguments().get("max_results"));
        int maxResults = requestedMax == null ? 20 : Math.max(1, Math.min(50, requestedMax));
        Integer requestedCount = asInt(inv.arguments().get("count"));
        int giveCount = requestedCount == null ? 64 : Math.max(1, Math.min(64, requestedCount));
        Integer requestedDataValue = firstPresentInt(inv.arguments(), "data_value", "data", "metadata", "damage");
        String target = asString(inv.arguments().get("target"));
        if (target == null || target.isBlank()) {
            target = "@p";
        }

        JsonObject out = new JsonObject();
        out.addProperty("query", itemName);
        out.addProperty("give_count", giveCount);
        out.addProperty("target", target);
        out.addProperty("source", "emi");
        if (requestedDataValue != null) {
            out.addProperty("requested_data_value", requestedDataValue);
        }
        try {
            Class<?> emiApiCls = Class.forName("dev.emi.emi.api.EmiApi");
            java.lang.reflect.Method getIndex = emiApiCls.getMethod("getIndexStacks");
            Object indexObj = getIndex.invoke(null);
            if (!(indexObj instanceof java.util.List<?> indexList)) {
                return errorJson("emi_name_lookup: EmiApi.getIndexStacks() did not return a List");
            }

            JsonArray exact = new JsonArray();
            JsonArray partial = new JsonArray();
            for (Object emiStack : indexList) {
                if (emiStack == null) continue;
                Object itemStackObj = invokeNoArgQuiet(emiStack, "getItemStack");
                if (!(itemStackObj instanceof ItemStack stack) || stack.isEmpty()) {
                    continue;
                }
                if (requestedDataValue != null && stack.getDamageValue() != requestedDataValue) {
                    continue;
                }
                Object nameObj = invokeNoArgQuiet(emiStack, "getName");
                String displayName = nameObj != null
                        ? invokeStringOrDefault(nameObj, "getString", stack.getHoverName().getString())
                        : stack.getHoverName().getString();
                String itemId = itemId(stack);
                String normalizedDisplay = normalizeSearchText(displayName);
                long emiAmount = 1L;
                Object amountObj = invokeNoArgQuiet(emiStack, "getAmount");
                if (amountObj instanceof Number amt) emiAmount = amt.longValue();
                JsonObject match = describeEmiStack(stack, emiAmount, target, giveCount);
                match.addProperty("display_name", displayName);
                if (normalizedDisplay.equals(needle) || itemId.equals(itemName.trim())) {
                    exact.add(match);
                } else if (normalizedDisplay.contains(needle) || itemId.contains(needle)) {
                    partial.add(match);
                }
                if (exact.size() + partial.size() >= maxResults * 3) {
                    break;
                }
            }

            JsonArray results = new JsonArray();
            addLimited(results, exact, maxResults);
            addLimited(results, partial, maxResults);
            out.addProperty("ok", true);
            out.addProperty("result_count", results.size());
            out.add("results", results);
            return GSON.toJson(out);
        } catch (ClassNotFoundException e) {
            return errorJson("emi_name_lookup: EMI mod is not installed on this client (missing class "
                    + e.getMessage() + ")");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            return errorJson("emi_name_lookup: EMI reflection failed: "
                    + (cause == null ? e.getMessage() : cause.getMessage()));
        } catch (Exception e) {
            return errorJson("emi_name_lookup: reflection failure: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    /** Reflection helper: call a no-arg method and return its toString()
     *  (or fallback) without throwing. Used to pull Text.getString() etc. */
    private static String invokeStringOrDefault(Object target, String method, String fallback) {
        try {
            Object res = target.getClass().getMethod(method).invoke(target);
            return res == null ? fallback : res.toString();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /** Collect recipes from JEI for a given ItemStack. Uses the focus-based
     *  lookup API: create a focus with role OUTPUT (recipes that produce
     *  this item) or INPUT (recipes that consume this item), then query
     *  all recipe types and collect matching recipes. Each result entry
     *  has the category title (e.g. "Crafting", "Smelting") and the
     *  recipe's string representation. Capped at 20 results per category
     *  and 5 categories to keep the tool result bounded. */
    private static JsonArray collectJeiRecipes(Object runtime, Object ingMgr, Object itemType,
                                               Object itemStack, boolean uses) throws Exception {
        JsonArray out = new JsonArray();
        // runtime.getJeiHelpers() → IJeiHelpers
        Object helpers = runtime.getClass().getMethod("getJeiHelpers").invoke(runtime);
        if (helpers == null) return out;
        // helpers.getFocusFactory() → IFocusFactory
        Object focusFactory = helpers.getClass().getMethod("getFocusFactory").invoke(helpers);
        if (focusFactory == null) return out;
        // RecipeIngredientRole enum: OUTPUT or INPUT
        Class<?> roleCls = Class.forName("mezz.jei.api.recipe.RecipeIngredientRole");
        Object role = Enum.valueOf((Class<Enum>) roleCls.asSubclass(Enum.class),
                uses ? "INPUT" : "OUTPUT");
        // focusFactory.createFocus(role, itemType, itemStack) → IFocus
        java.lang.reflect.Method createFocus = focusFactory.getClass().getMethod(
                "createFocus", roleCls, Class.forName("mezz.jei.api.ingredients.IIngredientType"), Object.class);
        Object focus = createFocus.invoke(focusFactory, role, itemType, itemStack);
        if (focus == null) return out;
        // helpers.getAllRecipeTypes() → Stream<IRecipeType<?>>
        java.lang.reflect.Method getAllTypes = helpers.getClass().getMethod("getAllRecipeTypes");
        Object typesStream = getAllTypes.invoke(helpers);
        if (typesStream == null) return out;
        // Collect all recipe types into a list. Cast to Stream directly —
        // using getClass().getMethod("forEach") grabs the concrete
        // ReferencePipeline$Head class which is module-inaccessible.
        java.util.List<Object> typeList = new java.util.ArrayList<>();
        ((java.util.stream.Stream<?>) typesStream).forEach(typeList::add);
        // runtime.getRecipeManager() → IRecipeManager
        Object recipeMgr = runtime.getClass().getMethod("getRecipeManager").invoke(runtime);
        if (recipeMgr == null) return out;
        // IRecipeManager.createRecipeLookup(IRecipeType) → IRecipeLookup
        java.lang.reflect.Method createLookup = null;
        for (java.lang.reflect.Method m : recipeMgr.getClass().getMethods()) {
            if (m.getName().equals("createRecipeLookup") && m.getParameterCount() == 1) {
                createLookup = m;
                break;
            }
        }
        if (createLookup == null) return out;
        // For each recipe type, look up matching recipes and extract info.
        int categoryCount = 0;
        for (Object recipeType : typeList) {
            if (categoryCount >= 5) break;
            try {
                Object lookup = createLookup.invoke(recipeMgr, recipeType);
                // lookup.limitFocus(List.of(focus))
                java.lang.reflect.Method limitFocus = lookup.getClass().getMethod(
                        "limitFocus", java.util.Collection.class);
                lookup = limitFocus.invoke(lookup, java.util.List.of(focus));
                // lookup.get() → Stream<recipe>
                java.lang.reflect.Method getStream = lookup.getClass().getMethod("get");
                Object recipeStream = getStream.invoke(lookup);
                if (recipeStream == null) continue;
                // Collect recipes (cap at 20 per type). Cast to Stream
                // directly — same module-access fix as above.
                java.util.List<Object> recipes = new java.util.ArrayList<>();
                java.util.concurrent.atomic.AtomicInteger cnt = new java.util.concurrent.atomic.AtomicInteger();
                ((java.util.stream.Stream<?>) recipeStream).forEach(r -> {
                    if (cnt.getAndIncrement() < 20) recipes.add(r);
                });
                if (recipes.isEmpty()) continue;
                // Get the recipe category to extract the title.
                // recipeMgr.getRecipeCategory(recipeType) → IRecipeCategory
                java.lang.reflect.Method getCat = null;
                for (java.lang.reflect.Method m : recipeMgr.getClass().getMethods()) {
                    if (m.getName().equals("getRecipeCategory") && m.getParameterCount() == 1) {
                        getCat = m;
                        break;
                    }
                }
                String categoryTitle = "unknown";
                String categoryUid = "";
                if (getCat != null) {
                    try {
                        Object category = getCat.invoke(recipeMgr, recipeType);
                        if (category != null) {
                            java.lang.reflect.Method getTitle = category.getClass().getMethod("getTitle");
                            Object titleComp = getTitle.invoke(category);
                            if (titleComp != null) categoryTitle = titleComp.toString();
                            // category.getRecipeType().getUid()
                            java.lang.reflect.Method getRT = category.getClass().getMethod("getRecipeType");
                            Object rt = getRT.invoke(category);
                            if (rt != null) {
                                java.lang.reflect.Method getUid = rt.getClass().getMethod("getUid");
                                Object uid = getUid.invoke(rt);
                                if (uid != null) categoryUid = uid.toString();
                            }
                        }
                    } catch (Exception ignored) {}
                }
                JsonObject catObj = new JsonObject();
                catObj.addProperty("category", categoryTitle);
                catObj.addProperty("type", categoryUid);
                catObj.addProperty("count", recipes.size());
                JsonArray recipeArr = new JsonArray();
                for (Object recipe : recipes) {
                    JsonObject rObj = new JsonObject();
                    // Try to extract a recipe id or string representation.
                    // For RecipeHolder, the id is accessible via id().
                    // For other recipe types, just use toString().
                    String recipeStr = recipe.toString();
                    try {
                        java.lang.reflect.Method getId = recipe.getClass().getMethod("id");
                        Object id = getId.invoke(recipe);
                        if (id != null) recipeStr = id.toString();
                    } catch (Exception ignored) {}
                    rObj.addProperty("id", recipeStr);
                    // Also try to get the recipe's output items via
                    // recipeMgr.getRecipeIngredients(category, recipe)
                    try {
                        if (getCat != null) {
                            Object category = getCat.invoke(recipeMgr, recipeType);
                            if (category != null) {
                                java.lang.reflect.Method getIngs = recipeMgr.getClass().getMethod(
                                        "getRecipeIngredients",
                                        Class.forName("mezz.jei.api.recipe.category.IRecipeCategory"),
                                        Object.class);
                                Object supplier = getIngs.invoke(recipeMgr, category, recipe);
                                if (supplier != null) {
                                    // supplier.getIngredients(role) → List<ITypedIngredient>
                                    Object outRole = Enum.valueOf(
                                            (Class<Enum>) roleCls.asSubclass(Enum.class), "OUTPUT");
                                    java.lang.reflect.Method getIngsByRole = supplier.getClass().getMethod(
                                            "getIngredients", roleCls);
                                    Object ingList = getIngsByRole.invoke(supplier, outRole);
                                    if (ingList instanceof java.util.List<?> list) {
                                        JsonArray outArr = new JsonArray();
                                        for (Object typedIng : list) {
                                            if (typedIng == null) continue;
                                            try {
                                                java.lang.reflect.Method getIng = typedIng.getClass().getMethod(
                                                        "getIngredient", Class.forName(
                                                                "mezz.jei.api.ingredients.IIngredientType"));
                                                // VanillaTypes.ITEM_STACK
                                                Object opt = getIng.invoke(typedIng, itemType);
                                                if (opt instanceof java.util.Optional<?> optVal && optVal.isPresent()) {
                                                    Object is = optVal.get();
                                                    if (is != null) outArr.add(is.toString());
                                                }
                                            } catch (Exception ignored2) {}
                                        }
                                        if (outArr.size() > 0) rObj.add("outputs", outArr);
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                    recipeArr.add(rObj);
                }
                catObj.add("recipes", recipeArr);
                out.add(catObj);
                categoryCount++;
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** Collect tags for a given ingredient via IIngredientHelper.getTagStream(V).
     *  Returns a JsonArray of tag identifiers (e.g. "minecraft:planks",
     *  "c:ingots"). Capped at 50 to bound the result size. */
    private static JsonArray collectJeiTags(Object helper, Object itemStack) throws Exception {
        JsonArray tags = new JsonArray();
        java.lang.reflect.Method getTagStream = null;
        for (java.lang.reflect.Method m : helper.getClass().getMethods()) {
            if (m.getName().equals("getTagStream") && m.getParameterCount() == 1) {
                getTagStream = m;
                break;
            }
        }
        if (getTagStream == null) return tags;
        Object stream = getTagStream.invoke(helper, itemStack);
        if (stream == null) return tags;
        java.util.concurrent.atomic.AtomicInteger cnt = new java.util.concurrent.atomic.AtomicInteger();
        ((java.util.stream.Stream<?>) stream).forEach(tagId -> {
            if (cnt.getAndIncrement() < 50 && tagId != null) {
                tags.add(tagId.toString());
            }
        });
        return tags;
    }

    /** Reflection helper: call a no-arg-or-one-V method on `target` and
     *  record the result under `key` in the JSON output if it returns
     *  a String. Silently skips methods that don't exist or throw — JEI
     *  versions vary in which methods IIngredientHelper exposes, and
     *  the LLM would rather get partial info than nothing. */
    private static void callStringIfPresent(Object target, String method, JsonObject out,
                                            String key, Object arg) {
        try {
            Class<?>[] argTypes = arg == null ? new Class<?>[0] : new Class<?>[]{arg.getClass()};
            java.lang.reflect.Method m = null;
            // try exact type, then walk superclasses / interfaces.
            Class<?> cursor = arg == null ? Object.class : arg.getClass();
            while (cursor != null) {
                try {
                    m = target.getClass().getMethod(method, cursor);
                    break;
                } catch (NoSuchMethodException ignored) {
                    cursor = cursor.getSuperclass();
                }
            }
            if (m == null) return;
            Object res = arg == null ? m.invoke(target) : m.invoke(target, arg);
            if (res != null) out.addProperty(key, res.toString());
        } catch (Exception ignored) {
            // Skip quietly — partial info is better than a tool failure.
        }
    }

    private static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static Integer firstPresentInt(java.util.Map<?, ?> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key)) {
                Integer value = asInt(values.get(key));
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private static long asLong(Object o, long fallback) {
        if (o == null) return fallback;
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return fallback; }
        }
        return fallback;
    }

    private static boolean asBool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return Boolean.parseBoolean(s.trim());
        return false;
    }

    /** Boolean arg with a default when the key is missing. A present-but-
     *  empty / unparseable value still falls back to {@code defaultValue}
     *  via {@link #asBool(Object)}'s lenient parsing. */
    private static boolean asBoolWithDefault(Map<String, Object> args, String key, boolean defaultValue) {
        if (args == null || !args.containsKey(key)) return defaultValue;
        return asBool(args.get(key));
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    /** Stable cache key for a tool invocation, used by the per-turn
     *  result cache. Combines tool name with a deterministic JSON
     *  serialization of the arguments (sorted keys, so order from the
     *  LLM doesn't change the key). Two invocations with the same
     *  (name, args) collapse to the same key. */
    private static String toolCacheKey(ToolInvocation inv) {
        StringBuilder sb = new StringBuilder();
        sb.append(inv.name() == null ? "" : inv.name());
        sb.append(" ");
        Map<String, Object> args = inv.arguments();
        if (args == null || args.isEmpty()) {
            sb.append("{}");
        } else {
            // TreeMap → sorted-key iteration → stable JSON.
            java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<>(args);
            sb.append(gsonStable().toJson(sorted));
        }
        return sb.toString();
    }

    /** Cached, lazily-constructed {@link com.google.gson.Gson} used for
     *  stable cache-key serialization. Sharing a single instance is
     *  cheaper than rebuilding one per tool call. */
    private static com.google.gson.Gson GSON_STABLE = new com.google.gson.GsonBuilder()
            .disableHtmlEscaping()
            .create();
    private static com.google.gson.Gson gsonStable() {
        return GSON_STABLE;
    }

    /** Heuristic: does a tool result string indicate a failure? The two
     *  shapes we emit are `{"error":"..."}` from the catch-alls and
     *  `{"ok":false,...}` from query tools. A result like
     *  `{"ok":true,...}` or `{"block_id":"..."}` (no ok/error field) is
     *  considered success. We use substring checks rather than JSON
     *  parse because the LLM doesn't care about exact matches and the
     *  cost of full parsing isn't worth it. */
    private static boolean toolResultIsFailure(String result) {
        if (result == null || result.isEmpty()) {
            return true;
        }
        // "ok":false (allow whitespace variations)
        if (result.contains("\"ok\":false") || result.contains("\"ok\": false")) {
            return true;
        }
        // "error":"..." from errorJson() — check for the key signature
        // but only when it looks like a real top-level key (not e.g. an
        // array element "errors" or a nested "error_message").
        if (result.contains("\"error\":\"") || result.contains("\"error\":\"")) {
            return true;
        }
        return false;
    }

    /**
     * True if the query is pure-ASCII English with no Minecraft / mod
     * keyword hint. Used by {@link #runMcmodSearch} to short-circuit
     * obvious non-MC queries (e.g. "rust async tutorial", "weather in
     * Tokyo") before burning a request on MC百科 — mcmod.cn is a
     * Chinese wiki and will return zero results for those, leaving the
     * agent guessing. Conservative on purpose: if the query has any
     * CJK characters OR an MC keyword (minecraft / mod / forge /
     * fabric / jei / recipe / common mod names) we let it through.
     */
    private static boolean isClearlyNonMCQuery(String s) {
        if (s == null || s.isBlank()) return false;
        int cjk = 0;
        int asciiLetter = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // CJK Unified Ideographs covers the common Chinese chars
            // the user is likely to type. Hiragana/Katakana/Hangul
            // (0x3040-0x30FF / 0xAC00-0xD7AF) are also treated as
            // non-ASCII and thus as "not a non-MC English query".
            if (c >= 0x4E00 && c <= 0x9FFF) cjk++;
            else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) asciiLetter++;
        }
        if (cjk > 0) return false;
        // Too short to judge confidently — let it through; mcmod will
        // return empty and the agent will move on.
        if (asciiLetter < 5) return false;
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        for (String hint : MC_QUERY_HINTS) {
            if (containsWord(lower, hint)) return false;
        }
        return true;
    }

    /** Lowercase substring match with non-letter boundaries on both sides. */
    private static boolean containsWord(String haystack, String needle) {
        int from = 0;
        int n = needle.length();
        while (true) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) return false;
            boolean leftOk  = idx == 0
                    || !isAsciiLetter(haystack.charAt(idx - 1));
            boolean rightOk = idx + n == haystack.length()
                    || !isAsciiLetter(haystack.charAt(idx + n));
            if (leftOk && rightOk) return true;
            from = idx + 1;
        }
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z');
    }

    /** MC / mod keywords that count as "this query is MC-related even
     *  if it has no Chinese". Keep the list small and high-signal —
     *  false positives (allowing a query through that mcmod can't
     *  answer) are cheap (empty result); false negatives (rejecting
     *  a valid MC query) are expensive (the agent has to backtrack). */
    private static final String[] MC_QUERY_HINTS = new String[] {
            // generic
            "minecraft", "mc", "mod", "mods", "modpack", "datapack", "resource",
            "loader", "client", "server", "shader",
            // mod loaders / ecosystems
            "forge", "fabric", "neoforge", "liteloader", "rift", "quilt",
            // common recipe / item words
            "recipe", "recipes", "crafting", "smelt", "enchant", "enchantment",
            "potion", "biome", "dimension", "spawn",
            // mod-list / item-list words (mcmod specific)
            "jei", "nei", "item", "block", "mob", "entity",
            // platform names — word-boundary match can't catch 'mcmod'
            // (the substring 'mod' is inside 'mcmod'), so we list the
            // compound terms explicitly.
            "mcmod", "modrinth", "curseforge",
            // well-known mod name fragments (case-insensitive match)
            "tinkers", "buildcraft", "thermal", "mekanism", "botania",
            "thaumcraft", "immersive", "industrial", "create", "ae2",
            "applied energistics", "engineer", "ic2", "railcraft",
            "computercraft", "galacticraft", "draconic", "astral",
            "blood magic", "twilight", "forestry", "agricraft"
    };

    private static String errorJson(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        return GSON.toJson(o);
    }

    // ---- Debug logging for the agent tool-dispatch path ---------------------
    //
    // The user reported that the AI gets stuck after invoking
    // `ae2_pattern_write`. Without trace logs the only signal is the visible
    // chat (which freezes) and the regular log (which says nothing). These
    // helpers wrap the standard NeoForge logger with a config gate so we can
    // reproduce the hang, read the trace, and pinpoint which step blocks.
    //
    // The gate defaults to `true` (see `LLMEraConfig.DEBUG_AGENT_TOOLS`) so
    // the logs are produced out of the box; users can flip it off in
    // config/llmera-common.toml once the bug is diagnosed.
    private static boolean agentDebug() {
        try {
            return LLMEraConfig.DEBUG_AGENT_TOOLS.get();
        } catch (Exception | NoSuchFieldError ignored) {
            // Config may not be initialised yet during very early startup.
            return true;
        }
    }

    private static void agentDebug(String fmt, Object... args) {
        if (!agentDebug()) return;
        LLMEra.LOGGER.info("[{}:agent] " + fmt, prependModid(args));
    }

    private static void agentDebug(String fmt, Throwable th, Object... args) {
        if (!agentDebug()) return;
        Object[] withCause = appendThrowable(args, th);
        LLMEra.LOGGER.info("[{}:agent] " + fmt, prependModid(withCause));
    }

    private static void agentWarn(String fmt, Object... args) {
        // Always-on warnings, but still tagged so they are easy to find.
        LLMEra.LOGGER.warn("[{}:agent] " + fmt, prependModid(args));
    }

    private static void agentWarn(String fmt, Throwable th, Object... args) {
        Object[] withCause = appendThrowable(args, th);
        LLMEra.LOGGER.warn("[{}:agent] " + fmt, prependModid(withCause));
    }

    private static Object[] prependModid(Object[] args) {
        if (args == null) {
            return new Object[]{LLMEra.MODID};
        }
        Object[] out = new Object[args.length + 1];
        out[0] = LLMEra.MODID;
        System.arraycopy(args, 0, out, 1, args.length);
        return out;
    }

    private static Object[] appendThrowable(Object[] args, Throwable th) {
        // SLF4J logs the last argument as the throwable when it implements
        // Throwable. Append it if it isn't already present.
        if (args != null && args.length > 0 && args[args.length - 1] == th) {
            return args;
        }
        Object[] out = args == null ? new Object[1] : new Object[args.length + 1];
        if (args != null) System.arraycopy(args, 0, out, 0, args.length);
        out[out.length - 1] = th;
        return out;
    }

    /** Truncate a string for log output, preserving both head and tail. */
    private static String previewForLog(String s, int max) {
        if (s == null) return "<null>";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...[+" + (s.length() - max) + " chars]";
    }

    /**
     * Cap assistant reasoning before forwarding. Thinking-capable models
     * emit multi-KB reasoning per turn; we keep the TAIL of the trace
     * (the most recent reasoning) because the conclusion of the chain
     * is what most influences the next LLM step. The prefix is
     * discarded and a marker is prepended so the model sees it's a
     * partial view. Truncation can be turned off per-transmitter with
     * the {@code /truncate} slash command (BE flag). The {@code /unlimit}
     * slash command bypasses this even when {@code /truncate} is on —
     * unlimited mode is a "nuclear" override that the user has been
     * warned about.
     */
    private static String truncateReasoning(String s, IntelligentTransmitterBlockEntity t) {
        if (s == null) return "";
        if (t != null && t.isUnlimitedTruncation()) return s;
        if (t != null && !t.isTruncateEnabled()) return s;
        if (s.length() <= MAX_REASONING_CHARS) return s;
        int drop = s.length() - MAX_REASONING_CHARS;
        return "[reasoning truncated, " + drop + " chars dropped from the head]\n"
                + s.substring(s.length() - MAX_REASONING_CHARS);
    }

    /**
     * Cap tool result payloads before persisting / forwarding. A single
     * place result is short but a read_block on a complex container can be
     * a few KB. We keep the TAIL (the end of the output, which is where
     * the meaningful state tends to land) and drop the prefix. Marker is
     * prepended so the model knows the data was clipped. Honored only
     * when truncation is enabled on the BE (see /truncate). Bypassed
     * entirely by the {@code /unlimit} command.
     */
    private static String truncateToolResult(String s, IntelligentTransmitterBlockEntity t) {
        if (s == null) return "";
        if (t != null && t.isUnlimitedTruncation()) return s;
        if (t != null && !t.isTruncateEnabled()) return s;
        if (s.length() <= MAX_TOOL_RESULT_CHARS) return s;
        int drop = s.length() - MAX_TOOL_RESULT_CHARS;
        return "[truncated, " + drop + " chars dropped from the head]…"
                + s.substring(s.length() - MAX_TOOL_RESULT_CHARS);
    }

    /**
     * Build an assistant message that carries both the visible text and the
     * structured {@code tool_calls} array. OpenAI requires tool_call_id on
     * the follow-up role=tool messages, and the LLM can only know which id to
     * echo if the assistant turn is reconstructed faithfully.
     */
    private static ChatMessage assistantToolCallMessage(LlmResponse resp) {
        com.google.gson.JsonArray tcs = new com.google.gson.JsonArray();
        for (ToolInvocation inv : resp.toolCalls()) {
            com.google.gson.JsonObject tc = new com.google.gson.JsonObject();
            tc.addProperty("id", inv.id() == null ? "" : inv.id());
            tc.addProperty("type", "function");
            com.google.gson.JsonObject fn = new com.google.gson.JsonObject();
            fn.addProperty("name", inv.name() == null ? "" : inv.name());
            // arguments MUST be a JSON-encoded string, not a nested object.
            fn.addProperty("arguments", GSON.toJson(inv.arguments()));
            tc.add("function", fn);
            tcs.add(tc);
        }
        return ChatMessage.assistantWithToolCalls(resp.text(), GSON.toJson(tcs));
    }
}

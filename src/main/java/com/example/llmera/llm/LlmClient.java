package com.example.llmera.llm;

import com.example.llmera.LLMEra;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * OpenAI-compatible chat completions client.
 *
 * <p>Endpoint contract: {@code POST {endpoint}/chat/completions}, with
 * {@code Authorization: Bearer {apiKey}} and a JSON body of shape
 * {@code { model, messages, tools, tool_choice }}. Tool calls are extracted
 * from {@code choices[0].message.tool_calls[]} (OpenAI style) — other
 * OpenAI-compatible vendors (DeepSeek, Zhipu, etc.) honour the same field.
 */
public final class LlmClient {
    private static final Gson GSON = new Gson();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * @param endpoint    e.g. "https://api.openai.com/v1" (no trailing slash).
     * @param model       e.g. "gpt-4o-mini".
     * @param apiKey      Bearer token. Will not be logged.
     * @param messages    full conversation history.
     * @param tools          tool schemas to advertise (may be empty for pure chat).
     * @param timeoutSec     HTTP timeout.
     * @param thinkingBudget  budget_tokens hint for thinking-capable models
     *                        (Anthropic / OpenRouter-style {@code reasoning.max_tokens});
     *                        0 = omit the field, let the provider decide.
     */
    public CompletableFuture<LlmResponse> call(String endpoint, String model, String apiKey,
                                               List<ChatMessage> messages, List<ToolDefinition> tools,
                                               int timeoutSec, int thinkingBudget) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String body = renderRequest(model, messages, tools, thinkingBudget);
                String url = stripTrailingSlash(endpoint) + "/chat/completions";
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(timeoutSec))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    // Dump the full response body at WARN for 4xx/5xx
                    // (especially 401/403/400 which are configuration
                    // errors and the body almost always contains the
                    // provider's diagnostic message). 4xx is logged
                    // at WARN (config error, not transient); 5xx at
                    // ERROR (server side).
                    String fullBody = resp.body() == null ? "" : resp.body();
                    if (resp.statusCode() < 500) {
                        LLMEra.LOGGER.warn(
                                "[{}] LLM HTTP {} (client error) — full body: {}",
                                LLMEra.MODID, resp.statusCode(), fullBody);
                    } else {
                        LLMEra.LOGGER.error(
                                "[{}] LLM HTTP {} (server error) — full body: {}",
                                LLMEra.MODID, resp.statusCode(), fullBody);
                    }
                    // Surface a useful hint in the thrown exception
                    // too: 401 specifically is almost always a key
                    // issue (wrong key, wrong header format, wrong
                    // provider base URL — e.g. OpenAI key sent to
                    // Anthropic base).
                    String hint = switch (resp.statusCode()) {
                        case 401 -> " (likely: invalid/expired api key, or key does not match provider at this endpoint)";
                        case 403 -> " (likely: key lacks scope/permission, or account is restricted)";
                        case 404 -> " (likely: wrong base URL — check endpoint, e.g. OpenRouter vs OpenAI vs Anthropic)";
                        case 429 -> " (rate limited — wait or use a different key)";
                        default -> "";
                    };
                    throw new RuntimeException("LLM HTTP " + resp.statusCode() + hint + ": "
                            + truncate(fullBody, 500));
                }
                return parseResponse(resp.body());
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                LLMEra.LOGGER.error("[{}] LLM call exception: {}", LLMEra.MODID, e.toString(), e);
                throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Streaming variant. Same contract as {@link #call} but sends
     * {@code stream: true} and consumes the response as Server-Sent Events.
     * Each SSE {@code data:} line is a JSON object whose
     * {@code choices[0].delta} carries incremental content / reasoning /
     * tool_calls fragments. We accumulate them locally and call
     * {@code onDelta} for every meaningful update so the server log shows
     * streaming progress in real time. The returned {@link LlmResponse}
     * is the same shape {@link #call} produces once the stream terminates
     * with the {@code [DONE]} sentinel.
     */
    public CompletableFuture<LlmResponse> stream(String endpoint, String model, String apiKey,
                                                List<ChatMessage> messages, List<ToolDefinition> tools,
                                                int timeoutSec, int thinkingBudget,
                                                StreamListener onDelta) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String body = renderStreamRequest(model, messages, tools, thinkingBudget);
                String url = stripTrailingSlash(endpoint) + "/chat/completions";
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(timeoutSec))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                // Stream requests can fail with a 4xx/5xx before the
                // first event — we want the body in that case (it
                // carries the provider's diagnostic). Ask for a
                // String body up front; if the response is 2xx, the
                // body is just a series of "data: ...\n" lines we
                // split on newlines anyway. Asking for ofLines()
                // makes the error path unreadable.
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    String fullBody = resp.body() == null ? "" : resp.body();
                    if (resp.statusCode() < 500) {
                        LLMEra.LOGGER.warn(
                                "[{}] LLM stream HTTP {} (client error) — full body: {}",
                                LLMEra.MODID, resp.statusCode(), fullBody);
                    } else {
                        LLMEra.LOGGER.error(
                                "[{}] LLM stream HTTP {} (server error) — full body: {}",
                                LLMEra.MODID, resp.statusCode(), fullBody);
                    }
                    String hint = switch (resp.statusCode()) {
                        case 401 -> " (likely: invalid/expired api key, or key does not match provider at this endpoint)";
                        case 403 -> " (likely: key lacks scope/permission, or account is restricted)";
                        case 404 -> " (likely: wrong base URL — check endpoint, e.g. OpenRouter vs OpenAI vs Anthropic)";
                        case 429 -> " (rate limited — wait or use a different key)";
                        default -> "";
                    };
                    throw new RuntimeException("LLM HTTP " + resp.statusCode() + hint + ": "
                            + truncate(fullBody, 500));
                }
                // Now process the SSE body line by line.
                java.util.List<String> lines = resp.body() == null
                        ? java.util.Collections.emptyList()
                        : java.util.Arrays.asList(resp.body().split("\\r?\\n"));
                java.util.Iterator<String> it = lines.iterator();
                // OpenAI sends chunks like:
                //   data: {"id":"...","choices":[{"delta":{"content":"hello"}}]}
                //   data: [DONE]
                StringBuilder contentBuf = new StringBuilder();
                StringBuilder reasoningBuf = new StringBuilder();
                // tool_calls may arrive over multiple chunks; we accumulate
                // {id, name, arguments} per index and merge at the end.
                java.util.Map<Integer, ToolCallAccum> tcAccum = new java.util.HashMap<>();
                while (it.hasNext()) {
                    String line = it.next();
                    if (line == null || line.isEmpty()) continue;
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) break;
                    JsonObject chunk;
                    try {
                        chunk = JsonParser.parseString(data).getAsJsonObject();
                    } catch (Exception e) {
                        continue; // ignore non-JSON heartbeats
                    }
                    if (!chunk.has("choices")) continue;
                    JsonArray choices = chunk.getAsJsonArray("choices");
                    if (choices.isEmpty()) continue;
                    JsonObject first = choices.get(0).getAsJsonObject();
                    if (!first.has("delta")) continue;
                    JsonObject delta = first.getAsJsonObject("delta");
                    String content = optString(delta, "content");
                    if (content != null && !content.isEmpty()) {
                        contentBuf.append(content);
                        if (onDelta != null) onDelta.onContent(content);
                    }
                    String reasoning = optString(delta, "reasoning_content");
                    if (reasoning == null) reasoning = optString(delta, "reasoning");
                    if (reasoning == null) reasoning = optString(delta, "thinking");
                    if (reasoning != null && !reasoning.isEmpty()) {
                        reasoningBuf.append(reasoning);
                        if (onDelta != null) onDelta.onReasoning(reasoning);
                    }
                    if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
                        for (JsonElement el : delta.getAsJsonArray("tool_calls")) {
                            JsonObject tc = el.getAsJsonObject();
                            int idx = tc.has("index") ? tc.get("index").getAsInt() : 0;
                            ToolCallAccum acc = tcAccum.computeIfAbsent(idx, i -> new ToolCallAccum());
                            if (tc.has("id") && !tc.get("id").isJsonNull()) {
                                acc.id = tc.get("id").getAsString();
                            }
                            if (tc.has("type") && !tc.get("type").isJsonNull()) {
                                acc.type = tc.get("type").getAsString();
                            }
                            if (tc.has("function") && tc.get("function").isJsonObject()) {
                                JsonObject fn = tc.getAsJsonObject("function");
                                if (fn.has("name") && !fn.get("name").isJsonNull()) {
                                    // name is streamed in fragments, like
                                    // arguments. Some providers send "pl" then
                                    // "ace_many" (appended), others send
                                    // "place" then "place_many" (full late).
                                    // Handle both: dedupe by prefix overlap.
                                    String incoming = fn.get("name").getAsString();
                                    String have = acc.nameBuf.toString();
                                    if (have.isEmpty()) {
                                        acc.nameBuf.append(incoming);
                                    } else if (incoming.length() <= have.length()) {
                                        // provider is sending partials — only
                                        // accept a fragment if it's a suffix
                                        // of what we already have (dedupe a
                                        // repeated prefix), otherwise it's a
                                        // new piece to append.
                                        if (have.endsWith(incoming)) {
                                            // duplicate, skip
                                        } else {
                                            acc.nameBuf.append(incoming);
                                        }
                                    } else if (incoming.startsWith(have)) {
                                        // full replacement; the provider sent
                                        // the complete final name.
                                        acc.nameBuf.setLength(0);
                                        acc.nameBuf.append(incoming);
                                    } else {
                                        // longer and doesn't share a prefix —
                                        // this is a second fragment past the
                                        // first one we received.
                                        acc.nameBuf.append(incoming);
                                    }
                                }
                                if (fn.has("arguments") && !fn.get("arguments").isJsonNull()) {
                                    // arguments may arrive as a string ("{...partial...").
                                    // Concatenate; final JSON.parse will assemble.
                                    if (fn.get("arguments").isJsonPrimitive()) {
                                        acc.argsBuf.append(fn.get("arguments").getAsString());
                                    } else {
                                        acc.argsBuf.append(GSON.toJson(fn.get("arguments")));
                                    }
                                }
                            }
                        }
                    }
                }
                // Finalize accumulated tool calls.
                java.util.List<ToolInvocation> finalTools = new java.util.ArrayList<>();
                java.util.List<Integer> idxs = new java.util.ArrayList<>(tcAccum.keySet());
                java.util.Collections.sort(idxs);
                for (int idx : idxs) {
                    ToolCallAccum acc = tcAccum.get(idx);
                    java.util.Map<String, Object> args = new java.util.HashMap<>();
                    String raw = acc.argsBuf.toString().trim();
                    if (!raw.isEmpty()) {
                        try {
                            JsonElement el = JsonParser.parseString(raw);
                            if (el.isJsonObject()) {
                                args = new com.google.gson.Gson().fromJson(el, java.util.Map.class);
                            } else {
                                args.put("raw", raw);
                            }
                        } catch (Exception e) {
                            args.put("raw", raw);
                        }
                    }
                    finalTools.add(new ToolInvocation(acc.id == null ? "call-" + idx : acc.id,
                            acc.nameBuf.toString(), args));
                }
                return new LlmResponse(contentBuf.toString(), reasoningBuf.toString(), finalTools);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("LLM stream failed: " + e.getMessage(), e);
            }
        });
    }

    private static String optString(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        if (!o.get(key).isJsonPrimitive()) return null;
        return o.get(key).getAsString();
    }

    private static final class ToolCallAccum {
        String id;
        String type = "function";
        // name arrives as fragments across multiple chunks ("pl" + "ace_many"),
        // so we accumulate into a StringBuilder like arguments. Some providers
        // send the full final name on a single late chunk — we use
        // startsWith() in the stream parser to drop duplicate prefixes.
        final StringBuilder nameBuf = new StringBuilder();
        final StringBuilder argsBuf = new StringBuilder();
    }

    /** Per-delta callback for streaming responses. */
    public interface StreamListener {
        void onContent(String chunk);
        void onReasoning(String chunk);
    }

    /** Render JSON body without leaking the api key anywhere in plain text. */
    private String renderRequest(String model, List<ChatMessage> messages, List<ToolDefinition> tools, int thinkingBudget) {
        return renderRequestImpl(model, messages, tools, thinkingBudget, false);
    }

    private String renderStreamRequest(String model, List<ChatMessage> messages, List<ToolDefinition> tools, int thinkingBudget) {
        return renderRequestImpl(model, messages, tools, thinkingBudget, true);
    }

    private String renderRequestImpl(String model, List<ChatMessage> messages, List<ToolDefinition> tools,
                                    int thinkingBudget, boolean stream) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("stream", stream);
        JsonArray msgs = new JsonArray();
        for (ChatMessage m : messages) {
            JsonObject mo = new JsonObject();
            mo.addProperty("role", m.role());
            mo.addProperty("content", m.content() == null ? "" : m.content());
            if ("tool".equals(m.role()) && m.toolCallId() != null && !m.toolCallId().isEmpty()) {
                mo.addProperty("tool_call_id", m.toolCallId());
            }
            if ("assistant".equals(m.role()) && m.toolCallsJson() != null && !m.toolCallsJson().isEmpty()) {
                // Pass through the pre-serialized tool_calls array. OpenAI expects
                // arguments to be a string (JSON-encoded), not a nested object.
                mo.add("tool_calls", JsonParser.parseString(m.toolCallsJson()));
            }
            msgs.add(mo);
        }
        root.add("messages", msgs);
        // OpenRouter / Anthropic reasoning budget. The field is silently
        // ignored by providers that don't understand it (e.g. vanilla
        // OpenAI chat-completions), and serves as the max_tokens cap on
        // thinking-capable models (DeepSeek R1, Claude 3.7 thinking,
        // Gemini thinking, etc.). Some providers (Anthropic native) expect
        // it nested in a "thinking" object; OpenRouter accepts the flat
        // form. We send both for max compatibility.
        if (thinkingBudget > 0) {
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty("max_tokens", thinkingBudget);
            root.add("reasoning", reasoning);
            // Anthropic native shape; OpenRouter translates this on the fly.
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "enabled");
            thinking.addProperty("budget_tokens", thinkingBudget);
            root.add("thinking", thinking);
        }
        if (tools != null && !tools.isEmpty()) {
            JsonArray ts = new JsonArray();
            for (ToolDefinition t : tools) {
                JsonObject f = new JsonObject();
                f.addProperty("name", t.name());
                f.addProperty("description", t.description());
                JsonObject params = new JsonObject();
                params.addProperty("type", "object");
                JsonObject props = new JsonObject();
                if (t.parameters() != null) {
                    for (Map.Entry<String, Object> e : t.parameters().entrySet()) {
                        props.add(e.getKey(), GSON.toJsonTree(e.getValue()));
                    }
                }
                params.add("properties", props);
                f.add("parameters", params);
                JsonObject wrap = new JsonObject();
                wrap.addProperty("type", "function");
                wrap.add("function", f);
                ts.add(wrap);
            }
            root.add("tools", ts);
        }
        return GSON.toJson(root);
    }

    private LlmResponse parseResponse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return new LlmResponse("", "", List.of());
        }
        JsonObject first = choices.get(0).getAsJsonObject();
        JsonObject message = first.getAsJsonObject("message");
        if (message == null) {
            return new LlmResponse("", "", List.of());
        }
        String text = message.has("content") && !message.get("content").isJsonNull()
                ? message.get("content").getAsString() : "";
        // OpenAI doesn't ship a "reasoning" channel yet; the field name is
        // vendor-specific. We try the three that show up in practice:
        //   - "reasoning_content" (DeepSeek R1, OpenRouter R1 mirror)
        //   - "reasoning"          (OpenRouter standardized wrapper)
        //   - "thinking"           (Anthropic / Claude thought signatures)
        String reasoning = "";
        for (String key : new String[]{"reasoning_content", "reasoning", "thinking", "chain_of_thought"}) {
            if (message.has(key) && !message.get(key).isJsonNull() && message.get(key).isJsonPrimitive()) {
                reasoning = message.get(key).getAsString();
                if (!reasoning.isEmpty()) break;
            }
        }
        // Anthropic / OpenRouter sometimes ship a structured "thinking_blocks" array.
        if (reasoning.isEmpty() && message.has("thinking_blocks") && message.get("thinking_blocks").isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (var el : message.getAsJsonArray("thinking_blocks")) {
                if (el.isJsonObject() && el.getAsJsonObject().has("thinking")
                        && el.getAsJsonObject().get("thinking").isJsonPrimitive()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(el.getAsJsonObject().get("thinking").getAsString());
                }
            }
            reasoning = sb.toString();
        }
        if (reasoning.isEmpty() && message.has("reasoning_details") && message.get("reasoning_details").isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (var el : message.getAsJsonArray("reasoning_details")) {
                if (el.isJsonObject() && el.getAsJsonObject().has("text")
                        && el.getAsJsonObject().get("text").isJsonPrimitive()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(el.getAsJsonObject().get("text").getAsString());
                }
            }
            reasoning = sb.toString();
        }

        List<ToolInvocation> invocations = new ArrayList<>();
        if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
            for (JsonElement el : message.getAsJsonArray("tool_calls")) {
                JsonObject tc = el.getAsJsonObject();
                String id = tc.has("id") ? tc.get("id").getAsString() : "";
                JsonObject fn = tc.getAsJsonObject("function");
                String name = fn.has("name") ? fn.get("name").getAsString() : "";
                String argsRaw = fn.has("arguments") ? fn.get("arguments").getAsString() : "{}";
                Map<String, Object> args = new HashMap<>();
                try {
                    JsonElement parsed = JsonParser.parseString(argsRaw);
                    if (parsed.isJsonObject()) {
                        for (Map.Entry<String, JsonElement> e : parsed.getAsJsonObject().entrySet()) {
                            args.put(e.getKey(), e.getValue().getAsString());
                        }
                    }
                } catch (Exception ignored) { }
                invocations.add(new ToolInvocation(id, name, args));
            }
        }
        return new LlmResponse(text == null ? "" : text, reasoning, invocations);
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}

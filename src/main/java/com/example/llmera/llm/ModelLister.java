package com.example.llmera.llm;

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
import java.util.List;

/**
 * Fetches the model list from an OpenAI-compatible /v1/models endpoint and
 * parses out the {@code data[*].id} field. Pure dev-tool; not thread-safe.
 */
public final class ModelLister {
    private static final Gson GSON = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public String fetch(String endpoint, String apiKey, int timeoutSec) {
        try {
            String base = stripTrailingSlash(endpoint);
            // /v1/models is the canonical path. If the user already typed
            // something past /v1, don't double up.
            String url = base.endsWith("/models") ? base : base + "/models";
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .header("Accept", "application/json");
            if (apiKey != null && !apiKey.isBlank()) {
                b.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> resp = http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                return "ERR HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 160);
            }
            List<String> ids = new ArrayList<>();
            JsonElement root = JsonParser.parseString(resp.body());
            // OpenAI: {"data":[{"id":...},...]}; some vendors return just [...].
            if (root.isJsonObject() && root.getAsJsonObject().has("data")) {
                JsonArray arr = root.getAsJsonObject().getAsJsonArray("data");
                for (JsonElement el : arr) {
                    addIfId(ids, el);
                }
            } else if (root.isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray()) {
                    addIfId(ids, el);
                }
            } else {
                return "ERR unexpected JSON shape";
            }
            return ids.isEmpty() ? "ERR no model ids parsed" : String.join(", ", ids);
        } catch (Exception e) {
            return "ERR " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static void addIfId(List<String> ids, JsonElement el) {
        if (el == null || !el.isJsonObject()) return;
        JsonObject o = el.getAsJsonObject();
        if (o.has("id") && !o.get("id").isJsonNull()) {
            ids.add(o.get("id").getAsString());
        } else if (o.has("name") && !o.get("name").isJsonNull()) {
            ids.add(o.get("name").getAsString());
        }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}

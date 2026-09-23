package com.lawlessmc.zombiepig;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

public final class GrokClient {

    public record Result(String source, String text, double remainingPercent, String reason) {
        boolean grok() {
            return "grok".equals(source) && text != null && !text.isBlank();
        }
    }

    private final ZombiePigPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    GrokClient(ZombiePigPlugin plugin) {
        this.plugin = plugin;
    }

    private String base() {
        String url = plugin.getConfig().getString("sidecar-url", "http://127.0.0.1:18787");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public Result credits() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/credits"))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            double remaining = json.has("remaining_percent") ? json.get("remaining_percent").getAsDouble() : -1;
            boolean canned = json.has("canned") && json.get("canned").getAsBoolean();
            String reason = canned ? "credits_low" : "ok";
            if (!json.has("ok") || !json.get("ok").getAsBoolean()) {
                return new Result("canned_gate", "", remaining, "credits_unavailable");
            }
            return new Result(canned ? "canned_gate" : "ok", "", remaining, reason);
        } catch (Exception e) {
            return new Result("canned_gate", "", -1, "sidecar_down");
        }
    }

    Result chat(String system, List<String[]> messages) {
        try {
            JsonArray arr = new JsonArray();
            for (String[] m : messages) {
                JsonObject o = new JsonObject();
                o.addProperty("role", m[0]);
                o.addProperty("content", m[1]);
                arr.add(o);
            }
            JsonObject body = new JsonObject();
            body.addProperty("system", system);
            body.add("messages", arr);
            body.addProperty("max_tokens", 120);
            HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/chat"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            String source = json.has("source") ? json.get("source").getAsString() : "canned_gate";
            String text = json.has("text") && !json.get("text").isJsonNull() ? json.get("text").getAsString() : "";
            double remaining = json.has("remaining_percent") ? json.get("remaining_percent").getAsDouble() : -1;
            String reason = json.has("reason") ? json.get("reason").getAsString() : source;
            return new Result(source, text, remaining, reason);
        } catch (Exception e) {
            return new Result("canned_gate", "", -1, "sidecar_down");
        }
    }
}

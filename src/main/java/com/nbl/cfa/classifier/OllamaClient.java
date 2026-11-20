package com.nbl.cfa.classifier;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gọi Ollama /api/chat với structured output (format = JSON schema).
 */
@Component
public class OllamaClient {

    private final String profile;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final JsonMapper jsonMapper;
    private final HttpClient http = HttpClient.newHttpClient();

    public OllamaClient(@Value("${cfa.llm.profile:local}") String profile,
                        @Value("${cfa.llm.base-url:http://localhost:11434}") String baseUrl,
                        @Value("${cfa.llm.api-key:}") String apiKey,
                        @Value("${cfa.llm.model:qwen3:4b}") String model,
                        @Value("${cfa.llm.timeout-seconds:300}") long timeoutSeconds,
                        JsonMapper jsonMapper) {
        if (!profile.equals("local") && !profile.equals("cloud")) {
            throw new IllegalArgumentException("cfa.llm.profile must be local or cloud");
        }
        this.profile = profile;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.jsonMapper = jsonMapper;
    }

    public String model() {
        return model;
    }

    public String profile() {
        return profile;
    }

    /** Trả về chuỗi JSON do mô hình sinh ra (đã bị ép theo schema). */
    public String chatJson(String systemPrompt, String userPrompt, Map<String, Object> schema) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        if (profile.equals("local")) {
            body.put("think", false);
            body.put("format", schema);
            body.put("options", Map.of("temperature", 0, "num_ctx", 8192));
        } else {
            body.put("temperature", 0);
            body.put("response_format", Map.of("type", "json_object"));
        }

        String endpoint = profile.equals("local") ? "/api/chat" : "/chat/completions";
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(baseUrl + endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)));
        if (!apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response;
        try {
            response = http.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Không gọi được LLM profile " + profile + " tại " + baseUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Bị ngắt khi gọi Ollama", e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("LLM profile " + profile + " trả về HTTP " + response.statusCode()
                    + ": " + response.body());
        }

        String content;
        if (profile.equals("local")) {
            ChatResponse parsed = jsonMapper.readValue(response.body(), ChatResponse.class);
            content = parsed.message() == null ? null : parsed.message().content();
        } else {
            Map<?, ?> parsed = jsonMapper.readValue(response.body(), Map.class);
            List<?> choices = parsed.get("choices") instanceof List<?> list ? list : List.of();
            Object first = choices.isEmpty() ? null : choices.get(0);
            Object message = first instanceof Map<?, ?> map ? map.get("message") : null;
            content = message instanceof Map<?, ?> map && map.get("content") instanceof String text ? text : null;
        }
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("LLM profile " + profile + " trả về nội dung rỗng");
        }
        return content;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatResponse(Message message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Message(String content) {
    }
}

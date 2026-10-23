package com.wardrobe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class OllamaClient {
    private final ObjectMapper mapper;
    private final URI base;
    private final HttpClient http;

    public OllamaClient(ObjectMapper mapper, WardrobeProperties properties) {
        this.mapper = mapper;
        String url = properties.ollamaBaseUrl().replaceAll("/+$", "");
        base = URI.create(url + "/");
        if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("OLLAMA_BASE_URL must be an HTTP(S) URL without credentials, query, or fragment");
        }
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public Set<String> models() {
        HttpRequest request = HttpRequest.newBuilder(base.resolve("api/tags"))
                .timeout(Duration.ofSeconds(3)).GET().build();
        JsonNode response = send(request, false);
        JsonNode models = response.get("models");
        if (models == null || !models.isArray()) { throw ApiException.unavailable(); }
        Set<String> names = new HashSet<>();
        for (JsonNode model : models) {
            if (model.path("name").isTextual()) { names.add(model.get("name").textValue()); }
            if (model.path("model").isTextual()) { names.add(model.get("model").textValue()); }
        }
        return names;
    }

    public JsonNode chat(String model, String instructions, String data, List<String> images) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", data);
        if (!images.isEmpty()) { user.put("images", images); }
        Map<String, Object> body = Map.of("model", model, "stream", false, "format", "json",
                "messages", List.of(Map.of("role", "system", "content", instructions), user),
                "options", Map.of("temperature", 0.2, "num_predict", 2000));
        String json;
        try { json = mapper.writeValueAsString(body); }
        catch (JsonProcessingException error) { throw ApiException.invalidAi(); }
        HttpRequest request = HttpRequest.newBuilder(base.resolve("api/chat"))
                .timeout(Duration.ofSeconds(120)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        JsonNode response = send(request, true);
        JsonNode content = response.path("message").path("content");
        if (!content.isTextual() || content.textValue().isBlank() || content.textValue().length() > 100_000) {
            throw ApiException.invalidAi();
        }
        try {
            return mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(content.textValue());
        } catch (IOException error) { throw ApiException.invalidAi(); }
    }

    private JsonNode send(HttpRequest request, boolean ai) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 404 || code == 408 || code == 429 || code >= 500) { throw ApiException.unavailable(); }
            if (code < 200 || code >= 300) {
                throw ai ? ApiException.invalidAi() : ApiException.unavailable();
            }
            if (response.body().length() > 1_000_000) {
                throw ai ? ApiException.invalidAi() : ApiException.unavailable();
            }
            JsonNode parsed = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(response.body());
            if (parsed == null || !parsed.isObject()) {
                throw ai ? ApiException.invalidAi() : ApiException.unavailable();
            }
            return parsed;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw ApiException.unavailable();
        } catch (JsonProcessingException error) {
            throw ai ? ApiException.invalidAi() : ApiException.unavailable();
        } catch (IOException error) { throw ApiException.unavailable(); }
    }
}
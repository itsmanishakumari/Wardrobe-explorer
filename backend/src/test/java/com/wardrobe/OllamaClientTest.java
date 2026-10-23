package com.wardrobe;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;

class OllamaClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private OllamaClient client;
    private int responseCode = 200;
    private String response = "{}";
    private final AtomicReference<JsonNode> request = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", exchange -> {
            try (exchange) {
                byte[] incoming = exchange.getRequestBody().readAllBytes();
                if (incoming.length > 0) { request.set(mapper.readTree(incoming)); }
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseCode, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        client = new OllamaClient(mapper, new WardrobeProperties(Path.of("unused"),
                "http://127.0.0.1:" + server.getAddress().getPort(), "vision", "text"));
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void readsTagsAndSerializesNonStreamingJsonVisionRequest() throws Exception {
        response = "{\"models\":[{\"name\":\"vision:latest\"},{\"model\":\"text:4b\"}]}";
        assertThat(client.models()).containsExactlyInAnyOrder("vision:latest", "text:4b");
        response = mapper.writeValueAsString(Map.of("message", Map.of("content", "{\"name\":\"shirt\"}")));
        assertThat(client.chat("vision", "system instructions", "photo data", List.of("YWJj")).get("name").asText())
                .isEqualTo("shirt");
        JsonNode sent = request.get();
        assertThat(sent.get("model").asText()).isEqualTo("vision");
        assertThat(sent.get("stream").asBoolean()).isFalse();
        assertThat(sent.get("format").asText()).isEqualTo("json");
        assertThat(sent.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(sent.path("messages").get(1).path("images").get(0).asText()).isEqualTo("YWJj");
        assertThat(sent.has("tools")).isFalse();
    }

    @Test
    void malformedEnvelopesAndGeneratedJsonAreBadGateway() throws Exception {
        for (String body : List.of("not JSON", "null", "[]", "{}", "{\"message\":{\"content\":5}}",
                "{\"message\":{\"content\":\"{invalid}\"}}",
                mapper.writeValueAsString(Map.of("message", Map.of("content", "{} {}"))),
                "{\"message\":{\"content\":\"\"}}")) {
            response = body;
            assertThatThrownBy(() -> client.chat("text", "system", "data", List.of()))
                    .isInstanceOf(ApiException.class)
                    .extracting(error -> ((ApiException) error).status()).isEqualTo(HttpStatus.BAD_GATEWAY);
        }
    }

    @Test
    void httpFailuresAndConnectionRefusedAreUnavailableWithoutInternalDetails() {
        for (int code : List.of(404, 429, 500, 503)) {
            responseCode = code;
            response = "secret backend error";
            assertThatThrownBy(() -> client.chat("text", "system", "data", List.of()))
                    .isInstanceOf(ApiException.class)
                    .satisfies(error -> {
                        assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(error.getMessage()).doesNotContain("secret");
                    });
        }
        server.stop(0);
        assertThatThrownBy(client::models).isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void malformedTagsReturnUnavailable() {
        for (String body : List.of("garbage", "{}", "{\"models\":false}")) {
            response = body;
            assertThatThrownBy(client::models).isInstanceOf(ApiException.class)
                    .extracting(error -> ((ApiException) error).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
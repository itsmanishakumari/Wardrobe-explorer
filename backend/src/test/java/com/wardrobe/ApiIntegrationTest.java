package com.wardrobe;

import com.fasterxml.jackson.databind.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:wardrobe-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class ApiIntegrationTest {
    static final Path IMAGES;
    static {
        try { IMAGES = Files.createTempDirectory("wardrobe-api-test-"); }
        catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("wardrobe.images-directory", IMAGES::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired GarmentRepository repository;
    @Autowired ImageStorage storage;
    @MockitoBean OllamaClient ollama;

    @BeforeEach
    void clear() { repository.deleteAll(); reset(ollama); }

    @AfterAll
    static void cleanup() throws Exception {
        try (var files = Files.list(IMAGES)) {
            for (Path path : files.toList()) { Files.deleteIfExists(path); }
        }
        Files.deleteIfExists(IMAGES);
    }

    @Test
    void crudPersistsAndIgnoresReadOnlyFields() throws Exception {
        JsonNode created = create("""
                {"name":" Blue shirt ","category":"TOP","colors":"blue","notes":"cotton",
                 "id":999,"imageUrl":"https://untrusted.invalid","createdAt":"invalid"}
                """);
        long id = created.get("id").asLong();
        assertThat(id).isNotEqualTo(999);
        assertThat(created.get("createdAt").asText()).isNotBlank();
        assertThat(created.get("imageId").isNull()).isTrue();
        assertThat(created.get("imageUrl").isNull()).isTrue();
        assertThat(repository.findById(id).orElseThrow().name).isEqualTo("Blue shirt");
        mvc.perform(get("/api/garments")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id)).andExpect(jsonPath("$[0].pattern").value(""));
        mvc.perform(put("/api/garments/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dress\",\"category\":\"ONE_PIECE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.category").value("ONE_PIECE"))
                .andExpect(jsonPath("$.createdAt").value(created.get("createdAt").asText()));
        assertThat(repository.findById(id).orElseThrow().category).isEqualTo(Category.ONE_PIECE);
        mvc.perform(delete("/api/garments/{id}", id)).andExpect(status().isNoContent());
        assertThat(repository.findById(id)).isEmpty();
        mvc.perform(get("/api/garments")).andExpect(content().json("[]"));
    }

    @Test
    void validatesWritableFieldsAndMissingIds() throws Exception {
        for (String json : List.of("{}", "{\"name\":\" \",\"category\":\"TOP\"}",
                "{\"name\":\"x\",\"category\":\"ALIEN\"}",
                mapper.writeValueAsString(Map.of("name", "x".repeat(121), "category", "TOP")),
                mapper.writeValueAsString(Map.of("name", "x", "category", "TOP", "notes", "x".repeat(2001))),
                "{\"name\":\"x\",\"category\":\"TOP\",\"imageId\":\"../secret.png\"}")) {
            mvc.perform(post("/api/garments").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
        }
        mvc.perform(put("/api/garments/999999").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"category\":\"TOP\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/garments/999999")).andExpect(status().isNotFound());
        mvc.perform(post("/api/garments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"category\":\"TOP\",\"imageId\":\"" + UUID.randomUUID() + ".png\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/garments").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
    }

    @Test
    void uploadsReencodesStreamsAndRetainsSharedPhotos() throws Exception {
        byte[] photo = photo("png");
        byte[] withPayload = Arrays.copyOf(photo, photo.length + 20);
        Arrays.fill(withPayload, photo.length, withPayload.length, (byte) 'X');
        JsonNode uploaded = upload(withPayload, "../../photo.html", "text/html");
        String id = uploaded.get("imageId").asText();
        assertThat(id).matches(ImageStorage.IMAGE_ID_PATTERN);
        assertThat(Files.readAllBytes(storage.require(id))).isNotEqualTo(withPayload);
        mvc.perform(get(uploaded.get("imageUrl").asText())).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        String input = mapper.writeValueAsString(Map.of("name", "Shared", "category", "TOP", "imageId", id));
        long first = create(input).get("id").asLong();
        long second = create(input).get("id").asLong();
        mvc.perform(delete("/api/garments/{id}", first)).andExpect(status().isNoContent());
        mvc.perform(get(uploaded.get("imageUrl").asText())).andExpect(status().isOk());
        assertThat(repository.findById(second).orElseThrow().imageId).isEqualTo(id);
        JsonNode jpeg = upload(photo("jpg"), "photo.jpg", "image/jpeg");
        mvc.perform(get(jpeg.get("imageUrl").asText())).andExpect(content().contentType(MediaType.IMAGE_JPEG));
    }

    @Test
    void rejectsUnsafeMissingInvalidAndOversizedImages() throws Exception {
        mvc.perform(get("/api/images/not-a-uuid.png")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/images/" + UUID.randomUUID() + ".png")).andExpect(status().isNotFound());
        mvc.perform(get("/api/images/..%2Fsecret.png")).andExpect(status().isBadRequest());
        for (byte[] bytes : List.of(new byte[0], "not an image".getBytes(), photo("gif"))) {
            mvc.perform(multipart("/api/images").file(new MockMultipartFile("file", "fake.png", "image/png", bytes)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
        }
        mvc.perform(multipart("/api/images").file(new MockMultipartFile("file", "big.jpg", "image/jpeg",
                        new byte[(int) ImageStorage.MAX_BYTES + 1])))
                .andExpect(status().isPayloadTooLarge());
        var resolver = new ApiExceptionHandler();
        assertThat(resolver.oversized().getStatusCode().value()).isEqualTo(413);
        Path symlink = IMAGES.resolve(UUID.randomUUID() + ".png");
        Files.createSymbolicLink(symlink, Path.of("/etc/hosts"));
        mvc.perform(get("/api/images/" + symlink.getFileName())).andExpect(status().isNotFound());
    }

        @Test
        void rejectsCompressedPixelBombBeforeRasterDecode() throws Exception {
                byte[] png = photo("png");
                java.nio.ByteBuffer.wrap(png).putInt(16, 5000).putInt(20, 5000);
                var crc = new java.util.zip.CRC32();
                crc.update(png, 12, 17);
                java.nio.ByteBuffer.wrap(png).putInt(29, (int) crc.getValue());
                mvc.perform(multipart("/api/images").file(new MockMultipartFile("file", "bomb.png", "image/png", png)))
                                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Image exceeds the 20 million pixel limit."));
        }

    @Test
    void statusIsUsableWhenOfflineOrModelsMissing() throws Exception {
        when(ollama.models()).thenThrow(ApiException.unavailable());
        mvc.perform(get("/api/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ollamaAvailable").value(false))
                .andExpect(jsonPath("$.visionModelReady").value(false))
                .andExpect(jsonPath("$.textModelReady").value(false))
                .andExpect(jsonPath("$.visionModel").value("gemma3:4b"));
        doReturn(Set.of()).when(ollama).models();
        mvc.perform(get("/api/status")).andExpect(jsonPath("$.ollamaAvailable").value(true))
                .andExpect(jsonPath("$.visionModelReady").value(false));
        when(ollama.models()).thenReturn(Set.of("gemma3:4b"));
        mvc.perform(get("/api/status")).andExpect(jsonPath("$.visionModelReady").value(true))
                .andExpect(jsonPath("$.textModelReady").value(true));
    }

    @Test
    void analysisDraftDoesNotPersistAndRejectsBadAi() throws Exception {
        String id = upload(photo("png"), "photo.png", "image/png").get("imageId").asText();
        String request = mapper.writeValueAsString(Map.of("imageId", id));
        JsonNode draft = mapper.readTree("""
                {"name":"Shirt","category":"TOP","colors":"blue","pattern":"solid",
                 "sleeves":"short","season":"summer","style":"casual","notes":"Review fit","id":999}
                """);
        when(ollama.chat(anyString(), anyString(), anyString(), anyList())).thenReturn(draft);
        mvc.perform(post("/api/analysis").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.draft.name").value("Shirt"))
                .andExpect(jsonPath("$.draft.id").doesNotExist()).andExpect(jsonPath("$.message").isString());
        assertThat(repository.count()).isZero();
        verify(ollama).chat(eq("gemma3:4b"), contains("untrusted"), anyString(), argThat(images -> images.size() == 1));
        ((com.fasterxml.jackson.databind.node.ObjectNode) draft).put("category", "UNKNOWN");
        mvc.perform(post("/api/analysis").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.message").isString());
        when(ollama.chat(anyString(), anyString(), anyString(), anyList())).thenThrow(ApiException.unavailable());
        mvc.perform(post("/api/analysis").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/analysis").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageId\":\"" + UUID.randomUUID() + ".png\"}"))
                .andExpect(status().isNotFound());
        assertThat(repository.count()).isZero();
    }

    @Test
    void incompleteInventoryNeverCallsAiAndValidatesOutfitRequests() throws Exception {
        String request = "{\"occasion\":\"office\",\"count\":3}";
        mvc.perform(post("/api/outfits").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outfits").isEmpty());
        create("{\"name\":\"Top\",\"category\":\"TOP\"}");
        mvc.perform(post("/api/outfits").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outfits").isEmpty());
        verifyNoInteractions(ollama);
        for (String json : List.of("{}", "{\"occasion\":\"x\",\"count\":0}",
                "{\"occasion\":\"x\",\"count\":4}", "{\"occasion\":\" \",\"count\":1}",
                mapper.writeValueAsString(Map.of("occasion", "x".repeat(201), "count", 1)))) {
            mvc.perform(post("/api/outfits").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void completeWardrobeAcceptsFewerOutfitsRejectsHallucinationsAndHandlesOffline() throws Exception {
        long id = create("{\"name\":\"Dress\",\"category\":\"ONE_PIECE\"}").get("id").asLong();
        when(ollama.chat(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(mapper.readTree("{\"outfits\":[{\"title\":\"Easy\",\"explanation\":\"Your dress\",\"garmentIds\":[" + id + "]}]}"));
        mvc.perform(post("/api/outfits").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"occasion\":\"dinner\",\"count\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outfits.length()").value(1))
                .andExpect(jsonPath("$.outfits[0].garmentIds[0]").value(id));
        when(ollama.chat(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(mapper.readTree("{\"outfits\":[{\"title\":\"Fake\",\"explanation\":\"Fake\",\"garmentIds\":[999999]}]}"));
        mvc.perform(post("/api/outfits").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"occasion\":\"dinner\",\"count\":1}"))
                .andExpect(status().isBadGateway());
        when(ollama.chat(anyString(), anyString(), anyString(), anyList())).thenThrow(ApiException.unavailable());
        mvc.perform(post("/api/outfits").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"occasion\":\"dinner\",\"count\":1}"))
                .andExpect(status().isServiceUnavailable());
    }

    private JsonNode create(String input) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/garments").contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode upload(byte[] bytes, String name, String type) throws Exception {
        return mapper.readTree(mvc.perform(multipart("/api/images")
                        .file(new MockMultipartFile("file", name, type, bytes)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    static byte[] photo(String format) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), format, output);
        return output.toByteArray();
    }
}
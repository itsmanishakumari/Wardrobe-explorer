package com.wardrobe;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class ApiModels {
    private ApiModels() { }

    public record GarmentInput(
            @NotBlank @Size(max = 120) String name,
            @NotNull Category category,
            @Size(max = 200) String colors,
            @Size(max = 120) String pattern,
            @Size(max = 120) String sleeves,
            @Size(max = 120) String season,
            @Size(max = 120) String style,
            @Size(max = 2000) String notes,
            @Pattern(regexp = ImageStorage.IMAGE_ID_PATTERN) String imageId) { }

    public record GarmentView(long id, String name, Category category, String colors,
                              String pattern, String sleeves, String season, String style,
                              String notes, String imageId, String imageUrl, Instant createdAt) {
        static GarmentView from(Garment item) {
            return new GarmentView(item.id, item.name, item.category, item.colors, item.pattern,
                    item.sleeves, item.season, item.style, item.notes, item.imageId,
                    ImageStorage.url(item.imageId), item.createdAt);
        }
    }

    public record ImageView(String imageId, String imageUrl) { }

    public record AnalysisInput(
            @NotNull @Pattern(regexp = ImageStorage.IMAGE_ID_PATTERN) String imageId) { }

    public record Draft(String name, Category category, String colors, String pattern,
                        String sleeves, String season, String style, String notes) { }

    public record AnalysisView(Draft draft, String message) { }

    public record OutfitInput(@NotBlank @Size(max = 200) String occasion,
                              @NotNull @Min(1) @Max(3) Integer count) { }

    public record Outfit(String title, String explanation, List<Long> garmentIds) { }

    public record OutfitsView(List<Outfit> outfits, String message) { }

    public record StatusView(boolean ollamaAvailable, String visionModel, String textModel,
                             boolean visionModelReady, boolean textModelReady, String message) { }

    public record ErrorView(String message) { }
}
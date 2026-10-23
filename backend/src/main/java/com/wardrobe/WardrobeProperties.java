package com.wardrobe;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wardrobe")
public record WardrobeProperties(Path imagesDirectory, String ollamaBaseUrl,
                                 String visionModel, String textModel) {
}
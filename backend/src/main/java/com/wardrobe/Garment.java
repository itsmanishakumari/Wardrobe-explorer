package com.wardrobe;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "garments", indexes = @Index(name = "idx_garment_category", columnList = "category"))
public class Garment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(nullable = false, length = 120)
    String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    Category category;
    @Column(nullable = false, length = 200)
    String colors;
    @Column(nullable = false, length = 120)
    String pattern;
    @Column(nullable = false, length = 120)
    String sleeves;
    @Column(nullable = false, length = 120)
    String season;
    @Column(nullable = false, length = 120)
    String style;
    @Column(nullable = false, length = 2000)
    String notes;
    @Column(length = 40)
    String imageId;
    @Column(nullable = false, updatable = false)
    Instant createdAt;

    protected Garment() { }

    @PrePersist
    void timestamp() {
        createdAt = Instant.now();
    }

    void update(ApiModels.GarmentInput input) {
        name = input.name().trim();
        category = input.category();
        colors = value(input.colors());
        pattern = value(input.pattern());
        sleeves = value(input.sleeves());
        season = value(input.season());
        style = value(input.style());
        notes = value(input.notes());
        imageId = input.imageId();
    }

    private static String value(String input) {
        return input == null ? "" : input.trim();
    }
}
package com.wardrobe;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.wardrobe.ApiModels.*;

@Component
public class AiOutputValidator {
    public Draft draft(JsonNode node) {
        if (node == null || !node.isObject()) { throw ApiException.invalidAi(); }
        Category category;
        try { category = Category.valueOf(text(node, "category", 20, true)); }
        catch (IllegalArgumentException error) { throw ApiException.invalidAi(); }
        return new Draft(text(node, "name", 120, true), category,
                text(node, "colors", 200, false), text(node, "pattern", 120, false),
                text(node, "sleeves", 120, false), text(node, "season", 120, false),
                text(node, "style", 120, false), text(node, "notes", 2000, false));
    }

    public List<Outfit> outfits(JsonNode node, Map<Long, Category> inventory, int count) {
        if (node == null || !node.isObject()) { throw ApiException.invalidAi(); }
        JsonNode outfits = node.get("outfits");
        if (outfits == null || !outfits.isArray() || outfits.isEmpty() || outfits.size() > count) {
            throw ApiException.invalidAi();
        }
        List<Outfit> result = new ArrayList<>();
        Set<Set<Long>> combinations = new HashSet<>();
        for (JsonNode outfit : outfits) {
            String title = text(outfit, "title", 120, true);
            String explanation = text(outfit, "explanation", 2000, true);
            JsonNode ids = outfit.get("garmentIds");
            if (ids == null || !ids.isArray() || ids.isEmpty() || ids.size() > 7) {
                throw ApiException.invalidAi();
            }
            List<Long> selected = new ArrayList<>();
            Set<Long> unique = new HashSet<>();
            Map<Category, Integer> categories = new EnumMap<>(Category.class);
            for (JsonNode id : ids) {
                if (!id.isIntegralNumber() || !id.canConvertToLong()) { throw ApiException.invalidAi(); }
                long value = id.longValue();
                Category category = inventory.get(value);
                if (category == null || !unique.add(value)) { throw ApiException.invalidAi(); }
                selected.add(value);
                categories.merge(category, 1, Integer::sum);
            }
            int onePiece = categories.getOrDefault(Category.ONE_PIECE, 0);
            int tops = categories.getOrDefault(Category.TOP, 0);
            int bottoms = categories.getOrDefault(Category.BOTTOM, 0);
            boolean complete = (onePiece == 1 && tops == 0 && bottoms == 0)
                    || (onePiece == 0 && tops == 1 && bottoms == 1);
            if (!complete || categories.getOrDefault(Category.SHOES, 0) > 1
                    || categories.getOrDefault(Category.LAYER, 0) > 1
                    || categories.getOrDefault(Category.ACCESSORY, 0) > 3
                    || !combinations.add(Set.copyOf(unique))) {
                throw ApiException.invalidAi();
            }
            result.add(new Outfit(title, explanation, List.copyOf(selected)));
        }
        return List.copyOf(result);
    }

    private String text(JsonNode node, String field, int limit, boolean required) {
        if (node == null || !node.isObject()) { throw ApiException.invalidAi(); }
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().length() > limit
                || (required && value.textValue().isBlank())) {
            throw ApiException.invalidAi();
        }
        return value.textValue().trim();
    }
}
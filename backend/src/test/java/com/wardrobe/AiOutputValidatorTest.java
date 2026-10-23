package com.wardrobe;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

class AiOutputValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AiOutputValidator validator = new AiOutputValidator();
    private final Map<Long, Category> inventory = Map.ofEntries(
            Map.entry(1L, Category.TOP), Map.entry(2L, Category.BOTTOM), Map.entry(3L, Category.ONE_PIECE),
            Map.entry(4L, Category.SHOES), Map.entry(5L, Category.SHOES),
            Map.entry(6L, Category.LAYER), Map.entry(7L, Category.LAYER),
            Map.entry(8L, Category.ACCESSORY), Map.entry(9L, Category.ACCESSORY),
            Map.entry(10L, Category.ACCESSORY), Map.entry(11L, Category.ACCESSORY),
            Map.entry(12L, Category.TOP), Map.entry(13L, Category.BOTTOM), Map.entry(14L, Category.ONE_PIECE));

    @Test
    void acceptsCompleteSetsWithOptionalItemsAndSharedItemsAcrossOutfits() throws Exception {
        var node = mapper.readTree("{\"outfits\":[" + outfit("1,2,4,6,8,9,10") + "," + outfit("3,4,8") + "]}");
        assertThat(validator.outfits(node, inventory, 3)).hasSize(2);
        assertThat(validator.outfits(mapper.readTree("{\"outfits\":[" + outfit("3") + "]}"), inventory, 1)).hasSize(1);
    }

    static Stream<String> invalidCombinations() {
        return Stream.of("999", "1", "2", "4,6", "1,2,3", "3,1", "3,2", "3,14",
                "1,12,2", "1,2,13", "1,1,2", "1,2,4,5", "3,6,7", "3,8,9,10,11",
                "", "\"1\",2", "1.0,2", "null,2", "true,2", "9223372036854775808");
    }

    @ParameterizedTest @MethodSource("invalidCombinations")
    void rejectsInvalidIdsAndIncompleteOrOverfullCombinations(String ids) throws Exception {
        var node = mapper.readTree("{\"outfits\":[" + outfit(ids) + "]}");
        assertThatThrownBy(() -> validator.outfits(node, inventory, 3))
                .isInstanceOf(ApiException.class).extracting(error -> ((ApiException) error).status().value()).isEqualTo(502);
    }

    @Test
    void rejectsDuplicateOutfitsTooManyMalformedAndUnboundedText() throws Exception {
        for (String json : List.of("null", "[]", "{}", "{\"outfits\":[]}",
                "{\"outfits\":[" + outfit("1,2") + "," + outfit("2,1") + "]}",
                "{\"outfits\":[{\"title\":\"\",\"explanation\":\"x\",\"garmentIds\":[3]}]}",
                "{\"outfits\":[{\"title\":\"" + "x".repeat(121) + "\",\"explanation\":\"x\",\"garmentIds\":[3]}]}",
                "{\"outfits\":[{\"title\":\"x\",\"explanation\":\"" + "x".repeat(2001) + "\",\"garmentIds\":[3]}]}",
                "{\"outfits\":[null]}", "{\"outfits\":[{\"title\":5}]}")) {
            var node = mapper.readTree(json);
            assertThatThrownBy(() -> validator.outfits(node, inventory, 3)).isInstanceOf(ApiException.class);
        }
        var tooMany = mapper.readTree("{\"outfits\":[" + outfit("1,2") + "," + outfit("3") + "]}");
        assertThatThrownBy(() -> validator.outfits(tooMany, inventory, 1)).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsMissingOrWrongTypeOrLongDraftFields() throws Exception {
        var draft = mapper.createObjectNode().put("name", "Dress").put("category", "ONE_PIECE")
                .put("colors", "blue").put("pattern", "").put("sleeves", "").put("season", "")
                .put("style", "").put("notes", "");
        assertThat(validator.draft(draft).category()).isEqualTo(Category.ONE_PIECE);
        for (String field : List.of("name", "category", "colors", "pattern", "sleeves", "season", "style", "notes")) {
            var missing = draft.deepCopy(); missing.remove(field);
            assertThatThrownBy(() -> validator.draft(missing)).isInstanceOf(ApiException.class);
            var wrong = draft.deepCopy().put(field, 7);
            assertThatThrownBy(() -> validator.draft(wrong)).isInstanceOf(ApiException.class);
            var longValue = draft.deepCopy().put(field, "x".repeat(2001));
            assertThatThrownBy(() -> validator.draft(longValue)).isInstanceOf(ApiException.class);
        }
    }

    private static String outfit(String ids) {
        return "{\"title\":\"Outfit\",\"explanation\":\"Uses your clothes\",\"garmentIds\":[" + ids + "]}";
    }
}
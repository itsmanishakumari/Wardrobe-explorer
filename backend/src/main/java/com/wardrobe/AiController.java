package com.wardrobe;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import static com.wardrobe.ApiModels.*;

@RestController
@RequestMapping("/api")
public class AiController {
    private final AiService ai;

    public AiController(AiService ai) { this.ai = ai; }

    @GetMapping("/status")
    public StatusView status() { return ai.status(); }

    @PostMapping("/analysis")
    public AnalysisView analyze(@Valid @RequestBody AnalysisInput input) { return ai.analyze(input.imageId()); }

    @PostMapping("/outfits")
    public OutfitsView outfits(@Valid @RequestBody OutfitInput input) { return ai.outfits(input); }
}
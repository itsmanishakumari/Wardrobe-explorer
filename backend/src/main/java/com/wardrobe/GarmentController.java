package com.wardrobe;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import static com.wardrobe.ApiModels.*;

@RestController
@RequestMapping("/api/garments")
public class GarmentController {
    private final GarmentService service;

    public GarmentController(GarmentService service) { this.service = service; }

    @GetMapping
    public List<GarmentView> list() { return service.list(); }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public GarmentView create(@Valid @RequestBody GarmentInput input) { return service.create(input); }

    @PutMapping("/{id}")
    public GarmentView update(@PathVariable long id, @Valid @RequestBody GarmentInput input) {
        return service.update(id, input);
    }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) { service.delete(id); }
}
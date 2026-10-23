package com.wardrobe;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.wardrobe.ApiModels.*;

@Service
@Transactional
public class GarmentService {
    private final GarmentRepository repository;
    private final ImageStorage images;

    public GarmentService(GarmentRepository repository, ImageStorage images) {
        this.repository = repository;
        this.images = images;
    }

    @Transactional(readOnly = true)
    public List<GarmentView> list() {
        return repository.findAllByOrderByCreatedAtDescIdDesc().stream().map(GarmentView::from).toList();
    }

    public GarmentView create(GarmentInput input) {
        validateImage(input.imageId());
        Garment item = new Garment();
        item.update(input);
        return GarmentView.from(repository.saveAndFlush(item));
    }

    public GarmentView update(long id, GarmentInput input) {
        Garment item = require(id);
        validateImage(input.imageId());
        item.update(input);
        return GarmentView.from(repository.saveAndFlush(item));
    }

    public void delete(long id) {
        repository.delete(require(id));
        // Photos may be shared by multiple garments. Never delete them implicitly.
    }

    private Garment require(long id) {
        return repository.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "Garment not found."));
    }

    private void validateImage(String id) {
        if (id != null) {
            images.require(id);
        }
    }
}
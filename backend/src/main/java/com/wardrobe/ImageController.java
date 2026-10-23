package com.wardrobe;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static com.wardrobe.ApiModels.ImageView;

@RestController
@RequestMapping("/api/images")
public class ImageController {
    private final ImageStorage images;

    public ImageController(ImageStorage images) { this.images = images; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImageView upload(@RequestParam("file") MultipartFile file) { return images.store(file); }

    @GetMapping("/{imageId}")
    public ResponseEntity<Resource> image(@PathVariable String imageId) {
        Resource resource = new FileSystemResource(images.require(imageId));
        return ResponseEntity.ok()
                .contentType(imageId.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + imageId + "\"")
                .cacheControl(CacheControl.noCache())
                .body(resource);
    }
}
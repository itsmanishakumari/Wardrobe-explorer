package com.wardrobe;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Iterator;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import static com.wardrobe.ApiModels.ImageView;

@Service
public class ImageStorage {
    public static final String IMAGE_ID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(png|jpg)";
    static final long MAX_BYTES = 10L * 1024 * 1024;
    static final long MAX_PIXELS = 20_000_000;
    private final Path root;

    public ImageStorage(WardrobeProperties properties) throws IOException {
        root = properties.imagesDirectory().toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    public ImageView store(MultipartFile file) {
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Images must be no larger than 10 MB.");
        }
        if (file.isEmpty()) {
            throw invalid();
        }
        BufferedImage image;
        String format;
        // Read dimensions before decoding so compressed pixel bombs cannot allocate huge rasters.
        try (InputStream input = file.getInputStream();
             ImageInputStream stream = ImageIO.createImageInputStream(input)) {
            if (stream == null) { throw invalid(); }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) { throw invalid(); }
            ImageReader reader = readers.next();
            try {
                format = reader.getFormatName();
                if (!format.equalsIgnoreCase("JPEG") && !format.equalsIgnoreCase("PNG")) {
                    throw invalid();
                }
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "Image exceeds the 20 million pixel limit.");
                }
                image = reader.read(0);
                if (image == null) { throw invalid(); }
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException error) {
            throw invalid();
        }

        // Write a fresh raster with no original metadata, filename, or appended payload.
        boolean jpeg = format.equalsIgnoreCase("JPEG");
        BufferedImage clean = new BufferedImage(image.getWidth(), image.getHeight(),
                jpeg ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        var graphics = clean.createGraphics();
        try { graphics.drawImage(image, 0, 0, null); }
        finally { graphics.dispose(); image.flush(); }
        String id = UUID.randomUUID() + (jpeg ? ".jpg" : ".png");
        Path target = root.resolve(id);
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            if (!ImageIO.write(clean, jpeg ? "jpg" : "png", output)) {
                throw new IOException("No image encoder");
            }
        } catch (IOException error) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) { /* best effort */ }
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "The photo could not be stored.");
        } finally {
            clean.flush();
        }
        return new ImageView(id, url(id));
    }

    public Path require(String id) {
        if (id == null || !id.matches(IMAGE_ID_PATTERN)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid image ID.");
        }
        Path path = root.resolve(id).normalize();
        if (!path.getParent().equals(root) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Image not found. Upload the photo first.");
        }
        return path;
    }

    public byte[] read(String id) {
        Path path = require(id);
        try { return Files.readAllBytes(path); }
        catch (IOException error) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "The photo could not be read.");
        }
    }

    public static String url(String id) { return id == null ? null : "/api/images/" + id; }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "Upload a valid JPEG or PNG image.");
    }
}
package com.example.metermonitor.storage;

import com.example.metermonitor.common.ApiException;
import com.example.metermonitor.config.AppProperties;
import jakarta.annotation.PostConstruct;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component @RequiredArgsConstructor
public class LocalImageStorage implements ImageStorage {
    private final AppProperties properties;
    private Path root;

    @PostConstruct void initialize() throws IOException {
        root = properties.upload().directory().toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    @Override public StoredImage store(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Выберите файл изображения");
        String type = Optional.ofNullable(file.getContentType()).orElse("").toLowerCase(Locale.ROOT);
        if (!properties.upload().allowedMimeTypes().contains(type)) throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Неподдерживаемый формат изображения");
        if (file.getSize() > properties.upload().maximumBytes()) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Загруженное изображение слишком велико");
        try {
            byte[] bytes = file.getBytes();
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            if (decoded == null || decoded.getWidth() < 10 || decoded.getHeight() < 10)
                throw new ApiException(HttpStatus.BAD_REQUEST, "Загруженный файл не является корректным изображением");
            String key = UUID.randomUUID() + extension(type);
            Path destination = root.resolve(key).normalize();
            if (!destination.startsWith(root)) throw new ApiException(HttpStatus.BAD_REQUEST, "Некорректный ключ хранилища");
            Files.write(destination, bytes, StandardOpenOption.CREATE_NEW);
            return new StoredImage(key, safeOriginalName(file.getOriginalFilename()), type);
        } catch (ApiException ex) { throw ex; }
        catch (IOException ex) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось сохранить загруженное изображение"); }
    }

    @Override public Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) throw new ApiException(HttpStatus.BAD_REQUEST, "Некорректный ключ хранилища");
        return path;
    }
    @Override public void delete(String key) { try { Files.deleteIfExists(resolve(key)); } catch (IOException ignored) {} }
    private String extension(String type) { return switch (type) { case "image/png" -> ".png"; case "image/webp" -> ".webp"; case "image/tiff" -> ".tif"; default -> ".jpg"; }; }
    private String safeOriginalName(String name) { return name == null ? "upload" : Paths.get(name).getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_"); }
}

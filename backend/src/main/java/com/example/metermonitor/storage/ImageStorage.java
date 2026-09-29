package com.example.metermonitor.storage;

import java.nio.file.Path;
import org.springframework.web.multipart.MultipartFile;

public interface ImageStorage {
    StoredImage store(MultipartFile file);
    Path resolve(String storageKey);
    void delete(String storageKey);
    record StoredImage(String storageKey, String originalFilename, String contentType) {}
}

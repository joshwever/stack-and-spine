package com.stackandspine.repository;

import com.stackandspine.model.MediaItem;
import com.stackandspine.model.MediaType;
import java.util.List;
import java.util.Optional;

public interface MediaRepository {
    List<MediaItem> findAll();
    List<MediaItem> findByTypeAndQuery(MediaType mediaType, String query);
    Optional<MediaItem> findById(String id);
    MediaItem save(MediaItem item);
    void deleteById(String id);
    String storageBackendName();
}

package com.stackandspine.repository;

import com.stackandspine.model.MediaItem;
import com.stackandspine.model.MediaType;
import java.util.List;
import java.util.Optional;

public interface MediaRepository {
    List<MediaItem> findAll(String userKey);
    List<MediaItem> findByTypeAndQuery(String userKey, MediaType mediaType, String query);
    Optional<MediaItem> findById(String userKey, String id);
    MediaItem save(String userKey, MediaItem item);
    void deleteById(String userKey, String id);
    String storageBackendName();
}

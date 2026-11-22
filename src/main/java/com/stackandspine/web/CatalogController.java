package com.stackandspine.web;

import com.stackandspine.model.MediaItem;
import com.stackandspine.model.MediaType;
import com.stackandspine.repository.MediaRepository;
import com.stackandspine.service.CoverStorageService;
import com.stackandspine.service.MetadataLookupService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

@Controller
public class CatalogController {

    private final MediaRepository mediaRepository;
    private final CoverStorageService coverStorageService;
    private final MetadataLookupService metadataLookupService;

    public CatalogController(
            MediaRepository mediaRepository,
            CoverStorageService coverStorageService,
            MetadataLookupService metadataLookupService) {
        this.mediaRepository = mediaRepository;
        this.coverStorageService = coverStorageService;
        this.metadataLookupService = metadataLookupService;
    }

    @GetMapping("/")
    public String index(
            @RequestParam(name = "type", required = false) MediaType mediaType,
            @RequestParam(name = "q", required = false) String query,
            @RequestHeader(name = "HX-Request", required = false) String hxRequest,
            Model model) {

        List<MediaItem> items = mediaRepository.findByTypeAndQuery(mediaType, query);
        enrichCoverUrls(items);
        List<MediaItem> allItems = mediaRepository.findAll();

        model.addAttribute("items", items);
        model.addAttribute("selectedType", mediaType);
        model.addAttribute("query", query == null ? "" : query);
        model.addAttribute("mediaTypes", MediaType.values());
        model.addAttribute("storageBackend", mediaRepository.storageBackendName());
        model.addAttribute("gcsEnabled", coverStorageService.isEnabled());
        model.addAttribute("tmdbConfigured", metadataLookupService.isTmdbConfigured());
        model.addAttribute("totalCount", allItems.size());
        model.addAttribute("bookCount", allItems.stream().filter(i -> i.getMediaType() == MediaType.BOOK).count());
        model.addAttribute("bluRayCount", allItems.stream().filter(i -> i.getMediaType() == MediaType.BLU_RAY).count());
        model.addAttribute("dvdCount", allItems.stream().filter(i -> i.getMediaType() == MediaType.DVD).count());
        model.addAttribute("newItem", new MediaItem());

        if ("true".equalsIgnoreCase(hxRequest)) {
            return "fragments/catalog-grid :: grid";
        }
        return "index";
    }

    @PostMapping("/items")
    public String createItem(
            @Valid @ModelAttribute("newItem") MediaItem newItem,
            BindingResult bindingResult,
            @RequestParam(name = "attrKey1", required = false) String attrKey1,
            @RequestParam(name = "attrVal1", required = false) String attrVal1,
            @RequestParam(name = "attrKey2", required = false) String attrKey2,
            @RequestParam(name = "attrVal2", required = false) String attrVal2) {

        if (bindingResult.hasErrors()) {
            return "redirect:/";
        }

        Map<String, String> attrs = new LinkedHashMap<>();
        if (attrKey1 != null && !attrKey1.isBlank() && attrVal1 != null && !attrVal1.isBlank()) {
            attrs.put(attrKey1.trim(), attrVal1.trim());
        }
        if (attrKey2 != null && !attrKey2.isBlank() && attrVal2 != null && !attrVal2.isBlank()) {
            attrs.put(attrKey2.trim(), attrVal2.trim());
        }
        newItem.setAttributes(attrs);

        mediaRepository.save(newItem);
        return "redirect:/";
    }

    @PostMapping("/items/{id}/cover")
    public String uploadCover(
            @PathVariable("id") String id,
            @RequestParam("file") MultipartFile file) throws IOException {
        mediaRepository.findById(id).ifPresent(item -> {
            try {
                String objectPath = coverStorageService.uploadCover(id, file);
                item.setCoverObjectPath(objectPath);
                mediaRepository.save(item);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to upload cover image to Cloud Storage", e);
            }
        });
        return "redirect:/";
    }

    @DeleteMapping("/items/{id}")
    @ResponseBody
    public String deleteItem(@PathVariable("id") String id) {
        mediaRepository.deleteById(id);
        return "";
    }

    @GetMapping("/api/items")
    @ResponseBody
    public List<MediaItem> apiListItems(
            @RequestParam(name = "type", required = false) MediaType mediaType,
            @RequestParam(name = "q", required = false) String query) {
        List<MediaItem> items = mediaRepository.findByTypeAndQuery(mediaType, query);
        enrichCoverUrls(items);
        return items;
    }

    @GetMapping("/api/lookup")
    @ResponseBody
    public Map<String, Object> apiLookupMetadata(
            @RequestParam(name = "type", defaultValue = "BOOK") MediaType type,
            @RequestParam(name = "q") String query) {
        return metadataLookupService.lookup(type, query);
    }

    private void enrichCoverUrls(List<MediaItem> items) {
        if (!coverStorageService.isEnabled()) {
            return;
        }
        for (MediaItem item : items) {
            if (item.getCoverObjectPath() != null && !item.getCoverObjectPath().isBlank()) {
                item.setCoverImageUrl(coverStorageService.generateSignedReadUrl(item.getCoverObjectPath()));
            }
        }
    }
}

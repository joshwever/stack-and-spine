package com.stackandspine.web;

import com.stackandspine.model.MediaItem;
import com.stackandspine.model.MediaType;
import com.stackandspine.repository.MediaRepository;
import com.stackandspine.service.CoverStorageService;
import com.stackandspine.service.MetadataLookupService;
import com.stackandspine.service.UserIdentityResolver;
import jakarta.servlet.http.HttpServletRequest;
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

    private static final int PAGE_SIZE = 48;

    private final MediaRepository mediaRepository;
    private final CoverStorageService coverStorageService;
    private final MetadataLookupService metadataLookupService;
    private final UserIdentityResolver userIdentityResolver;

    public CatalogController(
            MediaRepository mediaRepository,
            CoverStorageService coverStorageService,
            MetadataLookupService metadataLookupService,
            UserIdentityResolver userIdentityResolver) {
        this.mediaRepository = mediaRepository;
        this.coverStorageService = coverStorageService;
        this.metadataLookupService = metadataLookupService;
        this.userIdentityResolver = userIdentityResolver;
    }

    @GetMapping("/")
    public String index(
            @RequestParam(name = "type", required = false) MediaType mediaType,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "scanned", required = false) Boolean scanned,
            @RequestHeader(name = "HX-Request", required = false) String hxRequest,
            HttpServletRequest request,
            Model model) {

        String userKey = userIdentityResolver.resolveUserKey(request);
        List<MediaItem> filteredItems = mediaRepository.findByTypeAndQuery(userKey, mediaType, query);
        int filteredCount = filteredItems.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) filteredCount / PAGE_SIZE));
        int currentPage = Math.min(Math.max(page, 1), totalPages);
        int fromIndex = (currentPage - 1) * PAGE_SIZE;
        int toIndex = Math.min(fromIndex + PAGE_SIZE, filteredCount);

        List<MediaItem> pageItems = filteredCount == 0
                ? List.of()
                : filteredItems.subList(fromIndex, toIndex);
        enrichCoverUrls(pageItems);

        List<MediaItem> allItems = mediaRepository.findAll(userKey);
        String authenticatedUser = userIdentityResolver.resolveAuthenticatedDisplayUser(request);
        String authSource = userIdentityResolver.resolveAuthSource(request);

        model.addAttribute("items", pageItems);
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("filteredCount", filteredCount);
        model.addAttribute("showingFrom", filteredCount == 0 ? 0 : fromIndex + 1);
        model.addAttribute("showingTo", toIndex);
        model.addAttribute("selectedType", mediaType);
        model.addAttribute("query", query == null ? "" : query);
        model.addAttribute("scanned", Boolean.TRUE.equals(scanned));
        model.addAttribute("mediaTypes", MediaType.values());
        model.addAttribute("storageBackend", mediaRepository.storageBackendName());
        model.addAttribute("authenticatedUser", authenticatedUser);
        model.addAttribute("authSource", authSource);
        model.addAttribute("identityPlatformSession", "Identity Platform".equals(authSource));
        model.addAttribute("gcsEnabled", coverStorageService.isEnabled());
        model.addAttribute("tmdbConfigured", metadataLookupService.isTmdbConfigured());
        model.addAttribute("totalCount", allItems.size());
        model.addAttribute("bookCount", allItems.stream().filter(i -> i.getMediaType() == MediaType.BOOK).count());
        model.addAttribute("ebookCount", allItems.stream().filter(i -> i.getMediaType() == MediaType.EBOOK).count());
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
            @RequestParam(name = "attrVal2", required = false) String attrVal2,
            HttpServletRequest request) {

        if (bindingResult.hasErrors()) {
            return "redirect:/";
        }

        String userKey = userIdentityResolver.resolveUserKey(request);

        Map<String, String> attrs = new LinkedHashMap<>();
        if (attrKey1 != null && !attrKey1.isBlank() && attrVal1 != null && !attrVal1.isBlank()) {
            attrs.put(attrKey1.trim(), attrVal1.trim());
        }
        if (attrKey2 != null && !attrKey2.isBlank() && attrVal2 != null && !attrVal2.isBlank()) {
            attrs.put(attrKey2.trim(), attrVal2.trim());
        }
        newItem.setAttributes(attrs);

        MediaItem saved = mediaRepository.save(userKey, newItem);
        if (coverStorageService.isEnabled()
                && (saved.getMediaType() == MediaType.BOOK || saved.getMediaType() == MediaType.EBOOK)
                && saved.getIdentifierCode() != null
                && !saved.getIdentifierCode().isBlank()) {
            coverStorageService.fetchAndUploadOpenLibraryCover(userKey, saved.getId(), saved.getIdentifierCode())
                    .ifPresent(objectPath -> {
                        saved.setCoverObjectPath(objectPath);
                        mediaRepository.save(userKey, saved);
                    });
        }
        return "redirect:/";
    }

    @PostMapping("/items/{id}/cover")
    public String uploadCover(
            @PathVariable("id") String id,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) throws IOException {
        String userKey = userIdentityResolver.resolveUserKey(request);
        mediaRepository.findById(userKey, id).ifPresent(item -> {
            try {
                String objectPath = coverStorageService.uploadCover(userKey, id, file);
                item.setCoverObjectPath(objectPath);
                mediaRepository.save(userKey, item);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to upload cover image to Cloud Storage", e);
            }
        });
        return "redirect:/";
    }

    @PostMapping("/inbox/scan")
    public String uploadInboxScan(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) throws IOException {
        String userKey = userIdentityResolver.resolveUserKey(request);
        coverStorageService.uploadInboxPhoto(userKey, file);
        return "redirect:/?scanned=true";
    }

    @PostMapping("/items/{id}/cover/fetch")
    public String fetchOpenLibraryCover(
            @PathVariable("id") String id,
            HttpServletRequest request) {
        String userKey = userIdentityResolver.resolveUserKey(request);
        mediaRepository.findById(userKey, id).ifPresent(item -> {
            if (item.getIdentifierCode() != null && !item.getIdentifierCode().isBlank()) {
                coverStorageService.fetchAndUploadOpenLibraryCover(userKey, item.getId(), item.getIdentifierCode())
                        .ifPresent(objectPath -> {
                            item.setCoverObjectPath(objectPath);
                            mediaRepository.save(userKey, item);
                        });
            }
        });
        return "redirect:/";
    }

    @GetMapping("/items/{id}/edit")
    public String editItemForm(
            @PathVariable("id") String id,
            HttpServletRequest request,
            Model model) {
        String userKey = userIdentityResolver.resolveUserKey(request);
        MediaItem item = mediaRepository.findById(userKey, id)
                .orElseThrow(() -> new IllegalArgumentException("Media item not found: " + id));
        StringBuilder attrsText = new StringBuilder();
        if (item.getAttributes() != null) {
            item.getAttributes().forEach((k, v) -> {
                if (!attrsText.isEmpty()) {
                    attrsText.append("\n");
                }
                attrsText.append(k).append(": ").append(v);
            });
        }
        model.addAttribute("item", item);
        model.addAttribute("mediaTypes", MediaType.values());
        model.addAttribute("attributesText", attrsText.toString());
        return "fragments/edit-item-card :: editCard";
    }

    @GetMapping("/items/{id}/card")
    public String viewItemCard(
            @PathVariable("id") String id,
            HttpServletRequest request,
            Model model) {
        String userKey = userIdentityResolver.resolveUserKey(request);
        MediaItem item = mediaRepository.findById(userKey, id)
                .orElseThrow(() -> new IllegalArgumentException("Media item not found: " + id));
        enrichCoverUrls(List.of(item));
        model.addAttribute("item", item);
        model.addAttribute("gcsEnabled", coverStorageService.isEnabled());
        return "fragments/catalog-grid :: card";
    }

    @PostMapping("/items/{id}")
    public String updateItem(
            @PathVariable("id") String id,
            @RequestParam("mediaType") MediaType mediaType,
            @RequestParam("title") String title,
            @RequestParam("creator") String creator,
            @RequestParam(name = "releaseYear", required = false) Integer releaseYear,
            @RequestParam(name = "genre", required = false) String genre,
            @RequestParam(name = "identifierCode", required = false) String identifierCode,
            @RequestParam(name = "shelfLocation", required = false) String shelfLocation,
            @RequestParam(name = "description", required = false) String description,
            @RequestParam(name = "attributesText", required = false) String attributesText,
            HttpServletRequest request,
            Model model) {

        String userKey = userIdentityResolver.resolveUserKey(request);
        MediaItem existing = mediaRepository.findById(userKey, id)
                .orElseThrow(() -> new IllegalArgumentException("Media item not found: " + id));

        existing.setMediaType(mediaType);
        existing.setTitle(title.trim());
        existing.setCreator(creator.trim());
        existing.setReleaseYear(releaseYear);
        existing.setGenre(genre != null && !genre.isBlank() ? genre.trim() : null);
        existing.setIdentifierCode(identifierCode != null && !identifierCode.isBlank() ? identifierCode.trim() : null);
        existing.setShelfLocation(shelfLocation != null && !shelfLocation.isBlank() ? shelfLocation.trim() : null);
        existing.setDescription(description != null && !description.isBlank() ? description.trim() : null);

        Map<String, String> parsedAttrs = new LinkedHashMap<>();
        if (attributesText != null && !attributesText.isBlank()) {
            for (String line : attributesText.split("\\R")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String k = line.substring(0, colon).trim();
                    String v = line.substring(colon + 1).trim();
                    if (!k.isEmpty() && !v.isEmpty()) {
                        parsedAttrs.put(k, v);
                    }
                }
            }
        }
        existing.setAttributes(parsedAttrs);

        MediaItem saved = mediaRepository.save(userKey, existing);
        enrichCoverUrls(List.of(saved));

        List<MediaItem> allItems = mediaRepository.findAll(userKey);
        model.addAttribute("item", saved);
        model.addAttribute("gcsEnabled", coverStorageService.isEnabled());
        model.addAttribute("selectedType", null);
        model.addAttribute("totalCount", allItems.size());
        model.addAttribute("bookCount", allItems.stream().filter(i -> i.getMediaType() == MediaType.BOOK).count());
        model.addAttribute("ebookCount", allItems.stream().filter(i -> i.getMediaType() == MediaType.EBOOK).count());
        model.addAttribute("bluRayCount", allItems.stream().filter(i -> i.getMediaType() == MediaType.BLU_RAY).count());
        model.addAttribute("dvdCount", allItems.stream().filter(i -> i.getMediaType() == MediaType.DVD).count());

        return "fragments/edit-item-card :: updatedCardWithStats";
    }

    @DeleteMapping("/items/{id}")
    @ResponseBody
    public String deleteItem(
            @PathVariable("id") String id,
            HttpServletRequest request) {
        String userKey = userIdentityResolver.resolveUserKey(request);
        mediaRepository.deleteById(userKey, id);
        return "";
    }

    @GetMapping("/api/items")
    @ResponseBody
    public List<MediaItem> apiListItems(
            @RequestParam(name = "type", required = false) MediaType mediaType,
            @RequestParam(name = "q", required = false) String query,
            HttpServletRequest request) {
        String userKey = userIdentityResolver.resolveUserKey(request);
        List<MediaItem> items = mediaRepository.findByTypeAndQuery(userKey, mediaType, query);
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
        for (MediaItem item : items) {
            if (coverStorageService.isEnabled()
                    && item.getCoverObjectPath() != null
                    && !item.getCoverObjectPath().isBlank()) {
                item.setCoverImageUrl(coverStorageService.generateSignedReadUrl(item.getCoverObjectPath()));
            } else if (item.getMediaType() == MediaType.BOOK || item.getMediaType() == MediaType.EBOOK) {
                item.setCoverImageUrl(coverStorageService.openLibraryCoverUrl(item.getIdentifierCode()));
            }
        }
    }
}

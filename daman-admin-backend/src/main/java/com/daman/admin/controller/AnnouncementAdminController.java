package com.daman.admin.controller;

import com.daman.admin.catalog.FeatureCatalog;
import com.daman.admin.entity.Announcement;
import com.daman.admin.service.AnnouncementService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Writing announcements from the portal. Authenticated — deliberately NOT in
 * {@code AuthFilter.PUBLIC_PATHS}, and on {@code /api/announcements} rather than
 * {@code /api/public/announcements} so the two surfaces can never be confused for each other.
 *
 * <p>The one rule worth stating: a {@code featureKey} must be one the catalogue prices. A key
 * it does not know renders a card with a missing badge on every till, and by then it is on a
 * shop's screen — so {@code FeatureCatalog.ENTRIES}, the list that prices things, is the list
 * that decides what may be stored.
 */
@RestController
@RequestMapping("/api/announcements")
@RequiredArgsConstructor
public class AnnouncementAdminController {

    private final AnnouncementService service;

    @Data
    public static class AnnouncementRequest {
        private String slug;
        private String titleEn;
        private String titleAr;
        private String bodyEn;
        private String bodyAr;
        private String featureKey;
        private Integer sortOrder;
        private Boolean published;
    }

    /** The row as the portal sees it — everything except the bytes. */
    public record AnnouncementDto(Long id, String slug, String titleEn, String titleAr,
                                  String bodyEn, String bodyAr, String featureKey,
                                  int sortOrder, boolean published, boolean hasImage,
                                  LocalDateTime createdAt, LocalDateTime updatedAt) {}

    private static AnnouncementDto toDto(Announcement a) {
        return new AnnouncementDto(a.getId(), a.getSlug(), a.getTitleEn(), a.getTitleAr(),
                a.getBodyEn(), a.getBodyAr(), a.getFeatureKey(), a.getSortOrder(), a.isPublished(),
                a.getImage() != null && a.getImage().length > 0,
                a.getCreatedAt(), a.getUpdatedAt());
    }

    @GetMapping
    public List<AnnouncementDto> all() {
        return service.all().stream().map(AnnouncementAdminController::toDto).toList();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody AnnouncementRequest body) {
        String rejection = rejectUnknownFeatureKey(body.getFeatureKey());
        if (rejection != null) return ResponseEntity.badRequest().body(Map.of("error", rejection));
        try {
            Announcement saved = service.save(apply(new Announcement(), body));
            return ResponseEntity.status(HttpStatus.CREATED).body(toDto(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody AnnouncementRequest body) {
        Optional<Announcement> existing = service.byId(id);
        if (existing.isEmpty()) return ResponseEntity.notFound().build();
        String rejection = rejectUnknownFeatureKey(body.getFeatureKey());
        if (rejection != null) return ResponseEntity.badRequest().body(Map.of("error", rejection));
        try {
            return ResponseEntity.ok(toDto(service.save(apply(existing.get(), body))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** The picture lives in the row, so deleting the row takes it with it. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (service.byId(id).isEmpty()) return ResponseEntity.notFound().build();
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * The picture, for the owner looking at the row they are writing.
     *
     * <p><b>Why this exists alongside the public path.</b> The portal previewed through
     * {@code /api/public/announcements/{slug}/image}, on the reasoning that previewing anything
     * else is not previewing what the shops get. True about the bytes, wrong about reach: the
     * public path serves PUBLISHED rows only, so a draft with a picture rendered a broken image
     * in the portal — and a draft is exactly when the owner needs to see it. The bytes are the
     * same bytes; only the gate differs, and this one is the admin token.
     *
     * <p>Not cached, unlike the public path. The owner replaces a picture and looks straight at
     * it, and a cached copy of the previous one reads as an upload that failed.
     */
    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable Long id) {
        return service.byId(id)
                .filter(a -> a.getImage() != null && a.getImage().length > 0)
                .<ResponseEntity<byte[]>>map(a -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(
                                a.getImageType() == null ? "image/png" : a.getImageType()))
                        .cacheControl(CacheControl.noStore())
                        .body(a.getImage()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/image")
    public ResponseEntity<?> uploadImage(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        Optional<Announcement> existing = service.byId(id);
        if (existing.isEmpty()) return ResponseEntity.notFound().build();
        try {
            byte[] bytes = file.getBytes();
            // The service is the single gate on size and type; the controller's job is only to
            // turn its refusal into a 400 rather than letting it surface as a 500.
            service.validateImage(bytes, file.getContentType());
            Announcement a = existing.get();
            a.setImage(bytes);
            a.setImageType(file.getContentType());
            return ResponseEntity.ok(toDto(service.save(a)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (java.io.IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "could not read the uploaded file"));
        }
    }

    @DeleteMapping("/{id}/image")
    public ResponseEntity<?> removeImage(@PathVariable Long id) {
        Optional<Announcement> existing = service.byId(id);
        if (existing.isEmpty()) return ResponseEntity.notFound().build();
        Announcement a = existing.get();
        a.setImage(null);
        a.setImageType(null);
        return ResponseEntity.ok(toDto(service.save(a)));
    }

    /** Null or blank is fine — an announcement that sells nothing is a normal thing to write. */
    private static String rejectUnknownFeatureKey(String featureKey) {
        if (featureKey == null || featureKey.isBlank()) return null;
        boolean known = FeatureCatalog.ENTRIES.stream().anyMatch(e -> e.key().equals(featureKey));
        return known ? null : "unknown feature key: " + featureKey;
    }

    private static Announcement apply(Announcement entity, AnnouncementRequest body) {
        entity.setSlug(body.getSlug());
        entity.setTitleEn(body.getTitleEn());
        entity.setTitleAr(body.getTitleAr());
        entity.setBodyEn(body.getBodyEn());
        entity.setBodyAr(body.getBodyAr());
        entity.setFeatureKey(body.getFeatureKey() == null || body.getFeatureKey().isBlank()
                ? null : body.getFeatureKey());
        if (body.getSortOrder() != null) entity.setSortOrder(body.getSortOrder());
        if (body.getPublished() != null) entity.setPublished(body.getPublished());
        return entity;
    }
}

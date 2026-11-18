package com.daman.admin.controller;

import com.daman.admin.entity.Announcement;
import com.daman.admin.service.AnnouncementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * What a shop fetches, with no credential at all.
 *
 * <p>Anonymous because a shipped POS install holds no admin token — the same reason
 * {@code /api/public/feature-catalog} is anonymous, and bounded the same way: it reads published
 * rows and answers the same thing to everybody, so it discloses nothing about who the clients
 * are and is not an oracle for anything.
 *
 * <p><b>Two things are deliberately absent from the list.</b> There is no price: the till
 * already has the catalogue and resolves the badge from it, and a price here would be a second
 * place prices live, free to drift. And there are no picture bytes: inlining them would have
 * every till download every banner on every poll, including the polls where nothing changed.
 * The picture is a separate URL the till fetches once and caches.
 */
@RestController
@RequiredArgsConstructor
public class PublicAnnouncementController {

    private final AnnouncementService service;

    /** The slug is the id a shop sees; it never learns the row id. */
    public record PublicAnnouncement(String id,
                                     String titleEn,
                                     String titleAr,
                                     String bodyEn,
                                     String bodyAr,
                                     String featureKey,
                                     String imageUrl,
                                     LocalDateTime updatedAt) {}

    public record AnnouncementList(List<PublicAnnouncement> announcements) {}

    @GetMapping("/api/public/announcements")
    public AnnouncementList list() {
        return new AnnouncementList(service.published().stream().map(PublicAnnouncementController::toPublic).toList());
    }

    private static PublicAnnouncement toPublic(Announcement a) {
        boolean hasImage = a.getImage() != null && a.getImage().length > 0;
        return new PublicAnnouncement(
                a.getSlug(),
                a.getTitleEn(),
                a.getTitleAr(),
                a.getBodyEn(),
                a.getBodyAr(),
                a.getFeatureKey(),
                // Null rather than a URL that 404s: a till that sees no URL draws a text card,
                // which is the right outcome for a text-only announcement.
                hasImage ? "/api/public/announcements/" + a.getSlug() + "/image" : null,
                a.getUpdatedAt());
    }

    /**
     * The bytes, under the type they were uploaded with — which {@code AnnouncementService}
     * has already restricted to PNG or JPEG.
     *
     * <p>404 rather than an empty 200 for a missing picture: the till caches what it is given,
     * and a zero-byte file cached as a banner is a broken image on a shop's screen until
     * something clears it.
     */
    @GetMapping("/api/public/announcements/{slug}/image")
    public ResponseEntity<byte[]> image(@PathVariable String slug) {
        return service.bySlug(slug)
                .filter(a -> a.isPublished() && a.getImage() != null && a.getImage().length > 0)
                .<ResponseEntity<byte[]>>map(a -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(
                                a.getImageType() == null ? "image/png" : a.getImageType()))
                        .cacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePublic())
                        .body(a.getImage()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

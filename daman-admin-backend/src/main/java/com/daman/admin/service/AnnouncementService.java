package com.daman.admin.service;

import com.daman.admin.entity.Announcement;
import com.daman.admin.repository.AnnouncementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Announcements, and the two limits that have to hold wherever one is written.
 *
 * <p>Both exist because of where this data ends up. The picture is served from a
 * world-readable endpoint and then cached on every till, so its size and its type are settled
 * here — the only place every write path passes through — rather than at each caller. And the
 * slug becomes a URL segment and a filename on a shop's disk, so it is restricted to a shape
 * that cannot mean anything else.
 */
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    private final AnnouncementRepository repository;

    /** 512 KB. Large enough for a real banner, small enough that a till on a phone line copes. */
    public static final int IMAGE_MAX_BYTES = 512 * 1024;

    /**
     * Raster only. The bytes are served back under the type they arrived with, so accepting
     * {@code image/svg+xml} would hand anyone who can reach the public endpoint a script
     * running on our own origin.
     */
    public static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg");

    /**
     * The slug is a URL segment AND the filename each till caches the picture under, so it is
     * pinned to a shape with no separators, no dots and no leading dash — `..` and `a/b` can
     * never be slugs, which is what stops a server-supplied id walking out of a shop's cache
     * directory.
     */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    /** Published rows in display order. The shops' endpoint reads this and nothing else. */
    public List<Announcement> published() {
        return repository.findByPublishedTrueOrderBySortOrderAscIdDesc();
    }

    /** Everything, drafts included — the portal's own list. */
    public List<Announcement> all() {
        return repository.findAllByOrderBySortOrderAscIdDesc();
    }

    public Optional<Announcement> bySlug(String slug) {
        return repository.findBySlug(slug);
    }

    public Optional<Announcement> byId(Long id) {
        return repository.findById(id);
    }

    public void delete(Long id) {
        repository.deleteById(id);
    }

    /**
     * Validates and stores. {@code createdAt}/{@code updatedAt} are stamped here as well as in
     * the entity callbacks, because the callbacks only fire against a real EntityManager and
     * the dot on every till depends on {@code updatedAt} actually moving.
     */
    public Announcement save(Announcement announcement) {
        String slug = announcement.getSlug() == null ? "" : announcement.getSlug().trim();
        if (!SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException(
                    "slug must be lowercase letters, digits and dashes (1-64 chars), starting with a letter or digit");
        }
        announcement.setSlug(slug);

        // A row may keep its own slug; only a DIFFERENT row holding it is a collision.
        repository.findBySlug(slug).ifPresent(existing -> {
            if (announcement.getId() == null || !existing.getId().equals(announcement.getId())) {
                throw new IllegalArgumentException("slug already in use: " + slug);
            }
        });

        validateImage(announcement.getImage(), announcement.getImageType());

        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        if (announcement.getCreatedAt() == null) announcement.setCreatedAt(now);
        announcement.setUpdatedAt(now);
        return repository.save(announcement);
    }

    /**
     * No picture is fine — a text-only announcement is a normal thing to write. A picture must
     * be PNG or JPEG and within the cap.
     */
    public void validateImage(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) return;
        if (bytes.length > IMAGE_MAX_BYTES) {
            throw new IllegalArgumentException(
                    "image is " + bytes.length + " bytes; the limit is " + IMAGE_MAX_BYTES);
        }
        String type = contentType == null ? "" : contentType.trim().toLowerCase(java.util.Locale.ROOT);
        if (!IMAGE_TYPES.contains(type)) {
            throw new IllegalArgumentException("image must be PNG or JPEG, not: " + contentType);
        }
    }
}

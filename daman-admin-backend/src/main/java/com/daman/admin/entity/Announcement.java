package com.daman.admin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One announcement the owner writes in the portal, shown on every shop's features page.
 *
 * <p>The picture lives in this row rather than on a disk or in R2. There are a handful of
 * announcements, each capped at 512 KB, and keeping the bytes in the row means a backup of the
 * admin database is a complete backup — no second store to keep in step, and no orphaned file
 * when a row is deleted.
 *
 * <p>There is deliberately <b>no price column</b>. An announcement names a {@code featureKey}
 * and the price badge is resolved from {@code FeatureCatalog} wherever it is rendered, so a
 * price change never leaves a stale number sitting in an announcement nobody thought to edit.
 */
@Entity
@Table(name = "announcements",
       uniqueConstraints = @UniqueConstraint(name = "uk_announcement_slug", columnNames = "slug"))
@Getter
@Setter
public class Announcement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The stable public identifier. It is what the shops receive as the announcement's id, what
     * the image URL is built from, and what each till names its cached picture file — so it is
     * restricted to a safe shape before it is ever used as any of those.
     */
    @Column(nullable = false, length = 64)
    private String slug;

    @Column(length = 200)
    private String titleEn;

    @Column(length = 200)
    private String titleAr;

    @Column(length = 2000)
    private String bodyEn;

    @Column(length = 2000)
    private String bodyAr;

    /** PNG or JPEG bytes, or null for a text-only announcement. */
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "image")
    private byte[] image;

    /** The content type the bytes were uploaded under, and the one they are served back with. */
    @Column(length = 40)
    private String imageType;

    /**
     * Which catalogue feature this is about, or null for an announcement that is not about one.
     * A shop whose build predates the feature renders the card without a price badge rather
     * than not at all.
     */
    @Column(length = 64)
    private String featureKey;

    @Column(nullable = false)
    private int sortOrder;

    /** A draft until this is true. Nothing unpublished ever leaves the admin. */
    @Column(nullable = false)
    private boolean published;

    private LocalDateTime createdAt;

    /**
     * Moved on every save, including an edit. The till's "something new" dot is a comparison of
     * the newest {@code updatedAt} against what it last saw, so a silent edit would change the
     * card on screen and never light the dot.
     */
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

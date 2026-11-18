package com.daman.admin.controller;

import com.daman.admin.config.AuthFilter;
import com.daman.admin.entity.Announcement;
import com.daman.admin.service.AnnouncementService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a shop is allowed to fetch without any credential.
 *
 * <p>This is the second anonymous surface announcements add, so everything here is about what
 * it must NOT give away: no price (the till resolves that from the catalogue it already has),
 * no picture bytes inline (a list of base64 blobs on every poll, from every till), and nothing
 * that is still a draft.
 */
class PublicAnnouncementControllerTest {

    private final AnnouncementService service = mock(AnnouncementService.class);
    private final PublicAnnouncementController controller = new PublicAnnouncementController(service);

    private static Announcement row(String slug) {
        Announcement a = new Announcement();
        a.setId(1L);
        a.setSlug(slug);
        a.setTitleEn("Accounting");
        a.setTitleAr("المحاسبة");
        a.setBodyEn("Double-entry books.");
        a.setBodyAr("قيود مزدوجة.");
        a.setFeatureKey("accounting");
        a.setImage(new byte[]{1, 2, 3});
        a.setImageType("image/png");
        a.setPublished(true);
        a.setUpdatedAt(java.time.LocalDateTime.of(2026, 11, 18, 9, 0));
        return a;
    }

    /**
     * A price in the payload would be a second place prices live, free to drift from the
     * catalogue. Image bytes would make every till download every banner on every poll even
     * when nothing changed.
     */
    @Test
    void theListCarriesNoPriceAndNoImageBytes() {
        when(service.published()).thenReturn(List.of(row("accounting-launch")));

        var body = controller.list();
        var item = body.announcements().get(0);

        assertThat(item.id()).isEqualTo("accounting-launch");
        assertThat(item.featureKey()).isEqualTo("accounting");
        assertThat(item.imageUrl()).isEqualTo("/api/public/announcements/accounting-launch/image");

        // Reflection over the record's components: a price or a byte[] added later fails here
        // rather than quietly shipping to every till.
        for (var component : item.getClass().getRecordComponents()) {
            assertThat(component.getName().toLowerCase()).doesNotContain("price");
            assertThat(component.getType()).isNotEqualTo(byte[].class);
        }
    }

    /** An announcement with no picture advertises no picture URL, rather than one that 404s. */
    @Test
    void anAnnouncementWithoutAPictureHasNoImageUrl() {
        Announcement a = row("text-only");
        a.setImage(null);
        a.setImageType(null);
        when(service.published()).thenReturn(List.of(a));

        assertThat(controller.list().announcements().get(0).imageUrl()).isNull();
    }

    /** The controller reads published() and nothing else — a draft has no path out of here. */
    @Test
    void anUnpublishedAnnouncementIsNotServed() {
        when(service.published()).thenReturn(List.of());

        assertThat(controller.list().announcements()).isEmpty();
    }

    @Test
    void aMissingImageIs404NotAnEmpty200() {
        when(service.bySlug("gone")).thenReturn(Optional.empty());
        assertThat(controller.image("gone").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        Announcement noPicture = row("text-only");
        noPicture.setImage(null);
        when(service.bySlug("text-only")).thenReturn(Optional.of(noPicture));
        assertThat(controller.image("text-only").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anImageIsServedWithTheTypeItWasUploadedUnder() {
        when(service.bySlug("accounting-launch")).thenReturn(Optional.of(row("accounting-launch")));

        ResponseEntity<byte[]> response = controller.image("accounting-launch");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(1, 2, 3);
        assertThat(response.getHeaders().getContentType()).hasToString("image/png");
    }

    /**
     * Both paths have to be anonymous or no shop can read them. The filter matches exactly, so
     * the per-slug image path needs the prefix rule rather than a set entry.
     */
    @Test
    void bothPathsAreReachableWithoutACredential() {
        assertThat(AuthFilter.isPublicPath("/api/public/announcements")).isTrue();
        assertThat(AuthFilter.isPublicPath("/api/public/announcements/accounting-launch/image")).isTrue();
    }

    /**
     * And the prefix must not become a hole. It is scoped to this one branch — the admin CRUD
     * for announcements lives at /api/announcements and must still need a token.
     */
    @Test
    void thePrefixDoesNotOpenAnythingElse() {
        assertThat(AuthFilter.isPublicPath("/api/announcements")).isFalse();
        assertThat(AuthFilter.isPublicPath("/api/clients")).isFalse();
        assertThat(AuthFilter.isPublicPath("/api/public/announcementsX")).isFalse();
        assertThat(AuthFilter.isPublicPath("/api/public")).isFalse();
    }
}

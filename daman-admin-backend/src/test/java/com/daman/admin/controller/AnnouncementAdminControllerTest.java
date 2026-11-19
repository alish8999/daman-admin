package com.daman.admin.controller;

import com.daman.admin.entity.Announcement;
import com.daman.admin.service.AnnouncementService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Writing an announcement from the portal.
 *
 * <p>Authenticated, so none of this is about anonymous callers. It is about not storing
 * something the rest of the system cannot render: a feature key the catalogue does not price
 * would show a card with a missing badge on every till, and the only place that can be caught
 * is before it is written.
 */
class AnnouncementAdminControllerTest {

    private final AnnouncementService service = mock(AnnouncementService.class);
    private final AnnouncementAdminController controller = new AnnouncementAdminController(service);

    private static AnnouncementAdminController.AnnouncementRequest request(String featureKey) {
        var r = new AnnouncementAdminController.AnnouncementRequest();
        r.setSlug("accounting-launch");
        r.setTitleEn("Accounting");
        r.setTitleAr("المحاسبة");
        r.setFeatureKey(featureKey);
        return r;
    }

    private static Announcement stored(long id) {
        Announcement a = new Announcement();
        a.setId(id);
        a.setSlug("accounting-launch");
        return a;
    }

    @Test
    void anUploadOverTheCapIsRejectedWith400() {
        when(service.byId(1L)).thenReturn(Optional.of(stored(1)));
        byte[] tooBig = new byte[AnnouncementService.IMAGE_MAX_BYTES + 1];
        var file = new MockMultipartFile("file", "banner.png", "image/png", tooBig);
        // The service is the single gate; the controller must surface its refusal as a 400
        // rather than letting it become a 500.
        org.mockito.Mockito.doThrow(new IllegalArgumentException("too big"))
                .when(service).validateImage(any(), any());

        var response = controller.uploadImage(1L, file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).save(any());
    }

    /**
     * A key the catalogue does not price renders a card with no badge on every till, and by
     * then it is on a shop's screen. {@code FeatureCatalog.ENTRIES} is the list that prices
     * things, so it is the list that decides.
     */
    @Test
    void anUnknownFeatureKeyIsRejected() {
        var response = controller.create(request("thisIsNotAFeature"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).save(any());
    }

    @Test
    void aKnownFeatureKeyIsAccepted() {
        when(service.save(any())).thenAnswer(i -> i.getArgument(0));

        var response = controller.create(request("accounting"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(service).save(any());
    }

    /** An announcement that sells nothing — a notice, a warning, an opening hours change. */
    @Test
    void aNullFeatureKeyIsAccepted() {
        when(service.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(controller.create(request(null)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(controller.create(request("")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /** The picture lives in the row, so deleting the row takes it with it. */
    @Test
    void deletingRemovesTheRowAndItsImage() {
        when(service.byId(4L)).thenReturn(Optional.of(stored(4)));

        var response = controller.delete(4L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).delete(4L);
    }

    @Test
    void deletingSomethingThatIsNotThereIs404() {
        when(service.byId(99L)).thenReturn(Optional.empty());

        assertThat(controller.delete(99L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(service, never()).delete(any());
    }

    /** A slug collision is the author's mistake, not a server fault. */
    @Test
    void aDuplicateSlugIsA400NotA500() {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("slug already in use"))
                .when(service).save(any());

        assertThat(controller.create(request("accounting")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
// ── Previewing a picture the shops cannot see yet ────────────────────────────

    /**
     * The portal used to preview a picture through the PUBLIC path, which is right about the
     * bytes and wrong about reach: that path serves PUBLISHED rows only, so every draft with a
     * picture rendered a broken image in the portal. The owner is looking at a row they are
     * still writing — the one case the public path is built to refuse.
     */
    @Test
    void aPictureOnAnUnpublishedRowIsStillShownToThePortal() {
        Announcement a = stored(1);
        a.setPublished(false);
        a.setImage(new byte[]{1, 2, 3});
        a.setImageType("image/jpeg");
        when(service.byId(1L)).thenReturn(Optional.of(a));

        ResponseEntity<byte[]> response = controller.image(1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(1, 2, 3);
        assertThat(response.getHeaders().getContentType()).hasToString("image/jpeg");
    }

    /**
     * No caching header on this one, unlike the public path. The owner replaces a picture and
     * looks straight at it; a cached preview of the previous one reads as a failed upload.
     */
    @Test
    void thePreviewIsNotCached() {
        Announcement a = stored(1);
        a.setImage(new byte[]{1});
        a.setImageType("image/png");
        when(service.byId(1L)).thenReturn(Optional.of(a));

        assertThat(controller.image(1L).getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void aRowWithNoPictureHasNothingToPreview() {
        when(service.byId(1L)).thenReturn(Optional.of(stored(1)));

        assertThat(controller.image(1L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aRowThatIsNotThereHasNothingToPreview() {
        when(service.byId(9L)).thenReturn(Optional.empty());

        assertThat(controller.image(9L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
    // ── Publish / unpublish ──────────────────────────────────────────────────────
    // Its own endpoint rather than a field on the full update, because publishing is not an
    // edit: it is one button on the list, pressed on a row nobody is editing, and routing it
    // through PUT would mean sending back every field just to flip one flag.

    @Test
    void publishingADraftFlipsTheFlag() {
        Announcement a = stored(1);
        a.setPublished(false);
        when(service.byId(1L)).thenReturn(Optional.of(a));
        when(service.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = controller.setPublished(1L, publishBody(true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(a.isPublished()).isTrue();
        verify(service).save(a);
    }

    @Test
    void unpublishingFlipsItBack() {
        Announcement a = stored(1);
        a.setPublished(true);
        when(service.byId(1L)).thenReturn(Optional.of(a));
        when(service.save(any())).thenAnswer(inv -> inv.getArgument(0));

        controller.setPublished(1L, publishBody(false));

        assertThat(a.isPublished()).isFalse();
        verify(service).save(a);
    }

    /**
     * Pressing publish on a row that is already published must not write.
     *
     * <p>Every save moves {@code updatedAt} (the entity's {@code @PreUpdate}), and that
     * timestamp is what raises the "new" dot on every till and un-dismisses the band on every
     * dashboard. A double-click, or a button pressed against a list another tab already
     * changed, would otherwise re-announce the same thing to the whole fleet.
     */
    @Test
    void publishingAnAlreadyPublishedRowWritesNothing() {
        Announcement a = stored(1);
        a.setPublished(true);
        when(service.byId(1L)).thenReturn(Optional.of(a));

        var response = controller.setPublished(1L, publishBody(true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service, never()).save(any());
    }

    @Test
    void publishingARowThatIsNotThereIs404() {
        when(service.byId(9L)).thenReturn(Optional.empty());

        assertThat(controller.setPublished(9L, publishBody(true)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** A body with no flag says nothing, so it changes nothing rather than guessing. */
    @Test
    void aBodyWithNoFlagIsRejected() {
        Announcement a = stored(1);
        when(service.byId(1L)).thenReturn(Optional.of(a));

        assertThat(controller.setPublished(1L, publishBody(null)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).save(any());
    }

    private static AnnouncementAdminController.PublishRequest publishBody(Boolean published) {
        var r = new AnnouncementAdminController.PublishRequest();
        r.setPublished(published);
        return r;
    }
    // ── A picture the owner uploads is squeezed, not refused ─────────────────────
    // The AI path has run every drawing through AnnouncementImageFitter since 2026-11-19; an
    // upload went straight to validateImage and was rejected. So the SAME picture was accepted
    // when the model drew it and refused when the owner picked it off their disk, and the owner
    // was left to resize it by hand in some other program. Direct instruction, 2026-11-19:
    // "المهم ما اضطر يدويا انا اضغطها".

    /** A real PNG of the given size — the fitter decodes it, so random bytes will not do. */
    private static byte[] png(int width, int height) throws java.io.IOException {
        var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        for (int x = 0; x < width; x++) {
            // A gradient, not noise: noise does not compress, and what is on trial here is the
            // controller's routing, not how hard the squeeze ladder can push.
            g.setColor(new java.awt.Color(x * 255 / Math.max(1, width - 1), 128, 200));
            g.fillRect(x, 0, 1, height);
        }
        g.dispose();
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void anOversizePictureIsSqueezedAndStoredRatherThanRefused() throws Exception {
        Announcement row = stored(1);
        when(service.byId(1L)).thenReturn(Optional.of(row));
        when(service.save(any())).thenAnswer(i -> i.getArgument(0));
        byte[] big = png(2200, 1600);
        assertThat(big.length).as("the fixture must actually be over the cap")
                .isGreaterThan(AnnouncementService.IMAGE_MAX_BYTES);

        var response = controller.uploadImage(1L, new MockMultipartFile("file", "banner.png", "image/png", big));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(row.getImage()).isNotNull();
        assertThat(row.getImage().length).isLessThanOrEqualTo(AnnouncementService.IMAGE_MAX_BYTES);
        assertThat(row.getImageType()).isEqualTo("image/jpeg");
        verify(service).save(row);
    }

    /** A picture already under the cap is stored byte for byte, in the format it arrived in. */
    @Test
    void aPictureThatAlreadyFitsIsStoredUntouched() throws Exception {
        Announcement row = stored(1);
        when(service.byId(1L)).thenReturn(Optional.of(row));
        when(service.save(any())).thenAnswer(i -> i.getArgument(0));
        byte[] small = png(80, 60);
        assertThat(small.length).isLessThanOrEqualTo(AnnouncementService.IMAGE_MAX_BYTES);

        controller.uploadImage(1L, new MockMultipartFile("file", "small.png", "image/png", small));

        assertThat(row.getImage()).isEqualTo(small);
        assertThat(row.getImageType()).as("no needless re-encode").isEqualTo("image/png");
    }

    /** Something that is not an image at all is still a 400, not a 500. */
    @Test
    void bytesThatAreNotAPictureAreStillRejected() {
        when(service.byId(1L)).thenReturn(Optional.of(stored(1)));
        byte[] junk = new byte[AnnouncementService.IMAGE_MAX_BYTES + 1];

        var response = controller.uploadImage(1L,
                new MockMultipartFile("file", "banner.png", "image/png", junk));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).save(any());
    }
}

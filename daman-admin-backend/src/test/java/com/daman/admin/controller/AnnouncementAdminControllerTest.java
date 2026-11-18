package com.daman.admin.controller;

import com.daman.admin.entity.Announcement;
import com.daman.admin.service.AnnouncementService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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
}

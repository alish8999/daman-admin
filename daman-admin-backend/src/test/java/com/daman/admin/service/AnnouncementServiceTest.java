package com.daman.admin.service;

import com.daman.admin.entity.Announcement;
import com.daman.admin.repository.AnnouncementRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One announcement the owner writes in the portal and every shop sees on its features page.
 *
 * <p>Two things are bounded here rather than anywhere downstream. The picture travels inside the
 * admin database and then out over a world-readable endpoint, so its size and its type are
 * checked at the only place every write path passes through. And {@code updatedAt} is stamped on
 * save because the "something new" dot on the till is a timestamp comparison — a row that could
 * be edited without moving its timestamp would change on screen and never light the dot.
 */
class AnnouncementServiceTest {

    private final AnnouncementRepository repository = mock(AnnouncementRepository.class);
    private final AnnouncementService service = new AnnouncementService(repository);

    private static Announcement row(String slug) {
        Announcement a = new Announcement();
        a.setSlug(slug);
        a.setTitleEn("Title");
        a.setTitleAr("عنوان");
        return a;
    }

    /**
     * Spring derives the ordering from the repository METHOD NAME, so there is nothing in Java
     * to assert a sort against. The test pins the name instead: rename the method and this
     * fails, which is the only way the ordering can silently change.
     */
    @Test
    void publishedAreOrderedBySortOrderThenNewest() {
        Announcement a = row("first");
        Announcement b = row("second");
        when(repository.findByPublishedTrueOrderBySortOrderAscIdDesc()).thenReturn(List.of(a, b));

        assertThat(service.published()).containsExactly(a, b);
        verify(repository).findByPublishedTrueOrderBySortOrderAscIdDesc();
    }

    /** A draft is a draft. The shops' endpoint reads this method and nothing else. */
    @Test
    void anUnpublishedRowNeverLeavesTheAdmin() {
        when(repository.findByPublishedTrueOrderBySortOrderAscIdDesc()).thenReturn(List.of());

        assertThat(service.published()).isEmpty();
    }

    /**
     * The till decides "is there something new" by comparing the newest `updatedAt` against what
     * it last saw. An edit that left the timestamp alone would change the card on screen and
     * never light the dot.
     */
    @Test
    void savingStampsUpdatedAtSoTheDotCanUseIt() {
        Announcement a = row("news");
        when(repository.save(any(Announcement.class))).thenAnswer(i -> i.getArgument(0));

        Announcement saved = service.save(a);

        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void anImageOverTheCapIsRefused() {
        byte[] tooBig = new byte[AnnouncementService.IMAGE_MAX_BYTES + 1];

        assertThatThrownBy(() -> service.validateImage(tooBig, "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anImageAtExactlyTheCapIsAccepted() {
        byte[] atCap = new byte[AnnouncementService.IMAGE_MAX_BYTES];

        service.validateImage(atCap, "image/png");
    }

    /**
     * Raster only, and only these two. The bytes are served back with the content type they were
     * uploaded under, so accepting `image/svg+xml` would be handing anyone who can reach the
     * public endpoint a script that runs on our own origin.
     */
    @Test
    void onlyPngAndJpegAreAccepted() {
        byte[] bytes = new byte[10];

        assertThatThrownBy(() -> service.validateImage(bytes, "image/gif"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validateImage(bytes, "image/svg+xml"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validateImage(bytes, null))
                .isInstanceOf(IllegalArgumentException.class);

        service.validateImage(bytes, "image/png");
        service.validateImage(bytes, "image/jpeg");
    }

    /** No picture at all is fine — a text-only announcement is a normal thing to write. */
    @Test
    void noImageIsNotAnError() {
        service.validateImage(null, null);
        service.validateImage(new byte[0], null);
    }

    /**
     * The slug is the id the shops see and the filename the till caches the picture under, so it
     * cannot be blank and cannot collide with a row that already exists.
     */
    @Test
    void aSlugIsRequiredAndUnique() {
        Announcement blank = row("");
        assertThatThrownBy(() -> service.save(blank)).isInstanceOf(IllegalArgumentException.class);

        Announcement taken = row("news");
        Announcement existing = row("news");
        existing.setId(7L);
        when(repository.findBySlug("news")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.save(taken)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Re-saving the SAME row must not collide with itself. */
    @Test
    void savingAnExistingRowUnderItsOwnSlugIsFine() {
        Announcement a = row("news");
        a.setId(7L);
        Announcement stored = row("news");
        stored.setId(7L);
        when(repository.findBySlug("news")).thenReturn(Optional.of(stored));
        when(repository.save(any(Announcement.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(service.save(a).getSlug()).isEqualTo("news");
    }
}

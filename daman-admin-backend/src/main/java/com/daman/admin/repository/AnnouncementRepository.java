package com.daman.admin.repository;

import com.daman.admin.entity.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    /**
     * What the shops are served, in the order they are shown.
     *
     * <p><b>The ordering is the method name.</b> Spring derives it from the name, so renaming
     * this silently changes what every till displays first — which is why
     * {@code AnnouncementServiceTest} asserts the name rather than a sort in Java.
     *
     * <p>{@code sortOrder} first so the owner can pin one to the top, then newest id, so a row
     * added without touching sortOrder still appears above older ones.
     */
    List<Announcement> findByPublishedTrueOrderBySortOrderAscIdDesc();

    Optional<Announcement> findBySlug(String slug);

    List<Announcement> findAllByOrderBySortOrderAscIdDesc();
}

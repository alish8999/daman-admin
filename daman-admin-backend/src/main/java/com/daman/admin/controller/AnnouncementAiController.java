package com.daman.admin.controller;

import com.daman.admin.ai.AiBudgetGuard;
import com.daman.admin.ai.AnnouncementDraft;
import com.daman.admin.ai.AnnouncementDraftService;
import com.daman.admin.ai.AnnouncementImageService;
import com.daman.admin.ai.Picture;
import com.daman.admin.entity.Announcement;
import com.daman.admin.service.AnnouncementService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * The two buttons that spend money.
 *
 * <p>Authenticated and under {@code /api/announcements}, well away from anything a shop can
 * reach: these call paid providers, so an anonymous caller would be a bill with a stranger's
 * name on it.
 *
 * <p><b>Neither endpoint saves anything.</b> The draft goes back to the form for the owner to
 * edit; the picture is attached to a row the owner already created. Nothing here publishes.
 */
@Slf4j
@RestController
@RequestMapping("/api/announcements/ai")
@RequiredArgsConstructor
public class AnnouncementAiController {

    private final AnnouncementDraftService drafts;
    private final AnnouncementImageService images;
    private final AnnouncementService announcements;
    private final AiBudgetGuard budget;

    @Data
    public static class DraftBody {
        /** One line from the owner saying what the announcement is about. */
        private String intent;
        /** Optional catalogue key; the service resolves the name and price from it. */
        private String featureKey;
    }

    @Data
    public static class ImageBody {
        private String brief;
    }

    /** What is left today, so the portal can say so before a button is pressed. */
    @GetMapping("/budget")
    public AiBudgetGuard.Remaining budget() {
        return budget.remaining();
    }

    @PostMapping("/draft")
    public ResponseEntity<?> draft(@RequestBody DraftBody body) {
        try {
            AnnouncementDraft draft = drafts.write(
                    new AnnouncementDraftService.DraftRequest(body.getIntent(), body.getFeatureKey()));
            return ResponseEntity.ok(draft);
        } catch (AiBudgetGuard.BudgetExceededException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(error(e));
        } catch (AnnouncementDraftService.AiDraftException e) {
            return ResponseEntity.badRequest().body(error(e));
        }
    }

    /**
     * Draws a picture and attaches it to an existing announcement.
     *
     * <p>Attached rather than returned, because the alternative is shipping a megabyte of base64
     * to the browser so it can post the same bytes straight back.
     */
    @PostMapping("/{id}/image")
    public ResponseEntity<?> image(@PathVariable Long id, @RequestBody ImageBody body) {
        Optional<Announcement> existing = announcements.byId(id);
        if (existing.isEmpty()) return ResponseEntity.notFound().build();
        try {
            Picture picture = images.draw(body.getBrief());
            Announcement a = existing.get();
            a.setImage(picture.bytes());
            a.setImageType(picture.contentType());
            announcements.save(a);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (AiBudgetGuard.BudgetExceededException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(error(e));
        } catch (AnnouncementDraftService.AiDraftException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e));
        }
    }

    private static Map<String, String> error(Exception e) {
        return Map.of("error", e.getMessage() == null ? "something went wrong" : e.getMessage());
    }
}

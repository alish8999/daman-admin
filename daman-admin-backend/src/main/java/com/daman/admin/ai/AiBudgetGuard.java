package com.daman.admin.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A daily ceiling on AI calls, because this is the only part of the portal that spends money
 * every time a button is pressed.
 *
 * <p>It exists for the failure nobody plans for: a stuck retry, a double-click, a page left
 * reloading. None of those is malicious and all of them bill. The cap is per calendar day and
 * per kind — text and pictures are counted separately because a picture costs many times a
 * paragraph.
 *
 * <p><b>Counted before the call, never after.</b> A call that was made and then failed still
 * cost money, so the counter moves first; refunding a slot on failure would turn a failing
 * provider into an unbounded spend.
 *
 * <p>In memory on purpose. A restart resets the count, which is the forgiving direction: the
 * guard is a brake on a runaway loop, not an accounting record, and the alternative is a table
 * and a migration for something that resets every midnight anyway.
 */
@Slf4j
@Service
public class AiBudgetGuard {

    private final int textPerDay;
    private final int imagesPerDay;

    private LocalDate day = LocalDate.now();
    private final AtomicInteger textUsed = new AtomicInteger();
    private final AtomicInteger imagesUsed = new AtomicInteger();

    public AiBudgetGuard(@Value("${daman.ai.text-per-day:60}") int textPerDay,
                         @Value("${daman.ai.images-per-day:20}") int imagesPerDay) {
        this.textPerDay = textPerDay;
        this.imagesPerDay = imagesPerDay;
    }

    /** Thrown instead of calling the provider. The caller turns it into a 429 with this message. */
    public static class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(String message) {
            super(message);
        }
    }

    public synchronized void takeTextSlot() {
        rollDay();
        if (textUsed.get() >= textPerDay) {
            throw new BudgetExceededException(
                    "the daily limit of " + textPerDay + " AI drafts has been reached; it resets at midnight");
        }
        textUsed.incrementAndGet();
    }

    public synchronized void takeImageSlot() {
        rollDay();
        if (imagesUsed.get() >= imagesPerDay) {
            throw new BudgetExceededException(
                    "the daily limit of " + imagesPerDay + " AI pictures has been reached; it resets at midnight");
        }
        imagesUsed.incrementAndGet();
    }

    /** What is left today, for the portal to show before a button is pressed rather than after. */
    public synchronized Remaining remaining() {
        rollDay();
        return new Remaining(Math.max(0, textPerDay - textUsed.get()),
                             Math.max(0, imagesPerDay - imagesUsed.get()),
                             textPerDay, imagesPerDay);
    }

    public record Remaining(int text, int images, int textPerDay, int imagesPerDay) {}

    private void rollDay() {
        LocalDate today = LocalDate.now();
        if (!today.equals(day)) {
            day = today;
            textUsed.set(0);
            imagesUsed.set(0);
        }
    }
}

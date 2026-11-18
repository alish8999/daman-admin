package com.daman.admin.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The only thing standing between a stuck retry loop and a bill.
 *
 * <p>Nothing else in this portal spends money when a button is pressed, so the failure this
 * guards is one nobody plans for: a double-click, a page left reloading, a provider that times
 * out and is retried. None of them is malicious; all of them bill.
 */
class AiBudgetGuardTest {

    @Test
    void textAndImagesAreCountedSeparately() {
        AiBudgetGuard guard = new AiBudgetGuard(2, 1);

        guard.takeTextSlot();
        guard.takeImageSlot();

        AiBudgetGuard.Remaining left = guard.remaining();
        assertThat(left.text()).isEqualTo(1);
        assertThat(left.images()).isZero();
    }

    /** A picture costs many times a paragraph, so running out of one must not block the other. */
    @Test
    void exhaustingImagesLeavesTextAlone() {
        AiBudgetGuard guard = new AiBudgetGuard(5, 1);
        guard.takeImageSlot();

        assertThatThrownBy(guard::takeImageSlot)
                .isInstanceOf(AiBudgetGuard.BudgetExceededException.class);
        assertThatCode(guard::takeTextSlot).doesNotThrowAnyException();
    }

    @Test
    void theRefusalSaysWhatTheLimitIsAndWhenItLifts() {
        AiBudgetGuard guard = new AiBudgetGuard(1, 1);
        guard.takeTextSlot();

        assertThatThrownBy(guard::takeTextSlot)
                .hasMessageContaining("1")
                .hasMessageContaining("midnight");
    }

    @Test
    void remainingNeverGoesNegative() {
        AiBudgetGuard guard = new AiBudgetGuard(1, 1);
        guard.takeTextSlot();

        assertThat(guard.remaining().text()).isZero();
    }

    /** A cap of zero means the feature is off, not that one call slips through. */
    @Test
    void aZeroCapRefusesEverything() {
        AiBudgetGuard guard = new AiBudgetGuard(0, 0);

        assertThatThrownBy(guard::takeTextSlot)
                .isInstanceOf(AiBudgetGuard.BudgetExceededException.class);
        assertThatThrownBy(guard::takeImageSlot)
                .isInstanceOf(AiBudgetGuard.BudgetExceededException.class);
    }

    /** The portal shows the caps, so they have to come back with the counts. */
    @Test
    void remainingReportsTheCapsAsWellAsWhatIsLeft() {
        AiBudgetGuard guard = new AiBudgetGuard(60, 20);

        AiBudgetGuard.Remaining left = guard.remaining();
        assertThat(left.textPerDay()).isEqualTo(60);
        assertThat(left.imagesPerDay()).isEqualTo(20);
    }
}

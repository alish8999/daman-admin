package com.daman.admin.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checks that run on a model's answer before anybody sees it.
 *
 * <p>They exist because of one failure that is silent otherwise: a model asked for Arabic
 * sometimes returns English, or transliterated Arabic in Latin letters, and it looks like a
 * perfectly good answer. The owner reads this portal in Arabic and the shops read the result in
 * Arabic, so an English `titleAr` would travel all the way to a shop's screen before anyone
 * noticed.
 *
 * <p>Pure functions, no network: this is the half of the AI feature that can be tested at all.
 */
class AnnouncementDraftRulesTest {

    @Test
    void anArabicFieldMustActuallyBeArabic() {
        assertThat(AnnouncementDraftRules.isArabic("ميزة جديدة للمحل")).isTrue();
        assertThat(AnnouncementDraftRules.isArabic("A new feature")).isFalse();
        // Transliteration is the sneaky one: it reads as Arabic to a careless eye.
        assertThat(AnnouncementDraftRules.isArabic("meeza jdeede")).isFalse();
        assertThat(AnnouncementDraftRules.isArabic("")).isFalse();
        assertThat(AnnouncementDraftRules.isArabic(null)).isFalse();
    }

    /** A Latin brand name or a number inside an Arabic sentence is normal and must pass. */
    @Test
    void aLatinWordInsideArabicIsFine() {
        assertThat(AnnouncementDraftRules.isArabic("البيع من موبايل Android صار متاح")).isTrue();
        assertThat(AnnouncementDraftRules.isArabic("وفّر ٢٥$ على اشتراكك")).isTrue();
    }

    /** Mostly-Latin with one Arabic word is not an Arabic sentence. */
    @Test
    void oneArabicWordInAnEnglishSentenceIsNotArabic() {
        assertThat(AnnouncementDraftRules.isArabic("A new feature for your محل today")).isFalse();
    }

    @Test
    void anEnglishFieldMustNotComeBackInArabic() {
        assertThat(AnnouncementDraftRules.isLatin("Sell from a phone")).isTrue();
        assertThat(AnnouncementDraftRules.isLatin("البيع من الموبايل")).isFalse();
        assertThat(AnnouncementDraftRules.isLatin(null)).isFalse();
    }

    @Test
    void aDraftMissingAnyFieldIsRejected() {
        assertThat(AnnouncementDraftRules.problemWith(
                new AnnouncementDraft("Title", "عنوان", "Body text.", "نص عربي."))).isNull();

        assertThat(AnnouncementDraftRules.problemWith(
                new AnnouncementDraft(null, "عنوان", "Body.", "نص."))).isNotNull();
        assertThat(AnnouncementDraftRules.problemWith(
                new AnnouncementDraft("Title", "  ", "Body.", "نص."))).isNotNull();
    }

    @Test
    void aDraftWhoseArabicIsEnglishIsRejected() {
        String problem = AnnouncementDraftRules.problemWith(
                new AnnouncementDraft("Title", "Title in English", "Body.", "نص عربي."));

        assertThat(problem).isNotNull().contains("titleAr");
    }

    @Test
    void aDraftWhoseEnglishIsArabicIsRejected() {
        String problem = AnnouncementDraftRules.problemWith(
                new AnnouncementDraft("عنوان عربي", "عنوان", "Body.", "نص."));

        assertThat(problem).isNotNull().contains("titleEn");
    }

    /**
     * Length is trimmed, not rejected. A draft is something the owner edits before publishing, so
     * refusing a 330-character body and spending the call again would be worse than handing over
     * one sentence shorter.
     */
    @Test
    void anOverlongFieldIsTrimmedRatherThanRefused() {
        String longBody = "x".repeat(400);
        AnnouncementDraft trimmed = AnnouncementDraftRules.trim(
                new AnnouncementDraft("T", "ع", longBody, "ن".repeat(400)));

        assertThat(trimmed.bodyEn()).hasSize(AnnouncementDraftRules.BODY_MAX);
        assertThat(trimmed.bodyAr()).hasSize(AnnouncementDraftRules.BODY_MAX);
    }

    @Test
    void trimmingLeavesAShortDraftAlone() {
        AnnouncementDraft draft = new AnnouncementDraft("Title", "عنوان", "Body.", "نص.");

        assertThat(AnnouncementDraftRules.trim(draft)).isEqualTo(draft);
    }

    @Test
    void trimmingSurvivesNulls() {
        assertThat(AnnouncementDraftRules.trim(new AnnouncementDraft(null, null, null, null)))
                .isEqualTo(new AnnouncementDraft(null, null, null, null));
    }
}

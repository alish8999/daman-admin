package com.daman.admin.ai;

/**
 * What a model's answer has to satisfy before anyone sees it.
 *
 * <p>One failure is silent without this: a model asked for Arabic sometimes answers in English,
 * or in transliterated Arabic written in Latin letters, and the result looks like a perfectly
 * good draft. The owner reads this portal in Arabic and the shops read the announcement in
 * Arabic, so an English {@code titleAr} would reach a shop's screen before anyone noticed.
 *
 * <p>Pure functions with no network, so the half of this feature that can be tested, is.
 */
public final class AnnouncementDraftRules {

    private AnnouncementDraftRules() {}

    public static final int TITLE_MAX = 60;
    public static final int BODY_MAX = 320;

    /**
     * Whether this reads as an Arabic sentence.
     *
     * <p>Counts Arabic letters against Latin ones rather than looking for any Arabic at all: a
     * Latin brand name or a number inside an Arabic sentence is normal and must pass, while one
     * Arabic word dropped into an English sentence must not.
     */
    public static boolean isArabic(String text) {
        if (text == null || text.isBlank()) return false;
        int arabic = 0;
        int latin = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x0600 && c <= 0x06FF) arabic++;
            else if (Character.isLetter(c) && c < 0x0250) latin++;
        }
        return arabic > 0 && arabic >= latin;
    }

    /** The mirror: an English field that came back in Arabic is just as wrong. */
    public static boolean isLatin(String text) {
        if (text == null || text.isBlank()) return false;
        int arabic = 0;
        int latin = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x0600 && c <= 0x06FF) arabic++;
            else if (Character.isLetter(c) && c < 0x0250) latin++;
        }
        return latin > 0 && latin >= arabic;
    }

    /**
     * What is wrong with this draft, or null when nothing is.
     *
     * <p>Returns the field name so the message the owner sees says which half failed — "the
     * Arabic came back in English" is actionable, "the draft was rejected" is not.
     */
    public static String problemWith(AnnouncementDraft draft) {
        if (draft == null) return "the model returned nothing";
        if (blank(draft.titleEn())) return "titleEn was empty";
        if (blank(draft.titleAr())) return "titleAr was empty";
        if (blank(draft.bodyEn())) return "bodyEn was empty";
        if (blank(draft.bodyAr())) return "bodyAr was empty";
        if (!isLatin(draft.titleEn())) return "titleEn did not come back in English";
        if (!isLatin(draft.bodyEn())) return "bodyEn did not come back in English";
        if (!isArabic(draft.titleAr())) return "titleAr did not come back in Arabic";
        if (!isArabic(draft.bodyAr())) return "bodyAr did not come back in Arabic";
        return null;
    }

    /**
     * Cuts an over-long field to the limit instead of refusing the draft.
     *
     * <p>A draft is something the owner edits before publishing, so refusing a 330-character
     * body and paying for the call again would be worse than handing over one sentence shorter.
     */
    public static AnnouncementDraft trim(AnnouncementDraft draft) {
        if (draft == null) return null;
        return new AnnouncementDraft(
                cut(draft.titleEn(), TITLE_MAX),
                cut(draft.titleAr(), TITLE_MAX),
                cut(draft.bodyEn(), BODY_MAX),
                cut(draft.bodyAr(), BODY_MAX));
    }

    private static String cut(String text, int max) {
        if (text == null) return null;
        String t = text.trim();
        return t.length() <= max ? t : t.substring(0, max).trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

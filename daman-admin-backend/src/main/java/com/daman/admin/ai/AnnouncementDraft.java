package com.daman.admin.ai;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * The shape handed to the model as a structured output: exactly the four text fields an
 * announcement row needs.
 *
 * <p>Structured outputs rather than "reply with JSON": the SDK validates the response against
 * this record, so there is no hand-written parse here that could disagree with what arrives, and
 * a model that wandered off-format fails loudly instead of producing a half-filled row.
 *
 * <p><b>The annotations are the prompt.</b> Each description is sent to the model as part of the
 * schema, which is where the length and language rules belong — a sentence in the system prompt
 * is advice, a field description is attached to the field it governs.
 *
 * <p>{@code @JsonPropertyDescription} stays on {@code com.fasterxml.jackson.annotation} even in
 * this Jackson 3 project — the annotations package did not move. See CLAUDE.md.
 */
public record AnnouncementDraft(

        @JsonPropertyDescription(
                "Headline in ENGLISH. A plain statement of what is new, as a shopkeeper would say it. "
                + "At most 60 characters. No exclamation marks, no marketing superlatives.")
        String titleEn,

        @JsonPropertyDescription(
                "Headline in ARABIC. Natural Levantine Arabic that a Syrian shop owner would use, not "
                + "formal newspaper Arabic. At most 60 characters. No exclamation marks.")
        String titleAr,

        @JsonPropertyDescription(
                "Body in ENGLISH. Two or three short sentences saying what the feature does for the shop "
                + "and what it saves them. Concrete, no adjectives doing the work. At most 320 characters.")
        String bodyEn,

        @JsonPropertyDescription(
                "Body in ARABIC. The same content as bodyEn, written natively in Levantine Arabic — a "
                + "translation that reads like one is wrong. At most 320 characters.")
        String bodyAr) {}

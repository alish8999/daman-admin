package com.daman.admin.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.daman.admin.catalog.FeatureCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Writes an announcement draft, so the owner edits a paragraph instead of writing one in two
 * languages from nothing.
 *
 * <p><b>A draft, never a publication.</b> Nothing here saves a row or sets {@code published} —
 * the answer goes into the portal's form and the owner decides what survives. That is what makes
 * a mediocre draft cheap: it is a starting point, not an outcome.
 *
 * <p><b>Structured outputs, not "reply with JSON".</b> The SDK validates the answer against
 * {@link AnnouncementDraft}, so there is no hand-written parse that could disagree with what
 * arrived, and a model that wandered off-format fails loudly rather than half-filling a row.
 *
 * <p>Two things are borrowed from AdsBot's generator, which has run against this provider for
 * longer: anything that can fail is settled BEFORE the request is sent, so a mistake costs
 * nothing; and the two SDK exceptions are told apart, because one means nothing was billed and
 * the other means a reply arrived and was billed but could not be read.
 */
@Slf4j
@Service
public class AnnouncementDraftService {

    /** Thinking tokens count toward max_tokens, so leave room well above the small JSON answer. */
    private static final long MAX_TOKENS = 8000;

    private static final String SYSTEM = """
            You write short in-app announcements for Daman, a point-of-sale and accounting program \
            used by small retail shops in Syria and the Gulf.

            The reader is a shop owner, not a buyer of software. They are busy, they are not \
            technical, and they have seen a lot of advertising. Write the way a shopkeeper would \
            tell another shopkeeper what changed.

            Rules:
            - Say what the thing does and what it saves them. Nothing else.
            - No superlatives, no exclamation marks, no "revolutionary", no "powerful".
            - Never invent a price, a date, a discount or a capability you were not told about.
            - The Arabic is written natively in Levantine Arabic, as a Syrian shop owner speaks. \
            It is not a translation of the English and must not read like one.
            - If you are given a feature name, the announcement is about that feature and nothing else.
            """;

    private final AnthropicClient client;
    private final AiBudgetGuard budget;
    private final String model;

    public AnnouncementDraftService(AnthropicClient client,
                                    AiBudgetGuard budget,
                                    @Value("${daman.ai.text-model:claude-opus-5-5}") String model) {
        this.client = client;
        this.budget = budget;
        this.model = model;
    }

    /** What the owner typed, plus the feature the announcement is about. */
    public record DraftRequest(String intent, String featureKey) {}

    /**
     * One draft. Throws {@link AiDraftException} with a message meant for the owner's screen —
     * every failure here is something a person has to read and act on.
     */
    public AnnouncementDraft write(DraftRequest request) {
        String intent = request == null || request.intent() == null ? "" : request.intent().trim();
        if (intent.isEmpty()) {
            throw new AiDraftException("say in a line what the announcement should be about");
        }

        // Settled before the call: an unknown feature key must cost nothing, not be discovered
        // after the model has written a paragraph about something that does not exist.
        String featureLine = featureLine(request.featureKey());

        budget.takeTextSlot();

        StructuredMessageCreateParams<AnnouncementDraft> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .system(SYSTEM)
                // LOW: four short fields from a one-line brief. Higher effort buys nothing here
                // and the owner is waiting at a form.
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .outputConfig(AnnouncementDraft.class)
                .addUserMessage(userMessage(intent, featureLine))
                .build();

        StructuredMessage<AnnouncementDraft> reply;
        try {
            reply = client.messages().create(params);
        } catch (AnthropicInvalidDataException e) {
            // A 2xx arrived and could not be read: billed, and the slot is already spent.
            log.warn("Announcement draft: a reply arrived but could not be read", e);
            throw new AiDraftException("the model's answer could not be read; try again");
        } catch (AnthropicException e) {
            // Non-2xx, transport failure or timeout: nothing was billed.
            log.warn("Announcement draft: the provider call failed", e);
            throw new AiDraftException("could not reach the writing service; try again in a moment");
        }

        if (reply.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            throw new AiDraftException("the model declined to write this one; reword the brief");
        }

        AnnouncementDraft draft = reply.content().stream()
                .flatMap(cb -> cb.text().stream())
                .map(typed -> typed.text())
                .findFirst()
                .orElse(null);

        String problem = AnnouncementDraftRules.problemWith(draft);
        if (problem != null) {
            log.warn("Announcement draft rejected: {}", problem);
            throw new AiDraftException("the draft came back wrong (" + problem + "); try again");
        }
        return AnnouncementDraftRules.trim(draft);
    }

    /**
     * The feature's own name and price, resolved from the catalogue here rather than trusted
     * from the caller — the same rule the announcement row follows, for the same reason.
     */
    private static String featureLine(String featureKey) {
        if (featureKey == null || featureKey.isBlank()) {
            return "This announcement is not about a particular paid feature.";
        }
        Optional<FeatureCatalog.Entry> entry = FeatureCatalog.ENTRIES.stream()
                .filter(e -> e.key().equals(featureKey))
                .findFirst();
        if (entry.isEmpty()) {
            throw new AiDraftException("unknown feature: " + featureKey);
        }
        FeatureCatalog.Entry e = entry.get();
        String price = e.contactUs() ? "priced on request"
                : e.priceUsd() == null ? "no price listed"
                : e.priceUsd() == 0 ? "included at no extra cost"
                : "$" + e.priceUsd();
        // The price is CONTEXT, not copy: the card shows the badge itself, and a number written
        // into the body is the stale-price problem the row avoids by not having a price column.
        return "The announcement is about the feature '" + e.key() + "' (" + price + "). "
                + "Do NOT write the price into the text — it is shown separately.";
    }

    private static String userMessage(String intent, String featureLine) {
        return featureLine + "\n\nWhat the owner wants to say:\n" + intent;
    }

    /** A failure with a message meant for a person, not a stack trace. */
    public static class AiDraftException extends RuntimeException {
        public AiDraftException(String message) {
            super(message);
        }
    }
}

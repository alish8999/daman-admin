package com.daman.admin.ai;

import com.daman.admin.service.AnnouncementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Base64;
import java.util.Locale;

/**
 * Draws a picture for an announcement, through Gemini — the same provider and the same request
 * shape AdsBot already uses for ad images.
 *
 * <p><b>The logo is not part of this, deliberately.</b> An image model cannot reproduce a
 * specific logo: it draws something logo-SHAPED, with the letters wrong and the colours near, and
 * differently every time. Asking for one would produce a convincing forgery of the owner's own
 * brand. The right answer is to composite the real file over the generated picture afterwards,
 * which the owner has deferred — so until then the prompt tells the model to leave a clear band
 * for it and to put no text and no logo in the image at all.
 *
 * <p>Everything that can fail is settled before the request, so a mistake costs nothing. A 2xx
 * that cannot be read is told apart from a non-2xx, because the first was billed and the second
 * was not.
 */
@Slf4j
@Service
public class AnnouncementImageService {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /**
     * Added to whatever the owner asks for. The three rules exist because of what this picture
     * is FOR: a card on a shop's features page, read on a small screen, next to a price badge
     * and a button this image must not imitate.
     */
    private static final String STYLE_RULES = """

            Style requirements, which override anything above that conflicts with them:
            - A clean, simple illustration. No photorealism, no stock-photo look.
            - NO text, NO words, NO letters, NO numbers and NO logo anywhere in the image. \
            Text is added afterwards and anything you draw will be wrong or duplicated.
            - Leave the lower third visually calm and uncluttered.
            - 16:9, bright, works on a small screen. No borders, no frames, no drop shadows, \
            no user-interface elements, no buttons, no mock screenshots of an app.
            """;

    private final AiBudgetGuard budget;
    private final AnnouncementService announcements;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public AnnouncementImageService(AiBudgetGuard budget,
                                    AnnouncementService announcements,
                                    @Value("${daman.ai.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl,
                                    @Value("${daman.ai.gemini.api-key:}") String apiKey,
                                    @Value("${daman.ai.image-model:gemini-2.5-flash-image}") String model) {
        this.budget = budget;
        this.announcements = announcements;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    /**
     * One picture for the given brief.
     *
     * <p>The result is validated against {@link AnnouncementService}'s own limits before it is
     * returned, so an oversized or odd-typed image fails here rather than at the moment the
     * owner tries to attach it.
     */
    public Picture draw(String brief) {
        String text = brief == null ? "" : brief.trim();
        if (text.isEmpty()) {
            throw new AnnouncementDraftService.AiDraftException(
                    "say in a line what the picture should show");
        }
        if (apiKey == null || apiKey.isBlank()) {
            // Nothing was sent, so no slot is taken and nothing was billed.
            throw new AnnouncementDraftService.AiDraftException(
                    "GEMINI_API_KEY is not set on the server, so pictures cannot be drawn yet");
        }

        budget.takeImageSlot();

        String raw;
        try {
            raw = RestClient.builder().baseUrl(baseUrl).build()
                    .post()
                    .uri("/models/{model}:generateContent", model)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(text + STYLE_RULES))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            log.warn("Announcement image: provider returned {}", e.getStatusCode(), e);
            throw new AnnouncementDraftService.AiDraftException(
                    "the drawing service refused the request; reword the brief and try again");
        } catch (RestClientException e) {
            log.warn("Announcement image: the provider call failed", e);
            throw new AnnouncementDraftService.AiDraftException(
                    "could not reach the drawing service; try again in a moment");
        }

        // From here a 2xx has arrived and the call is billed, whatever goes wrong next.
        try {
            JsonNode root = JSON.readTree(raw);
            for (JsonNode part : root.path("candidates").path(0).path("content").path("parts")) {
                JsonNode inline = part.has("inlineData") ? part.get("inlineData") : part.get("inline_data");
                if (inline != null && inline.hasNonNull("data")) {
                    String mime = inline.path("mimeType")
                            .asText(inline.path("mime_type").asText("image/png"))
                            .toLowerCase(Locale.ROOT);
                    byte[] bytes = Base64.getDecoder().decode(inline.get("data").asText());
                    // The model returns whatever size it liked and this call is already billed,
                    // so an oversized picture is re-encoded to fit rather than thrown away.
                    Picture picture = AnnouncementImageFitter.fit(
                            bytes, mime, AnnouncementService.IMAGE_MAX_BYTES);
                    // The same gate every other picture passes through, so an AI image cannot
                    // enter by a side door that skips the size and type rules.
                    announcements.validateImage(picture.bytes(), picture.contentType());
                    return picture;
                }
            }
            String why = root.path("candidates").path(0).path("finishReason").asText("");
            log.warn("Announcement image: the reply held no picture (finishReason={})", why);
            throw new AnnouncementDraftService.AiDraftException(
                    "the drawing service returned no picture; try a different brief");
        } catch (AnnouncementDraftService.AiDraftException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            // validateImage's refusal, or undecodable base64.
            throw new AnnouncementDraftService.AiDraftException(
                    "the picture that came back could not be used: " + e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Announcement image: the reply could not be read", e);
            throw new AnnouncementDraftService.AiDraftException(
                    "the drawing service's answer could not be read; try again");
        }
    }

    private static String requestBody(String prompt) {
        ObjectNode body = JSON.createObjectNode();
        ArrayNode parts = body.putArray("contents").addObject().putArray("parts");
        parts.addObject().put("text", prompt);
        return body.toString();
    }
}

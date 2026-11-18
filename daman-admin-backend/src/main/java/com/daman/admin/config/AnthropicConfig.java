package com.daman.admin.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * The Anthropic client used to draft announcements.
 *
 * <p><b>A missing key must not stop the portal starting.</b> Everything else here — clients,
 * licences, builds, the fleet — has nothing to do with AI, and refusing to boot over an unset
 * environment variable would take the whole admin down for a feature nobody may use that day.
 * So the client is built with a placeholder and the first draft fails with a 401 that says so.
 *
 * <p>The base URL is a property only so a test can point it somewhere else.
 */
@Slf4j
@Configuration
public class AnthropicConfig {

    @Bean
    public AnthropicClient anthropicClient(
            @Value("${daman.ai.anthropic.base-url:https://api.anthropic.com}") String baseUrl,
            @Value("${daman.ai.anthropic.api-key:}") String apiKey,
            @Value("${daman.ai.anthropic.max-retries:2}") int maxRetries) {
        String key = apiKey;
        if (key == null || key.isBlank()) {
            log.warn("ANTHROPIC_API_KEY is not set: AI announcement drafts will fail until it is.");
            key = "missing-api-key";
        }
        return AnthropicOkHttpClient.builder()
                .baseUrl(baseUrl)
                .apiKey(key)
                .maxRetries(maxRetries)
                .timeout(Duration.ofSeconds(90))
                .build();
    }
}

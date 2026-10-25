package com.daman.admin.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngressEditorTest {

    /** The live /etc/cloudflared/config.yml, read 2026-10-24. */
    private static final String LIVE = """
            tunnel: 4f0ba07b-dfae-4929-bf81-3cefe14cdaaa
            credentials-file: /root/.cloudflared/4f0ba07b-dfae-4929-bf81-3cefe14cdaaa.json

            ingress:
              - hostname: admin-api.damansoft.com
                service: http://localhost:8083
              - hostname: viora-api.damansoft.com
                service: http://localhost:8090
              - service: http_status:404
            """;

    private static final String CATCH_ALL = "  - service: http_status:404";
    private static final String ACME_RULE =
            "  - hostname: acme-api.damansoft.com\n    service: http://localhost:8101\n";

    @Test
    void addedRuleGoesBeforeTheCatchAll() {
        String out = IngressEditor.addRule(LIVE, "acme-api.damansoft.com", 8101);
        assertThat(out).contains("""
                  - hostname: viora-api.damansoft.com
                    service: http://localhost:8090
                  - hostname: acme-api.damansoft.com
                    service: http://localhost:8101
                  - service: http_status:404
                """);
    }

    @Test
    void existingRulesSurviveVerbatim() {
        String out = IngressEditor.addRule(LIVE, "acme-api.damansoft.com", 8101);
        assertThat(out).startsWith("""
                tunnel: 4f0ba07b-dfae-4929-bf81-3cefe14cdaaa
                credentials-file: /root/.cloudflared/4f0ba07b-dfae-4929-bf81-3cefe14cdaaa.json

                ingress:
                  - hostname: admin-api.damansoft.com
                    service: http://localhost:8083
                  - hostname: viora-api.damansoft.com
                    service: http://localhost:8090
                """);
        // Removing only the added lines must give back the original byte-for-byte.
        assertThat(out.replace(ACME_RULE, "")).isEqualTo(LIVE);
    }

    @Test
    void catchAllRemainsTheLastEntry() {
        String out = IngressEditor.addRule(LIVE, "acme-api.damansoft.com", 8101);
        assertThat(out).endsWith(CATCH_ALL + "\n");
        assertThat(out.split("http_status:404", -1)).hasSize(2);
    }

    @Test
    void addingTheSameHostnameTwiceIsIdempotent() {
        String once = IngressEditor.addRule(LIVE, "acme-api.damansoft.com", 8101);
        String twice = IngressEditor.addRule(once, "acme-api.damansoft.com", 8101);
        assertThat(twice).isEqualTo(once);
        assertThat(twice.split("acme-api.damansoft.com", -1)).hasSize(2);
    }

    @Test
    void addingAnExistingHostnameOnADifferentPortIsRefused() {
        assertThatThrownBy(() -> IngressEditor.addRule(LIVE, "viora-api.damansoft.com", 9999))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void removeRuleDropsOnlyThatHostname() {
        String added = IngressEditor.addRule(LIVE, "acme-api.damansoft.com", 8101);
        assertThat(IngressEditor.removeRule(added, "acme-api.damansoft.com")).isEqualTo(LIVE);
    }

    @Test
    void removeRuleOnAbsentHostnameIsANoOp() {
        assertThat(IngressEditor.removeRule(LIVE, "nobody-api.damansoft.com")).isEqualTo(LIVE);
    }

    @Test
    void removeRuleKeepsTheCatchAllAndNeighbours() {
        String added = IngressEditor.addRule(LIVE, "acme-api.damansoft.com", 8101);
        String out = IngressEditor.removeRule(added, "viora-api.damansoft.com");
        assertThat(out).contains("admin-api.damansoft.com", "acme-api.damansoft.com")
                .doesNotContain("viora").endsWith(CATCH_ALL + "\n");
    }

    @Test
    void refusesAFileWithNoCatchAll() {
        String noCatchAll = LIVE.replace(CATCH_ALL + "\n", "");
        assertThatThrownBy(() -> IngressEditor.addRule(noCatchAll, "acme-api.damansoft.com", 8101))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("catch-all");
    }

    @Test
    void refusesAHostnameThatCouldInjectYaml() {
        assertThatThrownBy(() -> IngressEditor.addRule(LIVE, "x.com\n  - service: evil", 8101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outputHasOnlyLfLineEndings() {
        String crlf = LIVE.replace("\n", "\r\n");
        assertThat(IngressEditor.addRule(crlf, "acme-api.damansoft.com", 8101)).doesNotContain("\r");
        assertThat(IngressEditor.removeRule(crlf, "viora-api.damansoft.com")).doesNotContain("\r");
    }

    @Test
    void commentsAndFileWithoutTrailingNewlineSurvive() {
        String src = "# hand-maintained\n" + LIVE.stripTrailing();
        String out = IngressEditor.addRule(src, "acme-api.damansoft.com", 8101);
        assertThat(out).startsWith("# hand-maintained\n").endsWith(CATCH_ALL);
    }
}

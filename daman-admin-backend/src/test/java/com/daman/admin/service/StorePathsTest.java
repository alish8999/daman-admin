package com.daman.admin.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class StorePathsTest {

    @Test void allowsBothRealShapes() {
        assertThat(StorePaths.isAllowedDataDir("/root/stores/acme", "acme")).isTrue();
        assertThat(StorePaths.isAllowedDataDir("/root/.daman/viora", "viora")).isTrue();
    }

    @Test void refusesAnythingElse() {
        // Each of these has reached a delete in some real incident class: a parent, a traversal,
        // another store's directory, and the box's own root home.
        assertThat(StorePaths.isAllowedDataDir("/root/.daman", "viora")).isFalse();
        assertThat(StorePaths.isAllowedDataDir("/root/stores/acme/..", "acme")).isFalse();
        assertThat(StorePaths.isAllowedDataDir("/root/stores/other", "acme")).isFalse();
        assertThat(StorePaths.isAllowedDataDir("/root/stores/acme", "other")).isFalse();
        assertThat(StorePaths.isAllowedDataDir("/root", "acme")).isFalse();
        assertThat(StorePaths.isAllowedDataDir("", "acme")).isFalse();
        assertThat(StorePaths.isAllowedDataDir("/root/stores/", "")).isFalse();
    }

    @Test void refusesNullsAndUnsafeCodes() {
        assertThat(StorePaths.isAllowedDataDir(null, "acme")).isFalse();
        assertThat(StorePaths.isAllowedDataDir("/root/stores/acme", null)).isFalse();
        assertThat(StorePaths.isAllowedDataDir("/root/stores/..", "..")).isFalse();
        assertThat(StorePaths.isAllowedDataDir("/root/stores/a/b", "a/b")).isFalse();
        assertThat(StorePaths.isAllowedDataDir("/root/stores/acme/", "acme")).isFalse();
    }

    @Test void removedBackupZipNamesByCodeAndDate() {
        assertThat(StorePaths.removedBackupZip("acme", LocalDate.of(2026, 10, 26)))
                .isEqualTo("/root/removed-stores/acme-2026-10-26.zip");
    }
}

package com.daman.admin.service;

import com.daman.admin.entity.HostedInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StoreUnitRendererTest {

    private static HostedInstance instance(String clientCode, String subdomain, int port) {
        HostedInstance i = new HostedInstance();
        i.setClientCode(clientCode);
        i.setSubdomain(subdomain);
        i.setPort(port);
        i.setServiceName("daman-store-" + subdomain);
        i.setJwtSecret(StoreUnitRenderer.newJwtSecret());
        i.setApiHostname(subdomain + "-api.damansoft.com");
        return i;
    }

    private static String render(HostedInstance i) {
        return StoreUnitRenderer.render(i);
    }

    @Test void unitIsolatesUserHomePerStore() {            // Spec 3.1/3.2 - the silent-corruption bug
        String u = render(instance("alhasan-pharmacy", "alhasan", 8091));
        assertTrue(u.contains("-Duser.home=/root/stores/alhasan-pharmacy"));
    }

    @Test void unitCarriesPortClientCodeProfileJwtAndBackupDir() {
        String u = render(instance("alhasan-pharmacy", "alhasan", 8091));
        assertTrue(u.contains("Environment=DAMAN_PORT=8091"));
        assertTrue(u.contains("Environment=DAMAN_CLIENT_CODE=alhasan-pharmacy"));
        assertTrue(u.contains("Environment=SPRING_PROFILES_ACTIVE=desktop"));
        assertTrue(u.contains("Environment=DAMAN_JWT_SECRET="));
        assertTrue(u.contains("Environment=DAMAN_BACKUP_DIR=/root/stores/alhasan-pharmacy/backups"));
    }

    @Test void unitRestartsAlwaysNotOnFailure() {          // in-app restore exits 0 on purpose
        assertTrue(render(instance("c", "s", 8091)).contains("Restart=always"));
    }

    @Test void unitUsesTheSharedJarPath() {
        assertTrue(render(instance("c", "s", 8091)).contains("/root/daman-store.jar"));
    }

    @Test void unitHasOnlyLfLineEndings() {                // a stray CR breaks systemd
        assertFalse(render(instance("c", "s", 8091)).contains("\r"));
    }

    @Test void jwtSecretsAreLongRandomAndDistinct() {
        String a = StoreUnitRenderer.newJwtSecret(), b = StoreUnitRenderer.newJwtSecret();
        assertNotEquals(a, b);
        assertTrue(a.length() >= 48);
        assertNotEquals("mySecretKey12345678901234567890123456789012345678901234567890", a);
    }

    // ── Google Drive backup redirect (added 2026-10-29) ───────────────────────

    @Test void unitPinsThisStoresOwnGoogleRedirect() {
        // A hosted store runs SPRING_PROFILES_ACTIVE=desktop, so without this it inherits
        // application-desktop.yml's http://localhost:<port> default and Google sends the shop
        // owner's browser to their OWN machine. It must be this store's public API origin, https,
        // and the real callback path.
        String u = render(instance("alhasan-pharmacy", "alhasan", 8091));
        assertTrue(u.contains("Environment=DAMAN_GOOGLE_REDIRECT_URI=https://alhasan-api.damansoft.com/api/backup/google-drive/callback"),
                "unit must pin this store's own https redirect, got:\n" + u);
        assertFalse(u.contains("DAMAN_GOOGLE_REDIRECT_URI=http://localhost"),
                "a hosted store must never carry the desktop loopback default");
    }

    @Test void eachStoreGetsItsOwnRedirectNotAnotherStores() {
        String a = render(instance("alhasan-pharmacy", "alhasan", 8091));
        String b = render(instance("bistro-cafe", "bistro", 8092));
        assertTrue(a.contains("https://alhasan-api.damansoft.com/api/backup/google-drive/callback"));
        assertTrue(b.contains("https://bistro-api.damansoft.com/api/backup/google-drive/callback"));
        assertFalse(a.contains("bistro-api"), "store units must not cross-reference each other");
    }

    @Test void googleCredentialsComeFromAnOptionalRootOnlyFileNotTheUnit() {
        // The client secret is a credential and units are world-readable, so it lives in one
        // root-only file shared by every store. The leading '-' keeps a host that never configured
        // Google Drive bootable.
        String u = render(instance("alhasan-pharmacy", "alhasan", 8091));
        assertTrue(u.contains("EnvironmentFile=-/etc/daman/google.env"));
        // Match an actual assignment, not the word: the operator instructions in the unit comment
        // legitimately mention the variable name.
        assertFalse(u.contains("Environment=DAMAN_GOOGLE_CLIENT_SECRET="),
                "the client secret must never be written into a unit file");
        assertFalse(u.contains("Environment=DAMAN_GOOGLE_CLIENT_ID="),
                "the client id belongs in the shared file too, so stores stay identical");
    }

    @Test void refusesToRenderWithoutAnApiHostname() {
        // Rendering "https://null/..." would start the store fine and fail only when someone tried
        // to connect Google Drive, with a message that pointed at Google rather than at this.
        HostedInstance i = instance("alhasan-pharmacy", "alhasan", 8091);
        i.setApiHostname(null);
        assertThrows(IllegalStateException.class, () -> render(i));
        i.setApiHostname("   ");
        assertThrows(IllegalStateException.class, () -> render(i));
    }
}

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
}

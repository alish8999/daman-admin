package com.daman.admin.service;

import com.daman.admin.entity.HostedInstance;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Renders the systemd unit for one hosted store. Modelled on the unit written by
 * deploy-store-backend.ps1 (the frozen viora store). Pure text, no I/O.
 */
public final class StoreUnitRenderer {

    static final String SHARED_JAR = "/root/daman-store.jar";

    /**
     * Per-store JVM heap, in MB — the single definition of it. The unit template below formats this
     * value into its own {@code -Xmx}, and {@code HostedStoreProvisioningService}'s capacity guard
     * reserves it per existing store, so the number the guard reasons about and the number the JVM
     * is actually launched with cannot drift apart. It used to be a literal {@code -Xmx768m} here
     * and a separate {@code 768} constant there, held together by a comment.
     */
    public static final int HEAP_MB = 768;

    private static final SecureRandom RANDOM = new SecureRandom();

    private StoreUnitRenderer() {}

    /** 48 random bytes, base64url without padding = 64 chars. */
    public static String newJwtSecret() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * The one derivation of a store's data directory, shared with the provisioner so the registry
     * row and the running unit can never disagree. Derived from the client code, never read back
     * from {@code HostedInstance.dataDir}: viora's legacy row carries {@code /root/.daman/viora},
     * which is the truth for viora and would be wrong in a newly rendered unit.
     */
    public static String dataDir(String clientCode) {
        return "/root/stores/" + clientCode;
    }

    public static String render(HostedInstance i) {
        if (i.getJwtSecret() == null || i.getJwtSecret().isBlank()) {
            // Without a per-store secret the backend falls back to the public default: forgeable tokens.
            throw new IllegalStateException("HostedInstance has no jwtSecret; refusing to render a unit");
        }
        if (i.getApiHostname() == null || i.getApiHostname().isBlank()) {
            // It feeds DAMAN_GOOGLE_REDIRECT_URI. Rendering "https://null/..." would start the store
            // fine and fail only when someone tried to connect Google Drive, with a message pointing
            // at Google rather than at this.
            throw new IllegalStateException("HostedInstance has no apiHostname; refusing to render a unit");
        }
        String home = dataDir(i.getClientCode());
        String unit = """
                [Unit]
                Description=Daman Store Backend (%1$s)
                After=network.target

                [Service]
                Type=simple
                User=root
                WorkingDirectory=/root
                Environment=DAMAN_PORT=%2$d
                Environment=DAMAN_CLIENT_CODE=%1$s
                Environment=SPRING_PROFILES_ACTIVE=desktop
                Environment=DAMAN_JWT_SECRET=%3$s
                Environment=DAMAN_BACKUP_DIR=%4$s/backups
                # Google Drive backup: a hosted store runs SPRING_PROFILES_ACTIVE=desktop, so without
                # this it would inherit application-desktop.yml's http://localhost:<port> default and
                # Google would send the shop owner's BROWSER to their own machine. Not secret, and it
                # differs per store, so it belongs in the unit.
                Environment=DAMAN_GOOGLE_REDIRECT_URI=https://%8$s/api/backup/google-drive/callback
                # The WEB-type OAuth client id and secret, shared by every store on this host. Kept in
                # one root-only file rather than copied into each unit: it is a credential, and units
                # are world-readable. The leading '-' makes it optional, so a host that has never set
                # up Google Drive backup still starts normally - the add-on simply reports itself as
                # not configured. Create it as:
                #   printf 'DAMAN_GOOGLE_CLIENT_ID=...\\nDAMAN_GOOGLE_CLIENT_SECRET=...\\n' > /etc/daman/google.env
                #   chmod 600 /etc/daman/google.env
                # A DESKTOP-type client will NOT work here: it cannot register a fixed https redirect.
                EnvironmentFile=-/etc/daman/google.env
                ExecStart=/usr/bin/java -Xmx%7$dm -Duser.home=%4$s -jar %5$s
                # "always", not "on-failure": Settings -> Restore DB (and other in-app restarts) make the backend exit
                # with status 0 on purpose, expecting Electron's shell to relaunch it. on-failure treats a clean exit
                # as "done" and leaves the service dead. A manual 'systemctl stop' is still respected.
                Restart=always
                RestartSec=5
                StandardOutput=append:/var/log/%6$s.log
                StandardError=append:/var/log/%6$s.log

                [Install]
                WantedBy=multi-user.target
                """.formatted(i.getClientCode(), i.getPort(), i.getJwtSecret(), home, SHARED_JAR,
                        i.getServiceName(), HEAP_MB, i.getApiHostname());
        return unit.replace("\r\n", "\n");
    }
}

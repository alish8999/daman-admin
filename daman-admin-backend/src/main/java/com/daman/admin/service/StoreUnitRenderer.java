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
     * reserves {@link #MEMORY_BUDGET_MB} per existing store, so the number the guard reasons about
     * and the number the JVM is actually launched with cannot drift apart.
     *
     * <p><b>768 -> 320 (2026-11-08), from measurement on the live box.</b> Three stores for three
     * different businesses were each sitting at 674 / 719 / 758 MB of ANONYMOUS memory (cgroup
     * {@code memory.stat}; the page-cache share was 1-7 MB, so this was real memory, not file
     * cache). Usage that uniform across shops of different sizes is not data-driven — it is G1
     * expanding the heap toward whatever ceiling it is given and never returning it, with
     * {@code peak == current} on all three. The ceiling was the cause, not the symptom.
     *
     * <p>A single shop's working set here is small: one SQLite file, one cashier screen, a few
     * reports. What does push real bytes through the heap is invoice/PDF generation and product
     * images (stored as data URLs), so this is deliberately NOT cut to the bone — 320 MB with
     * SerialGC, then measured again before anyone goes lower.
     */
    public static final int HEAP_MB = 320;

    /**
     * What one store is expected to cost the host in total, in MB: the heap plus metaspace, code
     * cache, thread stacks and native buffers. The provisioning capacity guard and the fleet
     * dashboard's capacity strip BOTH reserve this, so a store can never be admitted on a budget
     * the dashboard did not report.
     *
     * <p>Measured, not guessed (2026-11-08, on the live box, the test store under the flags above):
     * 391 MB anonymous at idle and after exercise, peak 401 MB. 480 leaves ~90 MB of headroom for
     * the things that genuinely spike — invoice/PDF generation and product images, which are stored
     * as data URLs and pass through the heap.
     *
     * <p>No {@code MemoryHigh=}/{@code MemoryMax=} in the unit, deliberately. These cgroups are
     * almost pure anonymous memory (page cache measured at 1-7 MB) and the box has no swap, so
     * there is nothing for the kernel to reclaim: a soft limit would stall the store in a reclaim
     * loop rather than trim it, and a hard one plus {@code Restart=always} is a restart loop in the
     * middle of a trading day. The budget is enforced by refusing the next store, not by the kernel.
     */
    public static final int MEMORY_BUDGET_MB = 480;

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
                # Marks this as a store WE host, which the store's own backend cannot otherwise
                # tell: it runs the same `desktop` profile an installed copy does. It locks the
                # backup folder — the shop owner's Settings page hides the path control and the
                # backend refuses PUT /api/backup-settings. Without it the owner could point
                # their database dumps anywhere on this shared box (this JVM is root), and both
                # the fleet dashboard's "last backup" and the pre-removal safety backup read
                # %4$s/backups and would quietly find nothing.
                Environment=DAMAN_HOSTED=true
                # Google Drive backup: a hosted store runs SPRING_PROFILES_ACTIVE=desktop, so without
                # these it would inherit application-desktop.yml's http://localhost:<port> default and
                # Google would send the shop owner's BROWSER to their own machine. Neither is secret.
                #
                # The redirect URI is the SAME for every store, and is the only one registered on the
                # Google OAuth client. It used to be this store's own https origin, which meant every
                # newly provisioned store needed someone to paste its URI into the Google Cloud
                # console before Drive backup worked — Google has no API for adding one (the
                # projects.locations.oauthClients API is Workforce Identity Federation only). The
                # admin backend now relays instead: it reads the target store out of the OAuth
                # `state` and 302s there, refusing any host that is not in hosted_instances.
                # See GoogleDriveRelayController.
                Environment=DAMAN_GOOGLE_REDIRECT_URI=https://admin-api.damansoft.com/api/backup/google-drive/callback
                # This store's own origin, which it encodes into `state` so the relay knows where to
                # send the code back to. This is the per-store half.
                Environment=DAMAN_GOOGLE_RETURN_ORIGIN=https://%8$s
                # The WEB-type OAuth client id and secret, shared by every store on this host. Kept in
                # one root-only file rather than copied into each unit: it is a credential, and units
                # are world-readable. The leading '-' makes it optional, so a host that has never set
                # up Google Drive backup still starts normally - the add-on simply reports itself as
                # not configured. Create it as:
                #   printf 'DAMAN_GOOGLE_CLIENT_ID=...\\nDAMAN_GOOGLE_CLIENT_SECRET=...\\n' > /etc/daman/google.env
                #   chmod 600 /etc/daman/google.env
                # A DESKTOP-type client will NOT work here: it cannot register a fixed https redirect.
                EnvironmentFile=-/etc/daman/google.env
                # The desktop app has launched this same jar this way for years (electron/main.js):
                # TieredStopAtLevel=1 + SerialGC + a small -Xms. A hosted store is the same thing —
                # one shop, one process — and the server was the only place still on the G1 default,
                # which is what grew each store to ~700 MB. Measured on the box 2026-11-08: 747 MB
                # anon before, 391 MB after. NO MaxMetaspaceSize: capping it at 96m OOM-killed the
                # store on boot (Spring + Hibernate need far more). The GC log is how used-vs-
                # committed heap can be read at all on a box with a JRE and no jcmd.
                ExecStart=/usr/bin/java -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xms64m -Xmx%7$dm -Xss512k \
                    -Xlog:gc+heap=info:file=/var/log/%6$s-gc.log:time,uptime:filecount=3,filesize=5M \
                    -Duser.home=%4$s -jar %5$s
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

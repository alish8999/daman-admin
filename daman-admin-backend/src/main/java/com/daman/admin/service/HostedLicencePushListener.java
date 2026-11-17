package com.daman.admin.service;

import com.daman.admin.event.LicenceRemintedEvent;
import com.daman.admin.repository.HostedInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Hands a hosted store its re-minted licence immediately, rather than leaving it to the
 * 12-hour renewal poll.
 *
 * <p><b>The only place in the fleet where instant delivery is possible.</b> A desktop install has
 * to come and fetch its key — nothing can reach into a shop's machine. A hosted store runs on the
 * same box as this backend, which already knows its port and already calls
 * {@link ServerOps#activateLicence} during provisioning. Eight licences across five clients are
 * hosted today.
 *
 * <p><b>Fail-soft, deliberately.</b> The licence row is saved and committed before this runs, so
 * nothing here may throw: a store that is down, restarting, or refusing the key must not undo a
 * correct mint. It will collect the same key on its next poll, since hosted stores run the
 * desktop profile and therefore poll like any other install.
 *
 * <p>Only a store reported {@code ACTIVE} is pushed to. A {@code SUSPENDED} one is deliberately
 * stopped and a {@code PROVISIONING} one is mid-build; poking either achieves nothing and logs
 * noise that looks like a fault.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HostedLicencePushListener {

    private static final String ACTIVE = "ACTIVE";

    private final HostedInstanceRepository hostedInstanceRepository;
    private final ServerOps ops;

    @EventListener(LicenceRemintedEvent.class)
    public void onReminted(LicenceRemintedEvent event) {
        try {
            var instance = hostedInstanceRepository.findByClientCode(event.clientCode()).orElse(null);
            if (instance == null) return;          // an ordinary desktop licence
            if (!ACTIVE.equalsIgnoreCase(instance.getStatus() == null ? "" : instance.getStatus().trim())) {
                log.debug("Hosted store {} is {}; not pushing its re-minted licence",
                        event.clientCode(), instance.getStatus());
                return;
            }
            ops.activateLicence(instance.getPort(), event.licenseKey());
            log.info("Pushed re-minted licence straight into hosted store {} on port {}",
                    event.clientCode(), instance.getPort());
        } catch (RuntimeException e) {
            // The mint stands. The store picks the key up on its next poll.
            log.warn("Could not push the re-minted licence to hosted store {}: {}",
                    event.clientCode(), e.toString());
        }
    }
}

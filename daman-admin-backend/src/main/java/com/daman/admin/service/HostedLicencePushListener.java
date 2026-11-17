package com.daman.admin.service;

import com.daman.admin.event.LicenceRemintedEvent;
import com.daman.admin.repository.HostedInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
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
 * <p><b>Only that store's OWN key is pushed.</b> A client can hold several licences — {@code viora}
 * has three (two shop tills and its hosted store) and {@code daman} two — so matching on
 * {@code clientCode} alone would fire every re-minted key at the live store, most of them belonging
 * to a shop machine. That is not harmless: the store's activation runs
 * {@code LicenseService.verifyLicenseKey}, which clears the cached licence features, colours and
 * profile BEFORE it checks the machine id, and (unlike {@code attemptRenewal}) never restores them
 * on failure. The store would then serve a config with no branding and no frontend-gated add-ons to
 * every new visitor until its process restarted, and the 12-hour poll cannot heal it because an
 * unchanged key short-circuits to UNCHANGED. So the event's {@code machineId} is checked against
 * what the store itself reports, and a store that cannot be probed is left alone.
 *
 * <p><b>Fail-soft, deliberately.</b> This runs AFTER COMMIT, so the licence row is already durable
 * and nothing here may throw: a store that is down, restarting, or refusing the key must not undo a
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

    /**
     * AFTER_COMMIT, not a plain {@code @EventListener}: a plain one runs inside the re-mint's
     * transaction, which would hold a database transaction open across a blocking HTTP call to a
     * store and — if a later licence in the same batch threw — leave the store holding a key whose
     * row was rolled back. {@code fallbackExecution} keeps it working when something publishes
     * outside a transaction.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onReminted(LicenceRemintedEvent event) {
        try {
            var instance = hostedInstanceRepository.findByClientCode(event.clientCode()).orElse(null);
            if (instance == null) return;          // an ordinary desktop licence
            if (!ACTIVE.equalsIgnoreCase(instance.getStatus() == null ? "" : instance.getStatus().trim())) {
                log.debug("Hosted store {} is {}; not pushing its re-minted licence",
                        event.clientCode(), instance.getStatus());
                return;
            }

            String storeMachineId = ops.probeMachineId(instance.getPort())
                    .map(String::trim).filter(id -> !id.isEmpty()).orElse(null);
            if (storeMachineId == null) {
                log.debug("Hosted store {} did not report a machineId; not pushing", event.clientCode());
                return;
            }
            if (!storeMachineId.equals(event.machineId())) {
                // This re-mint belongs to one of the client's other machines, e.g. a shop till.
                log.debug("Re-minted licence for {} belongs to machine {}, not hosted store {}",
                        event.clientCode(), event.machineId(), storeMachineId);
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

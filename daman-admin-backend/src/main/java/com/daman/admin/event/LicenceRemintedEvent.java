package com.daman.admin.event;

/**
 * Published after a licence's key has been re-signed with a changed feature set and saved.
 *
 * <p>An event rather than a direct call so the re-mint path does not have to know about hosted
 * stores, or about whatever else later wants to react. The one listener today pushes the new key
 * straight into a running hosted store, which is the only place in the fleet where a feature
 * change can land without waiting for the desktop's 12-hour poll.
 *
 * <p>Carries the new key so a listener never has to re-read the row it was just told about.
 */
public record LicenceRemintedEvent(Long licenceId, String clientCode, String machineId, String licenseKey) {}

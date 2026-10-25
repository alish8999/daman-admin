package com.daman.admin.service;

/**
 * The ordered stages of provisioning one hosted store.
 *
 * <p>The stage about to be attempted is written to {@code HostedInstance.failedStage} before it
 * runs, so a failure leaves a row saying exactly how far it got and a re-run resumes from that
 * stage. <b>Declaration order is execution order</b> — {@link #ordinal()} is what the resume
 * comparison uses, so these must not be reordered.
 *
 * <p>{@link #VALIDATE} is the one stage that can never appear in a persisted row: it runs before
 * anything exists to persist, so its failures are refusals, not failed provisions.
 */
public enum ProvisionStage {
    VALIDATE,
    ALLOCATE,
    DATA_DIR,
    UNIT,
    START,
    INGRESS,
    API_DNS,
    FRONTEND_DNS,
    PAGES_ATTACH,
    PAGES_ACTIVE,
    LICENCE,
    VERIFY
}

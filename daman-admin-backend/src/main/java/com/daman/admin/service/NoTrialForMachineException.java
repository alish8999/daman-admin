package com.daman.admin.service;

/**
 * A {@code restoreOnly} request from a machine that has no trial registration
 * (HTTP 404 {@code no_trial_for_machine}).
 *
 * <p>Only ever thrown for {@code restoreOnly}: an ordinary registration from an unknown
 * machine is the normal case and mints a trial. It exists so the desktop can ask "does
 * this machine already have a trial?" and get a clean answer instead of inferring one
 * from a validation failure on a deliberately empty body.
 */
public class NoTrialForMachineException extends RuntimeException {
    public NoTrialForMachineException() {
        super("no_trial_for_machine");
    }
}

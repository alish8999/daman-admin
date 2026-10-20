package com.daman.admin.service;

/** trial.enabled is off: registration is refused (HTTP 403 trials_disabled). */
public class TrialsDisabledException extends RuntimeException {
    public TrialsDisabledException() {
        super("trials_disabled");
    }
}

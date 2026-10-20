package com.daman.admin.service;

/** This machine already had its trial and it has run out (HTTP 409 trial_already_used). */
public class TrialAlreadyUsedException extends RuntimeException {
    public TrialAlreadyUsedException() {
        super("trial_already_used");
    }
}

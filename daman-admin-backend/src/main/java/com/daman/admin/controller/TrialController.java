package com.daman.admin.controller;

import com.daman.admin.dto.TrialRegisterRequest;
import com.daman.admin.service.TrialAlreadyUsedException;
import com.daman.admin.service.TrialRegistrationService;
import com.daman.admin.service.TrialsDisabledException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * ANONYMOUS (listed in AuthFilter.PUBLIC_PATHS). Keep this thin and keep its responses
 * free of anything but the licence key: error bodies are fixed strings that echo nothing.
 */
@RestController
@RequestMapping("/api/trial")
@RequiredArgsConstructor
public class TrialController {

    private final TrialRegistrationService service;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody(required = false) TrialRegisterRequest body) {
        if (body == null) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request");
        }
        try {
            return ResponseEntity.ok(service.register(body, body.machineId(), body.deviceInfo()));
        } catch (TrialsDisabledException e) {
            return error(HttpStatus.FORBIDDEN, "trials_disabled");
        } catch (TrialAlreadyUsedException e) {
            return error(HttpStatus.CONFLICT, "trial_already_used");
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request");
        }
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }
}

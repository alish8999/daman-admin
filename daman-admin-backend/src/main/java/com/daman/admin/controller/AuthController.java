package com.daman.admin.controller;

import com.daman.admin.repository.AdminUserRepository;
import com.daman.admin.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SessionService sessionService;
    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthController(SessionService sessionService, AdminUserRepository adminUserRepository, PasswordEncoder passwordEncoder) {
        this.sessionService = sessionService;
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));
        }

        boolean valid = adminUserRepository.findByUsername(username)
                .map(user -> passwordEncoder.matches(password, user.getPasswordHash()))
                .orElse(false);

        if (valid) {
            return ResponseEntity.ok(Map.of("token", sessionService.createSession()));
        }
        return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            sessionService.invalidate(authHeader.substring(7));
        }
        return ResponseEntity.noContent().build();
    }
}

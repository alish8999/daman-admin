package com.daman.admin.config;

import com.daman.admin.entity.AdminUser;
import com.daman.admin.repository.AdminUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the single admin login account on first startup (table empty only —
 * never touches an existing row). Replaces the old application.yml-based
 * daman.admin.username/password credentials.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminUserSeeder implements ApplicationRunner {

    private final AdminUserRepository repository;
    private final PasswordEncoder passwordEncoder;

    @Value("${daman.admin.seed-username:admin}")
    private String seedUsername;

    @Value("${daman.admin.seed-password:ChangeMe123!}")
    private String seedPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) {
            return;
        }
        AdminUser user = new AdminUser();
        user.setUsername(seedUsername);
        user.setPasswordHash(passwordEncoder.encode(seedPassword));
        repository.save(user);

        log.warn("Seeded default admin account — username: '{}', password: '{}'. " +
                        "Change the password by updating the admin_users row (password_hash, bcrypt) — " +
                        "this message will not appear again once the table has a row.",
                seedUsername, seedPassword);
    }
}

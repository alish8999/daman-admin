package com.daman.admin.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Drops the legacy single-column UNIQUE constraint on licenses.client_code
 * that enforced a 1:1 client-to-license mapping.
 * Hibernate ddl-auto=update never removes constraints, so we do it here.
 * Safe to run on every startup — catches and ignores all errors.
 */
@Component
@RequiredArgsConstructor
public class LicenseMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<String> singleColUniqueConstraints = jdbc.queryForList(
                "SELECT tc.CONSTRAINT_NAME " +
                "FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc " +
                "JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu " +
                "  ON tc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME " +
                " AND tc.TABLE_SCHEMA = kcu.TABLE_SCHEMA " +
                "WHERE tc.TABLE_NAME = 'LICENSES' " +
                "  AND tc.CONSTRAINT_TYPE = 'UNIQUE' " +
                "GROUP BY tc.CONSTRAINT_NAME " +
                "HAVING COUNT(*) = 1",
                String.class);

            for (String name : singleColUniqueConstraints) {
                try {
                    jdbc.execute("ALTER TABLE LICENSES DROP CONSTRAINT IF EXISTS \"" + name + "\"");
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }
}

package com.daman.admin.entity;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pins the reason {@code demo} is a nullable {@link Boolean} rather than a primitive.
 *
 * <p>It shipped once as {@code @Column(nullable = false) boolean}, and the admin backend would not
 * boot: {@code ddl-auto=update} has to ADD the column to a {@code hosted_instances} table that
 * already has rows, and a NOT NULL column with no default cannot be added to a populated table.
 * Hibernate logs that failed ALTER and carries on, so the column silently never exists — and the
 * first query selecting it dies with {@code Column "DEMO" not found}, during startup, in a
 * CommandLineRunner. The symptom points at the query; the cause is the migration that never ran.
 *
 * <p>These tests run the two ALTERs against real H2 so the claim is demonstrated rather than
 * asserted from memory.
 */
class HostedInstanceDemoColumnTest {

    private Connection populatedTable(String name) throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE hosted_instances (id BIGINT PRIMARY KEY, client_code VARCHAR(64))");
            s.execute("INSERT INTO hosted_instances VALUES (1, 'viora'), (2, 'daman'), (3, 'demo1')");
        }
        return c;
    }

    @Test
    void addingANotNullColumnToAPopulatedTableIsRejected() throws SQLException {
        // The original mistake, reproduced. This is why the column never appeared.
        try (Connection c = populatedTable("notnull"); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.execute("ALTER TABLE hosted_instances ADD COLUMN demo BOOLEAN NOT NULL"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void addingANullableColumnToAPopulatedTableSucceeds() throws SQLException {
        // The fix. No default, no dialect-specific behaviour to rely on.
        try (Connection c = populatedTable("nullable"); Statement s = c.createStatement()) {
            assertThatCode(() -> s.execute("ALTER TABLE hosted_instances ADD COLUMN demo BOOLEAN"))
                    .doesNotThrowAnyException();
            try (var r = s.executeQuery("SELECT COUNT(*) FROM hosted_instances WHERE demo IS NULL")) {
                r.next();
                assertThat(r.getInt(1)).as("every pre-existing row carries NULL").isEqualTo(3);
            }
        }
    }

    @Test
    void nullAndFalseBothMeanNotADemo() {
        // Which is what makes the NULL left on existing rows safe: Reset refuses all three of these.
        HostedInstance fresh = new HostedInstance();
        assertThat(fresh.isDemo()).as("a brand new row").isFalse();

        HostedInstance legacy = new HostedInstance();
        legacy.setDemo(null);
        assertThat(legacy.isDemo()).as("a row from before the column existed").isFalse();

        HostedInstance explicitlyNot = new HostedInstance();
        explicitlyNot.setDemo(false);
        assertThat(explicitlyNot.isDemo()).isFalse();
    }

    @Test
    void onlyAnExplicitTrueMarksADemo() {
        HostedInstance demo = new HostedInstance();
        demo.setDemo(true);
        assertThat(demo.isDemo()).isTrue();
    }
}

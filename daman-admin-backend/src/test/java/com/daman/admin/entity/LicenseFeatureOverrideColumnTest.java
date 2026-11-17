package com.daman.admin.entity;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins why {@code features_override_json} is a NULLABLE TEXT column and not, say, a
 * {@code NOT NULL} one defaulting to {@code "{}"}.
 *
 * <p>{@code licenses} holds 105 rows in production. {@code ddl-auto=update} has to ADD this
 * column to that populated table, and a NOT NULL column with no default cannot be added to a
 * table that already has rows. Hibernate logs the failed ALTER and carries on, so the column
 * silently never exists — and then the first query selecting it dies with
 * {@code Column "FEATURES_OVERRIDE_JSON" not found}, during startup, inside a runner. The
 * symptom points at the query; the cause is the migration that never ran.
 *
 * <p>This is not hypothetical: it shipped exactly once before, on {@code hosted_instances.demo},
 * and {@link HostedInstanceDemoColumnTest} records that incident. These tests run the two ALTERs
 * against real H2 so the claim is demonstrated rather than asserted from memory.
 *
 * <p>Nullable also carries the domain meaning: <b>null means "inherit every flag from the
 * client"</b>, which is the correct value for all 105 existing rows.
 */
class LicenseFeatureOverrideColumnTest {

    private Connection populatedLicensesTable(String name) throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE licenses (id BIGINT PRIMARY KEY, client_code VARCHAR(64), machine_id VARCHAR(128))");
            s.execute("INSERT INTO licenses VALUES (1, 'viora', 'm1'), (2, 'daman-1', 'm2'), (3, 'c-14', 'm3')");
        }
        return c;
    }

    @Test
    void addingTheNullableTextColumnToAPopulatedTableSucceeds() throws SQLException {
        try (Connection c = populatedLicensesTable("lic_nullable");
             Statement s = c.createStatement()) {
            assertThatCode(() -> s.execute("ALTER TABLE licenses ADD COLUMN features_override_json TEXT"))
                    .doesNotThrowAnyException();
        }
    }

    /** The mistake this column must not repeat. */
    @Test
    void addingItAsNotNullWithNoDefaultIsRejected() throws SQLException {
        try (Connection c = populatedLicensesTable("lic_notnull");
             Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.execute(
                    "ALTER TABLE licenses ADD COLUMN features_override_json TEXT NOT NULL"))
                    .isInstanceOf(SQLException.class);
        }
    }

    /**
     * The guard that bites a future edit: the entity itself must keep the column nullable and
     * TEXT. The two ALTER tests above characterise H2; this one pins OUR declaration, which is
     * what {@code ddl-auto} actually reads.
     */
    @Test
    void theEntityDeclaresTheColumnNullableAndText() throws NoSuchFieldException {
        var field = License.class.getDeclaredField("featuresOverrideJson");
        var column = field.getAnnotation(jakarta.persistence.Column.class);
        org.assertj.core.api.Assertions.assertThat(column).isNotNull();
        org.assertj.core.api.Assertions.assertThat(column.name()).isEqualTo("features_override_json");
        org.assertj.core.api.Assertions.assertThat(column.nullable()).isTrue();
        org.assertj.core.api.Assertions.assertThat(column.columnDefinition()).isEqualTo("TEXT");
        org.assertj.core.api.Assertions.assertThat(field.getType()).isEqualTo(String.class);
    }

    /** Null is a legal, meaningful value in the nullable column: "inherit everything". */
    @Test
    void existingRowsReadBackNullAfterTheColumnIsAdded() throws SQLException {
        try (Connection c = populatedLicensesTable("lic_reads_null");
             Statement s = c.createStatement()) {
            s.execute("ALTER TABLE licenses ADD COLUMN features_override_json TEXT");
            try (var rs = s.executeQuery(
                    "SELECT COUNT(*) FROM licenses WHERE features_override_json IS NULL")) {
                rs.next();
                org.assertj.core.api.Assertions.assertThat(rs.getInt(1)).isEqualTo(3);
            }
        }
    }
}

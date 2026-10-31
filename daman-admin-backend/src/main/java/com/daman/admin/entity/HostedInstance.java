package com.daman.admin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/** Registry row for one hosted online store (systemd unit + DNS + Pages hostname). */
@Getter
@Setter
@Entity
@Table(name = "hosted_instances")
public class HostedInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String clientCode;

    @Column(nullable = false, unique = true)
    private String subdomain;

    @Column(nullable = false)
    private int port;

    @Column(nullable = false)
    private String serviceName;

    @Column(nullable = false)
    private String dataDir;

    @Column(nullable = false)
    private String apiHostname;

    @Column(nullable = false)
    private String frontendHostname;

    private String jwtSecret;

    /** PROVISIONING | ACTIVE | FAILED */
    @Column(nullable = false)
    private String status;

    private String failedStage;

    private String dnsRecordId;

    /**
     * True for a slot handed to prospects to try, never a paying customer's store.
     *
     * <p>This is the whole safety boundary for Reset, which destroys the store's database and
     * replaces it with a seed. Reset refuses any row where this is false, so it is <b>incapable</b>
     * of reaching a real client — rather than relying on an operator reading a confirm dialog or on
     * a naming convention like "codes starting with demo", which a mistyped code defeats.
     *
     * <p>Defaults to false on purpose: a store is a customer's unless something deliberately says
     * otherwise, so an older row created before this column existed is never resettable.
     */
    /**
     * Nullable on purpose, and read through {@link #isDemo()} which treats null as false.
     *
     * <p>It was first written as a non-null primitive, which broke the backend on the very first
     * run: {@code ddl-auto=update} adds the column to a table that already has rows, and
     * {@code ADD COLUMN demo boolean not null} with no default is rejected. Hibernate logs the
     * failed ALTER and carries on, so the column silently never exists and the first query for it
     * dies with {@code Column "DEMO" not found}. A nullable column always adds cleanly, on H2 and
     * on Postgres, with no reliance on the dialect honouring a DEFAULT in an ALTER.
     *
     * <p>Null therefore means "a row that predates this column", which is exactly a store that is
     * not a demo — the safe reading, since this flag is what lets Reset destroy a store's data.
     */
    private Boolean demo;

    /**
     * Which seed Reset loads: {@code mart} or {@code mobile}. Null for a non-demo store.
     *
     * <p>Kept on the row rather than passed to Reset, so resetting cannot silently change what a
     * slot is — a slot handed out as a supermarket stays a supermarket on its next reset.
     */
    private String demoFlavour;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /**
     * Whether Reset may destroy this store's data. Null (a row from before the column existed) and
     * false both mean no — the only answer that is ever "yes" is an explicit true, because this is
     * the single thing standing between Reset and a paying customer's shop.
     *
     * <p>Defined by hand rather than left to Lombok, which would generate {@code getDemo()}
     * returning a {@code Boolean} that is null for most rows and NPEs the moment anything unboxes
     * it in a condition.
     */
    public boolean isDemo() {
        return Boolean.TRUE.equals(demo);
    }
}

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
    @Column(nullable = false)
    private boolean demo = false;

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
}

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

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}

package com.daman.admin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "licenses")
public class License {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String clientCode;

    @Column(nullable = false)
    private String machineId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String licenseKey;

    @Column(nullable = false)
    private String status; // ACTIVE, REVOKED

    private String clientName;

    private LocalDate expiresAt;

    private String deviceInfo;

    @CreationTimestamp
    @Column(name = "activated_at", updatable = false)
    private LocalDateTime activatedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "renewed_at")
    private LocalDateTime renewedAt;
}

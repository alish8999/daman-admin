package com.daman.admin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** A self-serve trial prospect. One row per machine. */
@Getter
@Setter
@Entity
@Table(name = "trial_registrations")
public class TrialRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String clientCode;

    /** Unique: the idempotency guarantee against one machine claiming two trials on a race. */
    @Column(nullable = false, unique = true)
    private String machineId;

    @Column(nullable = false)
    private String shopName;

    private String phone;

    private String address;

    private String storeType;

    private Boolean hasTables;

    private String baseCurrency;

    @Column(nullable = false)
    private LocalDateTime registeredAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime convertedAt;
}

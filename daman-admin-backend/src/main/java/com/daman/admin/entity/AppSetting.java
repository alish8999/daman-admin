package com.daman.admin.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Generic key/value settings store. Each row holds one named setting whose
 * value is an opaque JSON document (see {@code value_json}). Currently used for
 * package definitions ({@code package.definitions}); reusable for future
 * admin-wide settings without adding a new table each time.
 */
@Getter
@Setter
@Entity
@Table(name = "app_settings")
public class AppSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "setting_key", nullable = false, unique = true)
    private String settingKey;

    @Column(name = "value_json", columnDefinition = "TEXT")
    private String valueJson;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

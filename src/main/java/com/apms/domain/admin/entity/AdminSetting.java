package com.apms.domain.admin.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Key-value store for admin settings persisted to SQL Server.
 * Replaces localStorage-based settings.
 */
@Entity
@Table(name = "admin_settings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "setting_key", unique = true, nullable = false, length = 100)
    private String settingKey;

    @Column(name = "setting_value", columnDefinition = "NVARCHAR(MAX)")
    private String settingValue;

    /**
     * Type hint for frontend rendering: STRING, INTEGER, BOOLEAN, JSON
     */
    @Builder.Default
    @Column(name = "setting_type", length = 20)
    private String settingType = "STRING";

    @Column(length = 255)
    private String description;

    @Column(name = "updated_by_id")
    private Long updatedById;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

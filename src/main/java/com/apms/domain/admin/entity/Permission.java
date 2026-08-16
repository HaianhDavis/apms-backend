package com.apms.domain.admin.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Permission definition. Each permission has a unique name (e.g. USER_RESET_PASSWORD),
 * belongs to a module group (e.g. "Users"), and has a human-readable description.
 */
@Entity
@Table(name = "permissions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "permission_name", unique = true, nullable = false, length = 100)
    private String name;

    @Column(length = 50)
    private String module;

    @Column(length = 255)
    private String description;
}

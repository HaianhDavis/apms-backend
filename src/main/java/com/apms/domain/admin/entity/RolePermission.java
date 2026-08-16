package com.apms.domain.admin.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Stores which permissions are assigned to which system roles.
 * Roles remain as SystemRole enum; this table is the extension point for RBAC.
 */
@Entity
@Table(name = "role_permissions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"role_key", "permission_name"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RolePermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "role_key", nullable = false, length = 50)
    private String roleKey;

    @Column(name = "permission_name", nullable = false, length = 100)
    private String permissionName;
}

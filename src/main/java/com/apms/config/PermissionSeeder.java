package com.apms.config;

import com.apms.common.enums.SystemRole;
import com.apms.domain.admin.entity.Permission;
import com.apms.domain.admin.entity.RolePermission;
import com.apms.domain.admin.repository.PermissionRepository;
import com.apms.domain.admin.repository.RolePermissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seeds the permissions table and default role-permission assignments on startup.
 * Only runs in dev profile. Run order 2 (after DataSeeder).
 */
@Slf4j
@Component
@Profile("dev")
@Order(2)
@RequiredArgsConstructor
public class PermissionSeeder implements CommandLineRunner {

    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;

    // All permissions grouped by module
    private static final List<Object[]> PERMISSIONS = List.of(
            // Module, Name, Description
            new Object[]{"Users", "USER_VIEW", "View user accounts"},
            new Object[]{"Users", "USER_CREATE", "Create new user accounts"},
            new Object[]{"Users", "USER_UPDATE", "Update user profile and details"},
            new Object[]{"Users", "USER_DELETE", "Soft-delete user accounts"},
            new Object[]{"Users", "USER_RESET_PASSWORD", "Reset passwords for other users"},
            new Object[]{"Users", "USER_TOGGLE_STATUS", "Enable or disable user accounts"},

            new Object[]{"Roles", "ROLE_VIEW", "View system roles"},
            new Object[]{"Roles", "ROLE_ASSIGN", "Assign roles to users"},

            new Object[]{"Permissions", "PERMISSION_VIEW", "View permission matrix"},
            new Object[]{"Permissions", "PERMISSION_UPDATE", "Update role permissions"},

            new Object[]{"Audit", "AUDIT_VIEW", "View audit logs"},
            new Object[]{"Audit", "AUDIT_EXPORT", "Export audit logs to CSV"},

            new Object[]{"Settings", "SETTINGS_VIEW", "View system settings"},
            new Object[]{"Settings", "SETTINGS_UPDATE", "Update system settings"},

            new Object[]{"Security", "SECURITY_VIEW", "View security settings"},
            new Object[]{"Security", "SECURITY_UPDATE", "Update security settings"},
            new Object[]{"Security", "IP_WHITELIST_MANAGE", "Manage IP whitelist entries"},

            new Object[]{"Health", "HEALTH_VIEW", "View system health status"}
    );

    // Default permissions per role
    private static final List<Object[]> ROLE_PERMISSIONS = List.of(
            // SYSTEM_ADMIN gets all permissions
            new Object[]{SystemRole.SYSTEM_ADMIN, List.of(
                    "USER_VIEW", "USER_CREATE", "USER_UPDATE", "USER_DELETE", "USER_RESET_PASSWORD", "USER_TOGGLE_STATUS",
                    "ROLE_VIEW", "ROLE_ASSIGN",
                    "PERMISSION_VIEW", "PERMISSION_UPDATE",
                    "AUDIT_VIEW", "AUDIT_EXPORT",
                    "SETTINGS_VIEW", "SETTINGS_UPDATE",
                    "SECURITY_VIEW", "SECURITY_UPDATE", "IP_WHITELIST_MANAGE",
                    "HEALTH_VIEW"
            )},
            // BUSINESS_OWNER can view users and audit
            new Object[]{SystemRole.BUSINESS_OWNER, List.of(
                    "USER_VIEW",
                    "ROLE_VIEW",
                    "AUDIT_VIEW",
                    "SETTINGS_VIEW",
                    "HEALTH_VIEW"
            )},
            // BD_MANAGER can view only
            new Object[]{SystemRole.BUSINESS_DEVELOPMENT_MANAGER, List.of(
                    "USER_VIEW",
                    "ROLE_VIEW",
                    "AUDIT_VIEW"
            )},
            // BD_STAFF — minimal
            new Object[]{SystemRole.BUSINESS_DEVELOPMENT_STAFF, List.of(
                    "USER_VIEW"
            )}
    );

    @Override
    @SuppressWarnings("unchecked")
    public void run(String... args) {
        log.info("Running PermissionSeeder...");

        // Seed permissions
        for (Object[] perm : PERMISSIONS) {
            String module = (String) perm[0];
            String name = (String) perm[1];
            String description = (String) perm[2];

            if (!permissionRepository.existsByName(name)) {
                permissionRepository.save(Permission.builder()
                        .module(module)
                        .name(name)
                        .description(description)
                        .build());
                log.info("Seeded permission: {}", name);
            }
        }

        // Seed role-permission assignments (idempotent)
        for (Object[] rp : ROLE_PERMISSIONS) {
            SystemRole role = (SystemRole) rp[0];
            List<String> perms = (List<String>) rp[1];
            String roleKey = role.name();

            for (String permName : perms) {
                if (!rolePermissionRepository.existsByRoleKeyAndPermissionName(roleKey, permName)) {
                    rolePermissionRepository.save(RolePermission.builder()
                            .roleKey(roleKey)
                            .permissionName(permName)
                            .build());
                    log.info("Assigned permission {} → {}", roleKey, permName);
                }
            }
        }

        log.info("PermissionSeeder completed.");
    }
}

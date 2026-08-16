package com.apms.domain.admin.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SystemRole;
import com.apms.domain.admin.dto.PermissionDto;
import com.apms.domain.admin.entity.Permission;
import com.apms.domain.admin.entity.RolePermission;
import com.apms.domain.admin.repository.PermissionRepository;
import com.apms.domain.admin.repository.RolePermissionRepository;
import com.apms.domain.audit.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PermissionService {

    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<PermissionDto> getAllPermissions() {
        return permissionRepository.findAllByOrderByModuleAscNameAsc()
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<String> getPermissionNamesForRole(String roleKey) {
        return rolePermissionRepository.findByRoleKey(roleKey)
                .stream()
                .map(RolePermission::getPermissionName)
                .collect(Collectors.toList());
    }

    /**
     * Replaces all permissions for a role with the provided set.
     */
    @Transactional
    public void updateRolePermissions(String roleKey, List<String> permissionNames, Long adminId) {
        // Validate role key
        boolean validRole = Arrays.stream(SystemRole.values())
                .anyMatch(r -> r.name().equals(roleKey));
        if (!validRole) {
            throw new IllegalArgumentException("Unknown role: " + roleKey);
        }

        // Delete existing
        rolePermissionRepository.deleteByRoleKey(roleKey);

        // Insert new
        List<RolePermission> newPerms = permissionNames.stream()
                .map(name -> RolePermission.builder()
                        .roleKey(roleKey)
                        .permissionName(name)
                        .build())
                .collect(Collectors.toList());

        rolePermissionRepository.saveAll(newPerms);

        auditLogService.log(adminId, AuditAction.ROLE_PERMISSIONS_UPDATED,
                "RolePermission", roleKey,
                "Updated permissions for role " + roleKey + ": " + permissionNames.size() + " permissions");
    }

    /**
     * Returns all roles with their assigned permissions.
     */
    @Transactional(readOnly = true)
    public Map<String, List<String>> getAllRolePermissions() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        Arrays.stream(SystemRole.values())
                .filter(r -> r != SystemRole.RESEARCH_STAFF)
                .forEach(role -> {
                    List<String> perms = rolePermissionRepository.findByRoleKey(role.name())
                            .stream()
                            .map(RolePermission::getPermissionName)
                            .collect(Collectors.toList());
                    result.put(role.name(), perms);
                });
        return result;
    }

    private PermissionDto toDto(Permission p) {
        return PermissionDto.builder()
                .id(p.getId())
                .name(p.getName())
                .module(p.getModule())
                .description(p.getDescription())
                .build();
    }
}

package com.apms.domain.score.service;

import com.apms.common.enums.SystemRole;
import com.apms.security.UserDetailsImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

final class RoleEvaluationAuthorityResolver {

    private RoleEvaluationAuthorityResolver() {
    }

    static SystemRole currentEvaluatorRoleOrDefault(SystemRole fallback) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserDetailsImpl user)) {
            return fallback;
        }
        if (hasRole(user, SystemRole.BUSINESS_OWNER)) {
            return SystemRole.BUSINESS_OWNER;
        }
        if (hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_MANAGER)) {
            return SystemRole.BUSINESS_DEVELOPMENT_MANAGER;
        }
        if (hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_STAFF) || hasRole(user, SystemRole.RESEARCH_STAFF)) {
            return SystemRole.BUSINESS_DEVELOPMENT_STAFF;
        }
        if (hasRole(user, SystemRole.SYSTEM_ADMIN)) {
            return SystemRole.SYSTEM_ADMIN;
        }
        return fallback;
    }

    private static boolean hasRole(UserDetailsImpl user, SystemRole role) {
        String roleName = "ROLE_" + role.name();
        return user.getAuthorities().stream().anyMatch(authority -> roleName.equals(authority.getAuthority()));
    }
}

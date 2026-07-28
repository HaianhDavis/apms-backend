package com.apms.domain.project.controller;

import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.ProjectType;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.annotation.RequestParamMethodArgumentResolver;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C5 regression test: verifies that the frontend filter values
 * (ACTIVE, RESEARCH_NEW_COMPANY, etc.) match the backend enums exactly.
 *
 * This prevents the old bug where the frontend sent fabricated values
 * like IN_PROGRESS, PARTNER_EVALUATION that the backend would reject.
 */
class ProjectFilterContractTest {

    private static final Set<String> VALID_BACKEND_STATUSES = Set.of(
            "DRAFT", "ACTIVE", "COMPLETED", "CANCELLED", "ARCHIVED"
    );

    private static final Set<String> VALID_BACKEND_TYPES = Set.of(
            "RESEARCH_NEW_COMPANY", "UPDATE_EXISTING_COMPANY", "RESEARCH_MULTIPLE_COMPANIES"
    );

    // ── C5.1: Backend enum values are the source of truth ──

    @Test
    void backendEnum_projectStatus_containsACTIVE() {
        assertNotNull(ProjectStatus.ACTIVE, "ProjectStatus.ACTIVE must exist");
        assertEquals("ACTIVE", ProjectStatus.ACTIVE.name());
    }

    @Test
    void backendEnum_projectStatus_containsAllExpectedValues() {
        Set<String> actual = Set.of(ProjectStatus.values()).stream()
                .map(Enum::name)
                .collect(Collectors.toSet());
        assertEquals(VALID_BACKEND_STATUSES, actual,
                "ProjectStatus enum must exactly match the expected set");
    }

    @Test
    void backendEnum_projectType_exactMatch() {
        Set<String> actual = Set.of(ProjectType.values()).stream()
                .map(Enum::name)
                .collect(Collectors.toSet());
        assertEquals(VALID_BACKEND_TYPES, actual,
                "ProjectType enum must exactly match the expected set");
    }

    // ── C5.2: Controller method accepts ProjectStatus enum (not raw String) ──

    @Test
    void controller_getAllProjects_acceptsProjectStatusEnum() throws NoSuchMethodException {
        Method method = ProjectController.class.getMethod(
                "getAllProjects",
                ProjectStatus.class,
                ProjectType.class,
                int.class,
                int.class,
                com.apms.security.UserDetailsImpl.class
        );

        assertNotNull(method, "getAllProjects method must exist");

        // Find @RequestParam on the 'status' parameter by index
        java.lang.annotation.Annotation[][] paramAnnotations = method.getParameterAnnotations();
        RequestParam statusParam = findRequestParamByNameOrType(paramAnnotations, 0, "status");
        assertNotNull(statusParam, "@RequestParam for 'status' must exist");
        assertFalse(statusParam.required(), "status param must be optional (required=false)");
    }

    @Test
    void controller_getAllProjects_acceptsProjectTypeEnum() throws NoSuchMethodException {
        Method method = ProjectController.class.getMethod(
                "getAllProjects",
                ProjectStatus.class,
                ProjectType.class,
                int.class,
                int.class,
                com.apms.security.UserDetailsImpl.class
        );

        java.lang.annotation.Annotation[][] paramAnnotations = method.getParameterAnnotations();
        RequestParam typeParam = findRequestParamByNameOrType(paramAnnotations, 1, "type");
        assertNotNull(typeParam, "@RequestParam for 'type' must exist");
        assertFalse(typeParam.required(), "type param must be optional (required=false)");
    }

    // ── C5.3: Frontend filter values (documented) match backend enums ──

    @Test
    void frontendStatusFilter_values_matchBackendEnum() {
        // These are the exact values the frontend ProjectsOverview.tsx sends:
        String[] frontendStatusValues = {"DRAFT", "ACTIVE", "COMPLETED", "CANCELLED", "ARCHIVED"};

        for (String value : frontendStatusValues) {
            assertDoesNotThrow(() -> ProjectStatus.valueOf(value),
                    "Frontend status filter '" + value + "' must be a valid ProjectStatus enum");
        }
    }

    @Test
    void frontendTypeFilter_values_matchBackendEnum() {
        // These are the exact values the frontend ProjectsOverview.tsx sends:
        String[] frontendTypeValues = {"RESEARCH_NEW_COMPANY", "UPDATE_EXISTING_COMPANY", "RESEARCH_MULTIPLE_COMPANIES"};

        for (String value : frontendTypeValues) {
            assertDoesNotThrow(() -> ProjectType.valueOf(value),
                    "Frontend type filter '" + value + "' must be a valid ProjectType enum");
        }
    }

    @Test
    void frontendStatusFilter_rejectsOldWrongValues() {
        // These were the OLD wrong values that the frontend used before the C5 fix:
        String[] oldWrongValues = {"IN_PROGRESS", "NEEDS_APPROVAL"};

        for (String value : oldWrongValues) {
            assertThrows(IllegalArgumentException.class, () -> ProjectStatus.valueOf(value),
                    "Old wrong status '" + value + "' must NOT be a valid ProjectStatus");
        }
    }

    @Test
    void frontendTypeFilter_rejectsOldWrongValues() {
        // These were the OLD wrong values that the frontend used before the C5 fix:
        String[] oldWrongValues = {"PARTNER_EVALUATION", "COMPETITOR_ANALYSIS", "MARKET_RESEARCH"};

        for (String value : oldWrongValues) {
            assertThrows(IllegalArgumentException.class, () -> ProjectType.valueOf(value),
                    "Old wrong type '" + value + "' must NOT be a valid ProjectType");
        }
    }

    // ── Helpers ──

    private RequestParam findRequestParamByNameOrType(java.lang.annotation.Annotation[][] paramAnnotations, int index, String paramName) {
        if (index >= paramAnnotations.length) return null;
        for (java.lang.annotation.Annotation ann : paramAnnotations[index]) {
            if (ann instanceof RequestParam rpa) {
                return rpa;
            }
        }
        return null;
    }
}

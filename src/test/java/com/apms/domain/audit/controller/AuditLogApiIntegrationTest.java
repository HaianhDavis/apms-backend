package com.apms.domain.audit.controller;

import com.apms.common.enums.AuditAction;
import com.apms.common.exception.GlobalExceptionHandler;
import com.apms.domain.audit.dto.AuditLogResponse;
import com.apms.domain.audit.service.AuditLogQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class AuditLogApiIntegrationTest {

    private MockMvc mockMvc;

    @Mock
    private AuditLogQueryService auditLogQueryService;

    @InjectMocks
    private AuditLogController auditLogController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(auditLogController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/v1/audit-logs returns 200 OK with paginated audit logs")
    void getAuditLogs_shouldReturn200WithPageResponse() throws Exception {
        AuditLogResponse logItem = AuditLogResponse.builder()
                .id(100L)
                .actorUserId(1L)
                .actorEmail("admin@apms.com")
                .action(AuditAction.USER_STATUS_CHANGED.name())
                .entityType("Account")
                .entityId("2")
                .details("Changed status to: false")
                .createdAt(LocalDateTime.now())
                .build();

        when(auditLogQueryService.searchAuditLogs(any(), any(), any(), any(), any(), any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(logItem)));

        mockMvc.perform(get("/api/v1/audit-logs?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].action").value("USER_STATUS_CHANGED"))
                .andExpect(jsonPath("$.data.content[0].actorEmail").value("admin@apms.com"));
    }

    @Test
    @DisplayName("GET /api/v1/audit-logs with filters (action, entityType) passes query params")
    void getAuditLogs_withFilters_shouldReturnFilteredLogs() throws Exception {
        AuditLogResponse logItem = AuditLogResponse.builder()
                .id(101L)
                .action(AuditAction.USER_ROLES_UPDATED.name())
                .entityType("Account")
                .build();

        when(auditLogQueryService.searchAuditLogs(eq(2L), eq("USER_ROLES_UPDATED"), eq("Account"), any(), any(), any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(logItem)));

        mockMvc.perform(get("/api/v1/audit-logs?actorUserId=2&action=USER_ROLES_UPDATED&entityType=Account"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].action").value("USER_ROLES_UPDATED"));
    }

    @Test
    @DisplayName("GET /api/v1/audit-logs/export returns 200 OK with CSV headers and byte content")
    void exportAuditLogs_shouldReturnCsvFile() throws Exception {
        byte[] csvContent = "Timestamp,Actor,Action,EntityType,Details\n2026-07-25 08:00,admin@apms.com,USER_CREATED,Account,Created user".getBytes();
        when(auditLogQueryService.exportAuditLogs(any(), any(), any(), any(), any(), any()))
                .thenReturn(csvContent);

        mockMvc.perform(get("/api/v1/audit-logs/export"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "form-data; name=\"attachment\"; filename=\"audit_logs.csv\""))
                .andExpect(content().bytes(csvContent));
    }
}

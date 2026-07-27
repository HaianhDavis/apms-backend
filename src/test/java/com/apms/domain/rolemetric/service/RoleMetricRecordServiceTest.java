package com.apms.domain.rolemetric.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.RelationshipType;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.document.RawDocument;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.rolemetric.dto.CreateRoleMetricRequest;
import com.apms.domain.rolemetric.dto.RoleMetricResponse;
import com.apms.domain.rolemetric.dto.UpdateRoleMetricRequest;
import com.apms.domain.rolemetric.entity.RoleMetricRecord;
import com.apms.domain.rolemetric.enums.MetricPeriodType;
import com.apms.domain.rolemetric.enums.RoleMetricStatus;
import com.apms.domain.rolemetric.repository.RoleMetricEvidenceRepository;
import com.apms.domain.rolemetric.repository.RoleMetricRecordRepository;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoleMetricRecordServiceTest {

    @Mock
    private RoleMetricRecordRepository recordRepository;

    @Mock
    private RoleMetricEvidenceRepository evidenceRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectTaskRepository taskRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private SecurityContext securityContext;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private RoleMetricRecordService service;

    private Project project;
    private UserDetailsImpl userDetails;

    @BeforeEach
    void setup() {
        project = new Project();
        project.setId(1L);
        project.setTargetCompanyProfileId("comp-1");
        project.setTargetRelationshipType(RelationshipType.PARTNER_WITH);

        userDetails = new UserDetailsImpl(100L, "test@test.com", "password", java.util.Collections.emptyList(), true);
        SecurityContextHolder.setContext(securityContext);
        lenient().when(securityContext.getAuthentication()).thenReturn(authentication);
        lenient().when(authentication.getPrincipal()).thenReturn(userDetails);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createDraft_Success() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(recordRepository.findByProjectIdAndCompanyIdAndRelationshipTypeAndMetricKeyAndPeriodKey(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        RoleMetricRecord saved = new RoleMetricRecord();
        saved.setId(10L);
        saved.setProjectId(1L);
        saved.setCompanyId("comp-1");
        saved.setMetricKey("revenue_generated");
        saved.setPeriodKey("PERIOD:2025-01-01/2025-12-31");
        saved.setPeriodType(MetricPeriodType.PERIOD);

        when(recordRepository.save(any())).thenReturn(saved);

        CreateRoleMetricRequest req = new CreateRoleMetricRequest();

        req.setMetricKey("revenue_generated");
        req.setPeriodStart(LocalDate.of(2025, 1, 1));
        req.setPeriodEnd(LocalDate.of(2025, 12, 31));
        req.setTargetNumericValue(new BigDecimal("1000"));

        RoleMetricResponse resp = service.createDraft(1L, req);

        assertNotNull(resp);
        assertEquals(10L, resp.getId());
        assertEquals("PERIOD:2025-01-01/2025-12-31", resp.getPeriodKey());
        verify(auditLogService).log(eq(100L), eq(AuditAction.ROLE_METRIC_CREATED), eq("RoleMetricRecord"), eq("10"), any());
    }

    @Test
    void createDraft_FailsIfNotPartnerWith() {
        project.setTargetRelationshipType(RelationshipType.POTENTIAL_PARTNER_OF);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        CreateRoleMetricRequest req = new CreateRoleMetricRequest();
        req.setMetricKey("revenue_generated");

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> service.createDraft(1L, req));
        assertTrue(ex.getMessage().contains("PARTNER_WITH"));
    }

    @Test
    void createDraft_RejectsPointInTimeWithPeriodDates() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        CreateRoleMetricRequest req = new CreateRoleMetricRequest();

        req.setMetricKey("nps_score"); // POINT_IN_TIME
        req.setMeasurementDate(LocalDate.of(2025, 6, 1));
        req.setPeriodStart(LocalDate.of(2025, 1, 1));
        req.setPeriodEnd(LocalDate.of(2025, 12, 31));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> service.createDraft(1L, req));
        assertTrue(ex.getMessage().contains("period dates forbidden for POINT_IN_TIME"));
    }

    @Test
    void getWorkingDetail_RejectsProjectIdMismatch() {
        RoleMetricRecord record = new RoleMetricRecord();
        record.setId(10L);
        record.setProjectId(1L);
        record.setProjectId(1L);
        record.setProjectId(2L); // Different project

        when(recordRepository.findById(10L)).thenReturn(Optional.of(record));

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
            () -> service.getWorkingDetail(1L, 10L));
        assertEquals("Metric not found in this project", ex.getMessage());
    }

    @Test
    void createDraft_RejectsUnknownMetricKey() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        CreateRoleMetricRequest req = new CreateRoleMetricRequest();
        req.setMetricKey("unknown_key");

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> service.createDraft(1L, req));
        assertTrue(ex.getMessage().contains("Unknown metric key"));
    }

    @Test
    void updateDraft_RejectsIdentityChangeAfterApproval() {
        RoleMetricRecord record = new RoleMetricRecord();
        record.setId(10L);
        record.setProjectId(1L);
        record.setProjectId(1L);
        record.setProjectId(1L);
        record.setStatus(RoleMetricStatus.DRAFT);
        record.setMetricKey("revenue_generated");
        record.setCurrentApprovedVersionId(100L); // Already approved once
        record.setPeriodStart(LocalDate.of(2025, 1, 1));
        record.setPeriodEnd(LocalDate.of(2025, 12, 31));

        when(recordRepository.findById(10L)).thenReturn(Optional.of(record));

        UpdateRoleMetricRequest req = new UpdateRoleMetricRequest();
        req.setPeriodStart(LocalDate.of(2025, 2, 1)); // Attempted change

        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
            () -> service.updateDraft(1L, 10L, req));
        assertTrue(ex.getMessage().contains("Cannot modify identity fields"));
    }

    @Test
    void createDraft_RejectsBooleanValueForNumericMetric() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        CreateRoleMetricRequest req = new CreateRoleMetricRequest();
        req.setMetricKey("revenue_generated"); // Numeric
        req.setPeriodStart(LocalDate.of(2025, 1, 1));
        req.setPeriodEnd(LocalDate.of(2025, 12, 31));
        req.setTargetBooleanValue(true);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> service.createDraft(1L, req));
        assertTrue(ex.getMessage().contains("Boolean values not allowed for numeric metrics"));
    }

    @Test
    void testSubmitWithoutActualsThrowsException() {
        RoleMetricRecord record = new RoleMetricRecord();
        record.setId(10L);
        record.setProjectId(1L);
        record.setProjectId(1L);
        record.setStatus(com.apms.domain.rolemetric.enums.RoleMetricStatus.DRAFT);
        record.setTargetNumericValue(new BigDecimal("1000"));
        // actual is null
        when(recordRepository.findById(10L)).thenReturn(Optional.of(record));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> service.submitForReview(1L, 10L));
        assertTrue(ex.getMessage().contains("Target requires evidence"));
    }

    @Test
    void testUpdateDraft_AfterFirstApproval_CannotChangeIdentityFields() {
        RoleMetricRecord record = new RoleMetricRecord();
        record.setId(10L);
        record.setProjectId(1L);
        record.setProjectId(1L);
        record.setStatus(com.apms.domain.rolemetric.enums.RoleMetricStatus.DRAFT);
        record.setCurrentApprovedVersionId(99L); // Has been approved before
        record.setMetricKey("revenue_generated");
        when(recordRepository.findById(10L)).thenReturn(Optional.of(record));

        com.apms.domain.rolemetric.dto.UpdateRoleMetricRequest req = new com.apms.domain.rolemetric.dto.UpdateRoleMetricRequest();
        req.setMeasurementDate(LocalDate.of(2026, 1, 1)); // Try to change identity

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> service.updateDraft(1L, 10L, req));
        assertTrue(ex.getMessage().contains("Cannot modify identity fields (metricKey, period, measurementDate, unitCode) after initial approval"));
    }

    @Test
    void testSubmitForReview_Idempotency() {
        RoleMetricRecord record = new RoleMetricRecord();
        record.setId(10L);
        record.setProjectId(1L);
        record.setProjectId(1L);
        record.setStatus(com.apms.domain.rolemetric.enums.RoleMetricStatus.SUBMITTED); // Already submitted

        when(recordRepository.findById(10L)).thenReturn(Optional.of(record));

        service.submitForReview(1L, 10L);

        // No audit log generated, simply returns because it's idempotent
        verify(auditLogService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void testReviewMetric_Idempotency() {
        RoleMetricRecord record = new RoleMetricRecord();
        record.setId(10L);
        record.setProjectId(1L);
        record.setProjectId(1L);
        record.setStatus(com.apms.domain.rolemetric.enums.RoleMetricStatus.APPROVED); // Already approved
        record.setTaskId(null);
        when(recordRepository.findById(10L)).thenReturn(Optional.of(record));

        com.apms.domain.rolemetric.dto.ReviewRoleMetricRequest req = new com.apms.domain.rolemetric.dto.ReviewRoleMetricRequest();
        req.setDecision(com.apms.domain.rolemetric.enums.RoleMetricReviewDecision.APPROVE);

        service.reviewMetric(1L, 10L, req);

        // No new version saved because it's idempotent
        verify(evidenceRepository, never()).save(any());
    }

    @Test
    void testReopenMetric() {
        RoleMetricRecord record = new RoleMetricRecord();
        record.setId(10L);
        record.setProjectId(1L);
        record.setProjectId(1L);
        record.setStatus(com.apms.domain.rolemetric.enums.RoleMetricStatus.REJECTED);
        record.setWorkingRevisionNumber(1);
        when(recordRepository.findById(10L)).thenReturn(Optional.of(record));

        when(recordRepository.save(any())).thenReturn(record);

        service.reopenMetric(1L, 10L);

        assertEquals(com.apms.domain.rolemetric.enums.RoleMetricStatus.DRAFT, record.getStatus());
        assertEquals(2, record.getWorkingRevisionNumber());
        verify(recordRepository).save(record);
        verify(auditLogService).log(eq(100L), eq(AuditAction.ROLE_METRIC_REOPENED), eq("RoleMetricRecord"), eq("10"), any());
    }

    @Test
    void testReviseMetric_ThrowsIfStillDraft() {
        RoleMetricRecord record = new RoleMetricRecord();
        record.setId(10L);
        record.setProjectId(1L);
        record.setProjectId(1L);
        record.setStatus(com.apms.domain.rolemetric.enums.RoleMetricStatus.DRAFT);
        when(recordRepository.findById(10L)).thenReturn(Optional.of(record));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> service.reviseMetric(1L, 10L));
        assertTrue(ex.getMessage().contains("Only APPROVED metrics can be revised"));
    }

    @Test
    void testAttachEvidence_FailsSourceAlignment_MissingContract() {
        RoleMetricRecord record = new RoleMetricRecord();
        record.setId(10L);
        record.setProjectId(1L);
        record.setProjectId(1L);
        record.setStatus(com.apms.domain.rolemetric.enums.RoleMetricStatus.DRAFT);
        when(recordRepository.findById(10L)).thenReturn(Optional.of(record));

        com.apms.domain.rolemetric.dto.RoleMetricEvidenceRequest req = new com.apms.domain.rolemetric.dto.RoleMetricEvidenceRequest();
        req.setSourceType(com.apms.domain.rolemetric.enums.RoleMetricEvidenceSourceType.CONTRACT_CLAUSE);
        // Missing contract version ID

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> service.attachEvidence(1L, 10L, req));
        assertTrue(ex.getMessage().contains("Contract and clause versions required for CONTRACT_CLAUSE"));
    }
}

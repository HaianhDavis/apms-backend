package com.apms.domain.candidate.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.CandidateStatus;
import com.apms.common.enums.TaskStatus;
import com.apms.common.enums.TaskType;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.ai.service.AiExtractionService;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.domain.candidate.dto.CandidateResponse;
import com.apms.domain.candidate.repository.mongo.CandidateDraftSequenceRepository;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.document.repository.sql.ImportJobRepository;
import com.apms.domain.financial.DocumentCompanyValidationStatus;
import com.apms.domain.financial.service.DocumentCompanyMatcher;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.fieldapproval.FieldApprovalGuard;
import com.apms.domain.project.fieldapproval.FieldApprovalService;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.user.Account;
import com.apms.security.UserDetailsImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CandidateCompanyMatchTest {

    @Mock private CompanyCandidateRepository candidateRepository;
    @Mock private ImportJobRepository importJobRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private AiExtractionService aiExtractionService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private OwnerOrganizationService ownerOrganizationService;
    @Mock private FieldApprovalGuard fieldApprovalGuard;
    @Mock private FieldApprovalService fieldApprovalService;
    @Mock private ObjectMapper objectMapper;
    @Mock private ProjectTaskRepository projectTaskRepository;
    @Mock private RawDocumentRepository rawDocumentRepository;
    @Mock private CandidateDraftSequenceRepository draftSequenceRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private DocumentCompanyMatcher companyMatcher;

    @InjectMocks
    private CandidateService candidateService;

    private final Long projectId = 10L;
    private final Long taskId = 200L;
    private final Long staffId = 42L;

    private Project project;
    private ProjectTask task;
    private Account staffAccount;
    private CompanyCandidate candidate;

    @BeforeEach
    void setUp() {
        staffAccount = Account.builder()
                .id(staffId)
                .email("staff@apms.com")
                .passwordHash("hash")
                .build();

        project = Project.builder()
                .id(projectId)
                .projectName("Alpha Project")
                .targetCompanyName("Alpha Corp")
                .targetCompanyTaxCode("0102030405")
                .build();

        task = ProjectTask.builder()
                .id(taskId)
                .project(project)
                .taskType(TaskType.COMPANY_DATA_PREPARATION)
                .status(TaskStatus.IN_PROGRESS)
                .assignedToAccount(staffAccount)
                .build();

        candidate = CompanyCandidate.builder()
                .id("cand-1")
                .projectId(String.valueOf(projectId))
                .taskId(taskId)
                .status(CandidateStatus.DRAFT)
                .draftName("Draft 1")
                .draftSequence(1)
                .revisionNumber(1)
                .documentVersion(0L)
                .detectedCompanyName("Alpha Corp")
                .companyMatchStatus(DocumentCompanyValidationStatus.MATCH)
                .companyMatchConfirmed(true)
                .identity(CompanyCandidate.Identity.builder()
                        .legalName("Alpha Corp")
                        .taxCode("0102030405")
                        .build())
                .build();
    }

    private void mockSecurityContext(Long userId, String role) {
        UserDetailsImpl userDetails = new UserDetailsImpl(
                userId,
                "user@apms.com",
                "hash",
                Collections.singletonList(new SimpleGrantedAuthority(role)),
                true
        );
        Authentication auth = mock(Authentication.class);
        lenient().when(auth.getPrincipal()).thenReturn(userDetails);
        lenient().when(auth.getAuthorities()).thenReturn((java.util.Collection) Collections.singletonList(new SimpleGrantedAuthority(role)));
        SecurityContext securityContext = mock(SecurityContext.class);
        lenient().when(securityContext.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(securityContext);
    }

    @Test
    @DisplayName("CAND-COMPANY-001: MATCH status auto-confirms company match (confirmed=true, by=null, at=null)")
    void testMatchStatusAutoConfirms() {
        candidate.setCompanyMatchStatus(DocumentCompanyValidationStatus.MATCH);
        candidate.setCompanyMatchConfirmed(true);
        candidate.setCompanyMatchConfirmedBy(null);
        candidate.setCompanyMatchConfirmedAt(null);

        assertThat(candidateService.resolveCompanyMatchStatus(candidate)).isEqualTo(DocumentCompanyValidationStatus.MATCH);
        assertThat(candidateService.requiresCompanyConfirmation(candidate)).isFalse();
    }

    @Test
    @DisplayName("CAND-COMPANY-002: POSSIBLE_MATCH status requires confirmation and blocks submission")
    void testPossibleMatchRequiresConfirmation() {
        candidate.setCompanyMatchStatus(DocumentCompanyValidationStatus.POSSIBLE_MATCH);
        candidate.setCompanyMatchConfirmed(false);

        when(candidateRepository.findById("cand-1")).thenReturn(Optional.of(candidate));

        assertThat(candidateService.requiresCompanyConfirmation(candidate)).isTrue();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                candidateService.submitCandidate("cand-1", staffId)
        );
        assertThat(ex.getErrorCode()).isEqualTo("COMPANY_MATCH_UNCONFIRMED");
    }

    @Test
    @DisplayName("CAND-COMPANY-003: MISMATCH status requires confirmation and blocks submission")
    void testMismatchRequiresConfirmation() {
        candidate.setCompanyMatchStatus(DocumentCompanyValidationStatus.MISMATCH);
        candidate.setCompanyMatchConfirmed(false);

        when(candidateRepository.findById("cand-1")).thenReturn(Optional.of(candidate));

        assertThat(candidateService.requiresCompanyConfirmation(candidate)).isTrue();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                candidateService.submitCandidate("cand-1", staffId)
        );
        assertThat(ex.getErrorCode()).isEqualTo("COMPANY_MATCH_UNCONFIRMED");
    }

    @Test
    @DisplayName("CAND-COMPANY-004: UNKNOWN status requires confirmation and blocks submission")
    void testUnknownRequiresConfirmation() {
        candidate.setCompanyMatchStatus(DocumentCompanyValidationStatus.UNKNOWN);
        candidate.setCompanyMatchConfirmed(false);

        when(candidateRepository.findById("cand-1")).thenReturn(Optional.of(candidate));

        assertThat(candidateService.requiresCompanyConfirmation(candidate)).isTrue();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                candidateService.submitCandidate("cand-1", staffId)
        );
        assertThat(ex.getErrorCode()).isEqualTo("COMPANY_MATCH_UNCONFIRMED");
    }

    @Test
    @DisplayName("CAND-COMPANY-005: Successful manual confirmation unblocks submission")
    void testManualConfirmationUnblocksSubmission() {
        mockSecurityContext(staffId, "ROLE_BUSINESS_DEVELOPMENT_STAFF");
        when(candidateRepository.findById("cand-1")).thenReturn(Optional.of(candidate));
        when(projectRepository.existsByIdAndMembersAccountId(projectId, staffId)).thenReturn(true);
        when(projectTaskRepository.findById(taskId)).thenReturn(Optional.of(task));

        candidate.setCompanyMatchStatus(DocumentCompanyValidationStatus.MISMATCH);
        candidate.setCompanyMatchConfirmed(false);

        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(inv -> inv.getArgument(0));

        CandidateResponse response = candidateService.confirmCompanyMatch("cand-1", true, staffId);

        assertThat(candidate.getCompanyMatchConfirmed()).isTrue();
        assertThat(candidate.getCompanyMatchConfirmedBy()).isEqualTo(staffId);
        assertThat(candidate.getCompanyMatchConfirmedAt()).isNotNull();
        assertThat(candidateService.requiresCompanyConfirmation(candidate)).isFalse();

        verify(auditLogService).log(eq(staffId), eq(AuditAction.PROJECT_TASK_UPDATED), eq("CompanyCandidate"), eq("cand-1"), anyString());
    }

    @Test
    @DisplayName("CAND-COMPANY-008: Legacy candidate fallback: if companyMatchStatus is null, evaluate detectedCompanyName vs target")
    void testLegacyCandidateFallbackWithDetectedCompany() {
        candidate.setCompanyMatchStatus(null);
        candidate.setDetectedCompanyName("Alpha Corp Ltd");
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(companyMatcher.evaluateCompanyMatch("Alpha Corp Ltd", "Alpha Corp"))
                .thenReturn(DocumentCompanyValidationStatus.POSSIBLE_MATCH);

        DocumentCompanyValidationStatus status = candidateService.resolveCompanyMatchStatus(candidate);
        assertThat(status).isEqualTo(DocumentCompanyValidationStatus.POSSIBLE_MATCH);
    }

    @Test
    @DisplayName("CAND-COMPANY-008b: Legacy candidate fallback without detected company returns UNKNOWN (never false MATCH)")
    void testLegacyCandidateFallbackWithoutDetectedCompanyReturnsUnknown() {
        candidate.setCompanyMatchStatus(null);
        candidate.setDetectedCompanyName(null);
        candidate.setCompanyMatchConfirmed(null);
        // Even if identity.legalName is "Alpha Corp" (because it was overridden), it must return UNKNOWN
        candidate.getIdentity().setLegalName("Alpha Corp");

        DocumentCompanyValidationStatus status = candidateService.resolveCompanyMatchStatus(candidate);
        assertThat(status).isEqualTo(DocumentCompanyValidationStatus.UNKNOWN);
        assertThat(candidateService.requiresCompanyConfirmation(candidate)).isTrue();
    }

    @Test
    @DisplayName("CAND-COMPANY-010: Authorization: non-member or non-assigned cannot confirm")
    void testUnauthorizedCannotConfirm() {
        Long outsiderId = 999L;
        mockSecurityContext(outsiderId, "ROLE_BUSINESS_DEVELOPMENT_STAFF");
        when(candidateRepository.findById("cand-1")).thenReturn(Optional.of(candidate));
        when(projectRepository.existsByIdAndMembersAccountId(projectId, outsiderId)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () ->
                candidateService.confirmCompanyMatch("cand-1", true, outsiderId)
        );
    }

    @Test
    @DisplayName("CAND-COMPANY-011: Immutable: cannot confirm in APPROVED or PENDING_REVIEW candidate status")
    void testCannotConfirmApprovedOrPendingReview() {
        mockSecurityContext(staffId, "ROLE_BUSINESS_DEVELOPMENT_STAFF");
        candidate.setStatus(CandidateStatus.APPROVED);
        when(candidateRepository.findById("cand-1")).thenReturn(Optional.of(candidate));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                candidateService.confirmCompanyMatch("cand-1", true, staffId)
        );
        assertThat(ex.getErrorCode()).isEqualTo("CANDIDATE_APPROVED_IMMUTABLE");

        candidate.setStatus(CandidateStatus.PENDING_REVIEW);
        BusinessValidationException ex2 = assertThrows(BusinessValidationException.class, () ->
                candidateService.confirmCompanyMatch("cand-1", true, staffId)
        );
        assertThat(ex2.getErrorCode()).isEqualTo("CANDIDATE_IN_REVIEW");
    }

    @Test
    @DisplayName("CAND-COMPANY-012: Manual candidate creation sets MATCH and confirmed=true")
    void testCreateManualCandidateSetsMatchAndConfirmed() {
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(candidateRepository.findByTaskId(taskId)).thenReturn(new ArrayList<>());
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(inv -> inv.getArgument(0));

        CandidateResponse response = candidateService.createManualCandidate(projectId, taskId, staffId);

        assertThat(response.getCompanyMatchStatus()).isEqualTo(DocumentCompanyValidationStatus.MATCH);
        assertThat(response.getCompanyMatchConfirmed()).isTrue();
        assertThat(response.getDetectedCompanyName()).isEqualTo("Alpha Corp");
    }
}

package com.apms.domain.candidate.service;

import com.apms.common.enums.CandidateStatus;
import com.apms.common.enums.FieldApprovalStatus;
import com.apms.domain.ai.dto.ExtractionReviewStatus;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.domain.candidate.dto.CandidateReviewRequest;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.project.fieldapproval.FieldApprovalRecord;
import com.apms.domain.project.fieldapproval.FieldApprovalUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CandidateServiceManagerReviewTest {

    @Mock private CompanyCandidateRepository candidateRepository;
    @Mock private com.apms.domain.document.repository.sql.ImportJobRepository importJobRepository;
    @Mock private com.apms.domain.project.repository.sql.ProjectRepository projectRepository;
    @Mock private com.apms.domain.ai.service.AiExtractionService aiExtractionService;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock private com.apms.domain.profile.service.OwnerOrganizationService ownerOrganizationService;
    @Mock private com.apms.domain.project.fieldapproval.FieldApprovalGuard fieldApprovalGuard;
    @Mock private com.apms.domain.project.fieldapproval.FieldApprovalService fieldApprovalService;
    @Mock private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Mock private com.apms.domain.project.repository.sql.ProjectTaskRepository projectTaskRepository;
    @Mock private com.apms.domain.document.repository.mongo.RawDocumentRepository rawDocumentRepository;
    @Mock private com.apms.domain.candidate.repository.mongo.CandidateDraftSequenceRepository draftSequenceRepository;
    @Mock private com.apms.domain.audit.service.AuditLogService auditLogService;
    @Mock private com.apms.domain.financial.service.DocumentCompanyMatcher companyMatcher;

    @InjectMocks
    private CandidateService candidateService;

    @Test
    void managerApprovalOfOpportunitiesPersistsCanonicalFieldApproval() {
        CompanyCandidate candidate = CompanyCandidate.builder()
                .id("candidate-1")
                .projectId("10")
                .revisionNumber(1)
                .status(CandidateStatus.PENDING_REVIEW)
                .insights(CompanyCandidate.Insights.builder()
                        .opportunities(List.of("Partner ecosystem expansion"))
                        .build())
                .fieldApprovals(new ArrayList<>(List.of(
                        FieldApprovalRecord.builder()
                                .fieldPath("insights.opportunities")
                                .status(FieldApprovalStatus.PENDING_REVIEW)
                                .changedInRevision(1)
                                .build()
                )))
                .fieldResults(new java.util.HashMap<>())
                .build();

        CandidateReviewRequest.FieldReviewUpdate update = new CandidateReviewRequest.FieldReviewUpdate();
        update.setManager(true);
        update.setManagerReviewStatus(ExtractionReviewStatus.ACCEPTED);

        CandidateReviewRequest request = new CandidateReviewRequest();
        request.setFields(Map.of("insights.opportunities", update));

        when(candidateRepository.findById("candidate-1")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        candidateService.reviewCandidate("10", "candidate-1", request, 99L);

        ArgumentCaptor<CompanyCandidate> savedCandidate = ArgumentCaptor.forClass(CompanyCandidate.class);
        org.mockito.Mockito.verify(candidateRepository).save(savedCandidate.capture());

        CompanyCandidate saved = savedCandidate.getValue();
        FieldApprovalRecord opportunitiesApproval = FieldApprovalUtils.toMap(saved.getFieldApprovals()).get("insights.opportunities");
        assertThat(opportunitiesApproval.getStatus()).isEqualTo(FieldApprovalStatus.APPROVED);
        assertThat(opportunitiesApproval.getReviewedByAccountId()).isEqualTo(99L);
        assertThat(opportunitiesApproval.getReviewedRevision()).isEqualTo(1);
        assertThat(opportunitiesApproval.getApprovedValueHash()).isNotBlank();

        assertThat(saved.getFieldResults().values())
                .singleElement()
                .satisfies(field -> assertThat(field.getManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.ACCEPTED));
    }

    @Test
    void round1RequestChangesProducesCurrentDecisionAndNullPreviousDecision() {
        CompanyCandidate candidate = CompanyCandidate.builder()
                .id("cand-r1")
                .projectId("10")
                .revisionNumber(1)
                .status(CandidateStatus.PENDING_REVIEW)
                .identity(CompanyCandidate.Identity.builder().legalName("Test Corp").build())
                .fieldApprovals(new ArrayList<>(List.of(
                        FieldApprovalRecord.builder()
                                .fieldPath("identity.tradeName")
                                .status(FieldApprovalStatus.PENDING_REVIEW)
                                .changedInRevision(1)
                                .build()
                )))
                .fieldResults(new java.util.HashMap<>())
                .build();

        CandidateReviewRequest.FieldReviewUpdate update = new CandidateReviewRequest.FieldReviewUpdate();
        update.setManager(true);
        update.setManagerReviewStatus(ExtractionReviewStatus.NEEDS_REVIEW);
        update.setManagerReviewComment("sửa lại");

        CandidateReviewRequest request = new CandidateReviewRequest();
        request.setFields(Map.of("identity.tradeName", update));

        when(candidateRepository.findById("cand-r1")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        com.apms.domain.candidate.dto.CandidateResponse response = candidateService.reviewCandidate("10", "cand-r1", request, 99L);

        assertThat(response.getCurrentReviewRound()).isEqualTo(1);
        com.apms.domain.ai.dto.ExtractionFieldResult tradeNameResult = response.getFieldResults().get("identity.tradeName");
        assertThat(tradeNameResult).isNotNull();
        assertThat(tradeNameResult.getManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.NEEDS_REVIEW);
        assertThat(tradeNameResult.getManagerReviewComment()).isEqualTo("sửa lại");

        // Canonical current decision
        assertThat(tradeNameResult.getCurrentDecision()).isNotNull();
        assertThat(tradeNameResult.getCurrentDecision().getRoundNumber()).isEqualTo(1);
        assertThat(tradeNameResult.getCurrentDecision().getStatus()).isEqualTo(ExtractionReviewStatus.NEEDS_REVIEW);
        assertThat(tradeNameResult.getCurrentDecision().getComment()).isEqualTo("sửa lại");

        // INVARIANT: Round 1 MUST have previousDecision == null!
        assertThat(tradeNameResult.getPreviousDecision()).isNull();
        assertThat(tradeNameResult.getPreviousManagerReviewStatus()).isNull();
        assertThat(tradeNameResult.getPreviousManagerReviewComment()).isNull();
    }

    @Test
    void round2ResubmissionWithSameNullValueProducesPendingCurrentAndRound1PreviousDecision() {
        CompanyCandidate candidate = CompanyCandidate.builder()
                .id("cand-r2")
                .projectId("10")
                .revisionNumber(2)
                .companyMatchStatus(com.apms.domain.financial.DocumentCompanyValidationStatus.MATCH)
                .companyMatchConfirmed(true)
                .extractionSource(CompanyCandidate.ExtractionSource.builder().extractionMethod("MANUAL").build())
                .status(CandidateStatus.REVISION_REQUIRED)
                .identity(CompanyCandidate.Identity.builder().legalName("Test Corp").build())
                .fieldApprovals(new ArrayList<>(List.of(
                        FieldApprovalRecord.builder()
                                .fieldPath("identity.tradeName")
                                .status(FieldApprovalStatus.REVISION_REQUIRED)
                                .comment("sửa lại")
                                .reviewedRevision(1)
                                .reviewedByAccountId(99L)
                                .build()
                )))
                .fieldResults(new java.util.HashMap<>())
                .build();

        when(candidateRepository.findById("cand-r2")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Staff resubmits candidate in Round 2 without populating tradeName (still null)
        com.apms.domain.candidate.dto.CandidateResponse response = candidateService.submitCandidate("cand-r2", 88L);

        assertThat(response.getCurrentReviewRound()).isEqualTo(2);
        com.apms.domain.ai.dto.ExtractionFieldResult tradeNameResult = response.getFieldResults().get("identity.tradeName");
        assertThat(tradeNameResult).isNotNull();

        // Current round (Round 2) is PENDING
        assertThat(tradeNameResult.getManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.PENDING);
        assertThat(tradeNameResult.getCurrentDecision()).isNotNull();
        assertThat(tradeNameResult.getCurrentDecision().getRoundNumber()).isEqualTo(2);
        assertThat(tradeNameResult.getCurrentDecision().getStatus()).isEqualTo(ExtractionReviewStatus.PENDING);
        assertThat(tradeNameResult.getCurrentDecision().getComment()).isNull();

        // Previous round (Round 1) is preserved as CHANGES_REQUESTED / NEEDS_REVIEW with "sửa lại"
        assertThat(tradeNameResult.getPreviousDecision()).isNotNull();
        assertThat(tradeNameResult.getPreviousDecision().getRoundNumber()).isEqualTo(1);
        assertThat(tradeNameResult.getPreviousDecision().getStatus()).isEqualTo(ExtractionReviewStatus.NEEDS_REVIEW);
        assertThat(tradeNameResult.getPreviousDecision().getComment()).isEqualTo("sửa lại");
        assertThat(tradeNameResult.getPreviousManagerReviewStatus()).isEqualTo(ExtractionReviewStatus.NEEDS_REVIEW);
        assertThat(tradeNameResult.getPreviousManagerReviewComment()).isEqualTo("sửa lại");
        assertThat(tradeNameResult.getResubmittedInCurrentRound()).isTrue();
    }

    @Test
    void round2ManagerReviewPreservesPreviousSubmittedValueFromRound1AndShowsRevisedValueInCurrent() {
        // Round 1 submitted value: old@email.com
        // Manager requested changes in Round 1: "Please correct the email"
        // In Round 2, Staff revised value to: new@email.com
        com.apms.domain.ai.dto.ExtractionFieldResult initialFieldResult = new com.apms.domain.ai.dto.ExtractionFieldResult();
        initialFieldResult.setFieldName("contact.emails");
        initialFieldResult.setValue(List.of("old@email.com"));
        initialFieldResult.setPreviousSubmittedValue(List.of("old@email.com"));

        CompanyCandidate candidate = CompanyCandidate.builder()
                .id("cand-email-r2")
                .projectId("10")
                .revisionNumber(2)
                .companyMatchStatus(com.apms.domain.financial.DocumentCompanyValidationStatus.MATCH)
                .companyMatchConfirmed(true)
                .extractionSource(CompanyCandidate.ExtractionSource.builder().extractionMethod("MANUAL").build())
                .status(CandidateStatus.REVISION_REQUIRED)
                .identity(CompanyCandidate.Identity.builder().legalName("Test Corp").build())
                .contact(CompanyCandidate.Contact.builder()
                        .emails(List.of("new@email.com"))
                        .build())
                .fieldApprovals(new ArrayList<>(List.of(
                        FieldApprovalRecord.builder()
                                .fieldPath("contact.emails")
                                .status(FieldApprovalStatus.REVISION_REQUIRED)
                                .comment("Please correct the email")
                                .reviewedRevision(1)
                                .reviewedByAccountId(99L)
                                .pendingValue(List.of("old@email.com"))
                                .previousSubmittedValue(List.of("old@email.com"))
                                .build()
                )))
                .fieldResults(new java.util.HashMap<>(Map.of(
                        com.apms.domain.ai.service.FieldKeyCodec.encode("contact.emails"),
                        initialFieldResult
                )))
                .build();

        when(candidateRepository.findById("cand-email-r2")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Staff submits Round 2
        com.apms.domain.candidate.dto.CandidateResponse response = candidateService.submitCandidate("cand-email-r2", 88L);

        assertThat(response.getCurrentReviewRound()).isEqualTo(2);
        com.apms.domain.ai.dto.ExtractionFieldResult emailResult = response.getFieldResults().get("contact.emails");
        assertThat(emailResult).isNotNull();

        // Current round (Round 2) submitted value is the newly revised value: new@email.com
        assertThat(emailResult.getCurrentDecision()).isNotNull();
        assertThat(emailResult.getCurrentDecision().getRoundNumber()).isEqualTo(2);
        assertThat(emailResult.getCurrentDecision().getStatus()).isEqualTo(ExtractionReviewStatus.PENDING);
        assertThat(emailResult.getCurrentDecision().getSubmittedValue()).isEqualTo(List.of("new@email.com"));

        // Previous round (Round 1) submitted value MUST be old@email.com, NOT new@email.com
        assertThat(emailResult.getPreviousDecision()).isNotNull();
        assertThat(emailResult.getPreviousDecision().getRoundNumber()).isEqualTo(1);
        assertThat(emailResult.getPreviousDecision().getStatus()).isEqualTo(ExtractionReviewStatus.NEEDS_REVIEW);
        assertThat(emailResult.getPreviousDecision().getComment()).isEqualTo("Please correct the email");
        assertThat(emailResult.getPreviousDecision().getSubmittedValue()).isEqualTo(List.of("old@email.com"));
        assertThat(emailResult.getPreviousSubmittedValue()).isEqualTo(List.of("old@email.com"));
    }
}

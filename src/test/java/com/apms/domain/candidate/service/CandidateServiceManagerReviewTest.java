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
}

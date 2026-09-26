package com.apms.domain.contract.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SubmissionType;
import com.apms.common.enums.TaskStatus;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.contract.dto.*;
import com.apms.domain.contract.enums.*;
import com.apms.domain.contract.model.*;
import com.apms.domain.contract.repository.mongo.ContractResearchRepository;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.UserProfile;
import com.apms.domain.user.repository.sql.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContractResearchServiceTest {

    @Mock
    private ContractResearchRepository contractResearchRepository;
    @Mock
    private RawDocumentRepository rawDocumentRepository;
    @Mock
    private ProjectTaskRepository projectTaskRepository;
    @Mock
    private ProjectTaskSubmissionRepository projectTaskSubmissionRepository;
    @Mock
    private CompanyProfileRepository companyProfileRepository;
    @Mock
    private UserProfileRepository userProfileRepository;
    @Mock
    private ContractExtractionService extractionService;
    @Mock
    private ContractExtractionNormalizer normalizer;
    @Mock
    private ContractCompanyMatcher companyMatcher;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private ContractResearchService contractResearchService;

    private final Long projectId = 1L;
    private final Long taskId = 10L;
    private final Long staffId = 42L;
    private final Long managerId = 88L;

    private Project project;
    private ProjectTask task;
    private Account staffAccount;

    @BeforeEach
    void setUp() {
        project = Project.builder().id(projectId).projectName("Contract Project").build();
        staffAccount = Account.builder().id(staffId).email("staff@apms.com").build();
        task = ProjectTask.builder()
                .id(taskId)
                .project(project)
                .assignedToAccount(staffAccount)
                .status(TaskStatus.IN_PROGRESS)
                .build();
    }

    @Test
    @DisplayName("Create contract entry initializes DRAFT state")
    void createContractEntry_InitializesDraftState() {
        RawDocument doc = RawDocument.builder()
                .id("doc-1")
                .source(RawDocument.Source.builder().fileName("Agreement.pdf").build())
                .build();
        when(rawDocumentRepository.findById("doc-1")).thenReturn(Optional.of(doc));

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.DRAFT)
                .contracts(new ArrayList<>())
                .build();
        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateContractEntryRequest req = CreateContractEntryRequest.builder()
                .title("Cooperation Contract 2026")
                .documentId("doc-1")
                .declaredContractType(ContractTypeSelection.AUTO_DETECT)
                .documentDate(LocalDate.of(2026, 1, 15))
                .build();

        ContractResearchResponse response = contractResearchService.createContractEntry(taskId, req, staffId);

        assertThat(response).isNotNull();
        assertThat(response.getContracts()).hasSize(1);
        ContractEntry created = response.getContracts().get(0);
        assertThat(response.getCreatedContractId()).isEqualTo(created.getId());
        assertThat(created.getTitle()).isEqualTo("Cooperation Contract 2026");
        assertThat(created.getReviewStatus()).isEqualTo(ContractEntryReviewStatus.DRAFT);
        assertThat(created.getExtractionStatus()).isEqualTo(ContractExtractionStatus.NOT_EXTRACTED);
        assertThat(created.getDeclaredContractType()).isEqualTo(ContractType.COOPERATION_AGREEMENT);
    }

    @Test
    @DisplayName("Delete succeeds for recalled DRAFT contract even if it appeared in historical WITHDRAWN submission")
    void deleteContractEntry_SucceedsIfAppearedInHistoricalWithdrawnSubmission() {
        ContractEntry c1 = ContractEntry.builder().id("contract-1").reviewStatus(ContractEntryReviewStatus.DRAFT).build();
        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .contracts(new ArrayList<>(List.of(c1)))
                .build();

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectTaskSubmission historicalSub = ProjectTaskSubmission.builder()
                .id(101L)
                .targetItemIds("contract-1,contract-2")
                .status(SubmissionStatus.WITHDRAWN)
                .build();
        when(projectTaskSubmissionRepository.findByProjectTask_Id(taskId)).thenReturn(List.of(historicalSub));

        ContractResearchResponse response = contractResearchService.deleteContractEntry(taskId, "contract-1", staffId);

        assertThat(response).isNotNull();
        assertThat(research.getContracts()).isEmpty();
    }

    @Test
    @DisplayName("Delete is rejected if contract is currently part of an active submission awaiting review")
    void deleteContractEntry_FailsIfInActiveSubmission() {
        ContractEntry c1 = ContractEntry.builder().id("contract-1").reviewStatus(ContractEntryReviewStatus.DRAFT).build();
        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .contracts(new ArrayList<>(List.of(c1)))
                .build();

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));

        ProjectTaskSubmission activeSub = ProjectTaskSubmission.builder()
                .id(101L)
                .targetItemIds("contract-1,contract-2")
                .status(SubmissionStatus.IN_REVIEW)
                .build();
        when(projectTaskSubmissionRepository.findByProjectTask_Id(taskId)).thenReturn(List.of(activeSub));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                contractResearchService.deleteContractEntry(taskId, "contract-1", staffId)
        );

        assertThat(ex.getMessage()).contains("Contract cannot be deleted while it is under active review");
    }

    @Test
    @DisplayName("Submit research creates immutable ProjectTaskSubmission and sets contracts PENDING_REVIEW")
    void submitResearch_Success_CreatesImmutableSubmission() {
        ContractEntry c1 = ContractEntry.builder()
                .id("c-1")
                .title("Contract 1")
                .extractionStatus(ContractExtractionStatus.COMPLETED)
                .reviewStatus(ContractEntryReviewStatus.DRAFT)
                .typeValidationStatus(TypeValidationStatus.CONFIRMED)
                .companyMatchStatus(CompanyMatchStatus.MATCH)
                .companyMatchConfirmed(true)
                .commonData(CommonContractData.builder()
                        .purpose(ExtractedContractField.<String>builder()
                                .value("Purpose")
                                .qualityStatus(ContractFieldQualityStatus.VALID)
                                .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                                .build())
                        .build())
                .build();

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.DRAFT)
                .contracts(new ArrayList<>(List.of(c1)))
                .build();

        when(projectTaskSubmissionRepository.findByProjectTask_Id(taskId)).thenReturn(Collections.emptyList());
        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        SubmitContractResearchRequest req = SubmitContractResearchRequest.builder()
                .contractEntryIds(List.of("c-1"))
                .note("Please review")
                .build();

        ContractResearchResponse response = contractResearchService.submitResearch(projectId, taskId, req, staffId);

        assertThat(response).isNotNull();
        assertThat(c1.getReviewStatus()).isEqualTo(ContractEntryReviewStatus.PENDING_REVIEW);
        assertThat(research.getStatus()).isEqualTo(ContractResearchStatus.SUBMITTED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_REVIEW);

        verify(projectTaskSubmissionRepository).save(argThat(sub ->
                sub.getSubmissionType() == SubmissionType.PARTNER_CONTRACT_COLLECTION
                        && sub.getTargetItemIds().equals("c-1")
                        && sub.getStatus() == SubmissionStatus.IN_REVIEW
        ));
    }

    @Test
    @DisplayName("Submit research fails if double-clicked / active submission already exists")
    void submitResearch_Fails_WhenActiveSubmissionExists() {
        ProjectTaskSubmission activeSub = ProjectTaskSubmission.builder()
                .id(200L)
                .status(SubmissionStatus.IN_REVIEW)
                .build();
        when(projectTaskSubmissionRepository.findByProjectTask_Id(taskId)).thenReturn(List.of(activeSub));

        SubmitContractResearchRequest req = SubmitContractResearchRequest.builder()
                .contractEntryIds(List.of("c-1"))
                .build();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                contractResearchService.submitResearch(projectId, taskId, req, staffId)
        );

        assertThat(ex.getMessage()).contains("A submission is already active");
    }

    @Test
    @DisplayName("Submit research fails when extracted fields have not been verified by staff")
    void submitResearch_Fails_WhenFieldsUnverified() {
        ContractEntry c1 = ContractEntry.builder()
                .id("c-1")
                .title("Contract 1")
                .extractionStatus(ContractExtractionStatus.COMPLETED)
                .reviewStatus(ContractEntryReviewStatus.DRAFT)
                .typeValidationStatus(TypeValidationStatus.CONFIRMED)
                .companyMatchStatus(CompanyMatchStatus.MATCH)
                .companyMatchConfirmed(true)
                .commonData(CommonContractData.builder()
                        .purpose(ExtractedContractField.<String>builder()
                                .value("Purpose")
                                .qualityStatus(ContractFieldQualityStatus.VALID)
                                .verificationStatus(ContractFieldVerificationStatus.UNVERIFIED)
                                .build())
                        .build())
                .build();

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.DRAFT)
                .contracts(new ArrayList<>(List.of(c1)))
                .build();

        when(projectTaskSubmissionRepository.findByProjectTask_Id(taskId)).thenReturn(Collections.emptyList());
        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));

        SubmitContractResearchRequest req = SubmitContractResearchRequest.builder()
                .contractEntryIds(List.of("c-1"))
                .build();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                contractResearchService.submitResearch(projectId, taskId, req, staffId)
        );

        assertThat(ex.getErrorCode()).isEqualTo("UNVERIFIED_FIELDS");
    }

    @Test
    @DisplayName("Recall restores contracts and marks submission WITHDRAWN before Manager decision")
    void recallSubmission_Success_RestoresContracts() {
        ContractEntry c1 = ContractEntry.builder()
                .id("c-1")
                .reviewStatus(ContractEntryReviewStatus.PENDING_REVIEW)
                .build();
        ContractEntry c2 = ContractEntry.builder()
                .id("c-2")
                .reviewStatus(ContractEntryReviewStatus.PENDING_REVIEW)
                .reviewComment("Prior feedback: Fix sharing ratio")
                .build();

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .status(ContractResearchStatus.SUBMITTED)
                .contracts(new ArrayList<>(List.of(c1, c2)))
                .build();

        ProjectTaskSubmission activeSub = ProjectTaskSubmission.builder()
                .id(300L)
                .targetItemIds("c-1,c-2")
                .status(SubmissionStatus.IN_REVIEW)
                .build();

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(projectTaskSubmissionRepository.findByProjectTask_Id(taskId)).thenReturn(List.of(activeSub));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        ContractResearchResponse response = contractResearchService.recallSubmission(projectId, taskId, staffId);

        assertThat(response).isNotNull();
        assertThat(activeSub.getStatus()).isEqualTo(SubmissionStatus.WITHDRAWN);
        assertThat(c1.getReviewStatus()).isEqualTo(ContractEntryReviewStatus.DRAFT);
        // c2 had prior reviewComment -> restores to CHANGES_REQUESTED
        assertThat(c2.getReviewStatus()).isEqualTo(ContractEntryReviewStatus.CHANGES_REQUESTED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(research.getStatus()).isEqualTo(ContractResearchStatus.CHANGES_REQUESTED);
    }

    @Test
    @DisplayName("Recall is rejected if Manager has already approved any contract in current submission")
    void recallSubmission_Fails_WhenManagerAlreadyDecided() {
        ContractEntry c1 = ContractEntry.builder()
                .id("c-1")
                .reviewStatus(ContractEntryReviewStatus.APPROVED)
                .build();
        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .status(ContractResearchStatus.SUBMITTED)
                .contracts(new ArrayList<>(List.of(c1)))
                .build();

        ProjectTaskSubmission activeSub = ProjectTaskSubmission.builder()
                .id(300L)
                .targetItemIds("c-1")
                .status(SubmissionStatus.IN_REVIEW)
                .build();

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(projectTaskSubmissionRepository.findByProjectTask_Id(taskId)).thenReturn(List.of(activeSub));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                contractResearchService.recallSubmission(projectId, taskId, staffId)
        );

        assertThat(ex.getMessage()).contains("Manager has already made a decision");
    }

    @Test
    @DisplayName("Submission-scoped Manager review updates contract and records reviewHistory")
    void reviewContractEntry_Success_AppendsReviewHistory() {
        ContractEntry c1 = ContractEntry.builder()
                .id("c-1")
                .reviewStatus(ContractEntryReviewStatus.PENDING_REVIEW)
                .reviewHistory(new ArrayList<>())
                .build();

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .status(ContractResearchStatus.SUBMITTED)
                .contracts(new ArrayList<>(List.of(c1)))
                .build();

        ProjectTaskSubmission sub = ProjectTaskSubmission.builder()
                .id(500L)
                .targetItemIds("c-1")
                .status(SubmissionStatus.IN_REVIEW)
                .build();

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(projectTaskSubmissionRepository.findById(500L)).thenReturn(Optional.of(sub));
        when(userProfileRepository.findById(managerId)).thenReturn(Optional.of(UserProfile.builder().firstName("Manager").lastName("Alice").build()));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewContractEntryRequest req = ReviewContractEntryRequest.builder()
                .status(ContractEntryReviewStatus.APPROVED)
                .build();

        ContractResearchResponse response = contractResearchService.reviewContractEntry(projectId, taskId, 500L, "c-1", req, managerId);

        assertThat(response).isNotNull();
        assertThat(c1.getReviewStatus()).isEqualTo(ContractEntryReviewStatus.APPROVED);
        assertThat(c1.getReviewHistory()).hasSize(1);
        ContractReviewEvent event = c1.getReviewHistory().get(0);
        assertThat(event.getSubmissionId()).isEqualTo(500L);
        assertThat(event.getDecision()).isEqualTo(ContractEntryReviewStatus.APPROVED);
        assertThat(event.getReviewedByName()).isEqualTo("Manager Alice");

        // Whole package is approved and task is DONE
        assertThat(research.getStatus()).isEqualTo(ContractResearchStatus.APPROVED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
    }

    @Test
    @DisplayName("Package is NOT approved if active DRAFT contract remains (Selective Submission)")
    void reviewContractEntry_SelectiveSubmission_DoesNotCompletePackageIfDraftRemains() {
        ContractEntry c1 = ContractEntry.builder()
                .id("c-1")
                .reviewStatus(ContractEntryReviewStatus.PENDING_REVIEW)
                .reviewHistory(new ArrayList<>())
                .build();
        ContractEntry c2 = ContractEntry.builder()
                .id("c-2")
                .reviewStatus(ContractEntryReviewStatus.DRAFT)
                .build();

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .status(ContractResearchStatus.SUBMITTED)
                .contracts(new ArrayList<>(List.of(c1, c2)))
                .build();

        ProjectTaskSubmission sub = ProjectTaskSubmission.builder()
                .id(600L)
                .targetItemIds("c-1") // Only c-1 in this submission
                .status(SubmissionStatus.IN_REVIEW)
                .build();

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(projectTaskSubmissionRepository.findById(600L)).thenReturn(Optional.of(sub));
        when(userProfileRepository.findById(managerId)).thenReturn(Optional.empty());
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewContractEntryRequest req = ReviewContractEntryRequest.builder()
                .status(ContractEntryReviewStatus.APPROVED)
                .build();

        ContractResearchResponse response = contractResearchService.reviewContractEntry(projectId, taskId, 600L, "c-1", req, managerId);

        assertThat(response).isNotNull();
        assertThat(c1.getReviewStatus()).isEqualTo(ContractEntryReviewStatus.APPROVED);
        // c2 is still DRAFT -> research stays DRAFT, task stays IN_PROGRESS
        assertThat(research.getStatus()).isEqualTo(ContractResearchStatus.DRAFT);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Updating nested array item by itemId preserves qualityStatus and sets VERIFIED")
    void updateArrayItem_Success() {
        ContractParty partyA = ContractParty.builder()
                .id("party-a")
                .legalName("Company A")
                .qualityStatus(ContractFieldQualityStatus.NEEDS_REVIEW)
                .verificationStatus(ContractFieldVerificationStatus.UNVERIFIED)
                .build();
        ContractParty partyB = ContractParty.builder()
                .id("party-b")
                .legalName("Company B")
                .qualityStatus(ContractFieldQualityStatus.VALID)
                .verificationStatus(ContractFieldVerificationStatus.UNVERIFIED)
                .build();

        ContractEntry c1 = ContractEntry.builder()
                .id("c-1")
                .reviewStatus(ContractEntryReviewStatus.DRAFT)
                .commonData(CommonContractData.builder().parties(new ArrayList<>(List.of(partyA, partyB))).build())
                .build();

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .contracts(new ArrayList<>(List.of(c1)))
                .build();

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateArrayItemRequest req = UpdateArrayItemRequest.builder()
                .itemPayload(Map.of("legalName", "Company A Renamed", "taxCode", "0101234567"))
                .build();

        ContractResearchResponse response = contractResearchService.updateArrayItem(taskId, "c-1", "commonData.parties", "party-a", req, staffId);

        assertThat(response).isNotNull();
        assertThat(partyA.getLegalName()).isEqualTo("Company A Renamed");
        assertThat(partyA.getTaxCode()).isEqualTo("0101234567");
        assertThat(partyA.getVerificationStatus()).isEqualTo(ContractFieldVerificationStatus.VERIFIED);
        assertThat(partyA.getQualityStatus()).isEqualTo(ContractFieldQualityStatus.NEEDS_REVIEW); // Preserved
        assertThat(partyB.getLegalName()).isEqualTo("Company B"); // Untouched
    }

    @Test
    @DisplayName("Verify all contract fields updates all common scalar fields including term and parties to VERIFIED")
    void verifyAllContractFields_VerifiesAllFieldsIncludingTerm() {
        ContractParty partyA = ContractParty.builder().id("party-1").legalName("Company A").verificationStatus(ContractFieldVerificationStatus.UNVERIFIED).build();
        CommonContractData common = CommonContractData.builder()
                .contractNumber(ExtractedContractField.<String>builder().value("HD-001").verificationStatus(ContractFieldVerificationStatus.UNVERIFIED).build())
                .term(ExtractedContractField.<String>builder().value("12 months").verificationStatus(ContractFieldVerificationStatus.UNVERIFIED).build())
                .signingDate(ExtractedContractField.<LocalDate>builder().value(LocalDate.of(2025, 1, 1)).verificationStatus(ContractFieldVerificationStatus.UNVERIFIED).build())
                .effectiveDate(ExtractedContractField.<LocalDate>builder().value(LocalDate.of(2025, 1, 1)).verificationStatus(ContractFieldVerificationStatus.UNVERIFIED).build())
                .expiryDate(ExtractedContractField.<LocalDate>builder().value(LocalDate.of(2026, 1, 1)).verificationStatus(ContractFieldVerificationStatus.UNVERIFIED).build())
                .purpose(ExtractedContractField.<String>builder().value("Cooperation").verificationStatus(ContractFieldVerificationStatus.UNVERIFIED).build())
                .governingLaw(ExtractedContractField.<String>builder().value("Vietnam").verificationStatus(ContractFieldVerificationStatus.UNVERIFIED).build())
                .parties(new ArrayList<>(List.of(partyA)))
                .build();

        ContractEntry c1 = ContractEntry.builder()
                .id("c-1")
                .reviewStatus(ContractEntryReviewStatus.DRAFT)
                .commonData(common)
                .build();

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .contracts(new ArrayList<>(List.of(c1)))
                .build();

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        ContractResearchResponse response = contractResearchService.verifyAllContractFields(taskId, "c-1", staffId);

        assertThat(response).isNotNull();
        ContractEntry updated = response.getContracts().get(0);
        assertThat(updated.getCommonData().getTerm().getVerificationStatus()).isEqualTo(ContractFieldVerificationStatus.VERIFIED);
        assertThat(updated.getCommonData().getContractNumber().getVerificationStatus()).isEqualTo(ContractFieldVerificationStatus.VERIFIED);
        assertThat(updated.getCommonData().getParties().get(0).getVerificationStatus()).isEqualTo(ContractFieldVerificationStatus.VERIFIED);

        // Test unverifyAllContractFields
        contractResearchService.unverifyAllContractFields(taskId, "c-1", staffId);
        assertThat(updated.getCommonData().getTerm().getVerificationStatus()).isEqualTo(ContractFieldVerificationStatus.UNVERIFIED);
        assertThat(updated.getCommonData().getContractNumber().getVerificationStatus()).isEqualTo(ContractFieldVerificationStatus.UNVERIFIED);
        assertThat(updated.getCommonData().getParties().get(0).getVerificationStatus()).isEqualTo(ContractFieldVerificationStatus.UNVERIFIED);
    }
}

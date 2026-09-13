package com.apms.domain.contract.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SubmissionType;
import com.apms.common.enums.TaskStatus;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.contract.dto.ContractResearchResponse;
import com.apms.domain.contract.dto.ReviewContractEntryRequest;
import com.apms.domain.contract.dto.SubmitContractResearchRequest;
import com.apms.domain.contract.dto.UpdateContractEntryRequest;
import com.apms.domain.contract.dto.UpdateScalarFieldRequest;
import com.apms.domain.contract.enums.ContractEntryReviewStatus;
import com.apms.domain.contract.enums.ContractFieldVerificationStatus;
import com.apms.domain.contract.enums.ContractExtractionStatus;
import com.apms.domain.contract.enums.ContractResearchStatus;
import com.apms.domain.contract.model.CommonContractData;
import com.apms.domain.contract.model.ContractEntry;
import com.apms.domain.contract.model.ContractResearch;
import com.apms.domain.contract.model.ContractReviewEvent;
import com.apms.domain.contract.model.ExtractedContractField;
import com.apms.domain.contract.repository.mongo.ContractResearchRepository;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.UserProfile;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.domain.user.repository.sql.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContractResearchMultiDocumentReviewTest {

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
    private AccountRepository accountRepository;
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

    private final Long projectId = 100L;
    private final Long taskId = 200L;
    private final Long staffId = 10L;
    private final Long managerId = 20L;
    private final Long submissionId = 500L;

    private Project project;
    private ProjectTask task;
    private Account managerAccount;

    @BeforeEach
    void setUp() {
        project = Project.builder().id(projectId).projectName("Contract Project").build();
        Account staffAccount = Account.builder().id(staffId).email("staff@apms.com").build();
        managerAccount = Account.builder().id(managerId).email("manager@apms.com").build();
        task = ProjectTask.builder()
                .id(taskId)
                .project(project)
                .assignedToAccount(staffAccount)
                .status(TaskStatus.IN_REVIEW)
                .build();
    }

    private ContractEntry buildContract(String id, String title, ContractEntryReviewStatus status) {
        return ContractEntry.builder()
                .id(id)
                .title(title)
                .reviewStatus(status)
                .reviewHistory(new ArrayList<>())
                .build();
    }

    private ProjectTaskSubmission buildSubmission(Long subId, List<String> contractIds, SubmissionStatus status) {
        return ProjectTaskSubmission.builder()
                .id(subId)
                .projectTask(task)
                .project(project)
                .submissionType(SubmissionType.PARTNER_CONTRACT_COLLECTION)
                .targetEntityType("CONTRACT_RESEARCH")
                .targetItemIds(String.join(",", contractIds))
                .status(status)
                .submittedAt(LocalDateTime.now())
                .build();
    }

    // --- TEST 1: Request Changes on Doc A when Doc B is PENDING -> stays IN_REVIEW ---
    @Test
    @DisplayName("Test 1: Request changes on Doc A when Doc B is PENDING -> parent stays SUBMITTED / IN_REVIEW")
    void test1_RequestChangesOnDocA_WhenDocBPending_StaysInReview() {
        ContractEntry docA = buildContract("doc-a", "Contract A", ContractEntryReviewStatus.PENDING_REVIEW);
        ContractEntry docB = buildContract("doc-b", "Contract B", ContractEntryReviewStatus.PENDING_REVIEW);

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.SUBMITTED)
                .contracts(new ArrayList<>(List.of(docA, docB)))
                .build();

        ProjectTaskSubmission sub = buildSubmission(submissionId, List.of("doc-a", "doc-b"), SubmissionStatus.IN_REVIEW);

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(projectTaskSubmissionRepository.findById(submissionId)).thenReturn(Optional.of(sub));
        when(userProfileRepository.findById(managerId)).thenReturn(Optional.of(UserProfile.builder().firstName("Manager").lastName("John").build()));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewContractEntryRequest req = ReviewContractEntryRequest.builder()
                .status(ContractEntryReviewStatus.CHANGES_REQUESTED)
                .reason("Effective date is incorrect")
                .build();

        ContractResearchResponse response = contractResearchService.reviewContractEntry(projectId, taskId, submissionId, "doc-a", req, managerId);

        assertThat(response).isNotNull();
        assertThat(docA.getReviewStatus()).isEqualTo(ContractEntryReviewStatus.CHANGES_REQUESTED);
        assertThat(docA.getReviewComment()).isEqualTo("Effective date is incorrect");
        assertThat(docB.getReviewStatus()).isEqualTo(ContractEntryReviewStatus.PENDING_REVIEW);

        // Crucial requirement: Parent must remain in review because Doc B is still pending!
        assertThat(research.getStatus()).isEqualTo(ContractResearchStatus.SUBMITTED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_REVIEW);
        assertThat(sub.getStatus()).isEqualTo(SubmissionStatus.IN_REVIEW);
    }

    // --- TEST 2: Approve Doc B when Doc A has CHANGES_REQUESTED -> returns to Staff ---
    @Test
    @DisplayName("Test 2: Approve Doc B when Doc A is CHANGES_REQUESTED -> parent becomes CHANGES_REQUESTED / IN_PROGRESS")
    void test2_ApproveDocB_WhenDocAChangesRequested_ReturnsToStaff() {
        ContractEntry docA = buildContract("doc-a", "Contract A", ContractEntryReviewStatus.CHANGES_REQUESTED);
        docA.setReviewComment("Effective date is incorrect");
        ContractEntry docB = buildContract("doc-b", "Contract B", ContractEntryReviewStatus.PENDING_REVIEW);

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.SUBMITTED)
                .contracts(new ArrayList<>(List.of(docA, docB)))
                .build();

        ProjectTaskSubmission sub = buildSubmission(submissionId, List.of("doc-a", "doc-b"), SubmissionStatus.IN_REVIEW);

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(projectTaskSubmissionRepository.findById(submissionId)).thenReturn(Optional.of(sub));
        when(userProfileRepository.findById(managerId)).thenReturn(Optional.of(UserProfile.builder().firstName("Manager").lastName("John").build()));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewContractEntryRequest req = ReviewContractEntryRequest.builder()
                .status(ContractEntryReviewStatus.APPROVED)
                .build();

        ContractResearchResponse response = contractResearchService.reviewContractEntry(projectId, taskId, submissionId, "doc-b", req, managerId);

        assertThat(response).isNotNull();
        assertThat(docA.getReviewStatus()).isEqualTo(ContractEntryReviewStatus.CHANGES_REQUESTED);
        assertThat(docB.getReviewStatus()).isEqualTo(ContractEntryReviewStatus.APPROVED);

        // 0 pending remain; docA has CHANGES_REQUESTED -> task returns to Staff!
        assertThat(research.getStatus()).isEqualTo(ContractResearchStatus.CHANGES_REQUESTED);
        assertThat(research.getReviewReason()).contains("Contract A: Effective date is incorrect");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task.getCompletedAt()).isNull();
        assertThat(sub.getStatus()).isEqualTo(SubmissionStatus.REVISION_REQUESTED);
        assertThat(sub.getReviewComment()).contains("Contract A: Effective date is incorrect");
    }

    // --- TEST 3: Approve Doc B when Doc A is APPROVED -> parent becomes APPROVED / DONE ---
    @Test
    @DisplayName("Test 3: Approve Doc B when Doc A is APPROVED -> whole task becomes DONE / APPROVED")
    void test3_ApproveDocB_WhenDocAApproved_TaskBecomesDone() {
        ContractEntry docA = buildContract("doc-a", "Contract A", ContractEntryReviewStatus.APPROVED);
        ContractEntry docB = buildContract("doc-b", "Contract B", ContractEntryReviewStatus.PENDING_REVIEW);

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.SUBMITTED)
                .contracts(new ArrayList<>(List.of(docA, docB)))
                .build();

        ProjectTaskSubmission sub = buildSubmission(submissionId, List.of("doc-a", "doc-b"), SubmissionStatus.IN_REVIEW);

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(projectTaskSubmissionRepository.findById(submissionId)).thenReturn(Optional.of(sub));
        when(userProfileRepository.findById(managerId)).thenReturn(Optional.of(UserProfile.builder().firstName("Manager").lastName("John").build()));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewContractEntryRequest req = ReviewContractEntryRequest.builder()
                .status(ContractEntryReviewStatus.APPROVED)
                .build();

        ContractResearchResponse response = contractResearchService.reviewContractEntry(projectId, taskId, submissionId, "doc-b", req, managerId);

        assertThat(response).isNotNull();
        assertThat(docA.getReviewStatus()).isEqualTo(ContractEntryReviewStatus.APPROVED);
        assertThat(docB.getReviewStatus()).isEqualTo(ContractEntryReviewStatus.APPROVED);

        // All approved -> task DONE
        assertThat(research.getStatus()).isEqualTo(ContractResearchStatus.APPROVED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task.getCompletedAt()).isNotNull();
        assertThat(sub.getStatus()).isEqualTo(SubmissionStatus.APPROVED);
    }

    // --- TEST 4: Staff resubmits corrected Doc A; Doc B remains APPROVED ---
    @Test
    @DisplayName("Test 4: Staff resubmits corrected Doc A -> Doc A becomes PENDING_REVIEW, Doc B stays APPROVED")
    void test4_StaffResubmitsCorrectedDocA_ApprovedDocBRemainsApproved() {
        ContractEntry docA = buildContract("doc-a", "Contract A", ContractEntryReviewStatus.CHANGES_REQUESTED);
        docA.setReviewComment("Fix effective date");
        docA.setReviewedBy(managerId);
        docA.setReviewedByName("Manager");
        docA.setReviewedAt(LocalDateTime.now().minusHours(1));

        ContractEntry docB = buildContract("doc-b", "Contract B", ContractEntryReviewStatus.APPROVED);
        docB.setReviewedBy(managerId);

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.CHANGES_REQUESTED)
                .contracts(new ArrayList<>(List.of(docA, docB)))
                .build();

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(projectTaskSubmissionRepository.findByProjectTask_Id(taskId)).thenReturn(List.of());
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        SubmitContractResearchRequest submitReq = SubmitContractResearchRequest.builder()
                .contractEntryIds(List.of("doc-a"))
                .note("Fixed effective date for Doc A")
                .build();

        ContractResearchResponse response = contractResearchService.submitResearch(projectId, taskId, submitReq, staffId);

        assertThat(response).isNotNull();
        assertThat(docA.getReviewStatus()).isEqualTo(ContractEntryReviewStatus.PENDING_REVIEW);
        assertThat(docA.getReviewComment()).isNull();
        assertThat(docA.getReviewedBy()).isNull();

        // Doc B remains APPROVED and untouched
        assertThat(docB.getReviewStatus()).isEqualTo(ContractEntryReviewStatus.APPROVED);
        assertThat(docB.getReviewedBy()).isEqualTo(managerId);

        // Research & Task transition back to review
        assertThat(research.getStatus()).isEqualTo(ContractResearchStatus.SUBMITTED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_REVIEW);
    }

    // --- TEST 5: Manager approves resubmitted Doc A -> Completes Task ---
    @Test
    @DisplayName("Test 5: Manager approves resubmitted Doc A -> both APPROVED -> Task DONE")
    void test5_ManagerApprovesResubmittedDocA_CompletesTask() {
        ContractEntry docA = buildContract("doc-a", "Contract A", ContractEntryReviewStatus.PENDING_REVIEW);
        ContractEntry docB = buildContract("doc-b", "Contract B", ContractEntryReviewStatus.APPROVED);

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.SUBMITTED)
                .contracts(new ArrayList<>(List.of(docA, docB)))
                .build();

        ProjectTaskSubmission sub2 = buildSubmission(501L, List.of("doc-a"), SubmissionStatus.IN_REVIEW);

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(projectTaskSubmissionRepository.findById(501L)).thenReturn(Optional.of(sub2));
        when(userProfileRepository.findById(managerId)).thenReturn(Optional.of(UserProfile.builder().firstName("Manager").lastName("John").build()));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewContractEntryRequest req = ReviewContractEntryRequest.builder()
                .status(ContractEntryReviewStatus.APPROVED)
                .build();

        ContractResearchResponse response = contractResearchService.reviewContractEntry(projectId, taskId, 501L, "doc-a", req, managerId);

        assertThat(response).isNotNull();
        assertThat(docA.getReviewStatus()).isEqualTo(ContractEntryReviewStatus.APPROVED);
        assertThat(docB.getReviewStatus()).isEqualTo(ContractEntryReviewStatus.APPROVED);

        // Entire task completed
        assertThat(research.getStatus()).isEqualTo(ContractResearchStatus.APPROVED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(task.getCompletedAt()).isNotNull();
        assertThat(sub2.getStatus()).isEqualTo(SubmissionStatus.APPROVED);
    }

    // --- TEST 6: 3 Documents Sequence: A->APPROVED, B->CR, C->PENDING -> stays in review until C is decided ---
    @Test
    @DisplayName("Test 6: Three documents sequence stays IN_REVIEW until all reviewed, then aggregates to CHANGES_REQUESTED")
    void test6_ThreeDocumentsSequence_StaysInReviewUntilAllReviewed() {
        ContractEntry docA = buildContract("doc-a", "Contract A", ContractEntryReviewStatus.PENDING_REVIEW);
        ContractEntry docB = buildContract("doc-b", "Contract B", ContractEntryReviewStatus.PENDING_REVIEW);
        ContractEntry docC = buildContract("doc-c", "Contract C", ContractEntryReviewStatus.PENDING_REVIEW);

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.SUBMITTED)
                .contracts(new ArrayList<>(List.of(docA, docB, docC)))
                .build();

        ProjectTaskSubmission sub = buildSubmission(submissionId, List.of("doc-a", "doc-b", "doc-c"), SubmissionStatus.IN_REVIEW);

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(projectTaskSubmissionRepository.findById(submissionId)).thenReturn(Optional.of(sub));
        when(userProfileRepository.findById(managerId)).thenReturn(Optional.of(UserProfile.builder().firstName("Manager").lastName("John").build()));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        // Step 1: Approve docA
        contractResearchService.reviewContractEntry(projectId, taskId, submissionId, "doc-a",
                ReviewContractEntryRequest.builder().status(ContractEntryReviewStatus.APPROVED).build(), managerId);
        assertThat(research.getStatus()).isEqualTo(ContractResearchStatus.SUBMITTED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_REVIEW);

        // Step 2: Request changes on docB
        contractResearchService.reviewContractEntry(projectId, taskId, submissionId, "doc-b",
                ReviewContractEntryRequest.builder().status(ContractEntryReviewStatus.CHANGES_REQUESTED).reason("Missing clause").build(), managerId);
        // Doc C is still pending -> parent MUST remain IN_REVIEW!
        assertThat(research.getStatus()).isEqualTo(ContractResearchStatus.SUBMITTED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_REVIEW);
        assertThat(sub.getStatus()).isEqualTo(SubmissionStatus.IN_REVIEW);

        // Step 3: Approve docC
        contractResearchService.reviewContractEntry(projectId, taskId, submissionId, "doc-c",
                ReviewContractEntryRequest.builder().status(ContractEntryReviewStatus.APPROVED).build(), managerId);
        // All 3 decided, docB has CHANGES_REQUESTED -> task transitions to IN_PROGRESS
        assertThat(research.getStatus()).isEqualTo(ContractResearchStatus.CHANGES_REQUESTED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(sub.getStatus()).isEqualTo(SubmissionStatus.REVISION_REQUESTED);
    }

    // --- TEST 7: Multiple changes requested aggregates feedback from ALL changed documents ---
    @Test
    @DisplayName("Test 7: Multiple changes requested aggregates feedback from all changed documents")
    void test7_MultipleChangesRequested_AggregatesFeedback() {
        ContractEntry docA = buildContract("doc-a", "Contract A", ContractEntryReviewStatus.CHANGES_REQUESTED);
        docA.setReviewComment("Incorrect effective date");
        ContractEntry docB = buildContract("doc-b", "Contract B", ContractEntryReviewStatus.PENDING_REVIEW);

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.SUBMITTED)
                .contracts(new ArrayList<>(List.of(docA, docB)))
                .build();

        ProjectTaskSubmission sub = buildSubmission(submissionId, List.of("doc-a", "doc-b"), SubmissionStatus.IN_REVIEW);

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(projectTaskSubmissionRepository.findById(submissionId)).thenReturn(Optional.of(sub));
        when(userProfileRepository.findById(managerId)).thenReturn(Optional.of(UserProfile.builder().firstName("Manager").lastName("John").build()));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        // Request changes on docB
        ReviewContractEntryRequest req = ReviewContractEntryRequest.builder()
                .status(ContractEntryReviewStatus.CHANGES_REQUESTED)
                .reason("Incorrect contract value")
                .build();

        contractResearchService.reviewContractEntry(projectId, taskId, submissionId, "doc-b", req, managerId);

        // Both reasons should be aggregated
        assertThat(research.getReviewReason()).contains("Contract A: Incorrect effective date");
        assertThat(research.getReviewReason()).contains("Contract B: Incorrect contract value");
        assertThat(sub.getReviewComment()).contains("Contract A: Incorrect effective date");
        assertThat(sub.getReviewComment()).contains("Contract B: Incorrect contract value");
    }

    // --- TEST 8: Re-review protection ---
    @Test
    @DisplayName("Test 8: Re-reviewing already decided document throws BusinessValidationException")
    void test8_ReReviewProtection_DecidedDocumentRejectsRepeatedDecision() {
        ContractEntry docA = buildContract("doc-a", "Contract A", ContractEntryReviewStatus.CHANGES_REQUESTED);
        ContractEntry docB = buildContract("doc-b", "Contract B", ContractEntryReviewStatus.PENDING_REVIEW);

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.SUBMITTED)
                .contracts(new ArrayList<>(List.of(docA, docB)))
                .build();

        ProjectTaskSubmission sub = buildSubmission(submissionId, List.of("doc-a", "doc-b"), SubmissionStatus.IN_REVIEW);

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(projectTaskSubmissionRepository.findById(submissionId)).thenReturn(Optional.of(sub));

        ReviewContractEntryRequest req = ReviewContractEntryRequest.builder()
                .status(ContractEntryReviewStatus.APPROVED)
                .build();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                contractResearchService.reviewContractEntry(projectId, taskId, submissionId, "doc-a", req, managerId)
        );

        assertThat(ex.getMessage()).contains("This contract has already been reviewed in the current review cycle.");
    }

    // --- TEST 9: Approved immutability ---
    @Test
    @DisplayName("Test 9: Approved contract cannot be resubmitted or modified")
    void test9_ApprovedImmutability_CannotResubmitOrModifyApprovedDocument() {
        ContractEntry docA = buildContract("doc-a", "Contract A", ContractEntryReviewStatus.APPROVED);

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.CHANGES_REQUESTED)
                .contracts(new ArrayList<>(List.of(docA)))
                .build();

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));

        SubmitContractResearchRequest submitReq = SubmitContractResearchRequest.builder()
                .contractEntryIds(List.of("doc-a"))
                .build();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                contractResearchService.submitResearch(projectId, taskId, submitReq, staffId)
        );

        assertThat(ex.getMessage()).contains("Approved contract 'Contract A' cannot be resubmitted.");
    }

    // --- TEST 10: Current review package filtering (draft outside submission does not block review) ---
    @Test
    @DisplayName("Test 10: Draft contract outside active submission does not count as pending")
    void test10_CurrentReviewPackageFiltering_DraftOutsideSubmissionDoesNotBlock() {
        ContractEntry docA = buildContract("doc-a", "Contract A", ContractEntryReviewStatus.PENDING_REVIEW);
        ContractEntry docDraft = buildContract("doc-draft", "Draft Contract", ContractEntryReviewStatus.DRAFT);

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.SUBMITTED)
                .contracts(new ArrayList<>(List.of(docA, docDraft)))
                .build();

        // Active submission only has docA
        ProjectTaskSubmission sub = buildSubmission(submissionId, List.of("doc-a"), SubmissionStatus.IN_REVIEW);

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(projectTaskSubmissionRepository.findById(submissionId)).thenReturn(Optional.of(sub));
        when(userProfileRepository.findById(managerId)).thenReturn(Optional.of(UserProfile.builder().firstName("Manager").lastName("John").build()));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewContractEntryRequest req = ReviewContractEntryRequest.builder()
                .status(ContractEntryReviewStatus.CHANGES_REQUESTED)
                .reason("Need update")
                .build();

        ContractResearchResponse response = contractResearchService.reviewContractEntry(projectId, taskId, submissionId, "doc-a", req, managerId);

        assertThat(response).isNotNull();
        // docDraft outside package does NOT count as pending for current submission
        assertThat(sub.getStatus()).isEqualTo(SubmissionStatus.REVISION_REQUESTED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(research.getStatus()).isEqualTo(ContractResearchStatus.CHANGES_REQUESTED);
    }

    // --- TEST 11: Approve last pending when another document has changes requested -> Must NOT become DONE ---
    @Test
    @DisplayName("Test 11: Approve last pending when another document in package has changes requested -> Must NOT become DONE")
    void test11_ApproveLastPending_WhenAnotherDocHasChangesRequested_MustNotBecomeDone() {
        ContractEntry docA = buildContract("doc-a", "Contract A", ContractEntryReviewStatus.CHANGES_REQUESTED);
        docA.setReviewComment("Missing signature");
        ContractEntry docB = buildContract("doc-b", "Contract B", ContractEntryReviewStatus.PENDING_REVIEW);

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.SUBMITTED)
                .contracts(new ArrayList<>(List.of(docA, docB)))
                .build();

        ProjectTaskSubmission sub = buildSubmission(submissionId, List.of("doc-a", "doc-b"), SubmissionStatus.IN_REVIEW);

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(projectTaskSubmissionRepository.findById(submissionId)).thenReturn(Optional.of(sub));
        when(userProfileRepository.findById(managerId)).thenReturn(Optional.of(UserProfile.builder().firstName("Manager").lastName("John").build()));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewContractEntryRequest req = ReviewContractEntryRequest.builder()
                .status(ContractEntryReviewStatus.APPROVED)
                .build();

        contractResearchService.reviewContractEntry(projectId, taskId, submissionId, "doc-b", req, managerId);

        // Remaining pending == 0, but docA is CHANGES_REQUESTED -> MUST NOT BE DONE!
        assertThat(task.getStatus()).isNotEqualTo(TaskStatus.DONE);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(research.getStatus()).isEqualTo(ContractResearchStatus.CHANGES_REQUESTED);
        assertThat(sub.getStatus()).isEqualTo(SubmissionStatus.REVISION_REQUESTED);
    }

    // --- TEST 12: Draft outside current package: submission APPROVED but whole task remains IN_PROGRESS ---
    @Test
    @DisplayName("Test 12: Draft outside current package: submission APPROVED but task remains IN_PROGRESS")
    void test12_DraftOutsideCurrentPackage_SubmissionApprovedButTaskRemainsInProgress() {
        ContractEntry docA = buildContract("doc-a", "Contract A", ContractEntryReviewStatus.PENDING_REVIEW);
        ContractEntry docB = buildContract("doc-b", "Contract B", ContractEntryReviewStatus.APPROVED);
        ContractEntry docC = buildContract("doc-c", "Contract C", ContractEntryReviewStatus.DRAFT);

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.SUBMITTED)
                .contracts(new ArrayList<>(List.of(docA, docB, docC)))
                .build();

        // Active submission has only docA and docB
        ProjectTaskSubmission sub = buildSubmission(submissionId, List.of("doc-a", "doc-b"), SubmissionStatus.IN_REVIEW);

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(projectTaskSubmissionRepository.findById(submissionId)).thenReturn(Optional.of(sub));
        when(userProfileRepository.findById(managerId)).thenReturn(Optional.of(UserProfile.builder().firstName("Manager").lastName("John").build()));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewContractEntryRequest req = ReviewContractEntryRequest.builder()
                .status(ContractEntryReviewStatus.APPROVED)
                .build();

        contractResearchService.reviewContractEntry(projectId, taskId, submissionId, "doc-a", req, managerId);

        // Package (docA, docB) is all approved -> submission is APPROVED
        assertThat(sub.getStatus()).isEqualTo(SubmissionStatus.APPROVED);

        // But task has docC in DRAFT -> whole task is NOT DONE!
        assertThat(task.getStatus()).isNotEqualTo(TaskStatus.DONE);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(research.getStatus()).isEqualTo(ContractResearchStatus.DRAFT);
    }

    // --- TEST 13: Legacy null status is treated as pending and reviewable ---
    @Test
    @DisplayName("Test 13: Legacy null status is treated as pending review and can be reviewed")
    void test13_LegacyNullStatus_TreatedAsPendingAndReviewable() {
        ContractEntry docLegacy = buildContract("doc-legacy", "Legacy Contract", null);

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.SUBMITTED)
                .contracts(new ArrayList<>(List.of(docLegacy)))
                .build();

        ProjectTaskSubmission sub = buildSubmission(submissionId, List.of("doc-legacy"), SubmissionStatus.IN_REVIEW);

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(projectTaskSubmissionRepository.findById(submissionId)).thenReturn(Optional.of(sub));
        when(userProfileRepository.findById(managerId)).thenReturn(Optional.of(UserProfile.builder().firstName("Manager").lastName("John").build()));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        ReviewContractEntryRequest req = ReviewContractEntryRequest.builder()
                .status(ContractEntryReviewStatus.APPROVED)
                .build();

        // Must succeed without throwing CONTRACT_NOT_PENDING_REVIEW
        ContractResearchResponse response = contractResearchService.reviewContractEntry(projectId, taskId, submissionId, "doc-legacy", req, managerId);

        assertThat(response).isNotNull();
        assertThat(docLegacy.getReviewStatus()).isEqualTo(ContractEntryReviewStatus.APPROVED);
        assertThat(sub.getStatus()).isEqualTo(SubmissionStatus.APPROVED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
    }

    // --- Test 18: toResponse returns canRecallSubmission=true for active IN_REVIEW with no decisions ---
    @Test
    @DisplayName("Test 18: toResponse returns canRecallSubmission=true for active IN_REVIEW with no decisions")
    void test18_toResponse_ReturnsCanRecallSubmissionTrue_ForActiveInReview_WithNoDecisions() {
        ContractEntry docA = buildContract("doc-a", "Contract A", ContractEntryReviewStatus.DRAFT);
        ContractEntry docB = buildContract("doc-b", "Contract B", ContractEntryReviewStatus.DRAFT);
        
        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.DRAFT)
                .contracts(new ArrayList<>(List.of(docA, docB)))
                .build();
                
        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        
        // Use a list to capture saved submissions so toResponse() can find them
        List<ProjectTaskSubmission> savedSubmissions = new ArrayList<>();
        when(projectTaskSubmissionRepository.findByProjectTask_Id(taskId)).thenAnswer(inv -> List.copyOf(savedSubmissions));
        when(projectTaskSubmissionRepository.save(any(ProjectTaskSubmission.class))).thenAnswer(inv -> {
            ProjectTaskSubmission sub = inv.getArgument(0);
            sub.setId(submissionId);
            savedSubmissions.add(sub);
            return sub;
        });
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));
        
        SubmitContractResearchRequest submitReq = SubmitContractResearchRequest.builder()
                .contractEntryIds(List.of("doc-a", "doc-b"))
                .build();
                
        ContractResearchResponse response = contractResearchService.submitResearch(projectId, taskId, submitReq, staffId);
        
        assertThat(response.getCanRecallSubmission()).isTrue();
        assertThat(response.getActiveSubmissionStatus()).isEqualTo("IN_REVIEW");
        assertThat(response.getActiveSubmittedContractIds()).containsExactlyInAnyOrder("doc-a", "doc-b");
    }

    // --- Test 19: Full resubmission lifecycle ---
    @Test
    @DisplayName("Test 19: Full resubmission lifecycle")
    void test19_FullResubmissionLifecycle() {
        ContractEntry docA = buildContract("doc-a", "Contract A", ContractEntryReviewStatus.DRAFT);
        docA.setExtractionStatus(com.apms.domain.contract.enums.ContractExtractionStatus.COMPLETED);
        docA.setCompanyMatchConfirmed(true);
        ContractEntry docB = buildContract("doc-b", "Contract B", ContractEntryReviewStatus.DRAFT);
        docB.setExtractionStatus(com.apms.domain.contract.enums.ContractExtractionStatus.COMPLETED);
        docB.setCompanyMatchConfirmed(true);

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.DRAFT)
                .contracts(new ArrayList<>(List.of(docA, docB)))
                .build();

        // 2. Submit both
        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(projectTaskSubmissionRepository.findByProjectTask_Id(taskId)).thenReturn(List.of());
        
        ProjectTaskSubmission sub = buildSubmission(submissionId, List.of("doc-a", "doc-b"), SubmissionStatus.IN_REVIEW);
        when(projectTaskSubmissionRepository.save(any(ProjectTaskSubmission.class))).thenReturn(sub);
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        SubmitContractResearchRequest submitReq = SubmitContractResearchRequest.builder()
                .contractEntryIds(List.of("doc-a", "doc-b"))
                .build();

        ContractResearchResponse submitResp = contractResearchService.submitResearch(projectId, taskId, submitReq, staffId);

        // 3. Manager reviews A as CHANGES_REQUESTED
        when(projectTaskSubmissionRepository.findById(submissionId)).thenReturn(Optional.of(sub));
        when(userProfileRepository.findById(managerId)).thenReturn(Optional.of(UserProfile.builder().firstName("Manager").lastName("John").build()));
        
        ReviewContractEntryRequest reqA = ReviewContractEntryRequest.builder()
                .status(ContractEntryReviewStatus.CHANGES_REQUESTED)
                .reason("Need updates")
                .build();
        contractResearchService.reviewContractEntry(projectId, taskId, submissionId, "doc-a", reqA, managerId);
        
        // 4. Manager reviews B as APPROVED
        ReviewContractEntryRequest reqB = ReviewContractEntryRequest.builder()
                .status(ContractEntryReviewStatus.APPROVED)
                .build();
        ContractResearchResponse reviewResp = contractResearchService.reviewContractEntry(projectId, taskId, submissionId, "doc-b", reqB, managerId);

        // 5, 6, 7. Asserts
        assertThat(research.getStatus()).isEqualTo(ContractResearchStatus.CHANGES_REQUESTED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        
        // Setup mock for next submission search
        when(projectTaskSubmissionRepository.findByProjectTask_Id(taskId)).thenReturn(List.of(sub));
        ContractResearchResponse getResp = contractResearchService.getResearch(projectId, taskId).orElseThrow();
        assertThat(getResp.getActiveSubmissionStatus()).isEqualTo("REVISION_REQUESTED");

        // 8. Staff resubmits A
        ProjectTaskSubmission sub2 = buildSubmission(501L, List.of("doc-a"), SubmissionStatus.IN_REVIEW);
        when(projectTaskSubmissionRepository.save(any(ProjectTaskSubmission.class))).thenReturn(sub2);
        
        SubmitContractResearchRequest submitReq2 = SubmitContractResearchRequest.builder()
                .contractEntryIds(List.of("doc-a"))
                .build();
        ContractResearchResponse resubmitResp = contractResearchService.submitResearch(projectId, taskId, submitReq2, staffId);

        // 9, 10, 11
        assertThat(research.getStatus()).isEqualTo(ContractResearchStatus.SUBMITTED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_REVIEW);
        when(projectTaskSubmissionRepository.findByProjectTask_Id(taskId)).thenReturn(List.of(sub2, sub));
        ContractResearchResponse getResp2 = contractResearchService.getResearch(projectId, taskId).orElseThrow();
        assertThat(getResp2.getCanRecallSubmission()).isTrue();

        // 12. Manager approves A in the new submission
        when(projectTaskSubmissionRepository.findById(501L)).thenReturn(Optional.of(sub2));
        ReviewContractEntryRequest reqA2 = ReviewContractEntryRequest.builder()
                .status(ContractEntryReviewStatus.APPROVED)
                .build();
        contractResearchService.reviewContractEntry(projectId, taskId, 501L, "doc-a", reqA2, managerId);

        // 13, 14
        assertThat(research.getStatus()).isEqualTo(ContractResearchStatus.APPROVED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
    }

    // --- Test 20: toResponse returns activeSubmittedContractIds even for REVISION_REQUESTED ---
    @Test
    @DisplayName("Test 20: toResponse returns activeSubmittedContractIds even for REVISION_REQUESTED")
    void test20_toResponse_ReturnsActiveSubmittedContractIds_ForRevisionRequested() {
        ContractEntry docA = buildContract("doc-a", "Contract A", ContractEntryReviewStatus.CHANGES_REQUESTED);
        ContractEntry docB = buildContract("doc-b", "Contract B", ContractEntryReviewStatus.APPROVED);

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.CHANGES_REQUESTED)
                .contracts(new ArrayList<>(List.of(docA, docB)))
                .build();
                
        ProjectTaskSubmission sub = buildSubmission(submissionId, List.of("doc-a", "doc-b"), SubmissionStatus.REVISION_REQUESTED);

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(projectTaskSubmissionRepository.findByProjectTask_Id(taskId)).thenReturn(List.of(sub));

        ContractResearchResponse response = contractResearchService.getResearch(projectId, taskId).orElseThrow();

        assertThat(response.getActiveSubmittedContractIds()).isNotEmpty();
        assertThat(response.getActiveSubmittedContractIds()).containsExactlyInAnyOrder("doc-a", "doc-b");
        assertThat(response.getActiveSubmissionStatus()).isEqualTo("REVISION_REQUESTED");
        assertThat(response.getCanRecallSubmission()).isFalse();
    }

    // --- Test 21: APPROVED contract edit metadata -> rejected ---
    @Test
    @DisplayName("Test 21: APPROVED contract edit metadata -> rejected with CONTRACT_APPROVED_IMMUTABLE")
    void test21_ApprovedContract_EditMetadata_Rejected() {
        ContractEntry docA = buildContract("doc-a", "Contract A", ContractEntryReviewStatus.APPROVED);
        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.SUBMITTED)
                .contracts(new ArrayList<>(List.of(docA)))
                .build();

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));

        UpdateContractEntryRequest req = UpdateContractEntryRequest.builder()
                .title("New Title")
                .build();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                contractResearchService.updateContractEntry(taskId, "doc-a", req, staffId));

        assertThat(ex.getErrorCode()).isEqualTo("CONTRACT_APPROVED_IMMUTABLE");
    }

    // --- Test 22: APPROVED contract unverify field -> rejected ---
    @Test
    @DisplayName("Test 22: APPROVED contract unverify field -> rejected with CONTRACT_APPROVED_IMMUTABLE")
    void test22_ApprovedContract_UnverifyField_Rejected() {
        ContractEntry docA = buildContract("doc-a", "Contract A", ContractEntryReviewStatus.APPROVED);
        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.SUBMITTED)
                .contracts(new ArrayList<>(List.of(docA)))
                .build();

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                contractResearchService.unverifyScalarField(taskId, "doc-a", "contractNumber", staffId));

        assertThat(ex.getErrorCode()).isEqualTo("CONTRACT_APPROVED_IMMUTABLE");
    }

    // --- Test 23: APPROVED contract edit field -> rejected ---
    @Test
    @DisplayName("Test 23: APPROVED contract edit field -> rejected with CONTRACT_APPROVED_IMMUTABLE")
    void test23_ApprovedContract_EditField_Rejected() {
        ContractEntry docA = buildContract("doc-a", "Contract A", ContractEntryReviewStatus.APPROVED);
        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.SUBMITTED)
                .contracts(new ArrayList<>(List.of(docA)))
                .build();

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));

        UpdateScalarFieldRequest req = UpdateScalarFieldRequest.builder()
                .value("CN-12345")
                .build();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                contractResearchService.updateScalarField(taskId, "doc-a", "contractNumber", req, staffId));

        assertThat(ex.getErrorCode()).isEqualTo("CONTRACT_APPROVED_IMMUTABLE");
    }

    // --- Test 24: APPROVED contract re-extract -> rejected ---
    @Test
    @DisplayName("Test 24: APPROVED contract re-extract -> rejected with CONTRACT_APPROVED_IMMUTABLE")
    void test24_ApprovedContract_ReExtract_Rejected() {
        ContractEntry docA = buildContract("doc-a", "Contract A", ContractEntryReviewStatus.APPROVED);
        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.SUBMITTED)
                .contracts(new ArrayList<>(List.of(docA)))
                .build();

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                contractResearchService.reExtractContractEntry(taskId, "doc-a", staffId));

        assertThat(ex.getErrorCode()).isEqualTo("CONTRACT_APPROVED_IMMUTABLE");
    }

    // --- Test 25: CHANGES_REQUESTED contract edit metadata & field -> succeeds ---
    @Test
    @DisplayName("Test 25: CHANGES_REQUESTED contract edit metadata & field -> succeeds")
    void test25_ChangesRequestedContract_EditMetadataAndField_Succeeds() {
        ContractEntry docA = buildContract("doc-a", "Contract A", ContractEntryReviewStatus.CHANGES_REQUESTED);
        docA.setExtractionStatus(ContractExtractionStatus.COMPLETED);
        docA.setCommonData(CommonContractData.builder()
                .contractNumber(ExtractedContractField.<String>builder()
                        .value("OLD-123")
                        .verificationStatus(ContractFieldVerificationStatus.VERIFIED)
                        .build())
                .build());

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.CHANGES_REQUESTED)
                .contracts(new ArrayList<>(List.of(docA)))
                .build();

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        // 1. Edit metadata
        UpdateContractEntryRequest metaReq = UpdateContractEntryRequest.builder()
                .title("Updated Contract A Title")
                .build();
        ContractResearchResponse metaResp = contractResearchService.updateContractEntry(taskId, "doc-a", metaReq, staffId);
        assertThat(metaResp).isNotNull();
        assertThat(docA.getTitle()).isEqualTo("Updated Contract A Title");

        // 2. Edit scalar field
        UpdateScalarFieldRequest fieldReq = UpdateScalarFieldRequest.builder()
                .value("NEW-123")
                .build();
        ContractResearchResponse fieldResp = contractResearchService.updateScalarField(taskId, "doc-a", "contractNumber", fieldReq, staffId);
        assertThat(fieldResp).isNotNull();
        assertThat(docA.getCommonData().getContractNumber().getValue()).isEqualTo("NEW-123");
    }

    // --- Test 26: PENDING_REVIEW contract mutation -> rejected ---
    @Test
    @DisplayName("Test 26: PENDING_REVIEW contract mutation -> rejected with CONTRACT_IN_REVIEW")
    void test26_PendingReviewContract_Mutation_Rejected() {
        ContractEntry docA = buildContract("doc-a", "Contract A", ContractEntryReviewStatus.PENDING_REVIEW);
        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.SUBMITTED)
                .contracts(new ArrayList<>(List.of(docA)))
                .build();

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));

        UpdateContractEntryRequest metaReq = UpdateContractEntryRequest.builder()
                .title("New Title")
                .build();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                contractResearchService.updateContractEntry(taskId, "doc-a", metaReq, staffId));

        assertThat(ex.getErrorCode()).isEqualTo("CONTRACT_IN_REVIEW");
    }

    // --- Test 27: Resubmit with A = CHANGES_REQUESTED, B = APPROVED -> A becomes PENDING_REVIEW, B remains APPROVED ---
    @Test
    @DisplayName("Test 27: Resubmit with A = CHANGES_REQUESTED, B = APPROVED -> A becomes PENDING_REVIEW, B remains APPROVED")
    void test27_ResubmitContractA_WhenContractBApproved_PreservesReviewStatuses() {
        ContractEntry docA = buildContract("doc-a", "Contract A", ContractEntryReviewStatus.CHANGES_REQUESTED);
        docA.setExtractionStatus(ContractExtractionStatus.COMPLETED);
        docA.setCompanyMatchConfirmed(true);

        ContractEntry docB = buildContract("doc-b", "Contract B", ContractEntryReviewStatus.APPROVED);

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.CHANGES_REQUESTED)
                .contracts(new ArrayList<>(List.of(docA, docB)))
                .build();

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(projectTaskSubmissionRepository.findByProjectTask_Id(taskId)).thenReturn(List.of());

        ProjectTaskSubmission newSub = buildSubmission(502L, List.of("doc-a"), SubmissionStatus.IN_REVIEW);
        when(projectTaskSubmissionRepository.save(any(ProjectTaskSubmission.class))).thenReturn(newSub);
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        SubmitContractResearchRequest submitReq = SubmitContractResearchRequest.builder()
                .contractEntryIds(List.of("doc-a"))
                .build();

        ContractResearchResponse response = contractResearchService.submitResearch(projectId, taskId, submitReq, staffId);

        assertThat(response).isNotNull();
        assertThat(docA.getReviewStatus()).isEqualTo(ContractEntryReviewStatus.PENDING_REVIEW);
        assertThat(docB.getReviewStatus()).isEqualTo(ContractEntryReviewStatus.APPROVED);
        assertThat(research.getStatus()).isEqualTo(ContractResearchStatus.SUBMITTED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_REVIEW);
    }
}

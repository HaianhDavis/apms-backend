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
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.domain.user.repository.sql.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContractResearchManualWorkflowTest {

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
    @Mock
    private com.apms.domain.document.service.DocumentService documentService;

    @InjectMocks
    private ContractResearchService contractResearchService;

    private final Long projectId = 100L;
    private final Long taskId = 200L;
    private final Long staffId = 10L;
    private final Long managerId = 20L;
    private final Long submissionId = 500L;

    private Project project;
    private ProjectTask task;
    private Account staffAccount;
    private Account managerAccount;

    @BeforeEach
    void setUp() {
        project = Project.builder().id(projectId).projectName("Contract Project").build();
        staffAccount = Account.builder().id(staffId).email("staff@apms.com").build();
        managerAccount = Account.builder().id(managerId).email("manager@apms.com").build();
        task = ProjectTask.builder()
                .id(taskId)
                .project(project)
                .assignedToAccount(staffAccount)
                .status(TaskStatus.IN_PROGRESS)
                .build();
    }

    // --- TEST 1: Manual endpoint rejects AI contract ---
    @Test
    @DisplayName("Test 1: Manual save endpoint rejects AI contract with INVALID_DATA_ENTRY_METHOD")
    void test1_ManualEndpointRejectsAiContract() {
        ContractEntry aiContract = ContractEntry.builder()
                .id("c-ai-1")
                .title("AI Extracted Contract")
                .dataEntryMethod(ContractDataEntryMethod.AI_EXTRACTION)
                .reviewStatus(ContractEntryReviewStatus.DRAFT)
                .build();

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.DRAFT)
                .contracts(new ArrayList<>(List.of(aiContract)))
                .build();

        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));

        SaveManualContractRequest req = SaveManualContractRequest.builder()
                .contractNumber("HD-001")
                .build();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                contractResearchService.saveManualContract(projectId, taskId, "c-ai-1", req, staffId)
        );

        assertThat(ex.getErrorCode()).isEqualTo("INVALID_DATA_ENTRY_METHOD");
        assertThat(ex.getMessage()).contains("Manual contract endpoint can only modify MANUAL contracts");
        assertThat(aiContract.getDataEntryMethod()).isEqualTo(ContractDataEntryMethod.AI_EXTRACTION);
    }

    // --- TEST 2: AI contract requires document, MANUAL contract document is optional ---
    @Test
    @DisplayName("Test 2: AI contract requires document; MANUAL contract document is optional")
    void test2_AiContractRequiresDocument_ManualDocumentOptional() {
        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.DRAFT)
                .contracts(new ArrayList<>())
                .build();

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        // 2a. AI contract without document throws DOCUMENT_REQUIRED
        CreateContractEntryRequest aiReq = CreateContractEntryRequest.builder()
                .title("AI Contract Without Document")
                .dataEntryMethod(ContractDataEntryMethod.AI_EXTRACTION)
                .documentId(null)
                .build();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                contractResearchService.createContractEntry(taskId, aiReq, staffId)
        );
        assertThat(ex.getErrorCode()).isEqualTo("DOCUMENT_REQUIRED");

        // 2b. Manual contract without document succeeds
        CreateContractEntryRequest manualReq = CreateContractEntryRequest.builder()
                .title("Manual Contract No Doc")
                .dataEntryMethod(ContractDataEntryMethod.MANUAL)
                .documentId(null)
                .build();

        ContractResearchResponse response = contractResearchService.createContractEntry(taskId, manualReq, staffId);
        assertThat(response).isNotNull();
        assertThat(response.getContracts()).hasSize(1);
        ContractEntry created = response.getContracts().get(0);
        assertThat(created.getTitle()).isEqualTo("Manual Contract No Doc");
        assertThat(created.getDataEntryMethod()).isEqualTo(ContractDataEntryMethod.MANUAL);
        assertThat(created.getDocumentId()).isNull();
        assertThat(created.getDocumentName()).isNull();
        assertThat(created.getExtractionStatus()).isEqualTo(ContractExtractionStatus.COMPLETED);
    }

    // --- TEST 3: Manual party identity preservation ---
    @Test
    @DisplayName("Test 3: Manual party identity preserved across saves")
    void test3_ManualPartyIdentityPreservedAcrossSaves() {
        ContractEntry manualContract = ContractEntry.builder()
                .id("c-manual-1")
                .title("Manual Partnership")
                .dataEntryMethod(ContractDataEntryMethod.MANUAL)
                .reviewStatus(ContractEntryReviewStatus.DRAFT)
                .commonData(CommonContractData.builder().parties(new ArrayList<>()).build())
                .build();

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.DRAFT)
                .contracts(new ArrayList<>(List.of(manualContract)))
                .build();

        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        // First Save: Add Party A and Party B without IDs
        SaveManualContractRequest req1 = SaveManualContractRequest.builder()
                .contractNumber("HD-2026-10")
                .parties(List.of(
                        ManualContractPartyDto.builder().legalName("Company Alpha").role("Bên A").build(),
                        ManualContractPartyDto.builder().legalName("Company Beta").role("Bên B").build()
                ))
                .build();

        contractResearchService.saveManualContract(projectId, taskId, "c-manual-1", req1, staffId);

        List<ContractParty> savedParties = manualContract.getCommonData().getParties();
        assertThat(savedParties).hasSize(2);
        String partyAId = savedParties.get(0).getId();
        String partyBId = savedParties.get(1).getId();
        assertThat(partyAId).isNotNull().isNotEmpty();
        assertThat(partyBId).isNotNull().isNotEmpty();
        assertThat(partyAId).isNotEqualTo(partyBId);

        // Second Save: Update Party A and Party B with their IDs preserved
        SaveManualContractRequest req2 = SaveManualContractRequest.builder()
                .contractNumber("HD-2026-10")
                .parties(List.of(
                        ManualContractPartyDto.builder().id(partyAId).legalName("Company Alpha JSC").role("Bên A").build(),
                        ManualContractPartyDto.builder().id(partyBId).legalName("Company Beta LLC").role("Bên B").build()
                ))
                .build();

        contractResearchService.saveManualContract(projectId, taskId, "c-manual-1", req2, staffId);

        List<ContractParty> updatedParties = manualContract.getCommonData().getParties();
        assertThat(updatedParties).hasSize(2);
        assertThat(updatedParties.get(0).getId()).isEqualTo(partyAId);
        assertThat(updatedParties.get(0).getLegalName()).isEqualTo("Company Alpha JSC");
        assertThat(updatedParties.get(1).getId()).isEqualTo(partyBId);
        assertThat(updatedParties.get(1).getLegalName()).isEqualTo("Company Beta LLC");
    }

    // --- TEST 4: Completely empty manual contract submission rejected ---
    @Test
    @DisplayName("Test 4: Completely empty manual contract submission rejected with MANUAL_CONTRACT_EMPTY")
    void test4_CompletelyEmptyManualContractSubmissionRejected() {
        ContractEntry emptyManual = ContractEntry.builder()
                .id("c-manual-empty")
                .title("Empty Manual Contract")
                .dataEntryMethod(ContractDataEntryMethod.MANUAL)
                .reviewStatus(ContractEntryReviewStatus.DRAFT)
                .commonData(CommonContractData.builder().build())
                .build();

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.DRAFT)
                .contracts(new ArrayList<>(List.of(emptyManual)))
                .build();

        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));

        SubmitContractResearchRequest req = SubmitContractResearchRequest.builder()
                .contractEntryIds(List.of("c-manual-empty"))
                .build();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                contractResearchService.submitResearch(projectId, taskId, req, staffId)
        );

        assertThat(ex.getErrorCode()).isEqualTo("MANUAL_CONTRACT_EMPTY");
        assertThat(ex.getMessage()).contains("chưa có số liệu");
    }

    // --- TEST 5: Partial manual contract submission allowed ---
    @Test
    @DisplayName("Test 5: Partial manual contract submission allowed when meaningful data exists")
    void test5_PartialManualContractSubmissionAllowed() {
        ContractEntry partialManual = ContractEntry.builder()
                .id("c-manual-partial")
                .title("Partial Manual Contract")
                .dataEntryMethod(ContractDataEntryMethod.MANUAL)
                .reviewStatus(ContractEntryReviewStatus.DRAFT)
                .commonData(CommonContractData.builder()
                        .contractNumber(ExtractedContractField.<String>builder().value("HD-2026/XYZ").build())
                        .signingDate(ExtractedContractField.<LocalDate>builder().value(LocalDate.of(2026, 3, 1)).build())
                        .build())
                .build();

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.DRAFT)
                .contracts(new ArrayList<>(List.of(partialManual)))
                .build();

        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(projectTaskRepository.save(any(ProjectTask.class))).thenAnswer(inv -> inv.getArgument(0));
        when(projectTaskSubmissionRepository.save(any(ProjectTaskSubmission.class))).thenAnswer(inv -> inv.getArgument(0));

        SubmitContractResearchRequest req = SubmitContractResearchRequest.builder()
                .contractEntryIds(List.of("c-manual-partial"))
                .build();

        ContractResearchResponse response = contractResearchService.submitResearch(projectId, taskId, req, staffId);

        assertThat(response).isNotNull();
        assertThat(partialManual.getReviewStatus()).isEqualTo(ContractEntryReviewStatus.PENDING_REVIEW);
        assertThat(research.getStatus()).isEqualTo(ContractResearchStatus.SUBMITTED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_REVIEW);
    }

    // --- TEST 6: Mixed package review aggregation (Manual + AI contracts) ---
    @Test
    @DisplayName("Test 6: Mixed package review aggregation (Manual + AI contracts)")
    void test6_MixedPackageReviewAggregation() {
        ContractEntry manualContract = ContractEntry.builder()
                .id("c-manual")
                .title("Manual Contract")
                .dataEntryMethod(ContractDataEntryMethod.MANUAL)
                .reviewStatus(ContractEntryReviewStatus.PENDING_REVIEW)
                .build();

        ContractEntry aiContract = ContractEntry.builder()
                .id("c-ai")
                .title("AI Contract")
                .dataEntryMethod(ContractDataEntryMethod.AI_EXTRACTION)
                .reviewStatus(ContractEntryReviewStatus.PENDING_REVIEW)
                .build();

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.SUBMITTED)
                .contracts(new ArrayList<>(List.of(manualContract, aiContract)))
                .build();

        ProjectTaskSubmission submission = ProjectTaskSubmission.builder()
                .id(submissionId)
                .projectTask(task)
                .project(project)
                .submissionType(SubmissionType.PARTNER_CONTRACT_COLLECTION)
                .targetEntityType("CONTRACT_RESEARCH")
                .targetItemIds("c-manual,c-ai")
                .status(SubmissionStatus.IN_REVIEW)
                .submittedAt(LocalDateTime.now())
                .build();

        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskSubmissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
        when(userProfileRepository.findById(managerId)).thenReturn(Optional.of(UserProfile.builder().firstName("Manager").lastName("John").build()));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        // Manager approves manual contract first
        ReviewContractEntryRequest approveReq = ReviewContractEntryRequest.builder()
                .status(ContractEntryReviewStatus.APPROVED)
                .build();

        contractResearchService.reviewContractEntry(projectId, taskId, submissionId, "c-manual", approveReq, managerId);

        // Assert: manualContract is APPROVED, but package stays IN_REVIEW because aiContract is still pending
        assertThat(manualContract.getReviewStatus()).isEqualTo(ContractEntryReviewStatus.APPROVED);
        assertThat(aiContract.getReviewStatus()).isEqualTo(ContractEntryReviewStatus.PENDING_REVIEW);
        assertThat(research.getStatus()).isEqualTo(ContractResearchStatus.SUBMITTED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_REVIEW);
        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.IN_REVIEW);

        // Manager requests changes on AI contract
        ReviewContractEntryRequest changesReq = ReviewContractEntryRequest.builder()
                .status(ContractEntryReviewStatus.CHANGES_REQUESTED)
                .reason("Need clearer evidence on section 3")
                .build();

        contractResearchService.reviewContractEntry(projectId, taskId, submissionId, "c-ai", changesReq, managerId);

        // Assert: aiContract is CHANGES_REQUESTED, research returns to Staff as CHANGES_REQUESTED / IN_PROGRESS
        assertThat(aiContract.getReviewStatus()).isEqualTo(ContractEntryReviewStatus.CHANGES_REQUESTED);
        assertThat(research.getStatus()).isEqualTo(ContractResearchStatus.CHANGES_REQUESTED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.REVISION_REQUESTED);
    }

    // --- TEST 7: AI contract without PDF rejected ---
    @Test
    @DisplayName("Test 7: AI contract creation without PDF is rejected with DOCUMENT_REQUIRED")
    void test7_AiContractWithoutDocumentRejected() {
        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .contracts(new ArrayList<>())
                .build();
        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));

        CreateContractEntryRequest req = CreateContractEntryRequest.builder()
                .title("AI Contract No PDF")
                .dataEntryMethod(ContractDataEntryMethod.AI_EXTRACTION)
                .documentId(null)
                .build();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                contractResearchService.createContractEntry(taskId, req, staffId));
        assertThat(ex.getErrorCode()).isEqualTo("DOCUMENT_REQUIRED");
    }

    // --- TEST 8: Manual contract without PDF succeeds ---
    @Test
    @DisplayName("Test 8: Manual contract creation without PDF succeeds with null documentId and null documentName")
    void test8_ManualContractWithoutDocumentCreatedSuccessfully() {
        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .contracts(new ArrayList<>())
                .build();
        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateContractEntryRequest req = CreateContractEntryRequest.builder()
                .title("Manual Contract No PDF")
                .dataEntryMethod(ContractDataEntryMethod.MANUAL)
                .documentId(null)
                .build();

        ContractResearchResponse response = contractResearchService.createContractEntry(taskId, req, staffId);

        assertThat(response).isNotNull();
        assertThat(response.getContracts()).hasSize(1);
        ContractEntry created = response.getContracts().get(0);
        assertThat(created.getTitle()).isEqualTo("Manual Contract No PDF");
        assertThat(created.getDataEntryMethod()).isEqualTo(ContractDataEntryMethod.MANUAL);
        assertThat(created.getDocumentId()).isNull();
        assertThat(created.getDocumentName()).isNull();
        assertThat(created.getExtractionStatus()).isEqualTo(ContractExtractionStatus.COMPLETED);
    }

    // --- TEST 9: Extract Manual Contract Rejected ---
    @Test
    @DisplayName("Test 9: AI extraction and re-extraction on Manual contract are rejected with CANNOT_EXTRACT_MANUAL_CONTRACT")
    void test9_ExtractManualContractRejected() {
        ContractEntry manual = ContractEntry.builder()
                .id("c-manual")
                .title("Manual Contract")
                .dataEntryMethod(ContractDataEntryMethod.MANUAL)
                .documentId(null)
                .build();
        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .contracts(new ArrayList<>(List.of(manual)))
                .build();
        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));

        BusinessValidationException ex1 = assertThrows(BusinessValidationException.class, () ->
                contractResearchService.extractContractEntry(taskId, "c-manual", staffId));
        assertThat(ex1.getErrorCode()).isEqualTo("CANNOT_EXTRACT_MANUAL_CONTRACT");

        BusinessValidationException ex2 = assertThrows(BusinessValidationException.class, () ->
                contractResearchService.reExtractContractEntry(taskId, "c-manual", staffId));
        assertThat(ex2.getErrorCode()).isEqualTo("CANNOT_EXTRACT_MANUAL_CONTRACT");
    }

    // --- TEST 10: Replace Contract File on Manual Contract preserves manual fields ---
    @Test
    @DisplayName("Test 10: replaceContractFile on Manual contract updates reference PDF and preserves all manual fields")
    void test10_ReplaceContractFileOnManualContractPreservesFields() {
        CommonContractData common = CommonContractData.builder()
                .contractNumber(ExtractedContractField.<String>builder().value("HD-2026-999").build())
                .term(ExtractedContractField.<String>builder().value("36 Months").build())
                .parties(new ArrayList<>(List.of(ContractParty.builder().legalName("Công ty ABC").taxCode("0101234567").build())))
                .build();

        ContractEntry manual = ContractEntry.builder()
                .id("c-manual")
                .title("Manual Contract")
                .dataEntryMethod(ContractDataEntryMethod.MANUAL)
                .documentId(null)
                .documentName(null)
                .extractionStatus(ContractExtractionStatus.COMPLETED)
                .reviewStatus(ContractEntryReviewStatus.DRAFT)
                .commonData(common)
                .build();

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.DRAFT)
                .contracts(new ArrayList<>(List.of(manual)))
                .build();

        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile("file", "ReferenceContract.pdf", "application/pdf", "dummy pdf content".getBytes());
        com.apms.domain.document.dto.ImportJobResponse jobRes = com.apms.domain.document.dto.ImportJobResponse.builder()
                .rawDocumentId("raw-doc-new-123")
                .build();
        when(documentService.uploadPartnerContractDocument(eq(projectId), eq(taskId), any(), eq(staffId))).thenReturn(jobRes);

        ContractResearchResponse resp = contractResearchService.replaceContractFile(projectId, taskId, "c-manual", file, staffId);

        assertThat(resp).isNotNull();
        ContractEntry updated = resp.getContracts().get(0);
        assertThat(updated.getDocumentId()).isEqualTo("raw-doc-new-123");
        assertThat(updated.getDocumentName()).isEqualTo("ReferenceContract.pdf");
        assertThat(updated.getDataEntryMethod()).isEqualTo(ContractDataEntryMethod.MANUAL);
        assertThat(updated.getExtractionStatus()).isEqualTo(ContractExtractionStatus.COMPLETED);
        // Ensure manual fields were NOT reset
        assertThat(updated.getCommonData()).isNotNull();
        assertThat(updated.getCommonData().getContractNumber().getValue()).isEqualTo("HD-2026-999");
        assertThat(updated.getCommonData().getTerm().getValue()).isEqualTo("36 Months");
        assertThat(updated.getCommonData().getParties()).hasSize(1);
        assertThat(updated.getCommonData().getParties().get(0).getLegalName()).isEqualTo("Công ty ABC");
    }

    // --- TEST 11: Replace Contract File on AI Contract resets extraction state ---
    @Test
    @DisplayName("Test 11: replaceContractFile on AI contract resets extraction state")
    void test11_ReplaceContractFileOnAiContractResetsFields() {
        CommonContractData common = CommonContractData.builder()
                .contractNumber(ExtractedContractField.<String>builder().value("HD-AI-01").build())
                .build();

        ContractEntry aiEntry = ContractEntry.builder()
                .id("c-ai")
                .title("AI Contract")
                .dataEntryMethod(ContractDataEntryMethod.AI_EXTRACTION)
                .documentId("raw-doc-old")
                .documentName("OldDoc.pdf")
                .extractionStatus(ContractExtractionStatus.COMPLETED)
                .reviewStatus(ContractEntryReviewStatus.DRAFT)
                .commonData(common)
                .build();

        ContractResearch research = ContractResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.DRAFT)
                .contracts(new ArrayList<>(List.of(aiEntry)))
                .build();

        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile("file", "NewDoc.pdf", "application/pdf", "new pdf content".getBytes());
        com.apms.domain.document.dto.ImportJobResponse jobRes = com.apms.domain.document.dto.ImportJobResponse.builder()
                .rawDocumentId("raw-doc-new-456")
                .build();
        when(documentService.uploadPartnerContractDocument(eq(projectId), eq(taskId), any(), eq(staffId))).thenReturn(jobRes);

        ContractResearchResponse resp = contractResearchService.replaceContractFile(projectId, taskId, "c-ai", file, staffId);

        assertThat(resp).isNotNull();
        ContractEntry updated = resp.getContracts().get(0);
        assertThat(updated.getDocumentId()).isEqualTo("raw-doc-new-456");
        assertThat(updated.getDocumentName()).isEqualTo("NewDoc.pdf");
        assertThat(updated.getDataEntryMethod()).isEqualTo(ContractDataEntryMethod.AI_EXTRACTION);
        assertThat(updated.getExtractionStatus()).isEqualTo(ContractExtractionStatus.NOT_EXTRACTED);
        assertThat(updated.getCommonData()).isNull();
    }

    // --- DATE VALIDATION TESTS ---

    @Test
    @DisplayName("Date Validation Test 1: signing = 2026-09-17, effective = 2026-09-17, expiry = 2026-09-30 is valid")
    void testDateValidation_EqualSigningAndEffective_Valid() {
        LocalDate signing = LocalDate.of(2026, 9, 17);
        LocalDate effective = LocalDate.of(2026, 9, 17);
        LocalDate expiry = LocalDate.of(2026, 9, 30);

        // Should not throw
        contractResearchService.validateContractDates(signing, effective, expiry);
    }

    @Test
    @DisplayName("Date Validation Test 2: signing = 2026-09-17, effective = 2026-09-18, expiry = 2026-09-30 is valid")
    void testDateValidation_EffectiveAfterSigning_Valid() {
        LocalDate signing = LocalDate.of(2026, 9, 17);
        LocalDate effective = LocalDate.of(2026, 9, 18);
        LocalDate expiry = LocalDate.of(2026, 9, 30);

        contractResearchService.validateContractDates(signing, effective, expiry);
    }

    @Test
    @DisplayName("Date Validation Test 3: signing = 2026-09-17, effective = 2026-09-16 is rejected")
    void testDateValidation_EffectiveBeforeSigning_Rejected() {
        LocalDate signing = LocalDate.of(2026, 9, 17);
        LocalDate effective = LocalDate.of(2026, 9, 16);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                contractResearchService.validateContractDates(signing, effective, null)
        );
        assertThat(ex.getMessage()).isEqualTo("Ngày hiệu lực phải bằng hoặc sau ngày ký.");
        assertThat(ex.getErrorCode()).isEqualTo("INVALID_CONTRACT_DATES");
    }

    @Test
    @DisplayName("Date Validation Test 4: effective = 2026-09-17, expiry = 2026-09-17 is rejected")
    void testDateValidation_ExpiryEqualEffective_Rejected() {
        LocalDate effective = LocalDate.of(2026, 9, 17);
        LocalDate expiry = LocalDate.of(2026, 9, 17);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                contractResearchService.validateContractDates(null, effective, expiry)
        );
        assertThat(ex.getMessage()).isEqualTo("Ngày hết hạn phải sau ngày hiệu lực.");
        assertThat(ex.getErrorCode()).isEqualTo("INVALID_CONTRACT_DATES");
    }

    @Test
    @DisplayName("Date Validation Test 5: effective = 2026-09-17, expiry = 2026-09-16 is rejected")
    void testDateValidation_ExpiryBeforeEffective_Rejected() {
        LocalDate effective = LocalDate.of(2026, 9, 17);
        LocalDate expiry = LocalDate.of(2026, 9, 16);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                contractResearchService.validateContractDates(null, effective, expiry)
        );
        assertThat(ex.getMessage()).isEqualTo("Ngày hết hạn phải sau ngày hiệu lực.");
        assertThat(ex.getErrorCode()).isEqualTo("INVALID_CONTRACT_DATES");
    }

    @Test
    @DisplayName("Date Validation Test 6: valid signing/effective with expiry after effective is valid")
    void testDateValidation_ExpiryAfterEffective_Valid() {
        LocalDate signing = LocalDate.of(2026, 9, 1);
        LocalDate effective = LocalDate.of(2026, 9, 15);
        LocalDate expiry = LocalDate.of(2027, 9, 15);

        contractResearchService.validateContractDates(signing, effective, expiry);
    }

    @Test
    @DisplayName("Date Validation Test 7: Nullable combinations preserve optionality")
    void testDateValidation_NullableCombinations_PreserveOptionality() {
        // Only signing date
        contractResearchService.validateContractDates(LocalDate.of(2026, 9, 1), null, null);
        // Only effective date
        contractResearchService.validateContractDates(null, LocalDate.of(2026, 9, 1), null);
        // Only expiry date
        contractResearchService.validateContractDates(null, null, LocalDate.of(2026, 9, 1));
        // All null
        contractResearchService.validateContractDates(null, null, null);
        // Signing and expiry without effective
        contractResearchService.validateContractDates(LocalDate.of(2026, 9, 1), null, LocalDate.of(2026, 9, 30));
    }

    @Test
    @DisplayName("Test 12: saveManualContract rejects invalid date combinations")
    void test12_SaveManualContractRejectsInvalidDates() {
        ContractEntry manualContract = ContractEntry.builder()
                .id("c-manual-err")
                .title("Manual Contract Inconsistent Dates")
                .dataEntryMethod(ContractDataEntryMethod.MANUAL)
                .reviewStatus(ContractEntryReviewStatus.DRAFT)
                .commonData(CommonContractData.builder().build())
                .build();

        ContractResearch research = ContractResearch.builder()
                .id("res-err")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.DRAFT)
                .contracts(new ArrayList<>(List.of(manualContract)))
                .build();

        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));

        // Effective < Signing
        SaveManualContractRequest req1 = SaveManualContractRequest.builder()
                .title("Manual Contract")
                .signingDate(LocalDate.of(2026, 9, 29))
                .effectiveDate(LocalDate.of(2026, 9, 23))
                .expiryDate(LocalDate.of(2026, 6, 8))
                .build();

        BusinessValidationException ex1 = assertThrows(BusinessValidationException.class, () ->
                contractResearchService.saveManualContract(projectId, taskId, "c-manual-err", req1, staffId)
        );
        assertThat(ex1.getMessage()).isEqualTo("Ngày hiệu lực phải bằng hoặc sau ngày ký.");

        // Expiry <= Effective (Effective >= Signing is valid)
        SaveManualContractRequest req2 = SaveManualContractRequest.builder()
                .title("Manual Contract")
                .signingDate(LocalDate.of(2026, 9, 20))
                .effectiveDate(LocalDate.of(2026, 9, 25))
                .expiryDate(LocalDate.of(2026, 9, 25))
                .build();

        BusinessValidationException ex2 = assertThrows(BusinessValidationException.class, () ->
                contractResearchService.saveManualContract(projectId, taskId, "c-manual-err", req2, staffId)
        );
        assertThat(ex2.getMessage()).isEqualTo("Ngày hết hạn phải sau ngày hiệu lực.");
    }

    @Test
    @DisplayName("Test 13: Correction Workflow - repairing invalid legacy dates succeeds atomically without deadlock")
    void test13_CorrectionWorkflow_RepairInvalidLegacyDates() {
        // Contract with invalid legacy dates in MongoDB
        CommonContractData legacyCommon = CommonContractData.builder()
                .contractNumber(ExtractedContractField.<String>builder().value("HD-LEGACY").build())
                .signingDate(ExtractedContractField.<LocalDate>builder().value(LocalDate.of(2026, 9, 29)).build())
                .effectiveDate(ExtractedContractField.<LocalDate>builder().value(LocalDate.of(2026, 9, 23)).build())
                .expiryDate(ExtractedContractField.<LocalDate>builder().value(LocalDate.of(2026, 6, 8)).build())
                .build();

        ContractEntry manualContract = ContractEntry.builder()
                .id("c-manual-fix")
                .title("Legacy Inconsistent Contract")
                .dataEntryMethod(ContractDataEntryMethod.MANUAL)
                .reviewStatus(ContractEntryReviewStatus.DRAFT)
                .commonData(legacyCommon)
                .build();

        ContractResearch research = ContractResearch.builder()
                .id("res-fix")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.DRAFT)
                .contracts(new ArrayList<>(List.of(manualContract)))
                .build();

        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(contractResearchRepository.save(any(ContractResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        // Submit repaired dates together
        SaveManualContractRequest repairReq = SaveManualContractRequest.builder()
                .title("Corrected Contract")
                .contractNumber("HD-LEGACY-FIXED")
                .signingDate(LocalDate.of(2026, 9, 29))
                .effectiveDate(LocalDate.of(2026, 9, 29))
                .expiryDate(LocalDate.of(2026, 9, 30))
                .build();

        ContractResearchResponse response = contractResearchService.saveManualContract(projectId, taskId, "c-manual-fix", repairReq, staffId);
        assertThat(response).isNotNull();

        ContractEntry fixedContract = response.getContracts().get(0);
        assertThat(fixedContract.getCommonData().getSigningDate().getValue()).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(fixedContract.getCommonData().getEffectiveDate().getValue()).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(fixedContract.getCommonData().getExpiryDate().getValue()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    @DisplayName("Test 14: submitResearch blocks submission if any selected contract has invalid dates")
    void test14_SubmitResearchBlocksInvalidDates() {
        CommonContractData invalidCommon = CommonContractData.builder()
                .contractNumber(ExtractedContractField.<String>builder().value("HD-BAD-DATE").build())
                .signingDate(ExtractedContractField.<LocalDate>builder().value(LocalDate.of(2026, 9, 29)).build())
                .effectiveDate(ExtractedContractField.<LocalDate>builder().value(LocalDate.of(2026, 9, 23)).build())
                .expiryDate(ExtractedContractField.<LocalDate>builder().value(LocalDate.of(2026, 6, 8)).build())
                .parties(new ArrayList<>(List.of(ContractParty.builder().legalName("Company A").role("Bên A").build())))
                .build();

        ContractEntry badContract = ContractEntry.builder()
                .id("c-bad-date")
                .title("Hợp đồng ngày sai")
                .dataEntryMethod(ContractDataEntryMethod.MANUAL)
                .reviewStatus(ContractEntryReviewStatus.DRAFT)
                .commonData(invalidCommon)
                .build();

        ContractResearch research = ContractResearch.builder()
                .id("res-submit-test")
                .taskId(taskId)
                .projectId(projectId)
                .status(ContractResearchStatus.DRAFT)
                .contracts(new ArrayList<>(List.of(badContract)))
                .build();

        when(contractResearchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));

        SubmitContractResearchRequest submitReq = SubmitContractResearchRequest.builder()
                .contractEntryIds(List.of("c-bad-date"))
                .note("Submitting contract with invalid dates")
                .build();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                contractResearchService.submitResearch(projectId, taskId, submitReq, staffId)
        );

        assertThat(ex.getMessage()).contains("Hợp đồng 'Hợp đồng ngày sai' có ngày không hợp lệ: Ngày hiệu lực phải bằng hoặc sau ngày ký.");
        assertThat(ex.getErrorCode()).isEqualTo("INVALID_CONTRACT_DATES");
    }
}

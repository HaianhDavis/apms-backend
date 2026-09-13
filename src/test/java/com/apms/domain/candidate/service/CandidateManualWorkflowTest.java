package com.apms.domain.candidate.service;

import com.apms.common.enums.CandidateStatus;
import com.apms.common.enums.FieldApprovalStatus;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.ai.dto.ExtractionFieldResult;
import com.apms.domain.ai.dto.ExtractionReviewStatus;
import com.apms.domain.ai.dto.StaffFieldReviewStatus;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.common.enums.RelationshipType;
import com.apms.domain.candidate.dto.ApproveCandidateRequest;
import com.apms.domain.candidate.dto.CandidateResponse;
import com.apms.domain.candidate.dto.CandidateReviewRequest;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.company.model.FinancialInfo;
import com.apms.domain.project.Project;
import com.apms.domain.project.fieldapproval.FieldApprovalRecord;
import com.apms.domain.project.fieldapproval.FieldApprovalService;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CandidateManualWorkflowTest {

    @Mock private CompanyCandidateRepository candidateRepository;
    @Mock private com.apms.domain.document.repository.sql.ImportJobRepository importJobRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private com.apms.domain.ai.service.AiExtractionService aiExtractionService;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock private com.apms.domain.profile.service.OwnerOrganizationService ownerOrganizationService;
    @Mock private com.apms.domain.project.fieldapproval.FieldApprovalGuard fieldApprovalGuard;
    @Mock private FieldApprovalService fieldApprovalService;
    @Mock private com.apms.domain.project.repository.sql.ProjectTaskRepository projectTaskRepository;
    @Mock private com.apms.domain.document.repository.mongo.RawDocumentRepository rawDocumentRepository;
    @Mock private com.apms.domain.candidate.repository.mongo.CandidateDraftSequenceRepository draftSequenceRepository;
    @Mock private com.apms.domain.audit.service.AuditLogService auditLogService;
    @Mock private com.apms.domain.financial.service.DocumentCompanyMatcher companyMatcher;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private CandidateService candidateService;

    private CompanyCandidate createManualCandidate(String id, String legalName, String tradeName, String website) {
        Map<String, ExtractionFieldResult> fieldResults = new HashMap<>();
        for (String field : CandidateService.STAFF_REVIEWABLE_FIELDS) {
            fieldResults.put(com.apms.domain.ai.service.FieldKeyCodec.encode(field),
                    ExtractionFieldResult.builder()
                            .fieldName(field)
                            .staffReviewStatus(StaffFieldReviewStatus.PENDING)
                            .managerReviewStatus(ExtractionReviewStatus.PENDING)
                            .build());
        }

        return CompanyCandidate.builder()
                .id(id)
                .projectId("10")
                .companyMatchStatus(com.apms.domain.financial.DocumentCompanyValidationStatus.MATCH)
                .companyMatchConfirmed(true)
                .status(CandidateStatus.DRAFT)
                .identity(CompanyCandidate.Identity.builder()
                        .legalName(legalName)
                        .tradeName(tradeName)
                        .build())
                .contact(CompanyCandidate.Contact.builder()
                        .website(website)
                        .emails(new ArrayList<>())
                        .phones(new ArrayList<>())
                        .addresses(new ArrayList<>())
                        .build())
                .business(CompanyCandidate.Business.builder()
                        .industries(new ArrayList<>())
                        .markets(new ArrayList<>())
                        .targetCustomers(new ArrayList<>())
                        .products(new ArrayList<>())
                        .build())
                .companySize(CompanyCandidate.CompanySize.builder().build())
                .insights(CompanyCandidate.Insights.builder()
                        .strengths(new ArrayList<>())
                        .weaknesses(new ArrayList<>())
                        .opportunities(new ArrayList<>())
                        .threats(new ArrayList<>())
                        .build())
                .financial(new FinancialInfo())
                .fieldResults(fieldResults)
                .fieldApprovals(new ArrayList<>())
                .extractionSource(CompanyCandidate.ExtractionSource.builder()
                        .extractionMethod("MANUAL")
                        .build())
                .metadata(CompanyCandidate.Metadata.builder()
                        .createdBy("1")
                        .build())
                .build();
    }

    private CompanyCandidate createAiCandidate(String id) {
        Map<String, ExtractionFieldResult> fieldResults = new HashMap<>();
        for (String field : CandidateService.STAFF_REVIEWABLE_FIELDS) {
            fieldResults.put(com.apms.domain.ai.service.FieldKeyCodec.encode(field),
                    ExtractionFieldResult.builder()
                            .fieldName(field)
                            .value("Extracted " + field)
                            .staffReviewStatus(StaffFieldReviewStatus.PENDING)
                            .managerReviewStatus(ExtractionReviewStatus.PENDING)
                            .build());
        }

        return CompanyCandidate.builder()
                .id(id)
                .projectId("10")
                .companyMatchStatus(com.apms.domain.financial.DocumentCompanyValidationStatus.MATCH)
                .companyMatchConfirmed(true)
                .status(CandidateStatus.DRAFT)
                .identity(CompanyCandidate.Identity.builder()
                        .legalName("AI Target Corp")
                        .tradeName("AI Trade")
                        .build())
                .fieldResults(fieldResults)
                .fieldApprovals(new ArrayList<>())
                .extractionSource(CompanyCandidate.ExtractionSource.builder()
                        .extractionMethod("SPRING_AI")
                        .build())
                .build();
    }

    @Test
    @DisplayName("Test 1: Manual candidate with 0 confirmations submits successfully")
    void test1_manualCandidateWithZeroConfirmationsSubmitsSuccessfully() {
        CompanyCandidate candidate = createManualCandidate("cand-1", "Acme Corporation", "Acme", "https://acme.com");

        when(candidateRepository.findById("cand-1")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(inv -> inv.getArgument(0));

        CandidateResponse response = candidateService.submitCandidate("cand-1", 100L);

        assertThat(response).isNotNull();
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.PENDING_REVIEW);
        verify(candidateRepository).save(candidate);
    }

    @Test
    @DisplayName("Test 2: Manual candidate with 3 fields filled, 10 empty submits successfully")
    void test2_manualCandidateWithPartialFieldsSubmitsSuccessfully() {
        CompanyCandidate candidate = createManualCandidate("cand-2", "Beta Ltd", "Beta", "https://beta.com");
        candidate.getContact().getEmails().add("info@beta.com");

        when(candidateRepository.findById("cand-2")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(inv -> inv.getArgument(0));

        CandidateResponse response = candidateService.submitCandidate("cand-2", 100L);

        assertThat(response).isNotNull();
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("Test 3: Empty optional fields allowed without rejection")
    void test3_emptyOptionalFieldsAllowedWithoutRejection() {
        // Candidate with ONLY legalName, all other fields empty/null
        CompanyCandidate candidate = createManualCandidate("cand-3", "Gamma Corp", null, null);

        when(candidateRepository.findById("cand-3")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(inv -> inv.getArgument(0));

        CandidateResponse response = candidateService.submitCandidate("cand-3", 100L);

        assertThat(response).isNotNull();
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("Test 4: Invalid email or URL rejected with BusinessValidationException")
    void test4_invalidEmailOrUrlRejectedWithValidationException() {
        CompanyCandidate candidateInvalidUrl = createManualCandidate("cand-4a", "Delta Corp", "Delta", "not-a-valid-url");
        when(candidateRepository.findById("cand-4a")).thenReturn(Optional.of(candidateInvalidUrl));

        assertThatThrownBy(() -> candidateService.submitCandidate("cand-4a", 100L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Invalid website URL format");

        CompanyCandidate candidateInvalidEmail = createManualCandidate("cand-4b", "Delta Corp", "Delta", "https://delta.com");
        candidateInvalidEmail.getContact().getEmails().add("invalid-email-address");
        when(candidateRepository.findById("cand-4b")).thenReturn(Optional.of(candidateInvalidEmail));

        assertThatThrownBy(() -> candidateService.submitCandidate("cand-4b", 100L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Invalid email format");
    }

    @Test
    @DisplayName("Test 5: AI candidate with unconfirmed fields is still rejected")
    void test5_aiCandidateWithUnconfirmedFieldsStillRejected() {
        CompanyCandidate aiCandidate = createAiCandidate("ai-1");
        when(candidateRepository.findById("ai-1")).thenReturn(Optional.of(aiCandidate));

        assertThatThrownBy(() -> candidateService.submitCandidate("ai-1", 100L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Candidate contains unconfirmed fields");
    }

    @Test
    @DisplayName("Test 6: Manager receives partial manual candidate with provided values and empty fields as null/empty")
    void test6_managerReceivesPartialManualCandidate() {
        CompanyCandidate candidate = createManualCandidate("cand-6", "Epsilon Corp", "Epsilon", "https://epsilon.com");
        when(candidateRepository.findById("cand-6")).thenReturn(Optional.of(candidate));

        CandidateResponse response = candidateService.getCandidate("cand-6");

        assertThat(response.getIdentity().getLegalName()).isEqualTo("Epsilon Corp");
        assertThat(response.getIdentity().getTradeName()).isEqualTo("Epsilon");
        assertThat(response.getContact().getWebsite()).isEqualTo("https://epsilon.com");
        assertThat(response.getContact().getEmails()).isEmpty();
        assertThat(response.getContact().getPhones()).isEmpty();
    }

    @Test
    @DisplayName("Test 7: Manager requests changes on partial manual candidate -> staff edits -> staff resubmits")
    void test7_managerRequestsChanges_staffEdits_resubmits() {
        CompanyCandidate candidate = createManualCandidate("cand-7", "Zeta Corp", "Zeta", "https://zeta.com");
        candidate.setStatus(CandidateStatus.REVISION_REQUIRED);
        candidate.setRevisionNumber(2);

        FieldApprovalRecord websiteRecord = FieldApprovalRecord.builder()
                .fieldPath("contact.website")
                .status(FieldApprovalStatus.REVISION_REQUIRED)
                .comment("Please provide HTTPS link with domain")
                .build();
        candidate.getFieldApprovals().add(websiteRecord);

        when(candidateRepository.findById("cand-7")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(inv -> inv.getArgument(0));

        // Staff updates contact.website
        CandidateReviewRequest.FieldReviewUpdate update = new CandidateReviewRequest.FieldReviewUpdate();
        update.setReviewedValue("https://zeta-corp.com");
        update.setManager(false);

        CandidateReviewRequest reviewRequest = new CandidateReviewRequest();
        reviewRequest.setFields(Map.of("contact.website", update));

        candidateService.reviewCandidate("10", "cand-7", reviewRequest, 100L);

        // Verify status was set to EDITED (not CONFIRMED)
        ExtractionFieldResult websiteResult = candidate.getFieldResults().get(
                com.apms.domain.ai.service.FieldKeyCodec.encode("contact.website"));
        assertThat(websiteResult.getStaffReviewStatus()).isEqualTo(StaffFieldReviewStatus.EDITED);
        assertThat(websiteResult.getStaffReviewedValue()).isEqualTo("https://zeta-corp.com");

        // Staff resubmits candidate
        CandidateResponse submitResponse = candidateService.submitCandidate("cand-7", 100L);
        assertThat(submitResponse).isNotNull();
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("Test 8: Manager approves partial manual candidate with only submitted fields approved, unprovided fields require no approval")
    void test8_managerApprovesPartialManualCandidate() {
        CompanyCandidate candidate = createManualCandidate("cand-8", "Eta Corp", "Eta", "https://eta.com");
        candidate.setStatus(CandidateStatus.PENDING_REVIEW);

        // Only submitted fields have field approval records
        FieldApprovalRecord tradeNameRecord = FieldApprovalRecord.builder()
                .fieldPath("identity.tradeName")
                .status(FieldApprovalStatus.APPROVED)
                .build();
        FieldApprovalRecord websiteRecord = FieldApprovalRecord.builder()
                .fieldPath("contact.website")
                .status(FieldApprovalStatus.APPROVED)
                .build();
        candidate.setFieldApprovals(new ArrayList<>(List.of(tradeNameRecord, websiteRecord)));

        // Unprovided fields (e.g. emails, phones, industries, etc.) have NO FieldApprovalRecord
        when(candidateRepository.findById("cand-8")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(inv -> inv.getArgument(0));

        Project project = new Project();
        project.setId(10L);
        project.setTargetRelationshipType(RelationshipType.SUPPLIER_OF);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));

        ApproveCandidateRequest approveRequest = new ApproveCandidateRequest();

        CandidateResponse response = candidateService.approveCandidate("cand-8", approveRequest, 200L);

        assertThat(response).isNotNull();
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.APPROVED);
        assertThat(candidate.getLifecycle().getStatus()).isEqualTo(CandidateStatus.APPROVED);
        assertThat(candidate.getReview().getReviewedBy()).isEqualTo("200");
    }

    @Test
    @DisplayName("Test 9: Partial manual candidate with pending field cannot be approved until decided")
    void test9_partialManualCandidateCannotBeApprovedWithPendingField() {
        CompanyCandidate candidate = createManualCandidate("cand-9", "Theta Corp", "Theta", "https://theta.com");
        candidate.setStatus(CandidateStatus.PENDING_REVIEW);

        // One submitted field is APPROVED, but another submitted field is still PENDING_REVIEW
        FieldApprovalRecord tradeNameRecord = FieldApprovalRecord.builder()
                .fieldPath("identity.tradeName")
                .status(FieldApprovalStatus.APPROVED)
                .build();
        FieldApprovalRecord websiteRecord = FieldApprovalRecord.builder()
                .fieldPath("contact.website")
                .status(FieldApprovalStatus.PENDING_REVIEW)
                .build();
        candidate.setFieldApprovals(new ArrayList<>(List.of(tradeNameRecord, websiteRecord)));

        when(candidateRepository.findById("cand-9")).thenReturn(Optional.of(candidate));

        ApproveCandidateRequest approveRequest = new ApproveCandidateRequest();

        assertThatThrownBy(() -> candidateService.approveCandidate("cand-9", approveRequest, 200L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    @DisplayName("Test 10: Manual partial data - 7 submitted approved, 6 unprovided optional -> approval succeeds")
    void test10_manualPartialDataSevenApprovedSixUnprovidedSucceeds() {
        CompanyCandidate candidate = createManualCandidate("cand-10", "Alpha Corp", "Alpha Trade", "https://alpha.com");
        candidate.setStatus(CandidateStatus.PENDING_REVIEW);
        candidate.getContact().setAddresses(new ArrayList<>(List.of(
                CompanyCandidate.Address.builder().type("HEADQUARTERS").fullAddress("123 Alpha St").build()
        )));
        candidate.getContact().getEmails().add("alpha@alpha.com");
        candidate.getContact().getPhones().add("1234567890");
        candidate.getBusiness().getIndustries().add("Software");
        candidate.getBusiness().getMarkets().add("Global");

        // Exactly 7 submitted reviewable fields:
        // 1. identity.tradeName, 2. contact.website, 3. contact.address, 4. contact.emails, 5. contact.phones,
        // 6. business.industries, 7. business.markets
        List<FieldApprovalRecord> approvals = new ArrayList<>();
        List<String> submittedPaths = List.of(
                "identity.tradeName", "contact.website", "contact.address", "contact.emails", "contact.phones",
                "business.industries", "business.markets"
        );
        for (String path : submittedPaths) {
            approvals.add(FieldApprovalRecord.builder().fieldPath(path).status(FieldApprovalStatus.APPROVED).build());
        }
        candidate.setFieldApprovals(approvals);

        when(candidateRepository.findById("cand-10")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(inv -> inv.getArgument(0));

        Project project = new Project();
        project.setId(10L);
        project.setTargetRelationshipType(RelationshipType.SUPPLIER_OF);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));

        CandidateResponse response = candidateService.approveCandidate("cand-10", new ApproveCandidateRequest(), 200L);

        assertThat(response).isNotNull();
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.APPROVED);
    }

    @Test
    @DisplayName("Test 11: Manual pending field - 6 approved, 1 pending -> approval fails")
    void test11_manualPendingFieldFails() {
        CompanyCandidate candidate = createManualCandidate("cand-11", "Alpha Corp", "Alpha Trade", "https://alpha.com");
        candidate.setStatus(CandidateStatus.PENDING_REVIEW);
        candidate.getContact().getEmails().add("alpha@alpha.com");
        candidate.getContact().getPhones().add("1234567890");
        candidate.getBusiness().getIndustries().add("Software");
        candidate.getBusiness().getMarkets().add("Global");

        List<FieldApprovalRecord> approvals = new ArrayList<>();
        List<String> approvedPaths = List.of(
                "identity.tradeName", "contact.website", "contact.emails", "contact.phones",
                "business.industries"
        );
        for (String path : approvedPaths) {
            approvals.add(FieldApprovalRecord.builder().fieldPath(path).status(FieldApprovalStatus.APPROVED).build());
        }
        // 1 field still PENDING_REVIEW
        approvals.add(FieldApprovalRecord.builder().fieldPath("business.markets").status(FieldApprovalStatus.PENDING_REVIEW).build());
        candidate.setFieldApprovals(approvals);

        when(candidateRepository.findById("cand-11")).thenReturn(Optional.of(candidate));

        assertThatThrownBy(() -> candidateService.approveCandidate("cand-11", new ApproveCandidateRequest(), 200L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    @DisplayName("Test 12: Manual rejected field - 6 approved, 1 rejected -> approval fails")
    void test12_manualRejectedFieldFails() {
        CompanyCandidate candidate = createManualCandidate("cand-12", "Alpha Corp", "Alpha Trade", "https://alpha.com");
        candidate.setStatus(CandidateStatus.PENDING_REVIEW);
        candidate.getContact().getEmails().add("alpha@alpha.com");
        candidate.getContact().getPhones().add("1234567890");
        candidate.getBusiness().getIndustries().add("Software");
        candidate.getBusiness().getMarkets().add("Global");

        List<FieldApprovalRecord> approvals = new ArrayList<>();
        List<String> approvedPaths = List.of(
                "identity.tradeName", "contact.website", "contact.emails", "contact.phones",
                "business.industries"
        );
        for (String path : approvedPaths) {
            approvals.add(FieldApprovalRecord.builder().fieldPath(path).status(FieldApprovalStatus.APPROVED).build());
        }
        // 1 field REJECTED
        approvals.add(FieldApprovalRecord.builder().fieldPath("business.markets").status(FieldApprovalStatus.REJECTED).build());
        candidate.setFieldApprovals(approvals);

        when(candidateRepository.findById("cand-12")).thenReturn(Optional.of(candidate));

        assertThatThrownBy(() -> candidateService.approveCandidate("cand-12", new ApproveCandidateRequest(), 200L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("REJECTED");
    }

    @Test
    @DisplayName("Test 13: Optional fields missing - required legal name present, optional absent -> approval succeeds")
    void test13_optionalFieldsMissingSucceeds() {
        // Staff only provides legalName and tradeName
        CompanyCandidate candidate = createManualCandidate("cand-13", "Only Legal Corp", "Only Trade", null);
        candidate.setStatus(CandidateStatus.PENDING_REVIEW);

        candidate.setFieldApprovals(new ArrayList<>(List.of(
                FieldApprovalRecord.builder().fieldPath("identity.tradeName").status(FieldApprovalStatus.APPROVED).build()
        )));

        when(candidateRepository.findById("cand-13")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(inv -> inv.getArgument(0));

        Project project = new Project();
        project.setId(10L);
        project.setTargetRelationshipType(RelationshipType.SUPPLIER_OF);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));

        CandidateResponse response = candidateService.approveCandidate("cand-13", new ApproveCandidateRequest(), 200L);

        assertThat(response).isNotNull();
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.APPROVED);
    }

    @Test
    @DisplayName("Test 14: Required field missing - legalName blank -> approval rejected")
    void test14_requiredLegalNameMissingFails() {
        CompanyCandidate candidate = createManualCandidate("cand-14", "", "Trade Only", "https://trade.com");
        candidate.getIdentity().setLegalName(null);
        candidate.setStatus(CandidateStatus.PENDING_REVIEW);

        candidate.setFieldApprovals(new ArrayList<>(List.of(
                FieldApprovalRecord.builder().fieldPath("identity.tradeName").status(FieldApprovalStatus.APPROVED).build(),
                FieldApprovalRecord.builder().fieldPath("contact.website").status(FieldApprovalStatus.APPROVED).build()
        )));

        when(candidateRepository.findById("cand-14")).thenReturn(Optional.of(candidate));

        assertThatThrownBy(() -> candidateService.approveCandidate("cand-14", new ApproveCandidateRequest(), 200L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("REQUIRED_CANDIDATE_FIELD_MISSING");
    }

    @Test
    @DisplayName("Test 15: Multi-round approval - Round 1 rejected field revised and approved in Round 2 -> succeeds")
    void test15_multiRoundResubmittedAndApprovedSucceeds() {
        CompanyCandidate candidate = createManualCandidate("cand-15", "Delta Corp", "Delta Trade", "https://delta.com");
        candidate.setStatus(CandidateStatus.PENDING_REVIEW);
        candidate.setRevisionNumber(2);

        FieldApprovalRecord tradeNameRecord = FieldApprovalRecord.builder()
                .fieldPath("identity.tradeName")
                .status(FieldApprovalStatus.APPROVED)
                .reviewedRevision(1)
                .build();
        FieldApprovalRecord websiteRecord = FieldApprovalRecord.builder()
                .fieldPath("contact.website")
                .status(FieldApprovalStatus.APPROVED)
                .reviewedRevision(2)
                .previousStatus(FieldApprovalStatus.REVISION_REQUIRED)
                .previousComment("Fix URL")
                .build();
        candidate.setFieldApprovals(new ArrayList<>(List.of(tradeNameRecord, websiteRecord)));

        when(candidateRepository.findById("cand-15")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(inv -> inv.getArgument(0));

        Project project = new Project();
        project.setId(10L);
        project.setTargetRelationshipType(RelationshipType.SUPPLIER_OF);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));

        CandidateResponse response = candidateService.approveCandidate("cand-15", new ApproveCandidateRequest(), 200L);

        assertThat(response).isNotNull();
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.APPROVED);
    }

    @Test
    @DisplayName("Test 16: Historical rejection - previousStatus in history does not block current approval")
    void test16_historicalRejectionDoesNotBlockApproval() {
        CompanyCandidate candidate = createManualCandidate("cand-16", "Echo Corp", "Echo", "https://echo.com");
        candidate.setStatus(CandidateStatus.PENDING_REVIEW);
        candidate.setRevisionNumber(3);

        FieldApprovalRecord websiteRecord = FieldApprovalRecord.builder()
                .fieldPath("contact.website")
                .status(FieldApprovalStatus.APPROVED)
                .reviewedRevision(3)
                .previousStatus(FieldApprovalStatus.REVISION_REQUIRED)
                .previousReviewedRevision(1)
                .previousComment("Round 1 rejection")
                .build();
        FieldApprovalRecord tradeNameRecord = FieldApprovalRecord.builder()
                .fieldPath("identity.tradeName")
                .status(FieldApprovalStatus.APPROVED)
                .reviewedRevision(1)
                .build();
        candidate.setFieldApprovals(new ArrayList<>(List.of(websiteRecord, tradeNameRecord)));

        when(candidateRepository.findById("cand-16")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(inv -> inv.getArgument(0));

        Project project = new Project();
        project.setId(10L);
        project.setTargetRelationshipType(RelationshipType.PARTNER_WITH);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));

        CandidateResponse response = candidateService.approveCandidate("cand-16", new ApproveCandidateRequest(), 200L);

        assertThat(response).isNotNull();
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.APPROVED);
    }

    @Test
    @DisplayName("Test 17: Old irrelevant approval records - database contains stale/non-reviewable PENDING records -> approval succeeds and records preserved")
    void test17_staleNonReviewablePendingRecordsDoNotBlockApproval() {
        CompanyCandidate candidate = createManualCandidate("cand-17", "Zeta Corp", "Zeta", "https://zeta.com");
        candidate.setStatus(CandidateStatus.PENDING_REVIEW);

        FieldApprovalRecord tradeNameRecord = FieldApprovalRecord.builder()
                .fieldPath("identity.tradeName")
                .status(FieldApprovalStatus.APPROVED)
                .build();
        FieldApprovalRecord websiteRecord = FieldApprovalRecord.builder()
                .fieldPath("contact.website")
                .status(FieldApprovalStatus.APPROVED)
                .build();

        // Stale non-reviewable record from legacy behavior
        FieldApprovalRecord financialRecord = FieldApprovalRecord.builder()
                .fieldPath("financial")
                .status(FieldApprovalStatus.PENDING_REVIEW)
                .build();
        // Stale record for an optional field that was NOT provided
        FieldApprovalRecord unprovidedProductRecord = FieldApprovalRecord.builder()
                .fieldPath("business.products")
                .status(FieldApprovalStatus.PENDING_REVIEW)
                .build();

        candidate.setFieldApprovals(new ArrayList<>(List.of(tradeNameRecord, websiteRecord, financialRecord, unprovidedProductRecord)));

        when(candidateRepository.findById("cand-17")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(inv -> inv.getArgument(0));

        Project project = new Project();
        project.setId(10L);
        project.setTargetRelationshipType(RelationshipType.SUPPLIER_OF);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));

        CandidateResponse response = candidateService.approveCandidate("cand-17", new ApproveCandidateRequest(), 200L);

        assertThat(response).isNotNull();
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.APPROVED);
        // Stale records remain stored for history/audit, not deleted
        assertThat(candidate.getFieldApprovals()).hasSize(4);
    }

    @Test
    @DisplayName("Test 18: Optional cleared field - Round 1 Website rejected, Round 2 Staff clears Website -> does not block approval")
    void test18_optionalClearedFieldDoesNotBlockApproval() {
        // Staff provided legalName, tradeName, but Website was CLEARED in Round 2 (null)
        CompanyCandidate candidate = createManualCandidate("cand-18", "Omega Corp", "Omega Trade", null);
        candidate.setStatus(CandidateStatus.PENDING_REVIEW);
        candidate.setRevisionNumber(2);

        FieldApprovalRecord tradeNameRecord = FieldApprovalRecord.builder()
                .fieldPath("identity.tradeName")
                .status(FieldApprovalStatus.APPROVED)
                .build();
        // Website was rejected in Round 1 and cleared in Round 2; approval record remains with REVISION_REQUIRED
        FieldApprovalRecord websiteRecord = FieldApprovalRecord.builder()
                .fieldPath("contact.website")
                .status(FieldApprovalStatus.REVISION_REQUIRED)
                .comment("Fix website")
                .reviewedRevision(1)
                .build();

        candidate.setFieldApprovals(new ArrayList<>(List.of(tradeNameRecord, websiteRecord)));

        when(candidateRepository.findById("cand-18")).thenReturn(Optional.of(candidate));
        when(candidateRepository.save(any(CompanyCandidate.class))).thenAnswer(inv -> inv.getArgument(0));

        Project project = new Project();
        project.setId(10L);
        project.setTargetRelationshipType(RelationshipType.SUPPLIER_OF);
        when(projectRepository.findById(10L)).thenReturn(Optional.of(project));

        CandidateResponse response = candidateService.approveCandidate("cand-18", new ApproveCandidateRequest(), 200L);

        assertThat(response).isNotNull();
        assertThat(candidate.getStatus()).isEqualTo(CandidateStatus.APPROVED);
        // Website historical record remains preserved
        assertThat(candidate.getFieldApprovals()).anyMatch(a -> "contact.website".equals(a.getFieldPath()) && a.getStatus() == FieldApprovalStatus.REVISION_REQUIRED);
    }
}

package com.apms.domain.profile.assessment;

import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.SystemRole;
import com.apms.common.exception.BusinessValidationException;
import org.springframework.security.access.AccessDeniedException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.contract.entity.PartnerContract;
import com.apms.domain.contract.enums.ContractReviewStatus;
import com.apms.domain.contract.repository.sql.PartnerContractRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.assessment.dto.*;
import com.apms.domain.profile.assessment.policy.RelationshipCommercialScoringPolicy;
import com.apms.domain.profile.assessment.policy.RelationshipScoreCalculator;
import com.apms.domain.profile.assessment.repository.CompanyRelationshipAssessmentRepository;
import com.apms.domain.profile.assessment.service.CompanyRelationshipAssessmentService;
import com.apms.domain.profile.assessment.service.RelationshipClosenessAccessEvaluator;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.profile.service.ProfileService;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CompanyRelationshipAssessmentServiceTest {

    @Mock
    private CompanyRelationshipAssessmentRepository assessmentRepository;
    @Mock
    private PartnerContractRepository partnerContractRepository;
    @Mock
    private CompanyProfileRepository companyProfileRepository;
    @Mock
    private OwnerOrganizationService ownerOrganizationService;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private ProfileService profileService;
    @Mock
    private RelationshipClosenessAccessEvaluator accessEvaluator;
    @Mock
    private com.apms.domain.notification.service.NotificationService notificationService;

    private RelationshipCommercialScoringPolicy scoringPolicy;
    private RelationshipScoreCalculator scoreCalculator;
    private CompanyRelationshipAssessmentService service;

    private final String ownerId = "owner-org-123";
    private final String targetId = "target-comp-456";

    private UserDetailsImpl managerUser;
    private UserDetailsImpl ownerUser;
    private UserDetailsImpl staffUser;

    @BeforeEach
    void setUp() {
        scoringPolicy = new RelationshipCommercialScoringPolicy();
        scoreCalculator = new RelationshipScoreCalculator();

        service = new CompanyRelationshipAssessmentService(
                assessmentRepository,
                partnerContractRepository,
                companyProfileRepository,
                ownerOrganizationService,
                projectRepository,
                auditLogService,
                scoringPolicy,
                scoreCalculator,
                profileService,
                accessEvaluator,
                notificationService
        );

        lenient().when(profileService.resolveRelationshipType(anyString())).thenReturn("PARTNER_WITH");
        lenient().when(accessEvaluator.resolveRelationshipType(anyString())).thenReturn("PARTNER_WITH");
        lenient().when(accessEvaluator.isEligibleRelationshipType(anyString())).thenReturn(true);

        managerUser = new UserDetailsImpl(
                10L, "manager@apms.com", "hash",
                List.of(new SimpleGrantedAuthority("ROLE_" + SystemRole.BUSINESS_DEVELOPMENT_MANAGER.name())),
                true
        );

        ownerUser = new UserDetailsImpl(
                1L, "owner@apms.com", "hash",
                List.of(new SimpleGrantedAuthority("ROLE_" + SystemRole.BUSINESS_OWNER.name())),
                true
        );

        staffUser = new UserDetailsImpl(
                20L, "staff@apms.com", "hash",
                List.of(new SimpleGrantedAuthority("ROLE_" + SystemRole.BUSINESS_DEVELOPMENT_STAFF.name())),
                true
        );

        lenient().when(ownerOrganizationService.getOwnerCompanyProfileId()).thenReturn(ownerId);
        lenient().when(ownerOrganizationService.isOwnerCompany(targetId)).thenReturn(false);

        CompanyProfile targetProfile = new CompanyProfile();
        targetProfile.setId(targetId);
        targetProfile.setCompanyId(targetId);
        targetProfile.setIsHidden(false);
        targetProfile.setIsDeleted(false);
        targetProfile.setResponsibleManagerId(10L);
        lenient().when(companyProfileRepository.findById(targetId)).thenReturn(Optional.of(targetProfile));
    }

    @Test
    void testCommercialSnapshotStability_AcrossManagerSubmitAndOwnerFinalize() {
        // Step 1: Initial contract in DB (1 contract, 1B VND, signed 1 month ago)
        PartnerContract c1 = PartnerContract.builder()
                .effectiveDate(LocalDate.now().minusMonths(1))
                .currency("VND")
                .totalContractValue(new BigDecimal("1000000000"))
                .build();
        when(partnerContractRepository.findByPartnerCompanyIdAndReviewStatus(targetId, ContractReviewStatus.APPROVED))
                .thenReturn(new ArrayList<>(List.of(c1)));

        // Create Draft
        when(assessmentRepository.existsByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusIn(eq(ownerId), eq(targetId), any()))
                .thenReturn(false);
        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdOrderByVersionNumberDesc(ownerId, targetId))
                .thenReturn(Optional.empty());

        when(assessmentRepository.saveAndFlush(any())).thenAnswer(inv -> {
            CompanyRelationshipAssessment a = inv.getArgument(0);
            a.setId(100L);
            return a;
        });

        CreateRelationshipAssessmentRequest createReq = CreateRelationshipAssessmentRequest.builder()
                .commercialAwardedScore(5)
                .commercialEvidenceNote("Initial commercial evidence")
                .cooperationScore(5)
                .cooperationEvidenceNote("Good cooperation")
                .strategicScore(4)
                .strategicEvidenceNote("Strategic partner")
                .relationshipNetworkScore(4)
                .relationshipNetworkNote("Network contacts")
                .engagementScore(4)
                .engagementEvidenceNote("Active engagement")
                .qualitativeScore(4)
                .qualitativeEvidenceNote("High quality")
                .managerNote("Initial draft evaluation")
                .build();

        RelationshipAssessmentResponse draft = service.createDraft(targetId, createReq, managerUser);
        assertNotNull(draft);
        assertEquals(RelationshipAssessmentStatus.DRAFT, draft.getStatus());
        assertNull(draft.getCommercialScore());
        assertEquals(5, draft.getCommercialAwardedScore());

        // Step 2: Manager Submits
        CompanyRelationshipAssessment draftEntity = CompanyRelationshipAssessment.builder()
                .id(100L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.DRAFT)
                .scoringPolicyVersion("RELATIONSHIP_CLOSENESS_V5")
                .commercialAwardedScore(5)
                .commercialScore(null)
                .commercialEvidenceNote("Commercial note")
                .cooperationScore(5)
                .cooperationEvidenceNote("Cooperation note")
                .strategicScore(4)
                .strategicEvidenceNote("Strategic note")
                .relationshipNetworkScore(4)
                .relationshipNetworkNote("Network note")
                .engagementScore(4)
                .engagementEvidenceNote("Engagement note")
                .qualitativeScore(4)
                .qualitativeEvidenceNote("Qualitative note")
                .managerNote("Detailed manager assessment")
                .scorableBase(30)
                .normalizationApplied(true)
                .build();
        when(assessmentRepository.findById(100L)).thenReturn(Optional.of(draftEntity));
        when(assessmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RelationshipAssessmentResponse submitted = service.submitAssessment(100L, managerUser);
        assertEquals(RelationshipAssessmentStatus.SUBMITTED, submitted.getStatus());
        assertNull(submitted.getCommercialScore());
        assertEquals(5, submitted.getCommercialAwardedScore());

        // Step 3: Owner Finalizes
        // Finalize must use the frozen submitted snapshot! Zero contracts queried!
        FinalizeRelationshipAssessmentRequest finReq = FinalizeRelationshipAssessmentRequest.builder().build();
        RelationshipAssessmentResponse finalized = service.finalizeAssessment(100L, finReq, ownerUser);

        assertEquals(RelationshipAssessmentStatus.FINALIZED, finalized.getStatus());
        assertNull(finalized.getCommercialScore());
        assertEquals(5, finalized.getOwnerCommercialScore());
    }

    @Test
    void testChangesRequestedWorkflow_PreservesCommercialSnapshotOnResubmit() {
        // Assessment is in SUBMITTED state with commercialScore = 15
        CompanyRelationshipAssessment assessment = CompanyRelationshipAssessment.builder()
                .id(200L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.SUBMITTED)
                .scoringPolicyVersion("RELATIONSHIP_CLOSENESS_V2")
                .commercialScore(15)
                .commercialSuggestedScore(15)
                .commercialAwardedScore(15)
                .contractValueScore(6)
                .contractCountScore(2)
                .relationshipDurationScore(1)
                .contractRecencyScore(6)
                .cooperationScore(10)
                .strategicScore(10)
                .relationshipNetworkScore(8)
                .engagementScore(3)
                .qualitativeScore(3)
                .managerNote("First submission note")
                .scorableBase(100)
                .normalizationApplied(false)
                .build();

        when(assessmentRepository.findById(200L)).thenReturn(Optional.of(assessment));
        when(assessmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Owner requests changes
        RequestChangesAssessmentRequest reqChanges = new RequestChangesAssessmentRequest("Please adjust qualitative note");
        RelationshipAssessmentResponse changesRequested = service.requestChanges(200L, reqChanges, ownerUser);

        assertEquals(RelationshipAssessmentStatus.CHANGES_REQUESTED, changesRequested.getStatus());
        assertEquals("Please adjust qualitative note", changesRequested.getChangesRequestedReason());
        assertNotNull(changesRequested.getChangesRequestedAt());

        // Manager edits note and resubmits
        UpdateRelationshipAssessmentRequest updateReq = UpdateRelationshipAssessmentRequest.builder()
                .managerNote("Updated qualitative assessment note addressing owner feedback")
                .build();
        service.updateDraft(200L, updateReq, managerUser);

        RelationshipAssessmentResponse resubmitted = service.submitAssessment(200L, managerUser);
        assertEquals(RelationshipAssessmentStatus.SUBMITTED, resubmitted.getStatus());
        // Commercial score must remain frozen at 15!
        assertEquals(15, resubmitted.getCommercialScore());
        assertEquals("Please adjust qualitative note", resubmitted.getChangesRequestedReason());
    }

    @Test
    void testReassessment_ComputesFreshCommercialSnapshotForNewVersion() {
        // Version 1 is FINALIZED with commercialScore = 10
        CompanyRelationshipAssessment v1 = CompanyRelationshipAssessment.builder()
                .id(1L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.FINALIZED)
                .scoringPolicyVersion("RELATIONSHIP_CLOSENESS_V1")
                .commercialScore(10)
                .cooperationScore(15)
                .strategicScore(15)
                .relationshipNetworkScore(12)
                .engagementScore(4)
                .qualitativeScore(4)
                .ownerCooperationScore(15)
                .ownerStrategicScore(15)
                .ownerRelationshipNetworkScore(12)
                .ownerEngagementScore(4)
                .ownerQualitativeScore(4)
                .ownerFinalTotalScore(70)
                .ownerFinalRank("B")
                .build();

        when(assessmentRepository.existsByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusIn(eq(ownerId), eq(targetId), any()))
                .thenReturn(false);
        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
                ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.of(v1));

        // Now new contracts exist in the database!
        PartnerContract cNew = PartnerContract.builder()
                .effectiveDate(LocalDate.now().minusMonths(1))
                .currency("VND")
                .totalContractValue(new BigDecimal("25000000000")) // 25B VND
                .build();
        when(partnerContractRepository.findByPartnerCompanyIdAndReviewStatus(targetId, ContractReviewStatus.APPROVED))
                .thenReturn(List.of(cNew));

        when(assessmentRepository.saveAndFlush(any())).thenAnswer(inv -> {
            CompanyRelationshipAssessment a = inv.getArgument(0);
            a.setId(2L);
            return a;
        });

        RelationshipAssessmentResponse v2 = service.createNewVersion(targetId, managerUser);

        assertEquals(2, v2.getVersionNumber());
        assertEquals(RelationshipAssessmentStatus.DRAFT, v2.getStatus());
        assertEquals(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5, v2.getScoringPolicyVersion());
        // Version 2 has newly computed commercial snapshot under V5!
        assertEquals(1, v2.getApprovedContractCount());
        // All criteria start null in V5!
        assertNull(v2.getCommercialAwardedScore());
        assertNull(v2.getEngagementScore());
        assertNull(v2.getRelationshipNetworkScore());
        assertNull(v2.getCooperationScore());
        assertNull(v2.getStrategicScore());
        assertNull(v2.getQualitativeScore());
        assertNull(v2.getManagerNote()); // Fresh note required starts null
    }

    @Test
    void testOwnerModification_PreservesManagerScoresSeparately() {
        CompanyRelationshipAssessment submitted = CompanyRelationshipAssessment.builder()
                .id(300L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.SUBMITTED)
                .scoringPolicyVersion("RELATIONSHIP_CLOSENESS_V2")
                .commercialScore(25)
                .commercialSuggestedScore(25)
                .commercialAwardedScore(25)
                .cooperationScore(15)
                .strategicScore(15)
                .relationshipNetworkScore(8)
                .engagementScore(4)
                .qualitativeScore(4)
                .managerRawScorableScore(71)
                .managerTotalScore(71)
                .managerRank("B")
                .scorableBase(100)
                .normalizationApplied(false)
                .build();

        when(assessmentRepository.findById(300L)).thenReturn(Optional.of(submitted));
        when(assessmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Owner adjusts Strategic score: 15 -> 20, providing reason
        FinalizeRelationshipAssessmentRequest finReq = FinalizeRelationshipAssessmentRequest.builder()
                .ownerCooperationScore(15)
                .ownerStrategicScore(20) // adjusted +5
                .ownerRelationshipNetworkScore(8)
                .ownerEngagementScore(4)
                .ownerQualitativeScore(4)
                .ownerAdjustmentReason("Formally upgraded to strategic partner status")
                .build();

        RelationshipAssessmentResponse finalized = service.finalizeAssessment(300L, finReq, ownerUser);

        // Manager score must remain preserved
        assertEquals(71, finalized.getManagerTotalScore());
        assertEquals("B", finalized.getManagerRank());

        // Owner score is updated: 25 + 15 + 20 + 8 + 4 + 4 = 76
        assertEquals(76, finalized.getOwnerFinalTotalScore());
        assertEquals("B", finalized.getOwnerFinalRank());
        assertEquals("Formally upgraded to strategic partner status", finalized.getOwnerAdjustmentReason());
    }

    @Test
    void testConcurrency_DualActiveAssessmentBlocked() {
        // Active assessment exists
        when(assessmentRepository.existsByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusIn(eq(ownerId), eq(targetId), any()))
                .thenReturn(true);

        assertThrows(BusinessValidationException.class, () ->
                service.createDraft(targetId, new CreateRelationshipAssessmentRequest(), managerUser));
    }

    @Test
    void testV2DraftNullableScoresAndStaffPermissions() {
        lenient().when(projectRepository.existsByTargetCompanyProfileIdAndMembersAccountIdAndStatusIn(eq(targetId), eq(20L), any()))
                .thenReturn(true);

        CompanyRelationshipAssessment draft = CompanyRelationshipAssessment.builder()
                .id(400L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.DRAFT)
                .scoringPolicyVersion("RELATIONSHIP_CLOSENESS_V2")
                .commercialSuggestedScore(25)
                .commercialAwardedScore(null) // null draft score allowed
                .cooperationScore(null)
                .strategicScore(null)
                .relationshipNetworkScore(null)
                .engagementScore(null)
                .qualitativeScore(null)
                .build();

        when(assessmentRepository.findById(400L)).thenReturn(Optional.of(draft));
        when(assessmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Staff updates draft with partial scores
        UpdateRelationshipAssessmentRequest updateReq = UpdateRelationshipAssessmentRequest.builder()
                .cooperationScore(18)
                .cooperationEvidenceNote("Regular quarterly meetings")
                .build();

        RelationshipAssessmentResponse response = service.updateDraft(400L, updateReq, staffUser);
        assertNotNull(response);
        assertEquals(18, response.getCooperationScore());
        assertEquals("Regular quarterly meetings", response.getCooperationEvidenceNote());
        assertEquals(2, response.getCompletedCriteriaCount());
        assertFalse(response.getIsComplete());

        // Staff tries to submit -> AccessDeniedException (Manager ONLY)
        assertThrows(AccessDeniedException.class, () -> service.submitAssessment(400L, staffUser));
    }

    @Test
    void testV2ManagerSubmission_EnforcesAllCriteriaAndReasonWhenCommercialDiffers() {
        CompanyRelationshipAssessment draft = CompanyRelationshipAssessment.builder()
                .id(500L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.DRAFT)
                .scoringPolicyVersion("RELATIONSHIP_CLOSENESS_V2")
                .commercialSuggestedScore(20)
                .commercialAwardedScore(25) // Differs from suggested (25 != 20)
                .cooperationScore(18)
                .strategicScore(13)
                .relationshipNetworkScore(7)
                .engagementScore(4)
                .qualitativeScore(4)
                .qualitativeEvidenceNote("Solid performance")
                .managerNote("Overall positive relationship")
                .commercialAdjustmentReason(null) // Missing reason!
                .build();

        when(assessmentRepository.findById(500L)).thenReturn(Optional.of(draft));

        // Missing adjustment reason when awarded != suggested -> BusinessValidationException
        assertThrows(BusinessValidationException.class, () -> service.submitAssessment(500L, managerUser));

        // Provide adjustment reason
        draft.setCommercialAdjustmentReason("High value ongoing unbilled projects");
        when(assessmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RelationshipAssessmentResponse submitted = service.submitAssessment(500L, managerUser);
        assertEquals(RelationshipAssessmentStatus.SUBMITTED, submitted.getStatus());
        // Direct sum: 25 + 18 + 13 + 7 + 4 + 4 = 71
        assertEquals(71, submitted.getManagerTotalScore());
        assertEquals("B", submitted.getManagerRank());
        assertEquals(6, submitted.getCompletedCriteriaCount());
        assertTrue(submitted.getIsComplete());
    }

    @Test
    void testV2OwnerCannotSubmit_CanReviewAndFinalize() {
        CompanyRelationshipAssessment draft = CompanyRelationshipAssessment.builder()
                .id(600L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.DRAFT)
                .scoringPolicyVersion("RELATIONSHIP_CLOSENESS_V2")
                .commercialSuggestedScore(20)
                .commercialAwardedScore(20)
                .cooperationScore(18)
                .strategicScore(13)
                .relationshipNetworkScore(7)
                .engagementScore(4)
                .qualitativeScore(4)
                .qualitativeEvidenceNote("Solid")
                .managerNote("Overall good")
                .build();

        when(assessmentRepository.findById(600L)).thenReturn(Optional.of(draft));

        // Owner cannot submit to Owner! Manager ONLY
        assertThrows(AccessDeniedException.class, () -> service.submitAssessment(600L, ownerUser));
    }

    @Test
    void testV3Reassessment_FromV3Finalized_SeedsDraftScores() {
        CompanyRelationshipAssessment v3Finalized = CompanyRelationshipAssessment.builder()
                .id(700L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.FINALIZED)
                .scoringPolicyVersion("RELATIONSHIP_CLOSENESS_V3")
                .commercialScore(40)
                .commercialSuggestedScore(40)
                .commercialAwardedScore(40)
                .engagementScore(15)
                .relationshipNetworkScore(22)
                .relationshipNetworkNote("Key contact CEO")
                .ownerEngagementScore(16)
                .ownerRelationshipNetworkScore(24)
                .ownerRelationshipNetworkNote("CEO & VP of Engineering")
                .ownerFinalTotalScore(80)
                .ownerFinalRank("A")
                .build();

        when(assessmentRepository.existsByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusIn(eq(ownerId), eq(targetId), any()))
                .thenReturn(false);
        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
                ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.of(v3Finalized));

        PartnerContract c = PartnerContract.builder()
                .effectiveDate(LocalDate.now().minusMonths(2))
                .currency("VND")
                .totalContractValue(new BigDecimal("1000000000"))
                .build();
        when(partnerContractRepository.findByPartnerCompanyIdAndReviewStatus(targetId, ContractReviewStatus.APPROVED))
                .thenReturn(List.of(c));

        when(assessmentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        RelationshipAssessmentResponse response = service.createNewVersion(targetId, managerUser);
        assertNotNull(response);
        assertEquals(2, response.getVersionNumber());
        assertEquals(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5, response.getScoringPolicyVersion());
        // All criteria start null in V5!
        assertNull(response.getCommercialAwardedScore());
        assertNull(response.getEngagementScore());
        assertNull(response.getRelationshipNetworkScore());
        assertNull(response.getCooperationScore());
        assertNull(response.getStrategicScore());
        assertNull(response.getQualitativeScore());
    }

    @Test
    void testV4CommercialReferenceOnly_NonVndContract() {
        PartnerContract c = PartnerContract.builder()
                .effectiveDate(LocalDate.now().minusMonths(2))
                .currency("USD")
                .totalContractValue(new BigDecimal("50000"))
                .build();
        when(partnerContractRepository.findByPartnerCompanyIdAndReviewStatus(targetId, ContractReviewStatus.APPROVED))
                .thenReturn(List.of(c));

        CommercialEvidenceResponse evidence = service.getLiveCommercialEvidence(targetId);
        assertNotNull(evidence);
        assertEquals("UNSCORABLE_NON_VND", evidence.getContractValueStatus());
        assertNull(evidence.getContractValueScore());
        assertEquals("REFERENCE_ONLY", evidence.getCommercialSuggestionStatus());
        assertNull(evidence.getCommercialAvailablePoints());
    }

    @Test
    void testV4CommercialReferenceOnly_MissingDates() {
        PartnerContract c = PartnerContract.builder()
                .effectiveDate(null) // No date
                .currency("VND")
                .totalContractValue(new BigDecimal("1000000000"))
                .build();
        when(partnerContractRepository.findByPartnerCompanyIdAndReviewStatus(targetId, ContractReviewStatus.APPROVED))
                .thenReturn(List.of(c));

        CommercialEvidenceResponse evidence = service.getLiveCommercialEvidence(targetId);
        assertNotNull(evidence);
        assertNull(evidence.getRelationshipDurationScore());
        assertNull(evidence.getContractRecencyScore());
        assertFalse(evidence.isHasValidHistoricalDates());
        assertEquals("REFERENCE_ONLY", evidence.getCommercialSuggestionStatus());
        assertNull(evidence.getCommercialAvailablePoints());
    }

    @Test
    void testV3FullLifecycle_DraftSubmitFinalize() {
        CompanyRelationshipAssessment draft = CompanyRelationshipAssessment.builder()
                .id(900L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.DRAFT)
                .scoringPolicyVersion("RELATIONSHIP_CLOSENESS_V3")
                .commercialScore(40)
                .commercialSuggestedScore(40)
                .commercialAwardedScore(40)
                .commercialAdjustmentReason("Strategic assessment based on full ecosystem partnership")
                .engagementScore(15)
                .engagementEvidenceNote("Active monthly reviews")
                .relationshipNetworkScore(22)
                .relationshipNetworkNote("Primary point of contact CTO")
                .managerNote("Strong collaboration")
                .build();

        when(assessmentRepository.findById(900L)).thenReturn(Optional.of(draft));
        when(assessmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Submit
        RelationshipAssessmentResponse submitted = service.submitAssessment(900L, managerUser);
        assertEquals(RelationshipAssessmentStatus.SUBMITTED, submitted.getStatus());
        assertEquals(77, submitted.getManagerTotalScore()); // 40 + 15 + 22 = 77
        assertEquals("B", submitted.getManagerRank());
        assertEquals(3, submitted.getCompletedCriteriaCount());
        assertEquals(3, submitted.getTotalCriteriaCount());
        assertTrue(submitted.getIsComplete());

        // Finalize with Owner Adjustment
        FinalizeRelationshipAssessmentRequest finReq = FinalizeRelationshipAssessmentRequest.builder()
                .ownerCommercialScore(40)
                .ownerEngagementScore(18) // modified 15 -> 18
                .ownerRelationshipNetworkScore(22)
                .ownerAdjustmentReason("Increased engagement due to executive sponsorship")
                .build();

        RelationshipAssessmentResponse finalized = service.finalizeAssessment(900L, finReq, ownerUser);
        assertEquals(RelationshipAssessmentStatus.FINALIZED, finalized.getStatus());
        assertEquals(80, finalized.getOwnerFinalTotalScore()); // 40 + 18 + 22 = 80
        assertEquals("B", finalized.getOwnerFinalRank());
        assertEquals("Increased engagement due to executive sponsorship", finalized.getOwnerAdjustmentReason());
    }

    @Test
    void testExistingV2Draft_ContinuesUnderV2Policy() {
        CompanyRelationshipAssessment v2Draft = CompanyRelationshipAssessment.builder()
                .id(950L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.DRAFT)
                .scoringPolicyVersion("RELATIONSHIP_CLOSENESS_V2")
                .commercialAwardedScore(25)
                .cooperationScore(18)
                .strategicScore(15)
                .relationshipNetworkScore(8)
                .engagementScore(4)
                .qualitativeScore(4)
                .managerNote("V2 draft note")
                .build();

        when(assessmentRepository.findById(950L)).thenReturn(Optional.of(v2Draft));
        when(assessmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateRelationshipAssessmentRequest updateReq = UpdateRelationshipAssessmentRequest.builder()
                .cooperationScore(20)
                .build();

        RelationshipAssessmentResponse updated = service.updateDraft(950L, updateReq, managerUser);
        assertEquals("RELATIONSHIP_CLOSENESS_V2", updated.getScoringPolicyVersion());
        assertEquals(6, updated.getTotalCriteriaCount());
        assertEquals(6, updated.getCompletedCriteriaCount());
        // 25 + 20 + 15 + 8 + 4 + 4 = 76
        assertEquals(76, updated.getDraftSubtotalScore());
        assertEquals("B", updated.getOfficialRank() != null ? updated.getOfficialRank() : updated.getManagerRank());
    }

    @Test
    void testV4Draft_AllCriteriaStartNull() {
        when(assessmentRepository.existsByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusIn(eq(ownerId), eq(targetId), any()))
                .thenReturn(false);
        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdOrderByVersionNumberDesc(ownerId, targetId))
                .thenReturn(Optional.empty());

        when(assessmentRepository.saveAndFlush(any())).thenAnswer(inv -> {
            CompanyRelationshipAssessment a = inv.getArgument(0);
            a.setId(1001L);
            return a;
        });

        CreateRelationshipAssessmentRequest createReq = CreateRelationshipAssessmentRequest.builder().build();

        RelationshipAssessmentResponse draft = service.createDraft(targetId, createReq, managerUser);
        assertNotNull(draft);
        assertEquals(RelationshipAssessmentStatus.DRAFT, draft.getStatus());
        assertEquals("RELATIONSHIP_CLOSENESS_V5", draft.getScoringPolicyVersion());
        assertEquals(6, draft.getTotalCriteriaCount());
        assertEquals(0, draft.getCompletedCriteriaCount());
        assertFalse(draft.getIsComplete());
        assertNull(draft.getDraftSubtotalScore());
        assertNull(draft.getCommercialAwardedScore());
        assertNull(draft.getCommercialScore());
        assertNull(draft.getCommercialSuggestedScore());
        assertNull(draft.getCooperationScore());
        assertNull(draft.getStrategicScore());
        assertNull(draft.getRelationshipNetworkScore());
        assertNull(draft.getEngagementScore());
        assertNull(draft.getQualitativeScore());
        assertNull(draft.getManagerRank());
    }

    @Test
    void testV4Draft_NoCommercialAutoFill_EvenWithApprovedContracts() {
        PartnerContract c = PartnerContract.builder()
                .effectiveDate(LocalDate.now().minusMonths(3))
                .currency("VND")
                .totalContractValue(new BigDecimal("5000000000"))
                .build();
        when(partnerContractRepository.findByPartnerCompanyIdAndReviewStatus(targetId, ContractReviewStatus.APPROVED))
                .thenReturn(List.of(c));

        when(assessmentRepository.existsByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusIn(eq(ownerId), eq(targetId), any()))
                .thenReturn(false);
        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdOrderByVersionNumberDesc(ownerId, targetId))
                .thenReturn(Optional.empty());

        when(assessmentRepository.saveAndFlush(any())).thenAnswer(inv -> {
            CompanyRelationshipAssessment a = inv.getArgument(0);
            a.setId(1002L);
            return a;
        });

        RelationshipAssessmentResponse draft = service.createDraft(targetId, null, managerUser);
        assertNotNull(draft);
        // Evidence is snapshotted:
        assertEquals(1, draft.getApprovedContractCount());
        assertEquals(new BigDecimal("5000000000"), draft.getTotalContractValueVnd());
        // But commercial score is NOT auto-filled:
        assertNull(draft.getCommercialAwardedScore());
        assertNull(draft.getCommercialScore());
        assertNull(draft.getCommercialSuggestedScore());
    }

    @Test
    void testV4Draft_ScoreZeroIsAssessed_NotTreatedAsMissing() {
        CompanyRelationshipAssessment draft = CompanyRelationshipAssessment.builder()
                .id(1003L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.DRAFT)
                .scoringPolicyVersion("RELATIONSHIP_CLOSENESS_V4")
                .build();

        when(assessmentRepository.findById(1003L)).thenReturn(Optional.of(draft));
        when(assessmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateRelationshipAssessmentRequest updateReq = UpdateRelationshipAssessmentRequest.builder()
                .commercialAwardedScore(0)
                .commercialEvidenceNote("No commercial ties yet")
                .build();

        RelationshipAssessmentResponse updated = service.updateDraft(1003L, updateReq, managerUser);
        assertEquals(1, updated.getCompletedCriteriaCount());
        assertEquals(0, updated.getDraftSubtotalScore());
        assertEquals(0, updated.getCommercialAwardedScore());
        assertFalse(updated.getIsComplete());
    }

    @Test
    void testV4Submit_RequiresAllSixScores() {
        // Missing qualitativeScore
        CompanyRelationshipAssessment assessment = CompanyRelationshipAssessment.builder()
                .id(1004L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.DRAFT)
                .scoringPolicyVersion("RELATIONSHIP_CLOSENESS_V4")
                .commercialAwardedScore(25)
                .commercialEvidenceNote("Commercial note")
                .cooperationScore(20)
                .cooperationEvidenceNote("Cooperation note")
                .strategicScore(15)
                .strategicEvidenceNote("Strategic note")
                .relationshipNetworkScore(8)
                .relationshipNetworkNote("Network note")
                .engagementScore(4)
                .engagementEvidenceNote("Engagement note")
                .qualitativeScore(null) // MISSING!
                .qualitativeEvidenceNote("Qualitative note")
                .managerNote("Overall note")
                .build();

        when(assessmentRepository.findById(1004L)).thenReturn(Optional.of(assessment));

        assertThrows(BusinessValidationException.class, () ->
                service.submitAssessment(1004L, managerUser));
    }

    @Test
    void testV4Submit_RequiresAllSixEvidenceNotes() {
        // Missing commercialEvidenceNote
        CompanyRelationshipAssessment assessment = CompanyRelationshipAssessment.builder()
                .id(1005L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.DRAFT)
                .scoringPolicyVersion("RELATIONSHIP_CLOSENESS_V4")
                .commercialAwardedScore(25)
                .commercialEvidenceNote("") // MISSING / BLANK!
                .cooperationScore(20)
                .cooperationEvidenceNote("Cooperation note")
                .strategicScore(15)
                .strategicEvidenceNote("Strategic note")
                .relationshipNetworkScore(8)
                .relationshipNetworkNote("Network note")
                .engagementScore(4)
                .engagementEvidenceNote("Engagement note")
                .qualitativeScore(4)
                .qualitativeEvidenceNote("Qualitative note")
                .managerNote("Overall note")
                .build();

        when(assessmentRepository.findById(1005L)).thenReturn(Optional.of(assessment));

        assertThrows(BusinessValidationException.class, () ->
                service.submitAssessment(1005L, managerUser));
    }

    @Test
    void testV4Submit_RequiresManagerOverallNote() {
        CompanyRelationshipAssessment assessment = CompanyRelationshipAssessment.builder()
                .id(1006L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.DRAFT)
                .scoringPolicyVersion("RELATIONSHIP_CLOSENESS_V4")
                .commercialAwardedScore(25)
                .commercialEvidenceNote("Commercial note")
                .cooperationScore(20)
                .cooperationEvidenceNote("Cooperation note")
                .strategicScore(15)
                .strategicEvidenceNote("Strategic note")
                .relationshipNetworkScore(8)
                .relationshipNetworkNote("Network note")
                .engagementScore(4)
                .engagementEvidenceNote("Engagement note")
                .qualitativeScore(4)
                .qualitativeEvidenceNote("Qualitative note")
                .managerNote("") // MISSING!
                .build();

        when(assessmentRepository.findById(1006L)).thenReturn(Optional.of(assessment));

        assertThrows(BusinessValidationException.class, () ->
                service.submitAssessment(1006L, managerUser));
    }

    @Test
    void testV4Submit_AllowsScoreZeroAcrossAllSix_YieldsRankD() {
        CompanyRelationshipAssessment assessment = CompanyRelationshipAssessment.builder()
                .id(1007L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.DRAFT)
                .scoringPolicyVersion("RELATIONSHIP_CLOSENESS_V4")
                .commercialAwardedScore(0)
                .commercialEvidenceNote("No commercial contracts")
                .cooperationScore(0)
                .cooperationEvidenceNote("No cooperation history")
                .strategicScore(0)
                .strategicEvidenceNote("Not a strategic partner")
                .relationshipNetworkScore(0)
                .relationshipNetworkNote("No contacts established")
                .engagementScore(0)
                .engagementEvidenceNote("Zero business engagement")
                .qualitativeScore(0)
                .qualitativeEvidenceNote("No qualitative track record")
                .managerNote("Initial partner evaluation with zero history")
                .build();

        when(assessmentRepository.findById(1007L)).thenReturn(Optional.of(assessment));
        when(assessmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RelationshipAssessmentResponse submitted = service.submitAssessment(1007L, managerUser);
        assertEquals(RelationshipAssessmentStatus.SUBMITTED, submitted.getStatus());
        assertEquals(0, submitted.getManagerTotalScore());
        assertEquals("D", submitted.getManagerRank());
        assertEquals(6, submitted.getCompletedCriteriaCount());
        assertTrue(submitted.getIsComplete());
    }

    @Test
    void testV4Finalize_CompleteOwnerSnapshot_DefaultsToManagerScores() {
        CompanyRelationshipAssessment submitted = CompanyRelationshipAssessment.builder()
                .id(1008L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.SUBMITTED)
                .scoringPolicyVersion("RELATIONSHIP_CLOSENESS_V4")
                .commercialAwardedScore(30)
                .cooperationScore(22)
                .strategicScore(18)
                .relationshipNetworkScore(9)
                .relationshipNetworkNote("Primary point of contact Director")
                .engagementScore(5)
                .qualitativeScore(5)
                .managerTotalScore(89)
                .managerRank("B")
                .build();

        when(assessmentRepository.findById(1008L)).thenReturn(Optional.of(submitted));
        when(assessmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Owner finalizes without specifying any overrides
        FinalizeRelationshipAssessmentRequest finReq = FinalizeRelationshipAssessmentRequest.builder().build();
        RelationshipAssessmentResponse finalized = service.finalizeAssessment(1008L, finReq, ownerUser);

        assertEquals(RelationshipAssessmentStatus.FINALIZED, finalized.getStatus());
        // All 6 owner scores are fully persisted and equal Manager values:
        assertEquals(30, finalized.getOwnerCommercialScore());
        assertEquals(22, finalized.getOwnerCooperationScore());
        assertEquals(18, finalized.getOwnerStrategicScore());
        assertEquals(9, finalized.getOwnerRelationshipNetworkScore());
        assertEquals(5, finalized.getOwnerEngagementScore());
        assertEquals(5, finalized.getOwnerQualitativeScore());
        assertEquals(89, finalized.getOwnerFinalTotalScore());
        assertEquals("B", finalized.getOwnerFinalRank());
    }

    @Test
    void testV4Finalize_OwnerOverrideRequiresReason() {
        CompanyRelationshipAssessment submitted = CompanyRelationshipAssessment.builder()
                .id(1009L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.SUBMITTED)
                .scoringPolicyVersion("RELATIONSHIP_CLOSENESS_V4")
                .commercialAwardedScore(30)
                .cooperationScore(20)
                .strategicScore(15)
                .relationshipNetworkScore(8)
                .engagementScore(4)
                .qualitativeScore(4)
                .build();

        when(assessmentRepository.findById(1009L)).thenReturn(Optional.of(submitted));

        // Owner modifies commercial score 30 -> 35 without reason
        FinalizeRelationshipAssessmentRequest finReq = FinalizeRelationshipAssessmentRequest.builder()
                .ownerCommercialScore(35)
                .build();

        assertThrows(BusinessValidationException.class, () ->
                service.finalizeAssessment(1009L, finReq, ownerUser));
    }

    @Test
    void testV4Finalize_OwnerOverrideWithReason_Succeeds() {
        CompanyRelationshipAssessment submitted = CompanyRelationshipAssessment.builder()
                .id(1010L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.SUBMITTED)
                .scoringPolicyVersion("RELATIONSHIP_CLOSENESS_V4")
                .commercialAwardedScore(30)
                .cooperationScore(20)
                .strategicScore(15)
                .relationshipNetworkScore(8)
                .engagementScore(4)
                .qualitativeScore(4)
                .build();

        when(assessmentRepository.findById(1010L)).thenReturn(Optional.of(submitted));
        when(assessmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Owner increases commercial 30 -> 35, cooperation 20 -> 24 with reason
        // Total = 35 + 24 + 15 + 8 + 4 + 4 = 90 -> Rank A
        FinalizeRelationshipAssessmentRequest finReq = FinalizeRelationshipAssessmentRequest.builder()
                .ownerCommercialScore(35)
                .ownerCooperationScore(24)
                .ownerAdjustmentReason("Expanded contract scope agreed at CEO summit")
                .build();

        RelationshipAssessmentResponse finalized = service.finalizeAssessment(1010L, finReq, ownerUser);
        assertEquals(RelationshipAssessmentStatus.FINALIZED, finalized.getStatus());
        assertEquals(35, finalized.getOwnerCommercialScore());
        assertEquals(24, finalized.getOwnerCooperationScore());
        assertEquals(15, finalized.getOwnerStrategicScore());
        assertEquals(8, finalized.getOwnerRelationshipNetworkScore());
        assertEquals(4, finalized.getOwnerEngagementScore());
        assertEquals(4, finalized.getOwnerQualitativeScore());
        assertEquals(90, finalized.getOwnerFinalTotalScore());
        assertEquals("A", finalized.getOwnerFinalRank());
        assertEquals("Expanded contract scope agreed at CEO summit", finalized.getOwnerAdjustmentReason());
    }

    // =========================================================================
    // V5 Unit Tests (Equal-Weight Guided Qualitative Scoring)
    // =========================================================================

    @Test
    void testV5Draft_AllCriteriaStartNull_AndReferenceOnly() {
        when(assessmentRepository.saveAndFlush(any())).thenAnswer(inv -> {
            CompanyRelationshipAssessment a = inv.getArgument(0);
            a.setId(1020L);
            return a;
        });

        RelationshipAssessmentResponse draft = service.createDraft(targetId, null, managerUser);
        assertNotNull(draft);
        assertEquals(RelationshipAssessmentStatus.DRAFT, draft.getStatus());
        assertEquals("RELATIONSHIP_CLOSENESS_V5", draft.getScoringPolicyVersion());
        assertEquals(6, draft.getTotalCriteriaCount());
        assertEquals(0, draft.getCompletedCriteriaCount());
        assertFalse(draft.getIsComplete());
        assertNull(draft.getDraftSubtotalScore());
        assertNull(draft.getCommercialScore());
        assertNull(draft.getCommercialSuggestedScore());
        assertNull(draft.getCommercialAwardedScore());
        assertNull(draft.getCooperationScore());
        assertNull(draft.getStrategicScore());
        assertNull(draft.getRelationshipNetworkScore());
        assertNull(draft.getEngagementScore());
        assertNull(draft.getQualitativeScore());
        assertNull(draft.getTrustScore());
        assertEquals("REFERENCE_ONLY", draft.getCommercialSuggestionStatus());
        assertNull(draft.getCommercialAvailablePoints());
    }

    @Test
    void testV5Draft_ValidationRanges_0To5() {
        // Valid 0 score
        CreateRelationshipAssessmentRequest reqZero = CreateRelationshipAssessmentRequest.builder()
                .commercialAwardedScore(0)
                .cooperationScore(0)
                .strategicScore(0)
                .relationshipNetworkScore(0)
                .engagementScore(0)
                .trustScore(0)
                .build();
        when(assessmentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        assertDoesNotThrow(() -> service.createDraft(targetId, reqZero, managerUser));

        // Invalid: score > 5
        CreateRelationshipAssessmentRequest reqTooHigh = CreateRelationshipAssessmentRequest.builder()
                .commercialAwardedScore(6)
                .build();
        BusinessValidationException ex1 = assertThrows(BusinessValidationException.class,
                () -> service.createDraft(targetId, reqTooHigh, managerUser));
        assertTrue(ex1.getMessage().contains("must be between 0 and 5"));

        // Invalid: score < 0
        CreateRelationshipAssessmentRequest reqNegative = CreateRelationshipAssessmentRequest.builder()
                .cooperationScore(-1)
                .build();
        BusinessValidationException ex2 = assertThrows(BusinessValidationException.class,
                () -> service.createDraft(targetId, reqNegative, managerUser));
        assertTrue(ex2.getMessage().contains("must be between 0 and 5"));
    }

    @Test
    void testV5Calculations_ExactThresholdsAndRanks() {
        // 30 -> 100 A
        var res30 = scoreCalculator.calculateV5(5, 5, 5, 5, 5, 5);
        assertEquals(30, res30.getRawScorableScore());
        assertEquals(100, res30.getNormalizedTotalScore());
        assertEquals(100.0, res30.getExactNormalizedScore(), 0.001);
        assertEquals(RelationshipAssessmentRank.A, res30.getRank());

        // 27 -> 90 A
        var res27 = scoreCalculator.calculateV5(5, 5, 5, 4, 4, 4);
        assertEquals(27, res27.getRawScorableScore());
        assertEquals(90, res27.getNormalizedTotalScore());
        assertEquals(90.0, res27.getExactNormalizedScore(), 0.001);
        assertEquals(RelationshipAssessmentRank.A, res27.getRank());

        // 26 -> 87 B (exact: 86.666...)
        var res26 = scoreCalculator.calculateV5(5, 5, 4, 4, 4, 4);
        assertEquals(26, res26.getRawScorableScore());
        assertEquals(87, res26.getNormalizedTotalScore());
        assertEquals(86.666, res26.getExactNormalizedScore(), 0.01);
        assertEquals(RelationshipAssessmentRank.B, res26.getRank());

        // 18 -> 60 B
        var res18 = scoreCalculator.calculateV5(3, 3, 3, 3, 3, 3);
        assertEquals(18, res18.getRawScorableScore());
        assertEquals(60, res18.getNormalizedTotalScore());
        assertEquals(60.0, res18.getExactNormalizedScore(), 0.001);
        assertEquals(RelationshipAssessmentRank.B, res18.getRank());

        // 17 -> 57 C (exact: 56.666...)
        var res17 = scoreCalculator.calculateV5(3, 3, 3, 3, 3, 2);
        assertEquals(17, res17.getRawScorableScore());
        assertEquals(57, res17.getNormalizedTotalScore());
        assertEquals(56.666, res17.getExactNormalizedScore(), 0.01);
        assertEquals(RelationshipAssessmentRank.C, res17.getRank());

        // 9 -> 30 C
        var res9 = scoreCalculator.calculateV5(2, 2, 2, 1, 1, 1);
        assertEquals(9, res9.getRawScorableScore());
        assertEquals(30, res9.getNormalizedTotalScore());
        assertEquals(30.0, res9.getExactNormalizedScore(), 0.001);
        assertEquals(RelationshipAssessmentRank.C, res9.getRank());

        // 8 -> 27 D (exact: 26.666...)
        var res8 = scoreCalculator.calculateV5(2, 2, 1, 1, 1, 1);
        assertEquals(8, res8.getRawScorableScore());
        assertEquals(27, res8.getNormalizedTotalScore());
        assertEquals(26.666, res8.getExactNormalizedScore(), 0.01);
        assertEquals(RelationshipAssessmentRank.D, res8.getRank());

        // 0 -> 0 D
        var res0 = scoreCalculator.calculateV5(0, 0, 0, 0, 0, 0);
        assertEquals(0, res0.getRawScorableScore());
        assertEquals(0, res0.getNormalizedTotalScore());
        assertEquals(0.0, res0.getExactNormalizedScore(), 0.001);
        assertEquals(RelationshipAssessmentRank.D, res0.getRank());
    }

    @Test
    void testV5Draft_IncompleteDraft_HasNullRankAndRawSubtotal() {
        CompanyRelationshipAssessment draft = CompanyRelationshipAssessment.builder()
                .id(1030L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.DRAFT)
                .scoringPolicyVersion("RELATIONSHIP_CLOSENESS_V5")
                .commercialAwardedScore(4)
                .cooperationScore(3)
                .strategicScore(2)
                .build();
        when(assessmentRepository.findById(1030L)).thenReturn(Optional.of(draft));
        when(assessmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateRelationshipAssessmentRequest updateReq = UpdateRelationshipAssessmentRequest.builder()
                .relationshipNetworkScore(3)
                .build();

        RelationshipAssessmentResponse response = service.updateDraft(1030L, updateReq, managerUser);
        assertEquals(4, response.getCompletedCriteriaCount());
        assertFalse(response.getIsComplete());
        // Raw subtotal = 4 + 3 + 2 + 3 = 12 (out of 30)
        assertEquals(12, response.getDraftSubtotalScore());
        // Rank must remain null when incomplete
        assertNull(response.getManagerRank());
    }

    @Test
    void testV5Submit_RequiresAllSixScoresAndAllSixNotes() {
        CompanyRelationshipAssessment draft = CompanyRelationshipAssessment.builder()
                .id(1040L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.DRAFT)
                .scoringPolicyVersion("RELATIONSHIP_CLOSENESS_V5")
                .commercialAwardedScore(4)
                .cooperationScore(4)
                .strategicScore(4)
                .relationshipNetworkScore(4)
                .engagementScore(4)
                // trustScore / qualitativeScore missing!
                .commercialEvidenceNote("Commercial note")
                .cooperationEvidenceNote("Coop note")
                .strategicEvidenceNote("Strat note")
                .relationshipNetworkNote("Net note")
                .engagementEvidenceNote("Eng note")
                .qualitativeEvidenceNote("Trust note")
                .managerNote("Overall note")
                .build();
        when(assessmentRepository.findById(1040L)).thenReturn(Optional.of(draft));

        BusinessValidationException exScore = assertThrows(BusinessValidationException.class,
                () -> service.submitAssessment(1040L, managerUser));
        assertTrue(exScore.getMessage().contains("Trust & Reliability score is required"));

        // Now add the missing score, but omit an evidence note
        draft.setQualitativeScore(4);
        draft.setQualitativeEvidenceNote(""); // Empty note

        BusinessValidationException exNote = assertThrows(BusinessValidationException.class,
                () -> service.submitAssessment(1040L, managerUser));
        assertTrue(exNote.getMessage().contains("Trust & Reliability evidence note is required"));

        // Add trust note, but omit managerNote
        draft.setQualitativeEvidenceNote("Solid partner track record");
        draft.setManagerNote("");

        BusinessValidationException exMgrNote = assertThrows(BusinessValidationException.class,
                () -> service.submitAssessment(1040L, managerUser));
        assertTrue(exMgrNote.getMessage().contains("Overall Assessment Note is required"));
    }

    @Test
    void testV5OwnerFinalize_UnchangedCopiesManager_ModifiedRequiresReason() {
        CompanyRelationshipAssessment submitted = CompanyRelationshipAssessment.builder()
                .id(1050L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.SUBMITTED)
                .scoringPolicyVersion("RELATIONSHIP_CLOSENESS_V5")
                .commercialAwardedScore(4)
                .cooperationScore(4)
                .strategicScore(4)
                .relationshipNetworkScore(4)
                .engagementScore(4)
                .qualitativeScore(4) // 24 / 30 -> 80% -> B
                .managerRawScorableScore(24)
                .managerTotalScore(80)
                .managerRank("B")
                .relationshipNetworkNote("Manager network note")
                .build();
        when(assessmentRepository.findById(1050L)).thenReturn(Optional.of(submitted));
        when(assessmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Case 1: Unchanged Owner finalize (empty request)
        FinalizeRelationshipAssessmentRequest finReqEmpty = FinalizeRelationshipAssessmentRequest.builder().build();
        RelationshipAssessmentResponse finalized = service.finalizeAssessment(1050L, finReqEmpty, ownerUser);

        assertEquals(RelationshipAssessmentStatus.FINALIZED, finalized.getStatus());
        assertEquals(4, finalized.getOwnerCommercialScore());
        assertEquals(4, finalized.getOwnerCooperationScore());
        assertEquals(4, finalized.getOwnerStrategicScore());
        assertEquals(4, finalized.getOwnerRelationshipNetworkScore());
        assertEquals(4, finalized.getOwnerEngagementScore());
        assertEquals(4, finalized.getOwnerQualitativeScore());
        assertEquals(4, finalized.getOwnerTrustScore());
        assertEquals(24, finalized.getOwnerRawScorableScore());
        assertEquals(80, finalized.getOwnerFinalTotalScore());
        assertEquals("B", finalized.getOwnerFinalRank());

        // Reset status for Case 2
        submitted.setStatus(RelationshipAssessmentStatus.SUBMITTED);

        // Case 2: Modified score without reason -> rejected
        FinalizeRelationshipAssessmentRequest finReqNoReason = FinalizeRelationshipAssessmentRequest.builder()
                .ownerTrustScore(5)
                .build();
        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> service.finalizeAssessment(1050L, finReqNoReason, ownerUser));
        assertTrue(ex.getMessage().contains("Owner Adjustment Reason is mandatory"));

        // Reset status for Case 3
        submitted.setStatus(RelationshipAssessmentStatus.SUBMITTED);

        // Case 3: Modified score with reason -> accepted
        // 4 + 4 + 4 + 4 + 4 + 5 = 25 / 30 -> 83% -> B
        FinalizeRelationshipAssessmentRequest finReqWithReason = FinalizeRelationshipAssessmentRequest.builder()
                .ownerTrustScore(5)
                .ownerAdjustmentReason("Exceptional multi-year performance verified by executive board")
                .build();
        RelationshipAssessmentResponse finalized2 = service.finalizeAssessment(1050L, finReqWithReason, ownerUser);
        assertEquals(5, finalized2.getOwnerTrustScore());
        assertEquals(5, finalized2.getOwnerQualitativeScore());
        assertEquals(25, finalized2.getOwnerRawScorableScore());
        assertEquals(83, finalized2.getOwnerFinalTotalScore());
        assertEquals("B", finalized2.getOwnerFinalRank());
    }

    @Test
    void testV5TrustScoreAlias_ConflictingValuesRejected() {
        CreateRelationshipAssessmentRequest conflictReq = CreateRelationshipAssessmentRequest.builder()
                .qualitativeScore(3)
                .trustScore(4) // Mismatch!
                .build();
        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> service.createDraft(targetId, conflictReq, managerUser));
        assertTrue(ex.getMessage().contains("Conflicting values provided for qualitativeScore and trustScore"));

        CreateRelationshipAssessmentRequest conflictNoteReq = CreateRelationshipAssessmentRequest.builder()
                .qualitativeEvidenceNote("Note A")
                .trustEvidenceNote("Note B") // Mismatch!
                .build();
        BusinessValidationException exNote = assertThrows(BusinessValidationException.class,
                () -> service.createDraft(targetId, conflictNoteReq, managerUser));
        assertTrue(exNote.getMessage().contains("Conflicting values provided for qualitativeEvidenceNote and trustEvidenceNote"));
    }

    @Test
    void testCreateDraft_WithZeroApprovedContracts_SetsContractCountScoreToZero_AndSucceeds() {
        when(partnerContractRepository.findByPartnerCompanyIdAndReviewStatus(targetId, ContractReviewStatus.APPROVED))
                .thenReturn(List.of());
        when(assessmentRepository.existsByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusIn(eq(ownerId), eq(targetId), any()))
                .thenReturn(false);
        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdOrderByVersionNumberDesc(ownerId, targetId))
                .thenReturn(Optional.empty());

        final CompanyRelationshipAssessment[] savedHolder = new CompanyRelationshipAssessment[1];
        when(assessmentRepository.saveAndFlush(any())).thenAnswer(inv -> {
            CompanyRelationshipAssessment a = inv.getArgument(0);
            savedHolder[0] = a;
            a.setId(101L);
            return a;
        });

        RelationshipAssessmentResponse response = service.createDraft(targetId, null, managerUser);
        assertNotNull(response);
        assertNotNull(savedHolder[0]);
        assertNotNull(savedHolder[0].getContractCountScore(), "contract_count_score column must never be NULL");
        assertEquals(0, savedHolder[0].getContractCountScore());
        assertEquals(0, savedHolder[0].getApprovedContractCount());
    }

    @Test
    void testCreateDraft_DataIntegrityViolation_NonUniqueConstraint_RethrowsException() {
        when(partnerContractRepository.findByPartnerCompanyIdAndReviewStatus(targetId, ContractReviewStatus.APPROVED))
                .thenReturn(List.of());
        when(assessmentRepository.existsByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusIn(eq(ownerId), eq(targetId), any()))
                .thenReturn(false);
        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdOrderByVersionNumberDesc(ownerId, targetId))
                .thenReturn(Optional.empty());

        when(assessmentRepository.saveAndFlush(any())).thenThrow(
                new org.springframework.dao.DataIntegrityViolationException(
                        "Cannot insert the value NULL into column 'contract_count_score', table 'apms.dbo.company_relationship_assessments'; column does not allow nulls."));

        org.springframework.dao.DataIntegrityViolationException ex = assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> service.createDraft(targetId, null, managerUser));
        assertTrue(ex.getMessage().contains("Cannot insert the value NULL into column 'contract_count_score'"));
    }

    @Test
    void testCreateDraft_DataIntegrityViolation_UniqueConstraint_ThrowsConcurrentAssessmentException() {
        when(partnerContractRepository.findByPartnerCompanyIdAndReviewStatus(targetId, ContractReviewStatus.APPROVED))
                .thenReturn(List.of());
        when(assessmentRepository.existsByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusIn(eq(ownerId), eq(targetId), any()))
                .thenReturn(false);
        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdOrderByVersionNumberDesc(ownerId, targetId))
                .thenReturn(Optional.empty());

        when(assessmentRepository.saveAndFlush(any())).thenThrow(
                new org.springframework.dao.DataIntegrityViolationException(
                        "Cannot insert duplicate key row in object 'dbo.company_relationship_assessments' with unique index 'uq_active_company_relationship_assessment'."));

        BusinessValidationException ex = assertThrows(
                BusinessValidationException.class,
                () -> service.createDraft(targetId, null, managerUser));
        assertTrue(ex.getMessage().contains("An active assessment was created concurrently"));
    }

    @Test
    void testUpdateDraftV5_SupportsPartialDraft_ExplicitScoreZero_AndClearing() {
        CompanyRelationshipAssessment existing = new CompanyRelationshipAssessment();
        existing.setId(200L);
        existing.setOwnerCompanyProfileId(ownerId);
        existing.setCompanyProfileId(targetId);
        existing.setStatus(RelationshipAssessmentStatus.DRAFT);
        existing.setScoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5);
        existing.setVersionNumber(6);
        existing.setCommercialAwardedScore(4);
        existing.setCommercialEvidenceNote("Initial note");
        existing.setCooperationScore(3);
        existing.setManagerNote("Historical Manager note");

        when(assessmentRepository.findById(200L)).thenReturn(Optional.of(existing));
        when(assessmentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // Step 1: Update draft with Commercial = 0, Cooperation = null (cleared), Note cleared, managerNote omitted
        UpdateRelationshipAssessmentRequest req = UpdateRelationshipAssessmentRequest.builder()
                .fullSnapshot(true)
                .commercialAwardedScore(0)
                .commercialEvidenceNote(null)
                .cooperationScore(null)
                .strategicScore(5)
                .strategicEvidenceNote("Strategic note")
                .relationshipNetworkScore(null)
                .engagementScore(null)
                .qualitativeScore(null)
                .managerNote(null)
                .build();

        RelationshipAssessmentResponse resp = service.updateDraft(200L, req, managerUser);

        // Commercial score must be explicitly 0, not null
        assertEquals(0, resp.getCommercialAwardedScore());
        assertNull(resp.getCommercialEvidenceNote());
        // Cooperation was cleared to null
        assertNull(resp.getCooperationScore());
        // Strategic is 5
        assertEquals(5, resp.getStrategicScore());
        assertEquals("Strategic note", resp.getStrategicEvidenceNote());
        // Incomplete draft -> rank and total score preview are null
        assertNull(resp.getManagerRank());
        assertNull(resp.getManagerTotalScore());
        // managerNote was omitted -> historical note preserved
        assertEquals("Historical Manager note", resp.getManagerNote());
    }

    @Test
    void testEligibility_CompetitorThrowsBusinessValidationException() {
        CompanyProfile profile = CompanyProfile.builder()
                .id(targetId)
                .companyId(targetId)
                .identity(CompanyProfile.Identity.builder().tradeName("Competitor Company").build())
                .build();
        when(companyProfileRepository.findById(targetId)).thenReturn(Optional.of(profile));
        when(ownerOrganizationService.isOwnerCompany(targetId)).thenReturn(false);
        when(accessEvaluator.resolveRelationshipType(targetId)).thenReturn("COMPETITOR_OF");
        when(accessEvaluator.isEligibleRelationshipType("COMPETITOR_OF")).thenReturn(false);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                service.createDraft(targetId, null, managerUser));

        assertEquals("Relationship Closeness assessment is not available for this company relationship type.", ex.getMessage());
    }

    @Test
    void testEligibility_NullOrUnsupportedThrowsBusinessValidationException() {
        CompanyProfile profile = CompanyProfile.builder()
                .id(targetId)
                .companyId(targetId)
                .identity(CompanyProfile.Identity.builder().tradeName("Unknown Company").build())
                .build();
        when(companyProfileRepository.findById(targetId)).thenReturn(Optional.of(profile));
        when(ownerOrganizationService.isOwnerCompany(targetId)).thenReturn(false);
        when(accessEvaluator.resolveRelationshipType(targetId)).thenReturn(null);
        when(accessEvaluator.isEligibleRelationshipType(null)).thenReturn(false);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                service.createDraft(targetId, null, managerUser));

        assertEquals("Relationship Closeness assessment is not available for this company relationship type.", ex.getMessage());
    }

    @Test
    void testEligibility_CustomerAndSupplierAllowed() {
        CompanyProfile profile = CompanyProfile.builder()
                .id(targetId)
                .companyId(targetId)
                .identity(CompanyProfile.Identity.builder().tradeName("Customer Company").build())
                .build();
        when(companyProfileRepository.findById(targetId)).thenReturn(Optional.of(profile));
        when(ownerOrganizationService.isOwnerCompany(targetId)).thenReturn(false);

        // CUSTOMER_OF allowed
        when(accessEvaluator.resolveRelationshipType(targetId)).thenReturn("CUSTOMER_OF");
        when(accessEvaluator.isEligibleRelationshipType("CUSTOMER_OF")).thenReturn(true);
        assertDoesNotThrow(() -> service.validateRelationshipClosenessEligibility(targetId));

        // SUPPLIER allowed
        when(accessEvaluator.resolveRelationshipType(targetId)).thenReturn("SUPPLIER");
        when(accessEvaluator.isEligibleRelationshipType("SUPPLIER")).thenReturn(true);
        assertDoesNotThrow(() -> service.validateRelationshipClosenessEligibility(targetId));
    }

    @Test
    void testEligibility_UpdateDraft_CompetitorThrows() {
        CompanyRelationshipAssessment draft = CompanyRelationshipAssessment.builder()
                .id(999L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.DRAFT)
                .scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5)
                .build();
        when(assessmentRepository.findById(999L)).thenReturn(Optional.of(draft));

        CompanyProfile profile = CompanyProfile.builder()
                .id(targetId)
                .companyId(targetId)
                .build();
        when(companyProfileRepository.findById(targetId)).thenReturn(Optional.of(profile));
        when(ownerOrganizationService.isOwnerCompany(targetId)).thenReturn(false);
        when(accessEvaluator.resolveRelationshipType(targetId)).thenReturn("COMPETITOR");
        when(accessEvaluator.isEligibleRelationshipType("COMPETITOR")).thenReturn(false);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                service.updateDraft(999L, UpdateRelationshipAssessmentRequest.builder().build(), managerUser));

        assertEquals("Relationship Closeness assessment is not available for this company relationship type.", ex.getMessage());
    }

    @Test
    void testRecentAssessmentsSummary_FirstAssessment_NewlyScored() {
        when(ownerOrganizationService.getOwnerCompanyProfileId()).thenReturn(ownerId);

        CompanyRelationshipAssessment v1 = CompanyRelationshipAssessment.builder()
                .id(101L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.FINALIZED)
                .assessmentType(RelationshipAssessmentType.MANAGER_ASSESSMENT)
                .managerTotalScore(74)
                .managerRank("B")
                .finalizedAt(java.time.LocalDateTime.now().minusHours(2))
                .commercialAwardedScore(25)
                .cooperationScore(4)
                .strategicScore(4)
                .relationshipNetworkScore(3)
                .engagementScore(4)
                .qualitativeScore(4)
                .build();

        when(assessmentRepository.findTop2FinalizedPerCompany(ownerId)).thenReturn(List.of(v1));

        CompanyProfile profile = CompanyProfile.builder()
                .id(targetId)
                .companyId(targetId)
                .identity(CompanyProfile.Identity.builder().tradeName("Vietnam Airlines").build())
                .build();
        when(companyProfileRepository.findAllById(anySet())).thenReturn(List.of(profile));
        when(accessEvaluator.resolveRelationshipType(targetId)).thenReturn("PARTNER");

        List<CompanyRecentAssessmentSummaryDto> summaries = service.getRecentAssessmentsSummary(ownerUser);

        assertNotNull(summaries);
        assertEquals(1, summaries.size());
        CompanyRecentAssessmentSummaryDto summary = summaries.get(0);
        assertEquals(targetId, summary.getCompanyProfileId());
        assertEquals("Vietnam Airlines", summary.getCompanyName());
        assertEquals("PARTNER", summary.getRelationshipType());

        assertNotNull(summary.getLatestAssessment());
        assertEquals(1, summary.getLatestAssessment().getVersionNumber());
        assertEquals(74, summary.getLatestAssessment().getScore());
        assertEquals("B", summary.getLatestAssessment().getRank());
        assertEquals("BUSINESS_DEVELOPMENT_MANAGER", summary.getLatestAssessment().getActorRole());
        assertNotNull(summary.getLatestAssessment().getFinalizedAt());
        assertTrue(summary.getLatestAssessment().getFinalizedAt().contains("+07:00"));

        assertNull(summary.getPreviousAssessment());
    }

    @Test
    void testRecentAssessmentsSummary_TwoAssessments_TrendDelta() {
        when(ownerOrganizationService.getOwnerCompanyProfileId()).thenReturn(ownerId);

        CompanyRelationshipAssessment v1 = CompanyRelationshipAssessment.builder()
                .id(101L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.FINALIZED)
                .assessmentType(RelationshipAssessmentType.MANAGER_ASSESSMENT)
                .managerTotalScore(74)
                .managerRank("B")
                .finalizedAt(java.time.LocalDateTime.now().minusHours(24))
                .commercialAwardedScore(25)
                .build();

        CompanyRelationshipAssessment v2 = CompanyRelationshipAssessment.builder()
                .id(102L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(2)
                .status(RelationshipAssessmentStatus.FINALIZED)
                .assessmentType(RelationshipAssessmentType.OWNER_ADJUSTMENT)
                .ownerFinalTotalScore(82)
                .ownerFinalRank("A")
                .finalizedAt(java.time.LocalDateTime.now().minusHours(1))
                .ownerCommercialScore(28)
                .build();

        when(assessmentRepository.findTop2FinalizedPerCompany(ownerId)).thenReturn(List.of(v2, v1));

        CompanyProfile profile = CompanyProfile.builder()
                .id(targetId)
                .companyId(targetId)
                .identity(CompanyProfile.Identity.builder().tradeName("Vingroup").build())
                .build();
        when(companyProfileRepository.findAllById(anySet())).thenReturn(List.of(profile));
        when(accessEvaluator.resolveRelationshipType(targetId)).thenReturn("PARTNER");

        List<CompanyRecentAssessmentSummaryDto> summaries = service.getRecentAssessmentsSummary(ownerUser);

        assertNotNull(summaries);
        assertEquals(1, summaries.size());
        CompanyRecentAssessmentSummaryDto summary = summaries.get(0);

        assertEquals(82, summary.getLatestAssessment().getScore());
        assertEquals("A", summary.getLatestAssessment().getRank());
        assertEquals("BUSINESS_OWNER", summary.getLatestAssessment().getActorRole());

        assertNotNull(summary.getPreviousAssessment());
        assertEquals(1, summary.getPreviousAssessment().getVersionNumber());
        assertEquals(74, summary.getPreviousAssessment().getScore());
        assertEquals("B", summary.getPreviousAssessment().getRank());
    }
}

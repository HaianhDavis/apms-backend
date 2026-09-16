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
import com.apms.domain.profile.assessment.dto.RelationshipAssessmentResponse;
import com.apms.domain.profile.assessment.dto.UpdateRelationshipAssessmentRequest;
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
public class CompanyRelationshipAssessmentCompleteTest {

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

        // Default: users in test are in scope
        lenient().when(projectRepository.existsByTargetCompanyProfileIdAndMembersAccountIdAndStatusIn(eq(targetId), anyLong(), any()))
                .thenReturn(true);
    }

    private CompanyRelationshipAssessment buildDraftV5(Long id) {
        return CompanyRelationshipAssessment.builder()
                .id(id)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.DRAFT)
                .scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5)
                .commercialAwardedScore(4)
                .commercialEvidenceNote("Strong commercial transaction history")
                .cooperationScore(4)
                .cooperationEvidenceNote("Good coordination across teams")
                .strategicScore(4)
                .strategicEvidenceNote("High strategic alignment")
                .relationshipNetworkScore(3)
                .relationshipNetworkNote("Direct contact with director")
                .engagementScore(4)
                .engagementEvidenceNote("Active quarterly meetings")
                .qualitativeScore(4)
                .qualitativeEvidenceNote("High reliability and trust")
                .managerNote("Overall solid relationship")
                .createdByAccountId(10L)
                .build();
    }

    @Test
    void testManagerCanCompleteV5Draft_PersistsAtomicallyAndFinalizes() {
        CompanyRelationshipAssessment draft = buildDraftV5(100L);
        when(assessmentRepository.findById(100L)).thenReturn(Optional.of(draft));
        when(assessmentRepository.save(any(CompanyRelationshipAssessment.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateRelationshipAssessmentRequest finalPayload = UpdateRelationshipAssessmentRequest.builder()
                .commercialAwardedScore(5)
                .commercialEvidenceNote("Updated commercial note: major contract signed")
                .cooperationScore(5)
                .cooperationEvidenceNote("Excellent daily coordination")
                .strategicScore(5)
                .strategicEvidenceNote("Core partner")
                .relationshipNetworkScore(5)
                .relationshipNetworkNote("Board level contact")
                .engagementScore(5)
                .engagementEvidenceNote("Weekly engagements")
                .trustScore(5)
                .trustEvidenceNote("Utmost trust")
                .managerNote("Final overall manager evaluation")
                .build();

        RelationshipAssessmentResponse response = service.completeAssessment(100L, finalPayload, managerUser);

        assertNotNull(response);
        assertEquals(RelationshipAssessmentStatus.FINALIZED, response.getStatus());
        assertEquals(5, response.getCommercialAwardedScore());
        assertEquals(5, response.getCooperationScore());
        assertEquals(5, response.getStrategicScore());
        assertEquals(5, response.getRelationshipNetworkScore());
        assertEquals(5, response.getEngagementScore());
        assertEquals(5, response.getTrustScore());
        assertEquals("Final overall manager evaluation", response.getManagerNote());

        // 30 / 30 = 100% => Rank A
        assertEquals(30, response.getManagerRawScorableScore());
        assertEquals(100, response.getManagerTotalScore());
        assertEquals("A", response.getManagerRank());

        // V5 Policy-Aware Official Score Fallback
        assertEquals(100, response.getOfficialScore());
        assertEquals("A", response.getOfficialRank());
        assertTrue(response.getIsOfficialFinalized());

        // Owner fields remain null, Manager identity preserved
        assertNull(response.getOwnerAccountId());
        assertNull(response.getOwnerFinalTotalScore());
        assertNull(response.getOwnerFinalRank());
        assertEquals(10L, response.getManagerAccountId());
        assertNotNull(response.getFinalizedAt());
        assertNull(response.getManagerSubmittedAt());

        // Verify audit log
        verify(auditLogService).log(eq(10L), any(), eq("CompanyRelationshipAssessment"), eq("100"),
                contains("DIRECT_MANAGER_COMPLETION"));
    }

    @Test
    void testManagerCannotCompleteV5Submitted_ThroughDirectEndpoint() {
        CompanyRelationshipAssessment submitted = buildDraftV5(101L);
        submitted.setStatus(RelationshipAssessmentStatus.SUBMITTED);
        when(assessmentRepository.findById(101L)).thenReturn(Optional.of(submitted));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                service.completeAssessment(101L, null, managerUser));
        assertTrue(ex.getMessage().contains("Only DRAFT assessments can be completed"));
    }

    @Test
    void testManagerCannotCompleteChangesRequested_ThroughDirectEndpoint() {
        CompanyRelationshipAssessment changesRequested = buildDraftV5(102L);
        changesRequested.setStatus(RelationshipAssessmentStatus.CHANGES_REQUESTED);
        when(assessmentRepository.findById(102L)).thenReturn(Optional.of(changesRequested));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                service.completeAssessment(102L, null, managerUser));
        assertTrue(ex.getMessage().contains("Only DRAFT assessments can be completed"));
    }

    @Test
    void testNonManagerCannotCallComplete() {
        CompanyRelationshipAssessment draft = buildDraftV5(103L);
        when(assessmentRepository.findById(103L)).thenReturn(Optional.of(draft));

        assertThrows(AccessDeniedException.class, () ->
                service.completeAssessment(103L, null, staffUser));

        assertThrows(AccessDeniedException.class, () ->
                service.completeAssessment(103L, null, ownerUser));
    }

    @Test
    void testCompletionSucceeds_WhenAllNotesNull() {
        CompanyRelationshipAssessment draft = buildDraftV5(104L);
        draft.setCommercialEvidenceNote(null);
        draft.setCooperationEvidenceNote(null);
        draft.setStrategicEvidenceNote(null);
        draft.setRelationshipNetworkNote(null);
        draft.setEngagementEvidenceNote(null);
        draft.setQualitativeEvidenceNote(null);
        draft.setManagerNote(null);
        when(assessmentRepository.findById(104L)).thenReturn(Optional.of(draft));
        when(assessmentRepository.save(any(CompanyRelationshipAssessment.class))).thenAnswer(inv -> inv.getArgument(0));

        RelationshipAssessmentResponse response = service.completeAssessment(104L, null, managerUser);
        assertNotNull(response);
        assertEquals(RelationshipAssessmentStatus.FINALIZED, response.getStatus());
        assertNull(response.getCommercialEvidenceNote());
        assertNull(response.getManagerNote());
    }

    @Test
    void testCompletionSucceeds_WhenAllNotesEmpty() {
        CompanyRelationshipAssessment draft = buildDraftV5(105L);
        when(assessmentRepository.findById(105L)).thenReturn(Optional.of(draft));
        when(assessmentRepository.save(any(CompanyRelationshipAssessment.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateRelationshipAssessmentRequest emptyNotesReq = UpdateRelationshipAssessmentRequest.builder()
                .commercialAwardedScore(4)
                .commercialEvidenceNote("")
                .cooperationScore(4)
                .cooperationEvidenceNote("   ")
                .strategicScore(4)
                .strategicEvidenceNote("")
                .relationshipNetworkScore(3)
                .relationshipNetworkNote("")
                .engagementScore(4)
                .engagementEvidenceNote("")
                .trustScore(4)
                .trustEvidenceNote("")
                .managerNote("")
                .build();

        RelationshipAssessmentResponse response = service.completeAssessment(105L, emptyNotesReq, managerUser);
        assertNotNull(response);
        assertEquals(RelationshipAssessmentStatus.FINALIZED, response.getStatus());
    }

    @Test
    void testCompletionSucceeds_WithExplicitZeroScore_AndCalculatesCorrectly() {
        CompanyRelationshipAssessment draft = buildDraftV5(106L);
        when(assessmentRepository.findById(106L)).thenReturn(Optional.of(draft));
        when(assessmentRepository.save(any(CompanyRelationshipAssessment.class))).thenAnswer(inv -> inv.getArgument(0));

        // 0, 3, 2, 4, 1, 3 => raw = 13 / 30 => exact = 43.333% => 43 / 100 => Rank D
        UpdateRelationshipAssessmentRequest zeroScoreReq = UpdateRelationshipAssessmentRequest.builder()
                .commercialAwardedScore(0)
                .cooperationScore(3)
                .strategicScore(2)
                .relationshipNetworkScore(4)
                .engagementScore(1)
                .trustScore(3)
                .build();

        RelationshipAssessmentResponse response = service.completeAssessment(106L, zeroScoreReq, managerUser);
        assertNotNull(response);
        assertEquals(RelationshipAssessmentStatus.FINALIZED, response.getStatus());
        assertEquals(0, response.getCommercialAwardedScore());
        assertEquals(13, response.getManagerRawScorableScore());
        assertEquals(43, response.getManagerTotalScore());
        assertEquals(43, response.getOfficialScore());
    }

    @Test
    void testCompletionFails_WhenOneScoreNull() {
        CompanyRelationshipAssessment draft = buildDraftV5(107L);
        draft.setCommercialAwardedScore(null);
        when(assessmentRepository.findById(107L)).thenReturn(Optional.of(draft));

        assertThrows(BusinessValidationException.class, () ->
                service.completeAssessment(107L, null, managerUser));
    }

    @Test
    void testCompletionFails_WhenScoreGreaterThan5() {
        CompanyRelationshipAssessment draft = buildDraftV5(108L);
        when(assessmentRepository.findById(108L)).thenReturn(Optional.of(draft));

        UpdateRelationshipAssessmentRequest outOfBoundsReq = UpdateRelationshipAssessmentRequest.builder()
                .cooperationScore(6)
                .build();

        assertThrows(BusinessValidationException.class, () ->
                service.completeAssessment(108L, outOfBoundsReq, managerUser));
    }

    @Test
    void testCompletionFails_WhenScoreNegative() {
        CompanyRelationshipAssessment draft = buildDraftV5(109L);
        when(assessmentRepository.findById(109L)).thenReturn(Optional.of(draft));

        UpdateRelationshipAssessmentRequest negativeReq = UpdateRelationshipAssessmentRequest.builder()
                .trustScore(-1)
                .build();

        assertThrows(BusinessValidationException.class, () ->
                service.completeAssessment(109L, negativeReq, managerUser));
    }

    @Test
    void testSuppliedOptionalNotes_ArePreserved() {
        CompanyRelationshipAssessment draft = buildDraftV5(110L);
        when(assessmentRepository.findById(110L)).thenReturn(Optional.of(draft));
        when(assessmentRepository.save(any(CompanyRelationshipAssessment.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateRelationshipAssessmentRequest notesReq = UpdateRelationshipAssessmentRequest.builder()
                .commercialAwardedScore(5)
                .commercialEvidenceNote("Special commercial partnership note")
                .cooperationScore(5)
                .strategicScore(5)
                .relationshipNetworkScore(5)
                .engagementScore(5)
                .trustScore(5)
                .managerNote("Executive summary note")
                .build();

        RelationshipAssessmentResponse response = service.completeAssessment(110L, notesReq, managerUser);
        assertNotNull(response);
        assertEquals("Special commercial partnership note", response.getCommercialEvidenceNote());
        assertEquals("Executive summary note", response.getManagerNote());
    }

    @Test
    void testCanCompletePermission_OnDraftForManager() {
        CompanyRelationshipAssessment draft = buildDraftV5(111L);
        when(assessmentRepository.findById(111L)).thenReturn(Optional.of(draft));

        RelationshipAssessmentResponse managerResp = service.getAssessmentById(111L, managerUser);
        assertTrue(managerResp.getCanComplete());

        RelationshipAssessmentResponse staffResp = service.getAssessmentById(111L, staffUser);
        assertFalse(staffResp.getCanComplete());
    }

    @Test
    void testHistoricalV3Policy_DoesNotUseManagerScoreAsOfficial_WhenNotFinalizedByOwner() {
        CompanyRelationshipAssessment v3 = CompanyRelationshipAssessment.builder()
                .id(200L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.FINALIZED)
                .scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V3)
                .commercialAwardedScore(40)
                .engagementScore(15)
                .relationshipNetworkScore(25)
                .managerTotalScore(80)
                .managerRank("B")
                .ownerFinalTotalScore(null)
                .ownerFinalRank(null)
                .build();

        when(assessmentRepository.findById(200L)).thenReturn(Optional.of(v3));

        RelationshipAssessmentResponse resp = service.getAssessmentById(200L, managerUser);
        // On historical V3, official score is ownerFinalTotalScore (null here)
        assertNull(resp.getOfficialScore());
        assertNull(resp.getOfficialRank());
        assertFalse(resp.getIsOfficialFinalized());
    }

    @Test
    void testFinalizedV5CanCreateV2_WhenPermissionAllows() {
        CompanyRelationshipAssessment v1Finalized = buildDraftV5(300L);
        v1Finalized.setStatus(RelationshipAssessmentStatus.FINALIZED);
        v1Finalized.setManagerTotalScore(75);
        v1Finalized.setManagerRank("B");
        v1Finalized.setFinalizedAt(java.time.LocalDateTime.now());

        when(assessmentRepository.findById(300L)).thenReturn(Optional.of(v1Finalized));

        RelationshipAssessmentResponse resp = service.getAssessmentById(300L, managerUser);
        assertTrue(resp.getCanCreateNewVersion());
    }
}

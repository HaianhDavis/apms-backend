package com.apms.domain.profile.assessment;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SystemRole;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.contract.entity.PartnerContract;
import com.apms.domain.contract.enums.ContractReviewStatus;
import com.apms.domain.contract.repository.sql.PartnerContractRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.assessment.dto.OwnerAdjustmentUpdateRequest;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CompanyRelationshipAssessmentOwnerAdjustmentTest {

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
        lenient().when(ownerOrganizationService.getOwnerCompanyProfileId()).thenReturn(ownerId);

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

        CompanyProfile profile = new CompanyProfile();
        profile.setId(targetId);
        profile.setCompanyId(targetId);
        lenient().when(companyProfileRepository.findById(targetId)).thenReturn(Optional.of(profile));
    }

    private CompanyRelationshipAssessment buildFinalizedV6() {
        return CompanyRelationshipAssessment.builder()
                .id(600L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(6)
                .status(RelationshipAssessmentStatus.FINALIZED)
                .assessmentType(RelationshipAssessmentType.MANAGER_ASSESSMENT)
                .scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5)
                .approvedContractCount(5)
                .totalContractValueVnd(new BigDecimal("10000000000"))
                .contractValueScore(4)
                .commercialAwardedScore(5)
                .cooperationScore(4)
                .strategicScore(3)
                .relationshipNetworkScore(2)
                .engagementScore(4)
                .qualitativeScore(4)
                .managerRawScorableScore(22)
                .managerTotalScore(73)
                .managerRank("B")
                .managerAccountId(10L)
                .finalizedAt(LocalDateTime.now().minusDays(1))
                .createdByAccountId(10L)
                .build();
    }

    /**
     * Case 1: V6 FINALIZED -> V7 Owner Adjustment CANCELLED -> next version must be V8
     */
    @Test
    void testVersionNumberGeneration_SkipsCancelledVersion() {
        CompanyRelationshipAssessment v6 = buildFinalizedV6();
        CompanyRelationshipAssessment v7Cancelled = CompanyRelationshipAssessment.builder()
                .id(700L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(7)
                .status(RelationshipAssessmentStatus.CANCELLED)
                .assessmentType(RelationshipAssessmentType.OWNER_ADJUSTMENT)
                .sourceAssessmentId(600L)
                .build();

        when(assessmentRepository.findById(600L)).thenReturn(Optional.of(v6));
        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
                ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.of(v6));
        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdOrderByVersionNumberDesc(
                ownerId, targetId)).thenReturn(Optional.of(v7Cancelled));
        when(assessmentRepository.existsByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusIn(eq(ownerId), eq(targetId), any()))
                .thenReturn(false);

        when(assessmentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        RelationshipAssessmentResponse response = service.createOwnerAdjustment(600L, ownerUser);
        assertEquals(8, response.getVersionNumber(), "Next version after V7 CANCELLED must be V8");
    }

    /**
     * Case 2: Historical finalized V5 -> Owner attempts adjustment -> rejected because V5 is not latest official
     */
    @Test
    void testHistoricalVersion_OwnerAdjustmentRejected_WhenNotLatestOfficial() {
        CompanyRelationshipAssessment v5 = CompanyRelationshipAssessment.builder()
                .id(500L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(5)
                .status(RelationshipAssessmentStatus.FINALIZED)
                .build();

        CompanyRelationshipAssessment v6 = buildFinalizedV6();

        when(assessmentRepository.findById(500L)).thenReturn(Optional.of(v5));
        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
                ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.of(v6));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                service.createOwnerAdjustment(500L, ownerUser)
        );
        assertTrue(ex.getMessage().contains("latest official finalized assessment"));
    }

    /**
     * Case 3: Owner adjustment creation -> evidence snapshot equals source V6 snapshot -> no live contract refresh
     */
    @Test
    void testOwnerAdjustment_CopiesEvidenceSnapshotFromSource_NoContractRefresh() {
        CompanyRelationshipAssessment v6 = buildFinalizedV6();
        when(assessmentRepository.findById(600L)).thenReturn(Optional.of(v6));
        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
                ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.of(v6));
        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdOrderByVersionNumberDesc(
                ownerId, targetId)).thenReturn(Optional.of(v6));
        when(assessmentRepository.existsByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusIn(eq(ownerId), eq(targetId), any()))
                .thenReturn(false);

        when(assessmentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        RelationshipAssessmentResponse resp = service.createOwnerAdjustment(600L, ownerUser);

        // Verify evidence snapshot is exactly identical to source V6
        assertEquals(v6.getApprovedContractCount(), resp.getApprovedContractCount());
        assertEquals(v6.getTotalContractValueVnd(), resp.getTotalContractValueVnd());
        assertEquals(v6.getContractValueScore(), resp.getContractValueScore());
        // Verify partnerContractRepository was NEVER queried during Owner Adjustment creation!
        verifyNoInteractions(partnerContractRepository);
    }

    /**
     * Case 4: Manager fields before Owner edit == Manager fields after Owner edit
     */
    @Test
    void testManagerFields_RemainStrictlyImmutable_DuringOwnerAdjustment() {
        CompanyRelationshipAssessment v7Adj = CompanyRelationshipAssessment.builder()
                .id(700L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(7)
                .status(RelationshipAssessmentStatus.DRAFT)
                .assessmentType(RelationshipAssessmentType.OWNER_ADJUSTMENT)
                .sourceAssessmentId(600L)
                .commercialAwardedScore(5)
                .cooperationScore(4)
                .strategicScore(3)
                .relationshipNetworkScore(2)
                .engagementScore(4)
                .qualitativeScore(4)
                .managerTotalScore(73)
                .managerRank("B")
                .ownerCommercialScore(5)
                .ownerCooperationScore(4)
                .ownerStrategicScore(3)
                .ownerRelationshipNetworkScore(2)
                .ownerEngagementScore(4)
                .ownerQualitativeScore(4)
                .createdByAccountId(ownerUser.getId())
                .build();

        when(assessmentRepository.findById(700L)).thenReturn(Optional.of(v7Adj));
        when(assessmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OwnerAdjustmentUpdateRequest req = OwnerAdjustmentUpdateRequest.builder()
                .ownerStrategicScore(5)
                .ownerAdjustmentReason("Strategic pivot for 2027")
                .build();

        RelationshipAssessmentResponse updated = service.updateOwnerAdjustment(700L, req, ownerUser);

        // Manager fields MUST NOT CHANGE
        assertEquals(5, updated.getCommercialAwardedScore());
        assertEquals(4, updated.getCooperationScore());
        assertEquals(3, updated.getStrategicScore());
        assertEquals(2, updated.getRelationshipNetworkScore());
        assertEquals(4, updated.getEngagementScore());
        assertEquals(4, updated.getQualitativeScore());
        assertEquals(73, updated.getManagerTotalScore());
        assertEquals("B", updated.getManagerRank());

        // Owner fields changed
        assertEquals(5, updated.getOwnerStrategicScore());
        assertEquals("Strategic pivot for 2027", updated.getOwnerAdjustmentReason());
    }

    /**
     * Case 5: Owner adjustment FINALIZED -> official score uses Owner values
     */
    @Test
    void testOwnerAdjustmentFinalized_OfficialScoreUsesOwnerValues() {
        CompanyRelationshipAssessment v7Adj = CompanyRelationshipAssessment.builder()
                .id(700L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(7)
                .status(RelationshipAssessmentStatus.DRAFT)
                .assessmentType(RelationshipAssessmentType.OWNER_ADJUSTMENT)
                .sourceAssessmentId(600L)
                .scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5)
                .commercialAwardedScore(5)
                .cooperationScore(4)
                .strategicScore(3)
                .relationshipNetworkScore(2)
                .engagementScore(4)
                .qualitativeScore(4)
                .managerTotalScore(73)
                .managerRank("B")
                .ownerCommercialScore(5)
                .ownerCooperationScore(5)
                .ownerStrategicScore(5)
                .ownerRelationshipNetworkScore(5)
                .ownerEngagementScore(5)
                .ownerQualitativeScore(5)
                .ownerAdjustmentReason("Major strategic upgrade across all dimensions")
                .createdByAccountId(ownerUser.getId())
                .build();

        when(assessmentRepository.findById(700L)).thenReturn(Optional.of(v7Adj));
        when(assessmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RelationshipAssessmentResponse finalized = service.completeOwnerAdjustment(700L, null, ownerUser);

        assertEquals(RelationshipAssessmentStatus.FINALIZED, finalized.getStatus());
        assertEquals(100, finalized.getOwnerFinalTotalScore());
        assertEquals("A", finalized.getOwnerFinalRank());
        // Official score MUST use Owner values (100, Rank A)
        assertEquals(100, finalized.getOfficialScore());
        assertEquals("A", finalized.getOfficialRank());
        assertTrue(finalized.getIsOfficialFinalized());
        assertTrue(finalized.getIsOwnerAdjustment());
    }

    /**
     * Case 6: Owner adjustment DRAFT/CANCELLED -> previous Manager FINALIZED remains official
     */
    @Test
    void testOwnerAdjustmentDraftOrCancelled_PreviousManagerFinalizedRemainsOfficial() {
        CompanyRelationshipAssessment v6 = buildFinalizedV6();
        CompanyRelationshipAssessment v7Draft = CompanyRelationshipAssessment.builder()
                .id(700L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(7)
                .status(RelationshipAssessmentStatus.DRAFT)
                .assessmentType(RelationshipAssessmentType.OWNER_ADJUSTMENT)
                .sourceAssessmentId(600L)
                .scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5)
                .ownerFinalTotalScore(null)
                .ownerFinalRank(null)
                .build();

        lenient().when(assessmentRepository.findById(600L)).thenReturn(Optional.of(v6));
        lenient().when(assessmentRepository.findById(700L)).thenReturn(Optional.of(v7Draft));
        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusInOrderByVersionNumberDesc(
                eq(ownerId), eq(targetId), any())).thenReturn(Optional.of(v7Draft));
        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
                ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.of(v6));

        Map<String, Object> overview = service.getOverview(targetId, ownerUser);
        RelationshipAssessmentResponse active = (RelationshipAssessmentResponse) overview.get("activeAssessment");
        RelationshipAssessmentResponse official = (RelationshipAssessmentResponse) overview.get("officialFinalizedAssessment");

        // Active draft should show V6 as its official baseline snapshot
        assertEquals(73, active.getOfficialScore());
        assertEquals("B", active.getOfficialRank());

        // Official finalized assessment is V6
        assertEquals(6, official.getVersionNumber());
        assertEquals(73, official.getOfficialScore());
        assertEquals("B", official.getOfficialRank());
    }

    /**
     * Case 7: Owner cannot edit Manager draft
     */
    @Test
    void testOwnerCannotEditManagerDraft() {
        CompanyRelationshipAssessment mgrDraft = CompanyRelationshipAssessment.builder()
                .id(800L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(8)
                .status(RelationshipAssessmentStatus.DRAFT)
                .assessmentType(RelationshipAssessmentType.MANAGER_ASSESSMENT)
                .createdByAccountId(10L)
                .build();

        when(assessmentRepository.findById(800L)).thenReturn(Optional.of(mgrDraft));

        UpdateRelationshipAssessmentRequest req = UpdateRelationshipAssessmentRequest.builder()
                .strategicScore(4)
                .build();

        assertThrows(AccessDeniedException.class, () ->
                service.updateDraft(800L, req, ownerUser)
        );
    }

    /**
     * Case 8: Manager cannot edit Owner adjustment
     */
    @Test
    void testManagerCannotEditOwnerAdjustment() {
        CompanyRelationshipAssessment ownerAdj = CompanyRelationshipAssessment.builder()
                .id(900L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(9)
                .status(RelationshipAssessmentStatus.DRAFT)
                .assessmentType(RelationshipAssessmentType.OWNER_ADJUSTMENT)
                .createdByAccountId(1L)
                .build();

        lenient().when(assessmentRepository.findById(900L)).thenReturn(Optional.of(ownerAdj));

        OwnerAdjustmentUpdateRequest req = OwnerAdjustmentUpdateRequest.builder()
                .ownerStrategicScore(4)
                .build();

        assertThrows(AccessDeniedException.class, () ->
                service.updateOwnerAdjustment(900L, req, managerUser)
        );
    }

    /**
     * Case 9: Cannot adjust an existing Owner Adjustment (repeated Owner-on-Owner restricted)
     */
    @Test
    void testCannotAdjustExistingOwnerAdjustment() {
        CompanyRelationshipAssessment v7OwnerAdjFinalized = CompanyRelationshipAssessment.builder()
                .id(700L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(7)
                .status(RelationshipAssessmentStatus.FINALIZED)
                .assessmentType(RelationshipAssessmentType.OWNER_ADJUSTMENT)
                .build();

        when(assessmentRepository.findById(700L)).thenReturn(Optional.of(v7OwnerAdjFinalized));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                service.createOwnerAdjustment(700L, ownerUser)
        );
        assertTrue(ex.getMessage().contains("Cannot adjust an existing Owner Adjustment"));
    }

    /**
     * Case 10: Complete Owner adjustment fails if no scores changed or if adjustment reason is missing
     */
    @Test
    void testCompleteOwnerAdjustment_RequiresScoreDiffAndReason() {
        CompanyRelationshipAssessment v7Adj = CompanyRelationshipAssessment.builder()
                .id(700L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(7)
                .status(RelationshipAssessmentStatus.DRAFT)
                .assessmentType(RelationshipAssessmentType.OWNER_ADJUSTMENT)
                .sourceAssessmentId(600L)
                .scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5)
                .commercialAwardedScore(5)
                .cooperationScore(4)
                .strategicScore(3)
                .relationshipNetworkScore(2)
                .engagementScore(4)
                .qualitativeScore(4)
                // Owner scores identical to Manager scores!
                .ownerCommercialScore(5)
                .ownerCooperationScore(4)
                .ownerStrategicScore(3)
                .ownerRelationshipNetworkScore(2)
                .ownerEngagementScore(4)
                .ownerQualitativeScore(4)
                .ownerAdjustmentReason("Some reason")
                .createdByAccountId(ownerUser.getId())
                .build();

        when(assessmentRepository.findById(700L)).thenReturn(Optional.of(v7Adj));

        // Subcase 1: No scores changed
        BusinessValidationException ex1 = assertThrows(BusinessValidationException.class, () ->
                service.completeOwnerAdjustment(700L, null, ownerUser)
        );
        assertTrue(ex1.getMessage().contains("At least one criterion score must be changed"));

        // Subcase 2: Scores changed but no adjustment reason
        v7Adj.setOwnerStrategicScore(5); // changed
        v7Adj.setOwnerAdjustmentReason(null); // missing reason

        BusinessValidationException ex2 = assertThrows(BusinessValidationException.class, () ->
                service.completeOwnerAdjustment(700L, null, ownerUser)
        );
        assertTrue(ex2.getMessage().contains("Owner Adjustment Reason is mandatory"));
    }

    /**
     * Case 11: Cancel Owner Adjustment releases active slot
     */
    @Test
    void testCancelOwnerAdjustment_ReleasesActiveSlot() {
        CompanyRelationshipAssessment v7Adj = CompanyRelationshipAssessment.builder()
                .id(700L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(7)
                .status(RelationshipAssessmentStatus.DRAFT)
                .assessmentType(RelationshipAssessmentType.OWNER_ADJUSTMENT)
                .createdByAccountId(ownerUser.getId())
                .build();

        when(assessmentRepository.findById(700L)).thenReturn(Optional.of(v7Adj));
        when(assessmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RelationshipAssessmentResponse cancelled = service.cancelOwnerAdjustment(700L, ownerUser);
        assertEquals(RelationshipAssessmentStatus.CANCELLED, cancelled.getStatus());
        verify(auditLogService).log(eq(ownerUser.getId()), eq(AuditAction.RELATIONSHIP_ASSESSMENT_CANCELLED),
                eq("CompanyRelationshipAssessment"), eq("700"), anyString());
    }
}

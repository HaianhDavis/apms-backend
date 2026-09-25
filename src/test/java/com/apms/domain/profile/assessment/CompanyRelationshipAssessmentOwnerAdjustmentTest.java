//package com.apms.domain.profile.assessment;
//
//import com.apms.common.enums.SystemRole;
//import com.apms.common.exception.BusinessConflictException;
//import com.apms.common.exception.BusinessValidationException;
//import com.apms.domain.audit.service.AuditLogService;
//import com.apms.domain.contract.repository.sql.PartnerContractRepository;
//import com.apms.domain.profile.CompanyProfile;
//import com.apms.domain.profile.assessment.dto.CompleteOwnerAdjustmentRequest;
//import com.apms.domain.profile.assessment.dto.RelationshipAssessmentResponse;
//import com.apms.domain.profile.assessment.policy.RelationshipCommercialScoringPolicy;
//import com.apms.domain.profile.assessment.policy.RelationshipScoreCalculator;
//import com.apms.domain.profile.assessment.repository.CompanyRelationshipAssessmentRepository;
//import com.apms.domain.profile.assessment.service.CompanyRelationshipAssessmentService;
//import com.apms.domain.profile.assessment.service.RelationshipClosenessAccessEvaluator;
//import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
//import com.apms.domain.profile.service.OwnerOrganizationService;
//import com.apms.domain.profile.service.ProfileService;
//import com.apms.domain.project.repository.sql.ProjectRepository;
//import com.apms.security.UserDetailsImpl;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.springframework.security.access.AccessDeniedException;
//import org.springframework.security.core.authority.SimpleGrantedAuthority;
//
//import java.math.BigDecimal;
//import java.time.LocalDateTime;
//import java.util.*;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.*;
//import static org.mockito.Mockito.*;
//
//@ExtendWith(MockitoExtension.class)
//public class CompanyRelationshipAssessmentOwnerAdjustmentTest {
//
//    @Mock
//    private CompanyRelationshipAssessmentRepository assessmentRepository;
//    @Mock
//    private com.apms.domain.profile.assessment.repository.RelationshipAssessmentDraftRepository relationshipAssessmentDraftRepository;
//    @Mock
//    private PartnerContractRepository partnerContractRepository;
//    @Mock
//    private CompanyProfileRepository companyProfileRepository;
//    @Mock
//    private OwnerOrganizationService ownerOrganizationService;
//    @Mock
//    private ProjectRepository projectRepository;
//    @Mock
//    private AuditLogService auditLogService;
//    @Mock
//    private ProfileService profileService;
//    @Mock
//    private RelationshipClosenessAccessEvaluator accessEvaluator;
//    @Mock
//    private com.apms.domain.notification.service.NotificationService notificationService;
//
//    private RelationshipCommercialScoringPolicy scoringPolicy;
//    private RelationshipScoreCalculator scoreCalculator;
//    private CompanyRelationshipAssessmentService service;
//
//    private final String ownerId = "owner-org-123";
//    private final String targetId = "target-comp-456";
//
//    private UserDetailsImpl managerUser;
//    private UserDetailsImpl ownerUser;
//    private UserDetailsImpl staffUser;
//
//    @BeforeEach
//    void setUp() {
//        scoringPolicy = new RelationshipCommercialScoringPolicy();
//        scoreCalculator = new RelationshipScoreCalculator();
//
//        service = new CompanyRelationshipAssessmentService(
//                assessmentRepository,
//                relationshipAssessmentDraftRepository,
//                partnerContractRepository,
//                companyProfileRepository,
//                ownerOrganizationService,
//                projectRepository,
//                auditLogService,
//                scoringPolicy,
//                scoreCalculator,
//                profileService,
//                accessEvaluator,
//                notificationService
//        );
//
//        lenient().when(profileService.resolveRelationshipType(anyString())).thenReturn("PARTNER_WITH");
//        lenient().when(accessEvaluator.resolveRelationshipType(anyString())).thenReturn("PARTNER_WITH");
//        lenient().when(accessEvaluator.isEligibleRelationshipType(anyString())).thenReturn(true);
//        lenient().when(ownerOrganizationService.getOwnerCompanyProfileId()).thenReturn(ownerId);
//
//        managerUser = new UserDetailsImpl(
//                10L, "manager@apms.com", "hash",
//                List.of(new SimpleGrantedAuthority("ROLE_" + SystemRole.BUSINESS_DEVELOPMENT_MANAGER.name())),
//                true
//        );
//
//        ownerUser = new UserDetailsImpl(
//                1L, "owner@apms.com", "hash",
//                List.of(new SimpleGrantedAuthority("ROLE_" + SystemRole.BUSINESS_OWNER.name())),
//                true
//        );
//
//        staffUser = new UserDetailsImpl(
//                20L, "staff@apms.com", "hash",
//                List.of(new SimpleGrantedAuthority("ROLE_" + SystemRole.BUSINESS_DEVELOPMENT_STAFF.name())),
//                true
//        );
//
//        CompanyProfile profile = new CompanyProfile();
//        profile.setId(targetId);
//        profile.setCompanyId(targetId);
//        lenient().when(companyProfileRepository.findById(targetId)).thenReturn(Optional.of(profile));
//
//        lenient().when(assessmentRepository.findMaxFinalizedMinorRevision(anyString(), anyList(), anyInt())).thenReturn(0);
//        lenient().when(assessmentRepository.findMaxFinalizedMajorVersion(anyString(), anyList())).thenReturn(0);
//        lenient().when(assessmentRepository.findMaxFinalizedVersionNumber(anyString(), anyList())).thenReturn(0);
//    }
//
//    private CompanyRelationshipAssessment buildFinalizedV6() {
//        return CompanyRelationshipAssessment.builder()
//                .id(600L)
//                .ownerCompanyProfileId(ownerId)
//                .companyProfileId(targetId)
//                .versionNumber(6)
//                .majorVersion(6)
//                .minorRevision(0)
//                .status(RelationshipAssessmentStatus.FINALIZED)
//                .assessmentType(RelationshipAssessmentType.MANAGER_ASSESSMENT)
//                .scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5)
//                .approvedContractCount(5)
//                .totalContractValueVnd(new BigDecimal("10000000000"))
//                .contractValueScore(4)
//                .commercialAwardedScore(5)
//                .cooperationScore(4)
//                .strategicScore(3)
//                .relationshipNetworkScore(2)
//                .engagementScore(4)
//                .qualitativeScore(4)
//                .managerRawScorableScore(22)
//                .managerTotalScore(73)
//                .managerRank("B")
//                .managerAccountId(10L)
//                .finalizedAt(LocalDateTime.now().minusDays(1))
//                .createdByAccountId(10L)
//                .build();
//    }
//
//    @Test
//    @DisplayName("Case 1: Version Number Generation uses max(finalized) + 1")
//    void testVersionNumberGeneration_AllocatesNextVersion() {
//        CompanyRelationshipAssessment v6 = buildFinalizedV6();
//        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
//                ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.of(v6));
//        when(assessmentRepository.saveAndFlush(any())).thenAnswer(inv -> {
//            CompanyRelationshipAssessment a = inv.getArgument(0);
//            a.setId(700L);
//            return a;
//        });
//
//        CompleteOwnerAdjustmentRequest req = CompleteOwnerAdjustmentRequest.builder()
//                .sourceAssessmentId(600L)
//                .ownerCommercialScore(5)
//                .ownerCooperationScore(4)
//                .ownerStrategicScore(5) // changed 3 -> 5
//                .ownerRelationshipNetworkScore(2)
//                .ownerEngagementScore(4)
//                .ownerQualitativeScore(4)
//                .build();
//
//        RelationshipAssessmentResponse response = service.completeDirectOwnerAdjustment(targetId, req, ownerUser);
//        assertEquals(6, response.getMajorVersion());
//        assertEquals(1, response.getMinorRevision());
//        assertEquals("V6.1", response.getFormattedVersion());
//        assertEquals(601, response.getVersionNumber());
//        assertEquals(RelationshipAssessmentStatus.FINALIZED, response.getStatus());
//    }
//
//    @Test
//    @DisplayName("Case 2: Historical Version Owner Adjustment Rejected When Stale")
//    void testHistoricalVersion_OwnerAdjustmentRejected_WhenNotLatestOfficial() {
//        CompanyRelationshipAssessment v6 = buildFinalizedV6();
//        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
//                ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.of(v6));
//
//        // Attempting adjustment based on historical V5 (id 500L) instead of V6 (600L)
//        CompleteOwnerAdjustmentRequest req = CompleteOwnerAdjustmentRequest.builder()
//                .sourceAssessmentId(500L)
//                .ownerCommercialScore(5)
//                .ownerCooperationScore(4)
//                .ownerStrategicScore(5)
//                .ownerRelationshipNetworkScore(2)
//                .ownerEngagementScore(4)
//                .ownerQualitativeScore(4)
//                .build();
//
//        BusinessConflictException ex = assertThrows(BusinessConflictException.class, () ->
//                service.completeDirectOwnerAdjustment(targetId, req, ownerUser)
//        );
//        assertTrue(ex.getMessage().contains("Bản đánh giá chính thức đã được cập nhật"));
//    }
//
//    @Test
//    @DisplayName("Case 3: Owner adjustment inherits evidence snapshot from source without live contract query")
//    void testOwnerAdjustment_CopiesEvidenceSnapshotFromSource_NoContractRefresh() {
//        CompanyRelationshipAssessment v6 = buildFinalizedV6();
//        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
//                ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.of(v6));
//        when(assessmentRepository.saveAndFlush(any())).thenAnswer(inv -> {
//            CompanyRelationshipAssessment a = inv.getArgument(0);
//            a.setId(700L);
//            return a;
//        });
//
//        CompleteOwnerAdjustmentRequest req = CompleteOwnerAdjustmentRequest.builder()
//                .sourceAssessmentId(600L)
//                .ownerCommercialScore(5)
//                .ownerCooperationScore(4)
//                .ownerStrategicScore(5)
//                .ownerRelationshipNetworkScore(2)
//                .ownerEngagementScore(4)
//                .ownerQualitativeScore(4)
//                .build();
//
//        RelationshipAssessmentResponse resp = service.completeDirectOwnerAdjustment(targetId, req, ownerUser);
//
//        assertEquals(v6.getApprovedContractCount(), resp.getApprovedContractCount());
//        assertEquals(v6.getTotalContractValueVnd(), resp.getTotalContractValueVnd());
//        assertEquals(v6.getContractValueScore(), resp.getContractValueScore());
//        verifyNoInteractions(partnerContractRepository);
//    }
//
//    @Test
//    @DisplayName("Case 4: Manager baseline fields remain strictly preserved during Owner adjustment")
//    void testManagerFields_RemainStrictlyImmutable_DuringOwnerAdjustment() {
//        CompanyRelationshipAssessment v6 = buildFinalizedV6();
//        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
//                ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.of(v6));
//        when(assessmentRepository.saveAndFlush(any())).thenAnswer(inv -> {
//            CompanyRelationshipAssessment a = inv.getArgument(0);
//            a.setId(700L);
//            return a;
//        });
//
//        CompleteOwnerAdjustmentRequest req = CompleteOwnerAdjustmentRequest.builder()
//                .sourceAssessmentId(600L)
//                .ownerCommercialScore(5)
//                .ownerCooperationScore(4)
//                .ownerStrategicScore(5) // changed from 3
//                .ownerRelationshipNetworkScore(2)
//                .ownerEngagementScore(4)
//                .ownerQualitativeScore(4)
//                .ownerAdjustmentReason("Strategic pivot for 2027")
//                .build();
//
//        RelationshipAssessmentResponse updated = service.completeDirectOwnerAdjustment(targetId, req, ownerUser);
//
//        // Manager fields MUST NOT CHANGE
//        assertEquals(5, updated.getCommercialAwardedScore());
//        assertEquals(4, updated.getCooperationScore());
//        assertEquals(3, updated.getStrategicScore());
//        assertEquals(2, updated.getRelationshipNetworkScore());
//        assertEquals(4, updated.getEngagementScore());
//        assertEquals(4, updated.getQualitativeScore());
//        assertEquals(73, updated.getManagerTotalScore());
//        assertEquals("B", updated.getManagerRank());
//
//        // Owner fields updated
//        assertEquals(5, updated.getOwnerStrategicScore());
//        assertEquals("Strategic pivot for 2027", updated.getOwnerAdjustmentReason());
//    }
//
//    @Test
//    @DisplayName("Case 5: Owner adjustment FINALIZED -> official score uses Owner values")
//    void testOwnerAdjustmentFinalized_OfficialScoreUsesOwnerValues() {
//        CompanyRelationshipAssessment v6 = buildFinalizedV6();
//        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
//                ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.of(v6));
//        when(assessmentRepository.saveAndFlush(any())).thenAnswer(inv -> {
//            CompanyRelationshipAssessment a = inv.getArgument(0);
//            a.setId(700L);
//            return a;
//        });
//
//        // Owner sets all 5s => 100%, Rank A
//        CompleteOwnerAdjustmentRequest req = CompleteOwnerAdjustmentRequest.builder()
//                .sourceAssessmentId(600L)
//                .ownerCommercialScore(5)
//                .ownerCooperationScore(5)
//                .ownerStrategicScore(5)
//                .ownerRelationshipNetworkScore(5)
//                .ownerEngagementScore(5)
//                .ownerQualitativeScore(5)
//                .ownerAdjustmentReason("Major strategic upgrade across all dimensions")
//                .build();
//
//        RelationshipAssessmentResponse finalized = service.completeDirectOwnerAdjustment(targetId, req, ownerUser);
//
//        assertEquals(RelationshipAssessmentStatus.FINALIZED, finalized.getStatus());
//        assertEquals(100, finalized.getOwnerFinalTotalScore());
//        assertEquals("A", finalized.getOwnerFinalRank());
//        assertEquals(100, finalized.getOfficialScore());
//        assertEquals("A", finalized.getOfficialRank());
//        assertTrue(finalized.getIsOfficialFinalized());
//        assertTrue(finalized.getIsOwnerAdjustment());
//    }
//
//    @Test
//    @DisplayName("Case 6: Consecutive adjustments traverse chain to find originating Manager")
//    void testConsecutiveAdjustments_NotificationTraversesChainToOriginatingManager() {
//        CompanyRelationshipAssessment v6Manager = buildFinalizedV6(); // managerAccountId = 10L
//        CompanyRelationshipAssessment v7Owner = CompanyRelationshipAssessment.builder()
//                .id(700L)
//                .ownerCompanyProfileId(ownerId)
//                .companyProfileId(targetId)
//                .versionNumber(7)
//                .majorVersion(7)
//                .minorRevision(0)
//                .status(RelationshipAssessmentStatus.FINALIZED)
//                .assessmentType(RelationshipAssessmentType.OWNER_ADJUSTMENT)
//                .sourceAssessmentId(600L)
//                .managerAccountId(null)
//                .commercialAwardedScore(5)
//                .cooperationScore(4)
//                .strategicScore(4)
//                .relationshipNetworkScore(3)
//                .engagementScore(4)
//                .qualitativeScore(4)
//                .managerTotalScore(80)
//                .managerRank("B")
//                .ownerCommercialScore(5)
//                .ownerCooperationScore(4)
//                .ownerStrategicScore(4)
//                .ownerRelationshipNetworkScore(3)
//                .ownerEngagementScore(4)
//                .ownerQualitativeScore(4)
//                .ownerFinalTotalScore(80)
//                .ownerFinalRank("B")
//                .build();
//
//        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
//                ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.of(v7Owner));
//        when(assessmentRepository.findById(700L)).thenReturn(Optional.of(v7Owner));
//        when(assessmentRepository.findById(600L)).thenReturn(Optional.of(v6Manager));
//        when(assessmentRepository.saveAndFlush(any())).thenAnswer(inv -> {
//            CompanyRelationshipAssessment a = inv.getArgument(0);
//            a.setId(800L);
//            return a;
//        });
//
//        CompleteOwnerAdjustmentRequest req = CompleteOwnerAdjustmentRequest.builder()
//                .sourceAssessmentId(700L)
//                .ownerCommercialScore(5)
//                .ownerCooperationScore(4)
//                .ownerStrategicScore(5) // changed from 4 to 5
//                .ownerRelationshipNetworkScore(3)
//                .ownerEngagementScore(4)
//                .ownerQualitativeScore(4)
//                .ownerAdjustmentReason("Strategic: Promoted to top-tier strategic partner")
//                .build();
//
//        RelationshipAssessmentResponse resp = service.completeDirectOwnerAdjustment(targetId, req, ownerUser);
//
//        assertEquals(RelationshipAssessmentStatus.FINALIZED, resp.getStatus());
//        assertEquals(7, resp.getMajorVersion());
//        assertEquals(1, resp.getMinorRevision());
//        assertEquals("V7.1", resp.getFormattedVersion());
//        assertEquals(701, resp.getVersionNumber());
//        verify(notificationService).notifyRelationshipAssessmentOwnerAdjusted(
//                any(CompanyRelationshipAssessment.class), eq(v7Owner), eq(10L), eq(ownerUser.getId()));
//    }
//
//    @Test
//    @DisplayName("Case 7: Owner changes one criterion, all notes null -> completion succeeds")
//    void testCompleteOwnerAdjustment_OneCriterionChanged_AllNotesNull_Success() {
//        CompanyRelationshipAssessment v6 = buildFinalizedV6();
//        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
//                ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.of(v6));
//        when(assessmentRepository.saveAndFlush(any())).thenAnswer(inv -> {
//            CompanyRelationshipAssessment a = inv.getArgument(0);
//            a.setId(700L);
//            return a;
//        });
//
//        CompleteOwnerAdjustmentRequest req = CompleteOwnerAdjustmentRequest.builder()
//                .sourceAssessmentId(600L)
//                .ownerCommercialScore(5)
//                .ownerCooperationScore(4)
//                .ownerStrategicScore(5) // changed from 3 to 5
//                .ownerRelationshipNetworkScore(2)
//                .ownerEngagementScore(4)
//                .ownerQualitativeScore(4)
//                .build();
//
//        RelationshipAssessmentResponse response = service.completeDirectOwnerAdjustment(targetId, req, ownerUser);
//
//        assertEquals(RelationshipAssessmentStatus.FINALIZED, response.getStatus());
//        assertEquals(5, response.getOwnerStrategicScore());
//        assertNull(response.getOwnerAdjustmentReason());
//        assertNull(response.getOwnerRelationshipNetworkNote());
//        assertNull(response.getOwnerNote());
//    }
//
//    @Test
//    @DisplayName("Case 8: Owner changes multiple criteria, notes empty string/whitespace -> completion succeeds")
//    void testCompleteOwnerAdjustment_MultipleCriteriaChanged_NotesEmpty_Success() {
//        CompanyRelationshipAssessment v6 = buildFinalizedV6();
//        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
//                ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.of(v6));
//        when(assessmentRepository.saveAndFlush(any())).thenAnswer(inv -> {
//            CompanyRelationshipAssessment a = inv.getArgument(0);
//            a.setId(700L);
//            return a;
//        });
//
//        CompleteOwnerAdjustmentRequest req = CompleteOwnerAdjustmentRequest.builder()
//                .sourceAssessmentId(600L)
//                .ownerCommercialScore(4) // changed from 5 to 4
//                .ownerCooperationScore(4)
//                .ownerStrategicScore(5)  // changed from 3 to 5
//                .ownerRelationshipNetworkScore(2)
//                .ownerEngagementScore(4)
//                .ownerQualitativeScore(4)
//                .ownerRelationshipNetworkNote("   ")
//                .ownerAdjustmentReason("")
//                .ownerNote("")
//                .build();
//
//        RelationshipAssessmentResponse response = service.completeDirectOwnerAdjustment(targetId, req, ownerUser);
//
//        assertEquals(RelationshipAssessmentStatus.FINALIZED, response.getStatus());
//        assertEquals(4, response.getOwnerCommercialScore());
//        assertEquals(5, response.getOwnerStrategicScore());
//    }
//
//    @Test
//    @DisplayName("Case 9: Owner enters optional notes -> notes are preserved")
//    void testCompleteOwnerAdjustment_WithOptionalNotes_NotesPreserved() {
//        CompanyRelationshipAssessment v6 = buildFinalizedV6();
//        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
//                ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.of(v6));
//        when(assessmentRepository.saveAndFlush(any())).thenAnswer(inv -> {
//            CompanyRelationshipAssessment a = inv.getArgument(0);
//            a.setId(700L);
//            return a;
//        });
//
//        CompleteOwnerAdjustmentRequest req = CompleteOwnerAdjustmentRequest.builder()
//                .sourceAssessmentId(600L)
//                .ownerCommercialScore(5)
//                .ownerCooperationScore(4)
//                .ownerStrategicScore(4) // changed 3 -> 4
//                .ownerRelationshipNetworkScore(2)
//                .ownerEngagementScore(4)
//                .ownerQualitativeScore(4)
//                .ownerStrategicNote("Expanding partnership into AI domain")
//                .ownerAdjustmentReason("Strategic Importance: Expanding partnership into AI domain")
//                .ownerRelationshipNetworkNote("Strong connection with leadership")
//                .ownerNote("Executive Owner Note")
//                .build();
//
//        RelationshipAssessmentResponse response = service.completeDirectOwnerAdjustment(targetId, req, ownerUser);
//
//        assertEquals(RelationshipAssessmentStatus.FINALIZED, response.getStatus());
//        assertEquals("Expanding partnership into AI domain", response.getOwnerStrategicNote());
//        assertEquals("Strategic Importance: Expanding partnership into AI domain", response.getOwnerAdjustmentReason());
//        assertEquals("Strong connection with leadership", response.getOwnerRelationshipNetworkNote());
//        assertEquals("Executive Owner Note", response.getOwnerNote());
//    }
//
//    @Test
//    @DisplayName("Case 10: No score changes -> completion rejected")
//    void testCompleteOwnerAdjustment_NoScoreChanges_Rejected() {
//        CompanyRelationshipAssessment v6 = buildFinalizedV6();
//        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
//                ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.of(v6));
//
//        // Exact identical scores as V6
//        CompleteOwnerAdjustmentRequest req = CompleteOwnerAdjustmentRequest.builder()
//                .sourceAssessmentId(600L)
//                .ownerCommercialScore(5)
//                .ownerCooperationScore(4)
//                .ownerStrategicScore(3)
//                .ownerRelationshipNetworkScore(2)
//                .ownerEngagementScore(4)
//                .ownerQualitativeScore(4)
//                .ownerAdjustmentReason("Some reason")
//                .build();
//
//        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
//                service.completeDirectOwnerAdjustment(targetId, req, ownerUser)
//        );
//        assertTrue(ex.getMessage().contains("ít nhất một tiêu chí thay đổi"));
//    }
//
//    @Test
//    @DisplayName("Case 11: Invalid score outside 0-5 -> rejected")
//    void testCompleteOwnerAdjustment_InvalidScoreOutsideZeroToFive_Rejected() {
//        CompanyRelationshipAssessment v6 = buildFinalizedV6();
//        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
//                ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.of(v6));
//
//        CompleteOwnerAdjustmentRequest reqOver = CompleteOwnerAdjustmentRequest.builder()
//                .sourceAssessmentId(600L)
//                .ownerCommercialScore(6)
//                .ownerCooperationScore(4)
//                .ownerStrategicScore(3)
//                .ownerRelationshipNetworkScore(2)
//                .ownerEngagementScore(4)
//                .ownerQualitativeScore(4)
//                .build();
//
//        assertThrows(BusinessValidationException.class, () ->
//                service.completeDirectOwnerAdjustment(targetId, reqOver, ownerUser)
//        );
//
//        CompleteOwnerAdjustmentRequest reqNeg = CompleteOwnerAdjustmentRequest.builder()
//                .sourceAssessmentId(600L)
//                .ownerCommercialScore(-1)
//                .ownerCooperationScore(4)
//                .ownerStrategicScore(3)
//                .ownerRelationshipNetworkScore(2)
//                .ownerEngagementScore(4)
//                .ownerQualitativeScore(4)
//                .build();
//
//        assertThrows(BusinessValidationException.class, () ->
//                service.completeDirectOwnerAdjustment(targetId, reqNeg, ownerUser)
//        );
//    }
//
//    @Test
//    @DisplayName("Case 12: Non-owner roles rejected from Owner adjustment")
//    void testNonOwner_CannotPerformOwnerAdjustment() {
//        CompleteOwnerAdjustmentRequest req = CompleteOwnerAdjustmentRequest.builder()
//                .sourceAssessmentId(600L)
//                .ownerCommercialScore(5)
//                .ownerCooperationScore(4)
//                .ownerStrategicScore(5)
//                .ownerRelationshipNetworkScore(2)
//                .ownerEngagementScore(4)
//                .ownerQualitativeScore(4)
//                .build();
//
//        assertThrows(AccessDeniedException.class, () ->
//                service.completeDirectOwnerAdjustment(targetId, req, managerUser)
//        );
//
//        assertThrows(AccessDeniedException.class, () ->
//                service.completeDirectOwnerAdjustment(targetId, req, staffUser)
//        );
//    }
//
//    @Test
//    @DisplayName("Case 13: Legacy owner adjustment endpoints throw BusinessValidationException")
//    void testLegacyOwnerAdjustmentEndpoints_ThrowValidationException() {
//        assertThrows(BusinessValidationException.class, () ->
//                service.createOwnerAdjustment(600L, ownerUser));
//
//        assertThrows(BusinessValidationException.class, () ->
//                service.updateOwnerAdjustment(700L, null, ownerUser));
//
//        assertThrows(BusinessValidationException.class, () ->
//                service.cancelOwnerAdjustment(700L, ownerUser));
//
//        assertThrows(BusinessValidationException.class, () ->
//                service.completeOwnerAdjustment(700L, null, ownerUser));
//    }
//}

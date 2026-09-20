package com.apms.domain.profile.assessment;

import com.apms.common.enums.SystemRole;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.contract.repository.sql.PartnerContractRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.assessment.dto.CompanyRecentAssessmentSummaryDto;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CompanyRelationshipAssessmentServiceTest {

    @Mock
    private CompanyRelationshipAssessmentRepository assessmentRepository;
    @Mock
    private com.apms.domain.profile.assessment.repository.RelationshipAssessmentDraftRepository relationshipAssessmentDraftRepository;
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
                relationshipAssessmentDraftRepository,
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
    @DisplayName("V5 calculation thresholds, exact scores and ranks")
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
    @DisplayName("Eligibility validation throws BusinessValidationException for unsupported relationship types")
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
                service.validateRelationshipClosenessEligibility(targetId));

        assertEquals("Relationship Closeness assessment is not available for this company relationship type.", ex.getMessage());
    }

    @Test
    @DisplayName("Eligibility validation passes for Customer and Supplier")
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
    @DisplayName("getOverview never returns activeAssessment or hasActiveAssessment=true")
    void testGetOverview_NeverReturnsActiveDraft() {
        CompanyRelationshipAssessment v1Finalized = CompanyRelationshipAssessment.builder()
                .id(100L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.FINALIZED)
                .assessmentType(RelationshipAssessmentType.MANAGER_ASSESSMENT)
                .scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5)
                .managerTotalScore(80)
                .managerRank("B")
                .finalizedAt(LocalDateTime.now().minusDays(1))
                .build();

        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
                ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.of(v1Finalized));

        Map<String, Object> overview = service.getOverview(targetId, managerUser);

        assertNotNull(overview);
        assertEquals(false, overview.get("hasActiveAssessment"));
        assertNull(overview.get("activeAssessment"));

        RelationshipAssessmentResponse official = (RelationshipAssessmentResponse) overview.get("officialFinalizedAssessment");
        assertNotNull(official);
        assertEquals(1, official.getVersionNumber());
        assertEquals(80, official.getOfficialScore());
        assertEquals("B", official.getOfficialRank());
    }

    @Test
    @DisplayName("Recent assessments summary: single assessment newly scored")
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
                .finalizedAt(LocalDateTime.now().minusHours(2))
                .commercialAwardedScore(4)
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
    @DisplayName("Recent assessments summary: two assessments with trend delta")
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
                .finalizedAt(LocalDateTime.now().minusHours(24))
                .commercialAwardedScore(4)
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
                .finalizedAt(LocalDateTime.now().minusHours(1))
                .ownerCommercialScore(5)
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

    @Test
    @DisplayName("Legacy draft lifecycle methods throw BusinessValidationException and never persist drafts")
    void testLegacyDraftMethods_Blocked() {
        assertThrows(BusinessValidationException.class, () ->
                service.createDraft(targetId, null, managerUser));

        assertThrows(BusinessValidationException.class, () ->
                service.updateDraft(100L, UpdateRelationshipAssessmentRequest.builder().build(), managerUser));

        assertThrows(BusinessValidationException.class, () ->
                service.submitAssessment(100L, managerUser));

        assertThrows(BusinessValidationException.class, () ->
                service.requestChanges(100L, null, ownerUser));

        assertThrows(BusinessValidationException.class, () ->
                service.finalizeAssessment(100L, null, ownerUser));

        assertThrows(BusinessValidationException.class, () ->
                service.createNewVersion(targetId, managerUser));

        verify(assessmentRepository, never()).save(any());
        verify(assessmentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Acceptance Test 1: V1 (67) -> V1.1 (97) -> V2 (60). V2 trend compares against V1.1 (-37 pts), History orders V2, V1.1, V1")
    void testAcceptanceTest1_V2ComparesAgainstV1_1() {
        LocalDateTime t1 = LocalDateTime.of(2026, 9, 1, 10, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 9, 10, 10, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 9, 18, 10, 0);

        CompanyRelationshipAssessment v1 = CompanyRelationshipAssessment.builder()
                .id(1L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .majorVersion(1)
                .minorRevision(0)
                .assessmentType(RelationshipAssessmentType.MANAGER_ASSESSMENT)
                .status(RelationshipAssessmentStatus.FINALIZED)
                .managerTotalScore(67)
                .managerRank("B")
                .finalizedAt(t1)
                .scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5)
                .build();

        CompanyRelationshipAssessment v1_1 = CompanyRelationshipAssessment.builder()
                .id(2L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(101)
                .majorVersion(1)
                .minorRevision(1)
                .assessmentType(RelationshipAssessmentType.OWNER_ADJUSTMENT)
                .sourceAssessmentId(1L)
                .status(RelationshipAssessmentStatus.FINALIZED)
                .managerTotalScore(67)
                .ownerFinalTotalScore(97)
                .ownerFinalRank("A")
                .finalizedAt(t2)
                .scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5)
                .build();

        CompanyRelationshipAssessment v2 = CompanyRelationshipAssessment.builder()
                .id(3L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(2)
                .majorVersion(2)
                .minorRevision(0)
                .assessmentType(RelationshipAssessmentType.MANAGER_ASSESSMENT)
                .sourceAssessmentId(2L)
                .status(RelationshipAssessmentStatus.FINALIZED)
                .managerTotalScore(60)
                .managerRank("B")
                .finalizedAt(t3)
                .scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5)
                .build();

        when(assessmentRepository.findAllByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByFinalizedAtDescMajorVersionDescMinorRevisionDesc(
                ownerId, targetId, RelationshipAssessmentStatus.FINALIZED))
                .thenReturn(List.of(v2, v1_1, v1));

        // Test V2 previous is V1.1 (-37 points)
        Optional<CompanyRelationshipAssessment> prevV2 = service.getPreviousOfficialAssessment(v2);
        assertTrue(prevV2.isPresent());
        assertEquals(v1_1.getId(), prevV2.get().getId());
        assertEquals("V1.1", prevV2.get().getFormattedVersion());
        assertEquals(97, service.getOfficialTotalScore(prevV2.get()));
        assertEquals(60, service.getOfficialTotalScore(v2));
        assertEquals(-37, service.getOfficialTotalScore(v2) - service.getOfficialTotalScore(prevV2.get()));

        // Test V1.1 previous is V1 (+30 points)
        Optional<CompanyRelationshipAssessment> prevV1_1 = service.getPreviousOfficialAssessment(v1_1);
        assertTrue(prevV1_1.isPresent());
        assertEquals(v1.getId(), prevV1_1.get().getId());
        assertEquals("V1", prevV1_1.get().getFormattedVersion());
        assertEquals(67, service.getOfficialTotalScore(prevV1_1.get()));
        assertEquals(30, service.getOfficialTotalScore(v1_1) - service.getOfficialTotalScore(prevV1_1.get()));

        // Test V1 previous is empty
        Optional<CompanyRelationshipAssessment> prevV1 = service.getPreviousOfficialAssessment(v1);
        assertTrue(prevV1.isEmpty());
    }

    @Test
    @DisplayName("Acceptance Tests 2, 3, 4: V2 (60) -> V2.1 (80, +20) -> V2.2 (75, -5) -> V3 (90, +15)")
    void testAcceptanceTests_ChainTransitions() {
        LocalDateTime t1 = LocalDateTime.of(2026, 9, 1, 10, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 9, 2, 10, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 9, 3, 10, 0);
        LocalDateTime t4 = LocalDateTime.of(2026, 9, 4, 10, 0);

        CompanyRelationshipAssessment v2 = CompanyRelationshipAssessment.builder()
                .id(20L).ownerCompanyProfileId(ownerId).companyProfileId(targetId)
                .versionNumber(2).majorVersion(2).minorRevision(0)
                .status(RelationshipAssessmentStatus.FINALIZED).assessmentType(RelationshipAssessmentType.MANAGER_ASSESSMENT)
                .managerTotalScore(60).managerRank("B").finalizedAt(t1).scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5).build();

        CompanyRelationshipAssessment v2_1 = CompanyRelationshipAssessment.builder()
                .id(21L).ownerCompanyProfileId(ownerId).companyProfileId(targetId)
                .versionNumber(201).majorVersion(2).minorRevision(1)
                .status(RelationshipAssessmentStatus.FINALIZED).assessmentType(RelationshipAssessmentType.OWNER_ADJUSTMENT)
                .sourceAssessmentId(20L).ownerFinalTotalScore(80).ownerFinalRank("A").finalizedAt(t2).scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5).build();

        CompanyRelationshipAssessment v2_2 = CompanyRelationshipAssessment.builder()
                .id(22L).ownerCompanyProfileId(ownerId).companyProfileId(targetId)
                .versionNumber(202).majorVersion(2).minorRevision(2)
                .status(RelationshipAssessmentStatus.FINALIZED).assessmentType(RelationshipAssessmentType.OWNER_ADJUSTMENT)
                .sourceAssessmentId(21L).ownerFinalTotalScore(75).ownerFinalRank("B").finalizedAt(t3).scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5).build();

        CompanyRelationshipAssessment v3 = CompanyRelationshipAssessment.builder()
                .id(30L).ownerCompanyProfileId(ownerId).companyProfileId(targetId)
                .versionNumber(3).majorVersion(3).minorRevision(0)
                .status(RelationshipAssessmentStatus.FINALIZED).assessmentType(RelationshipAssessmentType.MANAGER_ASSESSMENT)
                .sourceAssessmentId(22L).managerTotalScore(90).managerRank("A").finalizedAt(t4).scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5).build();

        when(assessmentRepository.findAllByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByFinalizedAtDescMajorVersionDescMinorRevisionDesc(
                ownerId, targetId, RelationshipAssessmentStatus.FINALIZED))
                .thenReturn(List.of(v3, v2_2, v2_1, v2));

        // Test 2: V2.1 compares against V2 -> delta = +20
        Optional<CompanyRelationshipAssessment> prevV2_1 = service.getPreviousOfficialAssessment(v2_1);
        assertTrue(prevV2_1.isPresent());
        assertEquals(v2.getId(), prevV2_1.get().getId());
        assertEquals(20, service.getOfficialTotalScore(v2_1) - service.getOfficialTotalScore(prevV2_1.get()));

        // Test 3: V2.2 compares against V2.1 -> delta = -5
        Optional<CompanyRelationshipAssessment> prevV2_2 = service.getPreviousOfficialAssessment(v2_2);
        assertTrue(prevV2_2.isPresent());
        assertEquals(v2_1.getId(), prevV2_2.get().getId());
        assertEquals(-5, service.getOfficialTotalScore(v2_2) - service.getOfficialTotalScore(prevV2_2.get()));

        // Test 4: V3 compares against V2.2 -> delta = +15
        Optional<CompanyRelationshipAssessment> prevV3 = service.getPreviousOfficialAssessment(v3);
        assertTrue(prevV3.isPresent());
        assertEquals(v2_2.getId(), prevV3.get().getId());
        assertEquals(15, service.getOfficialTotalScore(v3) - service.getOfficialTotalScore(prevV3.get()));
    }

    @Test
    @DisplayName("getHistory returns official assessments strictly in chronological order: newest to oldest")
    void testGetHistory_ChronologicalOrdering() {
        LocalDateTime t1 = LocalDateTime.of(2026, 9, 1, 10, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 9, 10, 10, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 9, 18, 10, 0);

        CompanyRelationshipAssessment v1 = CompanyRelationshipAssessment.builder()
                .id(1L).ownerCompanyProfileId(ownerId).companyProfileId(targetId)
                .versionNumber(1).majorVersion(1).minorRevision(0)
                .status(RelationshipAssessmentStatus.FINALIZED).finalizedAt(t1).scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5).build();

        CompanyRelationshipAssessment v1_1 = CompanyRelationshipAssessment.builder()
                .id(2L).ownerCompanyProfileId(ownerId).companyProfileId(targetId)
                .versionNumber(101).majorVersion(1).minorRevision(1)
                .status(RelationshipAssessmentStatus.FINALIZED).finalizedAt(t2).scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5).build();

        CompanyRelationshipAssessment v2 = CompanyRelationshipAssessment.builder()
                .id(3L).ownerCompanyProfileId(ownerId).companyProfileId(targetId)
                .versionNumber(2).majorVersion(2).minorRevision(0)
                .status(RelationshipAssessmentStatus.FINALIZED).finalizedAt(t3).scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5).build();

        // Pass in out-of-order list to test in-memory defensive sort
        when(assessmentRepository.findAllByOwnerCompanyProfileIdAndCompanyProfileIdOrderByFinalizedAtDescMajorVersionDescMinorRevisionDesc(
                ownerId, targetId)).thenReturn(List.of(v1_1, v2, v1));

        List<RelationshipAssessmentResponse> history = service.getHistory(targetId, managerUser);
        assertNotNull(history);
        assertEquals(3, history.size());
        assertEquals("V2", history.get(0).getFormattedVersion());
        assertEquals("V1.1", history.get(1).getFormattedVersion());
        assertEquals("V1", history.get(2).getFormattedVersion());
    }

    @Test
    @DisplayName("getOverview populates trendInfo and previousOfficialAssessment for V2 vs V1.1")
    void testGetOverview_TrendInfoPopulated() {
        LocalDateTime t1 = LocalDateTime.of(2026, 9, 10, 10, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 9, 18, 10, 0);

        CompanyRelationshipAssessment v1_1 = CompanyRelationshipAssessment.builder()
                .id(2L).ownerCompanyProfileId(ownerId).companyProfileId(targetId)
                .versionNumber(101).majorVersion(1).minorRevision(1)
                .status(RelationshipAssessmentStatus.FINALIZED).assessmentType(RelationshipAssessmentType.OWNER_ADJUSTMENT)
                .ownerFinalTotalScore(97).ownerFinalRank("A").finalizedAt(t1).scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5).build();

        CompanyRelationshipAssessment v2 = CompanyRelationshipAssessment.builder()
                .id(3L).ownerCompanyProfileId(ownerId).companyProfileId(targetId)
                .versionNumber(2).majorVersion(2).minorRevision(0)
                .status(RelationshipAssessmentStatus.FINALIZED).assessmentType(RelationshipAssessmentType.MANAGER_ASSESSMENT)
                .managerTotalScore(60).managerRank("B").finalizedAt(t2).scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5).build();

        when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByFinalizedAtDescMajorVersionDescMinorRevisionDesc(
                ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.of(v2));

        when(assessmentRepository.findAllByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByFinalizedAtDescMajorVersionDescMinorRevisionDesc(
                ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(List.of(v2, v1_1));

        Map<String, Object> overview = service.getOverview(targetId, managerUser);
        assertNotNull(overview);
        assertNotNull(overview.get("officialFinalizedAssessment"));
        assertNotNull(overview.get("previousOfficialAssessment"));
        assertNotNull(overview.get("trendInfo"));

        @SuppressWarnings("unchecked")
        Map<String, Object> trendInfo = (Map<String, Object>) overview.get("trendInfo");
        assertEquals("V1.1", trendInfo.get("prevFormattedVersion"));
        assertEquals(97, trendInfo.get("prevScore"));
        assertEquals("A", trendInfo.get("prevRank"));
        assertEquals("V2", trendInfo.get("currentFormattedVersion"));
        assertEquals(60, trendInfo.get("currentScore"));
        assertEquals("B", trendInfo.get("currentRank"));
        assertEquals(-37, trendInfo.get("diff"));
    }
}

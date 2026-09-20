package com.apms.domain.profile.assessment;

import com.apms.common.enums.SystemRole;
import com.apms.common.exception.BusinessConflictException;
import com.apms.common.exception.BusinessValidationException;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
public class CompanyRelationshipAssessmentCompleteTest {

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

        lenient().when(projectRepository.existsByTargetCompanyProfileIdAndMembersAccountIdAndStatusIn(eq(targetId), anyLong(), any()))
                .thenReturn(true);
    }

    private CompanyRelationshipAssessment buildFinalizedV1() {
        return CompanyRelationshipAssessment.builder()
                .id(100L)
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(targetId)
                .versionNumber(1)
                .status(RelationshipAssessmentStatus.FINALIZED)
                .assessmentType(RelationshipAssessmentType.MANAGER_ASSESSMENT)
                .scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5)
                .commercialAwardedScore(4)
                .cooperationScore(4)
                .strategicScore(4)
                .relationshipNetworkScore(3)
                .engagementScore(4)
                .qualitativeScore(4)
                .managerRawScorableScore(23)
                .managerTotalScore(77)
                .managerRank("B")
                .managerAccountId(10L)
                .finalizedAt(LocalDateTime.now().minusDays(2))
                .createdByAccountId(10L)
                .build();
    }

    @Nested
    @DisplayName("Direct Manager Assessment Completion Tests")
    class ManagerDirectCompletionTests {

        @Test
        @DisplayName("Manager completes initial assessment (v1) atomically without prior draft")
        void testManagerDirectCompletion_InitialAssessment_Success() {
            when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
                    ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.empty());
            when(assessmentRepository.findMaxFinalizedVersionNumber(eq(ownerId), anyList())).thenReturn(0);

            PartnerContract c1 = PartnerContract.builder()
                    .effectiveDate(LocalDate.now().minusMonths(2))
                    .currency("VND")
                    .totalContractValue(new BigDecimal("2000000000"))
                    .build();
            when(partnerContractRepository.findByPartnerCompanyIdAndReviewStatus(targetId, ContractReviewStatus.APPROVED))
                    .thenReturn(List.of(c1));

            when(assessmentRepository.saveAndFlush(any(CompanyRelationshipAssessment.class))).thenAnswer(inv -> {
                CompanyRelationshipAssessment a = inv.getArgument(0);
                a.setId(101L);
                return a;
            });

            CompleteRelationshipAssessmentRequest req = CompleteRelationshipAssessmentRequest.builder()
                    .sourceAssessmentId(null)
                    .commercialAwardedScore(5)
                    .cooperationScore(5)
                    .strategicScore(5)
                    .relationshipNetworkScore(5)
                    .engagementScore(5)
                    .trustScore(5)
                    .managerNote("Initial overall evaluation")
                    .commercialEvidenceNote("Initial contract note")
                    .build();

            RelationshipAssessmentResponse response = service.completeDirectAssessment(targetId, req, managerUser);

            assertNotNull(response);
            assertEquals(RelationshipAssessmentStatus.FINALIZED, response.getStatus());
            assertEquals(1, response.getVersionNumber());
            assertEquals(5, response.getCommercialAwardedScore());
            assertEquals(5, response.getCooperationScore());
            assertEquals(5, response.getStrategicScore());
            assertEquals(5, response.getRelationshipNetworkScore());
            assertEquals(5, response.getEngagementScore());
            assertEquals(5, response.getTrustScore());
            assertEquals(100, response.getManagerTotalScore());
            assertEquals("A", response.getManagerRank());
            assertEquals(100, response.getOfficialScore());
            assertEquals("A", response.getOfficialRank());
            assertTrue(response.getIsOfficialFinalized());
            assertEquals(10L, response.getManagerAccountId());

            verify(auditLogService).log(eq(10L), any(), eq("CompanyRelationshipAssessment"), eq("101"),
                    contains("Finalized Manager assessment"));
        }

        @Test
        @DisplayName("Manager completes subsequent assessment (v2) from v1")
        void testManagerDirectCompletion_FromV1ToV2_Success() {
            CompanyRelationshipAssessment v1 = buildFinalizedV1();
            when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
                    ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.of(v1));
            when(assessmentRepository.findMaxFinalizedVersionNumber(eq(ownerId), anyList())).thenReturn(1);

            when(assessmentRepository.saveAndFlush(any(CompanyRelationshipAssessment.class))).thenAnswer(inv -> {
                CompanyRelationshipAssessment a = inv.getArgument(0);
                a.setId(102L);
                return a;
            });

            CompleteRelationshipAssessmentRequest req = CompleteRelationshipAssessmentRequest.builder()
                    .sourceAssessmentId(100L)
                    .commercialAwardedScore(4)
                    .cooperationScore(4)
                    .strategicScore(4)
                    .relationshipNetworkScore(4)
                    .engagementScore(4)
                    .trustScore(4)
                    .build();

            RelationshipAssessmentResponse response = service.completeDirectAssessment(targetId, req, managerUser);

            assertNotNull(response);
            assertEquals(RelationshipAssessmentStatus.FINALIZED, response.getStatus());
            assertEquals(2, response.getVersionNumber());
            // 24 / 30 = 80% => Rank B
            assertEquals(80, response.getManagerTotalScore());
            assertEquals("B", response.getManagerRank());
        }

        @Test
        @DisplayName("Optimistic locking conflict: throws BusinessConflictException when sourceAssessmentId is stale")
        void testManagerDirectCompletion_ThrowsConflict_WhenStaleSource() {
            CompanyRelationshipAssessment v2 = CompanyRelationshipAssessment.builder()
                    .id(200L)
                    .ownerCompanyProfileId(ownerId)
                    .companyProfileId(targetId)
                    .versionNumber(2)
                    .status(RelationshipAssessmentStatus.FINALIZED)
                    .build();

            when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
                    ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.of(v2));

            CompleteRelationshipAssessmentRequest req = CompleteRelationshipAssessmentRequest.builder()
                    .sourceAssessmentId(100L)
                    .commercialAwardedScore(5)
                    .cooperationScore(5)
                    .strategicScore(5)
                    .relationshipNetworkScore(5)
                    .engagementScore(5)
                    .trustScore(5)
                    .build();

            assertThrows(BusinessConflictException.class, () ->
                    service.completeDirectAssessment(targetId, req, managerUser));
        }

        @Test
        @DisplayName("Direct completion fails when score is missing or outside 0-5")
        void testManagerDirectCompletion_ValidationErrors() {
            CompleteRelationshipAssessmentRequest nullScoreReq = CompleteRelationshipAssessmentRequest.builder()
                    .commercialAwardedScore(null)
                    .cooperationScore(5)
                    .strategicScore(5)
                    .relationshipNetworkScore(5)
                    .engagementScore(5)
                    .trustScore(5)
                    .build();

            assertThrows(BusinessValidationException.class, () ->
                    service.completeDirectAssessment(targetId, nullScoreReq, managerUser));

            CompleteRelationshipAssessmentRequest outOfBoundsReq = CompleteRelationshipAssessmentRequest.builder()
                    .commercialAwardedScore(6)
                    .cooperationScore(5)
                    .strategicScore(5)
                    .relationshipNetworkScore(5)
                    .engagementScore(5)
                    .trustScore(5)
                    .build();

            assertThrows(BusinessValidationException.class, () ->
                    service.completeDirectAssessment(targetId, outOfBoundsReq, managerUser));
        }

        @Test
        @DisplayName("Staff and Owner cannot perform manager direct completion")
        void testManagerDirectCompletion_UnauthorizedRoles() {
            CompleteRelationshipAssessmentRequest req = CompleteRelationshipAssessmentRequest.builder()
                    .commercialAwardedScore(5)
                    .cooperationScore(5)
                    .strategicScore(5)
                    .relationshipNetworkScore(5)
                    .engagementScore(5)
                    .trustScore(5)
                    .build();

            assertThrows(AccessDeniedException.class, () ->
                    service.completeDirectAssessment(targetId, req, staffUser));

            assertThrows(AccessDeniedException.class, () ->
                    service.completeDirectAssessment(targetId, req, ownerUser));
        }
    }

    @Nested
    @DisplayName("Direct Owner Adjustment Completion Tests")
    class OwnerDirectCompletionTests {

        @Test
        @DisplayName("Owner completes adjustment directly from latest finalized assessment")
        void testOwnerDirectCompletion_Success() {
            CompanyRelationshipAssessment v1 = buildFinalizedV1();
            when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
                    ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.of(v1));
            when(assessmentRepository.findById(100L)).thenReturn(Optional.of(v1));
            when(assessmentRepository.findMaxFinalizedMinorRevision(eq(ownerId), anyList(), eq(1))).thenReturn(0);

            when(assessmentRepository.saveAndFlush(any(CompanyRelationshipAssessment.class))).thenAnswer(inv -> {
                CompanyRelationshipAssessment a = inv.getArgument(0);
                a.setId(103L);
                return a;
            });

            CompleteOwnerAdjustmentRequest req = CompleteOwnerAdjustmentRequest.builder()
                    .sourceAssessmentId(100L)
                    .ownerCommercialScore(5) // changed from 4 to 5
                    .ownerCooperationScore(4)
                    .ownerStrategicScore(4)
                    .ownerRelationshipNetworkScore(3)
                    .ownerEngagementScore(4)
                    .ownerQualitativeScore(4)
                    .ownerCommercialNote("Upgraded commercial score due to key milestone")
                    .ownerAdjustmentReason("Strategic upgrade")
                    .build();

            RelationshipAssessmentResponse response = service.completeDirectOwnerAdjustment(targetId, req, ownerUser);

            assertNotNull(response);
            assertEquals(RelationshipAssessmentStatus.FINALIZED, response.getStatus());
            assertEquals(1, response.getMajorVersion());
            assertEquals(1, response.getMinorRevision());
            assertEquals("V1.1", response.getFormattedVersion());
            assertEquals(101, response.getVersionNumber());
            assertTrue(response.getIsOwnerAdjustment());
            assertEquals(5, response.getOwnerCommercialScore());
            assertEquals("Upgraded commercial score due to key milestone", response.getOwnerCommercialNote());
            assertEquals("Strategic upgrade", response.getOwnerAdjustmentReason());

            // Manager baseline preserved
            assertEquals(4, response.getCommercialAwardedScore());
            assertNull(response.getManagerAccountId());
            assertEquals(1L, response.getOwnerAccountId());

            // Contract repository was NEVER accessed (inherited from snapshot)
            verifyNoInteractions(partnerContractRepository);

            // Notification sent to manager
            verify(notificationService).notifyRelationshipAssessmentOwnerAdjusted(
                    any(CompanyRelationshipAssessment.class), eq(v1), eq(10L), eq(ownerUser.getId()));
        }

        @Test
        @DisplayName("Owner adjustment rejected when no scores are modified from baseline")
        void testOwnerDirectCompletion_FailsWhenNoScoresChanged() {
            CompanyRelationshipAssessment v1 = buildFinalizedV1();
            when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
                    ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.of(v1));

            // Exactly identical scores to v1
            CompleteOwnerAdjustmentRequest req = CompleteOwnerAdjustmentRequest.builder()
                    .sourceAssessmentId(100L)
                    .ownerCommercialScore(4)
                    .ownerCooperationScore(4)
                    .ownerStrategicScore(4)
                    .ownerRelationshipNetworkScore(3)
                    .ownerEngagementScore(4)
                    .ownerQualitativeScore(4)
                    .ownerAdjustmentReason("No score changes")
                    .build();

            BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                    service.completeDirectOwnerAdjustment(targetId, req, ownerUser));
            assertTrue(ex.getMessage().contains("ít nhất một tiêu chí thay đổi"));
        }

        @Test
        @DisplayName("Owner adjustment conflict: throws BusinessConflictException when source is stale")
        void testOwnerDirectCompletion_ThrowsConflict_WhenStaleSource() {
            CompanyRelationshipAssessment v2 = CompanyRelationshipAssessment.builder()
                    .id(200L)
                    .ownerCompanyProfileId(ownerId)
                    .companyProfileId(targetId)
                    .versionNumber(2)
                    .status(RelationshipAssessmentStatus.FINALIZED)
                    .build();

            when(assessmentRepository.findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
                    ownerId, targetId, RelationshipAssessmentStatus.FINALIZED)).thenReturn(Optional.of(v2));

            CompleteOwnerAdjustmentRequest req = CompleteOwnerAdjustmentRequest.builder()
                    .sourceAssessmentId(100L) // points to stale v1
                    .ownerCommercialScore(5)
                    .ownerCooperationScore(5)
                    .ownerStrategicScore(5)
                    .ownerRelationshipNetworkScore(5)
                    .ownerEngagementScore(5)
                    .ownerQualitativeScore(5)
                    .build();

            assertThrows(BusinessConflictException.class, () ->
                    service.completeDirectOwnerAdjustment(targetId, req, ownerUser));
        }

        @Test
        @DisplayName("Manager and Staff cannot perform Owner adjustment")
        void testOwnerDirectCompletion_UnauthorizedRoles() {
            CompleteOwnerAdjustmentRequest req = CompleteOwnerAdjustmentRequest.builder()
                    .sourceAssessmentId(100L)
                    .ownerCommercialScore(5)
                    .ownerCooperationScore(4)
                    .ownerStrategicScore(4)
                    .ownerRelationshipNetworkScore(3)
                    .ownerEngagementScore(4)
                    .ownerQualitativeScore(4)
                    .build();

            assertThrows(AccessDeniedException.class, () ->
                    service.completeDirectOwnerAdjustment(targetId, req, managerUser));

            assertThrows(AccessDeniedException.class, () ->
                    service.completeDirectOwnerAdjustment(targetId, req, staffUser));
        }
    }

    @Nested
    @DisplayName("Disabled Legacy Draft Endpoints Test Suite")
    class LegacyDraftEndpointsDisabledTests {

        @Test
        @DisplayName("All legacy draft lifecycle methods throw BusinessValidationException and never persist drafts")
        void testAllLegacyDraftEndpoints_ThrowValidationException_AndNeverPersist() {
            // 1. createDraft
            BusinessValidationException e1 = assertThrows(BusinessValidationException.class, () ->
                    service.createDraft(targetId, null, managerUser));
            assertTrue(e1.getMessage().contains("Draft workflow is no longer supported"));

            // 2. updateDraft
            BusinessValidationException e2 = assertThrows(BusinessValidationException.class, () ->
                    service.updateDraft(100L, null, managerUser));
            assertTrue(e2.getMessage().contains("Draft workflow is no longer supported"));

            // 3. submitAssessment
            BusinessValidationException e3 = assertThrows(BusinessValidationException.class, () ->
                    service.submitAssessment(100L, managerUser));
            assertTrue(e3.getMessage().contains("Draft workflow is no longer supported"));

            // 4. requestChanges
            BusinessValidationException e4 = assertThrows(BusinessValidationException.class, () ->
                    service.requestChanges(100L, null, ownerUser));
            assertTrue(e4.getMessage().contains("Draft workflow is no longer supported"));

            // 5. finalizeAssessment
            BusinessValidationException e5 = assertThrows(BusinessValidationException.class, () ->
                    service.finalizeAssessment(100L, null, ownerUser));
            assertTrue(e5.getMessage().contains("Draft workflow is no longer supported"));

            // 6. createNewVersion
            BusinessValidationException e6 = assertThrows(BusinessValidationException.class, () ->
                    service.createNewVersion(targetId, managerUser));
            assertTrue(e6.getMessage().contains("Draft workflow is no longer supported"));

            // 7. createOwnerAdjustment
            BusinessValidationException e7 = assertThrows(BusinessValidationException.class, () ->
                    service.createOwnerAdjustment(100L, ownerUser));
            assertTrue(e7.getMessage().contains("Draft workflow is no longer supported"));

            // 8. updateOwnerAdjustment
            BusinessValidationException e8 = assertThrows(BusinessValidationException.class, () ->
                    service.updateOwnerAdjustment(100L, null, ownerUser));
            assertTrue(e8.getMessage().contains("Draft workflow is no longer supported"));

            // 9. cancelOwnerAdjustment
            BusinessValidationException e9 = assertThrows(BusinessValidationException.class, () ->
                    service.cancelOwnerAdjustment(100L, ownerUser));
            assertTrue(e9.getMessage().contains("Draft workflow is no longer supported"));

            // 10. legacy completeAssessment by draft id
            BusinessValidationException e10 = assertThrows(BusinessValidationException.class, () ->
                    service.completeAssessment(100L, null, managerUser));
            assertTrue(e10.getMessage().contains("Draft workflow is no longer supported"));

            // 11. legacy completeOwnerAdjustment by draft id
            BusinessValidationException e11 = assertThrows(BusinessValidationException.class, () ->
                    service.completeOwnerAdjustment(100L, null, ownerUser));
            assertTrue(e11.getMessage().contains("Draft workflow is no longer supported"));

            // Crucial verification: assessmentRepository.save() or saveAndFlush() is NEVER invoked!
            verify(assessmentRepository, never()).save(any());
            verify(assessmentRepository, never()).saveAndFlush(any());
        }
    }
}

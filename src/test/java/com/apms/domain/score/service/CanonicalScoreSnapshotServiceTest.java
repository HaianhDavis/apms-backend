package com.apms.domain.score.service;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.profile.CompanyProfileVersion;
import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.score.RoleScoreRuleSet;
import com.apms.domain.score.ScoreSnapshot;
import com.apms.domain.score.dto.RoleEvaluationCalculationRequest;
import com.apms.domain.score.dto.RoleEvaluationCalculationResult;
import com.apms.domain.score.engine.RoleScoringEngine;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.enums.WeightSource;
import com.apms.domain.score.enums.WeightingMethod;
import com.apms.domain.score.repository.sql.RoleScoreRuleSetRepository;
import com.apms.domain.score.repository.sql.ScoreSnapshotRepository;
import com.apms.domain.user.repository.sql.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CanonicalScoreSnapshotServiceTest {

    @Mock
    private RoleScoringEngine scoringEngine;
    @Mock
    private ScoreSnapshotRepository scoreSnapshotRepository;
    @Mock
    private RoleScoreRuleSetRepository ruleSetRepository;
    @Mock
    private OwnerOrganizationService ownerOrganizationService;
    @Mock
    private CompanyProfileVersionRepository versionRepository;
    @Mock
    private CanonicalScoreJsonMapper jsonMapper;
    @Mock
    private AccountRepository accountRepository;

    @Captor
    private ArgumentCaptor<ScoreSnapshot> snapshotCaptor;

    private CanonicalScoreSnapshotService snapshotService;

    @BeforeEach
    void setUp() {
        snapshotService = new CanonicalScoreSnapshotService(
                scoringEngine,
                scoreSnapshotRepository,
                ruleSetRepository,
                ownerOrganizationService,
                versionRepository,
                jsonMapper,
                accountRepository
        );
    }

    @Test
    void shouldRejectOwnerAsTarget() {
        when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("owner123");

        RoleEvaluationCalculationRequest req = RoleEvaluationCalculationRequest.builder()
                .targetCompanyProfileId("owner123")
                .build();

        assertThatThrownBy(() -> snapshotService.createCanonicalSnapshot(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("The Owner Organization cannot be evaluated as a target");
    }

    @Test
    void shouldRejectReferenceMismatch() {
        when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("owner123");
        when(versionRepository.findByCompanyProfileIdAndVersion("target123", 1)).thenReturn(Optional.of(mock(CompanyProfileVersion.class)));

        RoleEvaluationCalculationRequest req = RoleEvaluationCalculationRequest.builder()
                .targetCompanyProfileId("target123")
                .targetProfileVersion(1)
                .referenceCompanyProfileId("wrong_ref")
                .build();

        assertThatThrownBy(() -> snapshotService.createCanonicalSnapshot(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Reference company must be the configured Owner");
    }

    @Test
    void shouldRejectMissingTargetVersion() {
        when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("owner123");
        when(versionRepository.findByCompanyProfileIdAndVersion("target123", 1)).thenReturn(Optional.empty());

        RoleEvaluationCalculationRequest req = RoleEvaluationCalculationRequest.builder()
                .targetCompanyProfileId("target123")
                .targetProfileVersion(1)
                .build();

        assertThatThrownBy(() -> snapshotService.createCanonicalSnapshot(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Target CompanyProfile version snapshot was not found");
    }

    @Test
    void shouldRejectMissingReferenceVersion() {
        when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("owner123");
        when(versionRepository.findByCompanyProfileIdAndVersion("target123", 1)).thenReturn(Optional.of(mock(CompanyProfileVersion.class)));
        when(versionRepository.findByCompanyProfileIdAndVersion("owner123", 1)).thenReturn(Optional.empty());

        RoleEvaluationCalculationRequest req = RoleEvaluationCalculationRequest.builder()
                .targetCompanyProfileId("target123")
                .targetProfileVersion(1)
                .referenceCompanyProfileId("owner123")
                .referenceProfileVersion(1)
                .build();

        assertThatThrownBy(() -> snapshotService.createCanonicalSnapshot(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Reference CompanyProfile version snapshot was not found");
    }

    @Test
    void shouldSaveCanonicalSnapshotAndLeaveLegacyFieldsNull() {
        when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("owner123");
        when(versionRepository.findByCompanyProfileIdAndVersion("target123", 1)).thenReturn(Optional.of(mock(CompanyProfileVersion.class)));
        when(versionRepository.findByCompanyProfileIdAndVersion("owner123", 2)).thenReturn(Optional.of(mock(CompanyProfileVersion.class)));

        RoleEvaluationCalculationRequest req = RoleEvaluationCalculationRequest.builder()
                .targetCompanyProfileId("target123")
                .targetProfileVersion(1)
                .referenceCompanyProfileId("owner123")
                .referenceProfileVersion(2)
                .evaluatedRole(CompanyRole.COMPETITOR)
                .ruleSetVersion("v1")
                .build();

        RoleEvaluationCalculationResult mockResult = RoleEvaluationCalculationResult.builder()
                .evaluatedRole(CompanyRole.COMPETITOR)
                .overallScore(new BigDecimal("85.50"))
                .completenessStatus(EvaluationCompletenessStatus.COMPLETE)
                .ruleSetVersion("v1")
                .weightingMethod(WeightingMethod.AHP)
                .weightSource(WeightSource.ILLUSTRATIVE)
                .weightVersion("wv1")
                .build();

        when(scoringEngine.calculate(req)).thenReturn(mockResult);

        RoleScoreRuleSet mockRuleSet = RoleScoreRuleSet.builder().id(99L).build();
        when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.COMPETITOR, "v1")).thenReturn(Optional.of(mockRuleSet));

        when(scoreSnapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        snapshotService.createCanonicalSnapshot(req);

        // Assert
        verify(scoreSnapshotRepository).save(snapshotCaptor.capture());
        ScoreSnapshot saved = snapshotCaptor.getValue();

        // Check canonical fields
        assertThat(saved.getTargetCompanyProfileId()).isEqualTo("target123");
        assertThat(saved.getTargetProfileVersion()).isEqualTo(1);
        assertThat(saved.getReferenceCompanyProfileId()).isEqualTo("owner123");
        assertThat(saved.getReferenceProfileVersion()).isEqualTo(2);
        assertThat(saved.getEvaluatedRole()).isEqualTo(CompanyRole.COMPETITOR);
        assertThat(saved.getRoleScoreRuleSet().getId()).isEqualTo(99L);
        assertThat(saved.getOverallScore()).isEqualTo(new BigDecimal("85.50"));
        assertThat(saved.getCompletenessStatus()).isEqualTo(EvaluationCompletenessStatus.COMPLETE);

        // Check legacy fields remain null
        assertThat(saved.getCompanyId()).isNull();
        assertThat(saved.getProject()).isNull();
        assertThat(saved.getCandidateId()).isNull();
        assertThat(saved.getRuleVersion()).isNull();
        assertThat(saved.getPartnerFitScore()).isNull();
        assertThat(saved.getCompetitionLevel()).isNull();
        assertThat(saved.getRiskLevel()).isNull();
        assertThat(saved.getRelationshipStrength()).isNull();
        assertThat(saved.getTotalScore()).isNull();
    }
}

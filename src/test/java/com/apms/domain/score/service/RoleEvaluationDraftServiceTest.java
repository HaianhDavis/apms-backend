package com.apms.domain.score.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.RelationshipType;
import com.apms.common.enums.TaskType;
import com.apms.config.OwnerOrganizationProperties;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.CompanyProfileVersion;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.score.RoleScoreRuleSet;
import com.apms.domain.score.draft.AutomaticSuggestion;
import com.apms.domain.score.draft.CriterionInput;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.RoleEvaluationCalculationRequest;
import com.apms.domain.score.dto.RoleEvaluationCalculationResult;
import com.apms.domain.score.dto.draft.AcceptAutomaticSuggestionRequest;
import com.apms.domain.score.dto.draft.CreateEvidenceRequest;
import com.apms.domain.score.dto.draft.CreateRoleEvaluationDraftRequest;
import com.apms.domain.score.dto.draft.RoleEvaluationDraftResponse;
import com.apms.domain.score.dto.draft.RoleEvaluationPreviewResponse;
import com.apms.domain.score.dto.draft.UpdateCriterionInputRequest;
import com.apms.domain.score.engine.RoleScoringEngine;
import com.apms.domain.score.enums.CriterionInputMethod;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import com.apms.domain.score.repository.sql.RoleScoreRuleSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.apms.domain.score.enums.CriterionSuggestionValidationStatus;
import com.apms.domain.score.service.CriterionSuggestionValidator.SuggestionValidationResult;
import java.util.ArrayList;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoleEvaluationDraftServiceTest {

    @Mock private RoleEvaluationDraftRepository draftRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectTaskRepository taskRepository;
    @Mock private RoleScoreRuleSetRepository ruleSetRepository;
    @Mock private CompanyProfileIdentifierResolver identifierResolver;
    @Mock private OwnerOrganizationProperties ownerProperties;
    @Mock private CompetitorComparisonService comparisonService;
    @Mock private RoleScoringEngine scoringEngine;
    @Mock private RelationshipTypeToCompanyRoleMapper roleMapper;
    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CriterionSuggestionValidator suggestionValidator;

    @InjectMocks
    private RoleEvaluationDraftService service;

    private Project project;
    private ProjectTask task;
    private CompanyProfile targetProfile;
    private CompanyProfile fptProfile;
    private RoleScoreRuleSet ruleSet;

    @BeforeEach
    void setUp() {
        project = new Project();
        project.setId(1L);
        project.setTargetCompanyProfileId("target-company-uuid");
        project.setTargetRelationshipType(RelationshipType.COMPETITOR_OF);

        task = new ProjectTask();
        task.setId(10L);
        task.setTaskType(TaskType.ROLE_EVALUATION);
        task.setProject(project);

        targetProfile = new CompanyProfile();
        targetProfile.setId("target-profile-doc-id");
        targetProfile.setCompanyId("target-company-uuid");
        targetProfile.setVersion(1);
        targetProfile.setReviewStatus("APPROVED");

        fptProfile = new CompanyProfile();
        fptProfile.setId("fpt-profile-doc-id");
        fptProfile.setCompanyId("fpt-company-uuid");
        fptProfile.setVersion(2);
        fptProfile.setReviewStatus("APPROVED");

        ruleSet = new RoleScoreRuleSet();
        ruleSet.setEvaluatedRole(CompanyRole.COMPETITOR);
        ruleSet.setRuleSetVersion("v1");

        lenient().when(ownerProperties.getCompanyProfileId()).thenReturn("fpt-profile-doc-id");
        lenient().when(roleMapper.map(RelationshipType.COMPETITOR_OF)).thenReturn(CompanyRole.COMPETITOR);
        lenient().when(suggestionValidator.validate(any(), any(), any())).thenReturn(
                new SuggestionValidationResult(CriterionSuggestionValidationStatus.PASS, false, new ArrayList<>())
        );
    }

    // --- Create Draft ---

    @Test
    void createDraft_Success() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));

        when(identifierResolver.resolveTargetProfile("target-company-uuid")).thenReturn(targetProfile);
        when(identifierResolver.resolveProfileByDocumentId("fpt-profile-doc-id")).thenReturn(fptProfile);

        CompanyProfileVersion targetVersion = CompanyProfileVersion.builder().version(1).build();
        when(identifierResolver.resolveVersion("target-profile-doc-id", 1)).thenReturn(targetVersion);

        CompanyProfileVersion fptVersion = CompanyProfileVersion.builder().version(2).build();
        when(identifierResolver.resolveVersion("fpt-profile-doc-id", 2)).thenReturn(fptVersion);

        when(ruleSetRepository.findByEvaluatedRoleAndActiveTrue(CompanyRole.COMPETITOR)).thenReturn(Optional.of(ruleSet));
        when(draftRepository.existsByActiveDraftKey("1:10:COMPETITOR")).thenReturn(false);

        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setId("draft-id-123");
        draft.setEvaluatedRole(CompanyRole.COMPETITOR);
        when(draftRepository.save(any(RoleEvaluationDraft.class))).thenReturn(draft);

        RoleEvaluationDraftResponse response = service.createDraft(1L, 10L, new CreateRoleEvaluationDraftRequest(), 999L);

        assertNotNull(response);
        verify(draftRepository).save(any(RoleEvaluationDraft.class));
        verify(auditLogService).log(eq(999L), eq(AuditAction.ROLE_EVALUATION_DRAFT_CREATED), eq("ROLE_EVALUATION_DRAFT"), any(), any());
    }

    @Test
    void createDraft_OwnerTarget_ThrowsException() {
        project.setTargetCompanyProfileId("fpt-profile-doc-id");

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));

        CompanyProfile targetAsOwner = new CompanyProfile();
        targetAsOwner.setId("fpt-profile-doc-id");
        targetAsOwner.setCompanyId("fpt-company-uuid");
        when(identifierResolver.resolveTargetProfile("fpt-profile-doc-id")).thenReturn(targetAsOwner);

        assertThrows(IllegalArgumentException.class, () ->
            service.createDraft(1L, 10L, new CreateRoleEvaluationDraftRequest(), 999L));
    }

    // --- Update Criterion ---

    @Test
    void updateCriterion_Success() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setId("draft-1");
        draft.setStatus(RoleEvaluationStatus.DRAFT);
        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));
        when(draftRepository.save(any())).thenReturn(draft);

        UpdateCriterionInputRequest req = new UpdateCriterionInputRequest();
        req.setRawScore(new BigDecimal("80.0"));
        req.setExplanation("Test explanation");

        RoleEvaluationDraftResponse res = service.updateCriterionInput("draft-1", "someCriterion", req, 999L);

        assertNotNull(res);
        CriterionInput input = draft.getCriterionInputs().get("someCriterion");
        assertEquals(new BigDecimal("80.0"), input.getRawScore());
        assertEquals(CriterionInputMethod.MANUAL_REVIEWED, input.getInputMethod());
        assertFalse(input.getManagerConfirmed());

        verify(auditLogService).log(eq(999L), eq(AuditAction.ROLE_EVALUATION_CRITERION_UPDATED), any(), eq("draft-1"), any());
    }

    // --- Suggestion & Accept ---

    @Test
    void suggestProductMarketOverlap_Success() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setId("draft-1");
        draft.setTargetProfileDocumentId("target-doc");
        draft.setReferenceProfileDocumentId("ref-doc");
        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));
        when(draftRepository.save(any())).thenReturn(draft);

        when(identifierResolver.resolveProfileByDocumentId("target-doc")).thenReturn(targetProfile);
        when(identifierResolver.resolveProfileByDocumentId("ref-doc")).thenReturn(fptProfile);

        AutomaticSuggestion suggestion = new AutomaticSuggestion();
        suggestion.setSuggestedRawScore(new BigDecimal("50.0"));
        when(comparisonService.suggestProductMarketOverlap(targetProfile, fptProfile, com.apms.domain.score.enums.OverlapSuggestionMode.LEGACY_PARTIAL)).thenReturn(suggestion);

        RoleEvaluationDraftResponse res = service.suggestProductMarketOverlap("draft-1");

        assertNotNull(res);
        assertNotNull(draft.getAutomaticSuggestions().get("productMarketOverlapScore"));
        verify(draftRepository).save(draft);
    }

    @Test
    void acceptAutomaticSuggestion_Success() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setId("draft-1");
        draft.setStatus(RoleEvaluationStatus.DRAFT);

        AutomaticSuggestion suggestion = new AutomaticSuggestion();
        suggestion.setSuggestedRawScore(new BigDecimal("75.0"));
        suggestion.setSuggestionRationale("Matched well");
        draft.getAutomaticSuggestions().put("productMarketOverlapScore", suggestion);

        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));
        when(draftRepository.save(any())).thenReturn(draft);

        AcceptAutomaticSuggestionRequest req = new AcceptAutomaticSuggestionRequest();
        req.setExplanation("Extra note");

        service.acceptAutomaticSuggestion("draft-1", req, 999L);

        CriterionInput input = draft.getCriterionInputs().get("productMarketOverlapScore");
        assertNotNull(input);
        assertEquals(new BigDecimal("75.0"), input.getRawScore());
        assertEquals(CriterionInputMethod.AUTOMATIC_PROPOSAL, input.getInputMethod());
        assertTrue(suggestion.getAccepted());

        verify(auditLogService).log(eq(999L), eq(AuditAction.ROLE_EVALUATION_SUGGESTION_ACCEPTED), any(), any(), any());
    }

    // --- Preview ---

    @Test
    void calculatePreview_Success() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setId("draft-1");
        draft.setEvaluatedRole(CompanyRole.COMPETITOR);

        CriterionInput input = new CriterionInput();
        input.setRawScore(new BigDecimal("90.0"));
        draft.getCriterionInputs().put("someCriterion", input);

        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));
        when(ruleSetRepository.findByEvaluatedRoleAndActiveTrue(CompanyRole.COMPETITOR)).thenReturn(Optional.of(ruleSet));

        RoleEvaluationCalculationResult result = RoleEvaluationCalculationResult.builder()
                .completenessStatus(EvaluationCompletenessStatus.INCOMPLETE)
                .overallScore(null)
                .build();
        when(scoringEngine.calculate(any())).thenReturn(result);

        RoleEvaluationPreviewResponse response = service.calculatePreview("draft-1");

        assertNotNull(response);
        assertEquals(EvaluationCompletenessStatus.INCOMPLETE, response.getCompletenessStatus());
        assertNull(response.getPreviewOverallScore());

        verify(draftRepository, never()).save(any());
        verify(auditLogService, never()).log(any(), any(), any(), any(), any());
    }
}

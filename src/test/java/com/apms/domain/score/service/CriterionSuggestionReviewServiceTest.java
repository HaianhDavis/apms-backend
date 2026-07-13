package com.apms.domain.score.service;

import com.apms.common.enums.RelationshipType;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.score.draft.AutomaticSuggestion;
import com.apms.domain.score.draft.CriterionInput;
import com.apms.domain.score.draft.EvidenceRecord;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.AcceptAutomaticSuggestionRequest;
import com.apms.domain.score.dto.draft.EditCriterionSuggestionRequest;
import com.apms.domain.score.dto.draft.NeedsMoreDataCriterionSuggestionRequest;
import com.apms.domain.score.dto.draft.RejectCriterionSuggestionRequest;
import com.apms.domain.score.enums.CriterionInputMethod;
import com.apms.domain.score.enums.CriterionSuggestionReviewStatus;
import com.apms.domain.score.enums.CriterionSuggestionValidationStatus;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import com.apms.domain.score.registry.CanonicalRoleCriteria;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.bson.Document;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CriterionSuggestionReviewServiceTest {

    @Mock
    private RoleEvaluationDraftRepository draftRepository;

    @Mock
    private AuditLogService auditLogService;

    private CriterionSuggestionValidator validator;

    private RoleEvaluationDraftService service;

    @BeforeEach
    void setup() {
        validator = new CriterionSuggestionValidator();
        service = new RoleEvaluationDraftService(
                draftRepository, null, null, null, null, null, null, null, null, auditLogService, validator
        );
        lenient().when(draftRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void testScoreRangeValidation() {
        RoleEvaluationDraft draft = createDraftWithSuggestion("productMarketOverlapScore", new BigDecimal("-1"));
        AcceptAutomaticSuggestionRequest req = new AcceptAutomaticSuggestionRequest();
        req.setExplanation("test");

        assertThatThrownBy(() -> service.acceptCriterionSuggestion("draft1", "productMarketOverlapScore", req, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 100");

        draft.getAutomaticSuggestions().get("productMarketOverlapScore").setSuggestedRawScore(new BigDecimal("101"));
        assertThatThrownBy(() -> service.acceptCriterionSuggestion("draft1", "productMarketOverlapScore", req, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 100");
    }

    @Test
    void testConfidenceAndCoverageRangeValidation() {
        RoleEvaluationDraft draft = createDraftWithSuggestion("productMarketOverlapScore", new BigDecimal("50"));
        draft.getAutomaticSuggestions().get("productMarketOverlapScore").setConfidence(new BigDecimal("1.5"));
        AcceptAutomaticSuggestionRequest req = new AcceptAutomaticSuggestionRequest();
        req.setExplanation("test");

        assertThatThrownBy(() -> service.acceptCriterionSuggestion("draft1", "productMarketOverlapScore", req, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Confidence must be between 0 and 1");

        draft.getAutomaticSuggestions().get("productMarketOverlapScore").setConfidence(new BigDecimal("0.5"));
        draft.getAutomaticSuggestions().get("productMarketOverlapScore").setEvidenceCoverage(new BigDecimal("-0.1"));

        assertThatThrownBy(() -> service.acceptCriterionSuggestion("draft1", "productMarketOverlapScore", req, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Evidence coverage must be between 0 and 1");
    }

    @Test
    void testUnknownCriterionRejection() {
        RoleEvaluationDraft draft = createDraftWithSuggestion("unknown", new BigDecimal("50"));
        AcceptAutomaticSuggestionRequest req = new AcceptAutomaticSuggestionRequest();
        req.setExplanation("test");

        assertThatThrownBy(() -> service.acceptCriterionSuggestion("draft1", "unknown", req, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown criterion key");
    }

    @Test
    void testMissingEvidenceReferenceRejection() {
        RoleEvaluationDraft draft = createDraftWithSuggestion("productMarketOverlapScore", new BigDecimal("50"));
        draft.getAutomaticSuggestions().get("productMarketOverlapScore").setEvidenceIds(List.of("bad-id"));

        AcceptAutomaticSuggestionRequest req = new AcceptAutomaticSuggestionRequest();
        req.setExplanation("test");

        assertThatThrownBy(() -> service.acceptCriterionSuggestion("draft1", "productMarketOverlapScore", req, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Referenced evidence ID not found");
    }

    @Test
    void testNonNullScoreRequiresExplanation() {
        RoleEvaluationDraft draft = createDraftWithSuggestion("productMarketOverlapScore", new BigDecimal("50"));
        draft.getAutomaticSuggestions().get("productMarketOverlapScore").setExplanation(null);
        draft.getAutomaticSuggestions().get("productMarketOverlapScore").setSuggestionRationale(null);

        AcceptAutomaticSuggestionRequest req = new AcceptAutomaticSuggestionRequest();

        assertThatThrownBy(() -> service.acceptCriterionSuggestion("draft1", "productMarketOverlapScore", req, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Explanation is required");
    }

    @Test
    void testNullScoreMayUseNeedsMoreData() {
        RoleEvaluationDraft draft = createDraftWithSuggestion("productMarketOverlapScore", null);
        draft.getAutomaticSuggestions().get("productMarketOverlapScore").setReviewStatus(CriterionSuggestionReviewStatus.NEEDS_MORE_DATA);
        draft.getAutomaticSuggestions().get("productMarketOverlapScore").setMissingData(List.of("Missing stuff"));

        NeedsMoreDataCriterionSuggestionRequest req = new NeedsMoreDataCriterionSuggestionRequest();
        req.setReviewComment("Need data");
        req.setMissingData(List.of("Missing stuff"));

        service.markSuggestionNeedsMoreData("draft1", "productMarketOverlapScore", req, 1L);
        assertThat(draft.getAutomaticSuggestions().get("productMarketOverlapScore").getReviewStatus()).isEqualTo(CriterionSuggestionReviewStatus.NEEDS_MORE_DATA);
    }

    @Test
    void testStaffAcceptsValidSuggestion() {
        RoleEvaluationDraft draft = createDraftWithSuggestion("productMarketOverlapScore", new BigDecimal("75"));
        draft.getAutomaticSuggestions().get("productMarketOverlapScore").setValidationStatus(CriterionSuggestionValidationStatus.PASS);

        AcceptAutomaticSuggestionRequest req = new AcceptAutomaticSuggestionRequest();
        req.setExplanation("Looks good");

        service.acceptCriterionSuggestion("draft1", "productMarketOverlapScore", req, 1L);

        CriterionInput input = draft.getCriterionInputs().get("productMarketOverlapScore");
        assertThat(input).isNotNull();
        assertThat(input.getRawScore()).isEqualTo(new BigDecimal("75"));
        assertThat(input.getInputMethod()).isEqualTo(CriterionInputMethod.AUTOMATIC_PROPOSAL);
        assertThat(input.getManagerConfirmed()).isFalse();
        assertThat(input.getExplanation()).contains("Looks good");

        assertThat(draft.getAutomaticSuggestions().get("productMarketOverlapScore").getReviewStatus()).isEqualTo(CriterionSuggestionReviewStatus.ACCEPTED);
        // Legacy support
        assertThat(draft.getAutomaticSuggestions().get("productMarketOverlapScore").getAccepted()).isTrue();
    }

    @Test
    void testStaffEditsSuggestionWithReason() {
        RoleEvaluationDraft draft = createDraftWithSuggestion("productMarketOverlapScore", new BigDecimal("75"));

        EditCriterionSuggestionRequest req = new EditCriterionSuggestionRequest();
        req.setRawScore(new BigDecimal("80"));
        req.setOverrideReason("Actually they overlap more");

        service.editCriterionSuggestion("draft1", "productMarketOverlapScore", req, 1L);

        CriterionInput input = draft.getCriterionInputs().get("productMarketOverlapScore");
        assertThat(input).isNotNull();
        assertThat(input.getRawScore()).isEqualTo(new BigDecimal("80"));
        assertThat(input.getPreviousValue()).isEqualTo(new BigDecimal("75"));
        assertThat(input.getInputMethod()).isEqualTo(CriterionInputMethod.MANUAL_OVERRIDE);
        assertThat(input.getOverrideReason()).isEqualTo("Actually they overlap more");

        assertThat(draft.getAutomaticSuggestions().get("productMarketOverlapScore").getReviewStatus()).isEqualTo(CriterionSuggestionReviewStatus.EDITED);
    }

    @Test
    void testStaffRejectsSuggestion() {
        RoleEvaluationDraft draft = createDraftWithSuggestion("productMarketOverlapScore", new BigDecimal("75"));

        // Setup an existing AUTOMATIC_PROPOSAL input to ensure it gets removed
        CriterionInput existing = new CriterionInput();
        existing.setInputMethod(CriterionInputMethod.AUTOMATIC_PROPOSAL);
        draft.getCriterionInputs().put("productMarketOverlapScore", existing);

        RejectCriterionSuggestionRequest req = new RejectCriterionSuggestionRequest();
        req.setReviewComment("Total garbage AI");

        service.rejectCriterionSuggestion("draft1", "productMarketOverlapScore", req, 1L);

        assertThat(draft.getCriterionInputs()).doesNotContainKey("productMarketOverlapScore");
        assertThat(draft.getAutomaticSuggestions().get("productMarketOverlapScore").getReviewStatus()).isEqualTo(CriterionSuggestionReviewStatus.REJECTED);
        assertThat(draft.getAutomaticSuggestions().get("productMarketOverlapScore").getReviewComment()).isEqualTo("Total garbage AI");
    }

    @Test
    void testFailSuggestionCannotBeAccepted() {
        RoleEvaluationDraft draft = createDraftWithSuggestion("productMarketOverlapScore", new BigDecimal("75"));
        draft.getAutomaticSuggestions().get("productMarketOverlapScore").setValidationStatus(CriterionSuggestionValidationStatus.FAIL);

        AcceptAutomaticSuggestionRequest req = new AcceptAutomaticSuggestionRequest();

        assertThatThrownBy(() -> service.acceptCriterionSuggestion("draft1", "productMarketOverlapScore", req, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot accept suggestion with status FAIL");
    }

    private RoleEvaluationDraft createDraftWithSuggestion(String key, BigDecimal score) {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setId("draft1");
        draft.setStatus(RoleEvaluationStatus.DRAFT);
        draft.setEvaluatedRole(CompanyRole.COMPETITOR);

        AutomaticSuggestion suggestion = new AutomaticSuggestion();
        suggestion.setCriterionKey(key);
        suggestion.setSuggestedRawScore(score);
        suggestion.setExplanation("A valid explanation");

        draft.getAutomaticSuggestions().put(key, suggestion);
        when(draftRepository.findById("draft1")).thenReturn(Optional.of(draft));
        return draft;
    }
}

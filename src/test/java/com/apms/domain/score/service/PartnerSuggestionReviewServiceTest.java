package com.apms.domain.score.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.score.draft.AutomaticSuggestion;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.PartnerSuggestionReviewRequest;
import com.apms.domain.score.enums.CriterionInputMethod;
import com.apms.domain.score.enums.CriterionSuggestionReviewStatus;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PartnerSuggestionReviewServiceTest {

    private RoleEvaluationDraftRepository draftRepository;
    private PartnerSuggestionReviewService service;

    @BeforeEach
    void setUp() {
        draftRepository = Mockito.mock(RoleEvaluationDraftRepository.class);
        service = new PartnerSuggestionReviewService(draftRepository);
    }

    @Test
    void testHandlesAcceptedAsIs() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setAutomaticSuggestions(new java.util.LinkedHashMap<>());
        draft.setCriterionInputs(new java.util.LinkedHashMap<>());
        
        AutomaticSuggestion suggestion = new AutomaticSuggestion();
        suggestion.setCriterionKey("businessValueContributionScore");
        suggestion.setSuggestionRationale("Original AI Rationale");
        suggestion.setEvidenceIds(List.of("ev1"));
        draft.getAutomaticSuggestions().put("businessValueContributionScore", suggestion);

        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));

        PartnerSuggestionReviewRequest req = new PartnerSuggestionReviewRequest();
        req.setStatus(CriterionSuggestionReviewStatus.ACCEPTED);

        service.reviewSuggestion("draft-1", "businessValueContributionScore", req, 999L);

        verify(draftRepository).save(draft);

        var input = draft.getCriterionInputs().get("businessValueContributionScore");
        assertNotNull(input);
        assertEquals("Original AI Rationale", input.getExplanation());
        assertEquals(CriterionInputMethod.AI_ASSISTED, input.getInputMethod());
        assertEquals(999L, input.getPreparedByAccountId());

        assertEquals(CriterionSuggestionReviewStatus.ACCEPTED, suggestion.getReviewStatus());
        assertTrue(suggestion.getAccepted());
    }

    @Test
    void testHandlesEditedRationale() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setAutomaticSuggestions(new java.util.LinkedHashMap<>());
        draft.setCriterionInputs(new java.util.LinkedHashMap<>());
        
        AutomaticSuggestion suggestion = new AutomaticSuggestion();
        suggestion.setCriterionKey("businessValueContributionScore");
        suggestion.setSuggestionRationale("Original AI Rationale");
        suggestion.setEvidenceIds(List.of("ev1"));
        draft.getAutomaticSuggestions().put("businessValueContributionScore", suggestion);

        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));

        PartnerSuggestionReviewRequest req = new PartnerSuggestionReviewRequest();
        req.setStatus(CriterionSuggestionReviewStatus.EDITED);
        req.setEditedRationale("Edited by staff");

        service.reviewSuggestion("draft-1", "businessValueContributionScore", req, 999L);

        verify(draftRepository).save(draft);

        var input = draft.getCriterionInputs().get("businessValueContributionScore");
        assertNotNull(input);
        assertEquals("Edited by staff", input.getExplanation());
        assertEquals(CriterionInputMethod.AI_ASSISTED_EDITED, input.getInputMethod());
        assertEquals(999L, input.getPreparedByAccountId());

        assertEquals("Original AI Rationale", suggestion.getSuggestionRationale());
        assertEquals(CriterionSuggestionReviewStatus.EDITED, suggestion.getReviewStatus());
        assertTrue(suggestion.getAccepted());
    }

    @Test
    void testHandlesRejected() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setAutomaticSuggestions(new java.util.LinkedHashMap<>());
        draft.setCriterionInputs(new java.util.LinkedHashMap<>());
        
        AutomaticSuggestion suggestion = new AutomaticSuggestion();
        suggestion.setCriterionKey("businessValueContributionScore");
        suggestion.setSuggestionRationale("Original AI Rationale");
        suggestion.setEvidenceIds(List.of("ev1"));
        draft.getAutomaticSuggestions().put("businessValueContributionScore", suggestion);

        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));

        PartnerSuggestionReviewRequest req = new PartnerSuggestionReviewRequest();
        req.setStatus(CriterionSuggestionReviewStatus.REJECTED);

        service.reviewSuggestion("draft-1", "businessValueContributionScore", req, 999L);

        verify(draftRepository).save(draft);

        var input = draft.getCriterionInputs().get("businessValueContributionScore");
        assertNull(input);

        assertEquals(CriterionSuggestionReviewStatus.REJECTED, suggestion.getReviewStatus());
        assertFalse(suggestion.getAccepted());
    }

    @Test
    void testThrowsIfEditedMissingRationale() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setAutomaticSuggestions(new java.util.LinkedHashMap<>());
        
        AutomaticSuggestion suggestion = new AutomaticSuggestion();
        suggestion.setCriterionKey("businessValueContributionScore");
        draft.getAutomaticSuggestions().put("businessValueContributionScore", suggestion);

        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));

        PartnerSuggestionReviewRequest req = new PartnerSuggestionReviewRequest();
        req.setStatus(CriterionSuggestionReviewStatus.EDITED);
        req.setEditedRationale("   "); // Blank

        assertThrows(BusinessValidationException.class, () -> 
            service.reviewSuggestion("draft-1", "businessValueContributionScore", req, 999L));
    }
}

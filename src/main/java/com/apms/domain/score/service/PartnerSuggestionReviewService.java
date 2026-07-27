package com.apms.domain.score.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.score.draft.AutomaticSuggestion;
import com.apms.domain.score.draft.CriterionInput;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.PartnerSuggestionReviewRequest;
import com.apms.domain.score.enums.CriterionInputMethod;
import com.apms.domain.score.enums.CriterionSuggestionReviewStatus;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class PartnerSuggestionReviewService {

    private final RoleEvaluationDraftRepository draftRepository;

    public void reviewSuggestion(String draftId, String criterionKey, PartnerSuggestionReviewRequest request, Long staffAccountId) {
        RoleEvaluationDraft draft = draftRepository.findById(draftId)
                .orElseThrow(() -> new BusinessValidationException("Draft not found"));

        // Validation for Staff assignment would go here or at controller level

        AutomaticSuggestion suggestion = draft.getAutomaticSuggestions().get(criterionKey);
        if (suggestion == null) {
            throw new BusinessValidationException("No AI suggestion exists for this criterion");
        }

        suggestion.setReviewedByAccountId(staffAccountId);
        suggestion.setReviewedAt(LocalDateTime.now());

        switch (request.getStatus()) {
            case ACCEPTED:
                CriterionInput inputAcc = draft.getCriterionInputs().computeIfAbsent(criterionKey, k -> new CriterionInput());
                inputAcc.setCriterionKey(criterionKey);
                suggestion.setReviewStatus(CriterionSuggestionReviewStatus.ACCEPTED);
                suggestion.setAccepted(true);
                suggestion.setAcceptedAt(LocalDateTime.now());
                suggestion.setAcceptedByAccountId(staffAccountId);

                inputAcc.setExplanation(suggestion.getSuggestionRationale());
                inputAcc.setEvidenceIds(new ArrayList<>(suggestion.getEvidenceIds()));
                inputAcc.setInputMethod(CriterionInputMethod.AI_ASSISTED);
                inputAcc.setPreparedByAccountId(staffAccountId);
                inputAcc.setPreparedAt(LocalDateTime.now());
                break;

            case EDITED:
                CriterionInput inputEdit = draft.getCriterionInputs().computeIfAbsent(criterionKey, k -> new CriterionInput());
                inputEdit.setCriterionKey(criterionKey);
                if (request.getEditedRationale() == null || request.getEditedRationale().trim().isEmpty()) {
                    throw new BusinessValidationException("Edited rationale must be provided");
                }
                suggestion.setReviewStatus(CriterionSuggestionReviewStatus.EDITED);
                suggestion.setAccepted(true); // Treat as accepted but edited
                suggestion.setAcceptedAt(LocalDateTime.now());
                suggestion.setAcceptedByAccountId(staffAccountId);

                // Original AI suggestion is preserved in `suggestion.getSuggestionRationale()`
                // Staff final rationale goes to `input.setExplanation()`
                inputEdit.setExplanation(request.getEditedRationale());
                inputEdit.setEvidenceIds(new ArrayList<>(suggestion.getEvidenceIds()));
                inputEdit.setInputMethod(CriterionInputMethod.AI_ASSISTED_EDITED);
                inputEdit.setPreparedByAccountId(staffAccountId);
                inputEdit.setPreparedAt(LocalDateTime.now());
                break;

            case REJECTED:
                draft.getCriterionInputs().remove(criterionKey);
                suggestion.setReviewStatus(CriterionSuggestionReviewStatus.REJECTED);
                suggestion.setAccepted(false);
                break;

            case NEEDS_MORE_DATA:
                suggestion.setReviewStatus(CriterionSuggestionReviewStatus.NEEDS_MORE_DATA);
                suggestion.setAccepted(false);
                break;

            default:
                throw new BusinessValidationException("Invalid review status");
        }

        draftRepository.save(draft);
    }
}

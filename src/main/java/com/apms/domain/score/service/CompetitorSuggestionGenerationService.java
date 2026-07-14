package com.apms.domain.score.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.ai.dto.CriterionSuggestionOutput;
import com.apms.domain.ai.service.provider.CriterionSuggestionProvider;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.score.draft.AutomaticSuggestion;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.CompetitorCriterionContext;
import com.apms.domain.score.dto.draft.GenerateSuggestionRequest;
import com.apms.domain.score.enums.CriterionSuggestionMethod;
import com.apms.domain.score.enums.CriterionSuggestionReviewStatus;
import com.apms.domain.score.enums.CriterionSuggestionValidationStatus;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompetitorSuggestionGenerationService {

    private final CompetitorComparisonService comparisonService;
    private final CompetitorCriterionEvidenceService evidenceService;
    private final CriterionSuggestionProvider aiProvider;
    private final RoleEvaluationDraftRepository draftRepository;
    private final ObjectMapper objectMapper;

    private static final List<String> ALL_CRITERIA = List.of(
            "productMarketOverlapScore",
            "marketPositionScore",
            "competitiveCapabilityScore",
            "strategicIntentScore",
            "growthMomentumScore",
            "competitiveThreatScore"
    );

    public Map<String, String> generateAll(RoleEvaluationDraft draft, GenerateSuggestionRequest request) {
        Map<String, String> outcomes = new HashMap<>();
        for (String criterionKey : ALL_CRITERIA) {
            String outcome = generateSingle(draft, criterionKey, request);
            outcomes.put(criterionKey, outcome);
        }
        draftRepository.save(draft);
        return outcomes;
    }

    public String generateSingleAndSave(RoleEvaluationDraft draft, String criterionKey, GenerateSuggestionRequest request) {
        String outcome = generateSingle(draft, criterionKey, request);
        draftRepository.save(draft);
        return outcome;
    }

    private String generateSingle(RoleEvaluationDraft draft, String criterionKey, GenerateSuggestionRequest request) {
        if (draft.getStatus() != com.apms.domain.score.enums.RoleEvaluationStatus.DRAFT &&
            draft.getStatus() != com.apms.domain.score.enums.RoleEvaluationStatus.REVISION_REQUIRED) {
            throw new BusinessValidationException("Draft is not editable");
        }

        AutomaticSuggestion existing = draft.getAutomaticSuggestions().get(criterionKey);
        boolean isReviewed = existing != null && (existing.getReviewStatus() == CriterionSuggestionReviewStatus.ACCEPTED || existing.getReviewStatus() == CriterionSuggestionReviewStatus.EDITED);

        boolean force = request != null && Boolean.TRUE.equals(request.getForce());
        boolean hasComment = request != null && request.getReviewComment() != null && !request.getReviewComment().trim().isEmpty();

        if (isReviewed) {
            if (!force || !hasComment) {
                return "PROTECTED_FROM_OVERWRITE";
            }
        }

        try {
            AutomaticSuggestion suggestion;
            if ("productMarketOverlapScore".equals(criterionKey)) {
                suggestion = generateOverlapSuggestion(draft, request);
            } else {
                suggestion = generateAiSuggestion(draft, criterionKey, request);
            }

            draft.getAutomaticSuggestions().put(criterionKey, suggestion);

            if (suggestion.getValidationStatus() == CriterionSuggestionValidationStatus.FAIL) {
                return "TECHNICAL_FAILURE";
            } else if (suggestion.getReviewStatus() == CriterionSuggestionReviewStatus.NEEDS_MORE_DATA) {
                return "NEEDS_MORE_DATA";
            }
            return "GENERATED";

        } catch (Exception e) {
            log.error("Technical failure generating suggestion for {}", criterionKey, e);
            AutomaticSuggestion failedSuggestion = new AutomaticSuggestion();
            failedSuggestion.setCriterionKey(criterionKey);
            failedSuggestion.setMethod(CriterionSuggestionMethod.AI_ASSISTED);
            failedSuggestion.setReviewStatus(CriterionSuggestionReviewStatus.PENDING);
            failedSuggestion.setValidationStatus(CriterionSuggestionValidationStatus.FAIL);
            failedSuggestion.setCalculationWarnings(List.of("Technical failure: " + e.getMessage()));
            failedSuggestion.setGeneratedAt(LocalDateTime.now());
            draft.getAutomaticSuggestions().put(criterionKey, failedSuggestion);
            return "TECHNICAL_FAILURE";
        }
    }

    private AutomaticSuggestion generateOverlapSuggestion(RoleEvaluationDraft draft, GenerateSuggestionRequest request) {
        // We need to fetch full snapshots via evidenceService (using a dummy context fetch for now to get versions)
        // A better way is to use CompetitorCriterionEvidenceService to fetch the versions directly.
        com.apms.domain.profile.CompanyProfileVersion referenceVersion = evidenceService.getAndValidateVersion(
                draft.getReferenceProfileDocumentId(),
                draft.getReferenceProfileVersion(),
                draft.getReferenceCompanyId()
        );
        com.apms.domain.profile.CompanyProfileVersion targetVersion = evidenceService.getAndValidateVersion(
                draft.getTargetProfileDocumentId(),
                draft.getTargetProfileVersion(),
                draft.getTargetCompanyId()
        );

        CompanyProfile referenceProfile = objectMapper.convertValue(referenceVersion.getSnapshot(), CompanyProfile.class);
        CompanyProfile targetProfile = objectMapper.convertValue(targetVersion.getSnapshot(), CompanyProfile.class);

        return comparisonService.suggestProductMarketOverlap(targetProfile, referenceProfile, com.apms.domain.score.enums.OverlapSuggestionMode.CANONICAL_STRICT);
    }

    private AutomaticSuggestion generateAiSuggestion(RoleEvaluationDraft draft, String criterionKey, GenerateSuggestionRequest request) {
        CompetitorCriterionContext context = evidenceService.buildContext(
                draft, criterionKey,
                request != null ? request.getPeriodStart() : null,
                request != null ? request.getPeriodEnd() : null
        );

        if (!preconditionsMet(criterionKey, context)) {
            AutomaticSuggestion needsMoreData = new AutomaticSuggestion();
            needsMoreData.setCriterionKey(criterionKey);
            needsMoreData.setMethod(CriterionSuggestionMethod.AI_ASSISTED);
            needsMoreData.setReviewStatus(CriterionSuggestionReviewStatus.NEEDS_MORE_DATA);
            needsMoreData.setValidationStatus(CriterionSuggestionValidationStatus.WARNING);
            needsMoreData.setMissingData(List.of("Failed preconditions for " + criterionKey));
            needsMoreData.setGeneratedAt(LocalDateTime.now());
            return needsMoreData;
        }

        String prompt = loadPrompt(criterionKey);

        CriterionSuggestionOutput output = aiProvider.generateCriterionSuggestion(context, prompt);

        AutomaticSuggestion suggestion = new AutomaticSuggestion();
        suggestion.setCriterionKey(output.getCriterionKey());
        suggestion.setSuggestedRawScore(output.getSuggestedRawScore());
        suggestion.setExplanation(output.getExplanation());
        suggestion.setSuggestionRationale(output.getExplanation());
        suggestion.setMissingData(output.getMissingData() != null ? output.getMissingData() : new ArrayList<>());
        suggestion.setMethod(CriterionSuggestionMethod.AI_ASSISTED);
        suggestion.setGeneratedAt(LocalDateTime.now());

        if (output.getSuggestedRawScore() == null) {
            suggestion.setValidationStatus(CriterionSuggestionValidationStatus.WARNING);
            suggestion.setReviewStatus(CriterionSuggestionReviewStatus.NEEDS_MORE_DATA);
        } else {
            suggestion.setValidationStatus(CriterionSuggestionValidationStatus.PASS);
            suggestion.setReviewStatus(CriterionSuggestionReviewStatus.PENDING);
        }

        if (context.getPeriodStart() != null && context.getPeriodEnd() != null) {
            LinkedHashMap<String, Object> details = new LinkedHashMap<>();
            details.put("periodStart", context.getPeriodStart());
            details.put("periodEnd", context.getPeriodEnd());
            suggestion.setCalculationDetails(details);
        }

        return suggestion;
    }

    private boolean preconditionsMet(String criterionKey, CompetitorCriterionContext context) {
        switch (criterionKey) {
            case "marketPositionScore":
                boolean hasRefMarket = context.getReferenceFacts().containsKey("markets") || context.getReferenceFacts().containsKey("mainMarkets") || context.getReferenceFacts().containsKey("industries") || context.getReferenceFacts().containsKey("targetCustomers");
                boolean hasTgtMarket = context.getTargetFacts().containsKey("markets") || context.getTargetFacts().containsKey("mainMarkets") || context.getTargetFacts().containsKey("industries") || context.getTargetFacts().containsKey("targetCustomers");
                boolean hasRefPos = context.getReferenceFacts().containsKey("marketShare") || context.getReferenceFacts().containsKey("brandRank") || context.getReferenceFacts().containsKey("clientCount");
                boolean hasTgtPos = context.getTargetFacts().containsKey("marketShare") || context.getTargetFacts().containsKey("brandRank") || context.getTargetFacts().containsKey("clientCount");
                return hasRefMarket && hasTgtMarket && hasRefPos && hasTgtPos;
            case "competitiveCapabilityScore":
                boolean hasRefConcreteCap = context.getReferenceFacts().containsKey("technologyCapabilities") || context.getReferenceFacts().containsKey("techStack") || context.getReferenceFacts().containsKey("patents") || context.getReferenceFacts().containsKey("rdInvestmentPercent") || context.getReferenceFacts().containsKey("techMaturityLevel") || context.getReferenceFacts().containsKey("productInnovationRate") || context.getReferenceFacts().containsKey("qualityCertifications");
                boolean hasTgtConcreteCap = context.getTargetFacts().containsKey("technologyCapabilities") || context.getTargetFacts().containsKey("techStack") || context.getTargetFacts().containsKey("patents") || context.getTargetFacts().containsKey("rdInvestmentPercent") || context.getTargetFacts().containsKey("techMaturityLevel") || context.getTargetFacts().containsKey("productInnovationRate") || context.getTargetFacts().containsKey("qualityCertifications");
                return hasRefConcreteCap && hasTgtConcreteCap;
            case "strategicIntentScore":
                return context.getExternalSignals().stream().anyMatch(signal -> {
                    String summary = (String) signal.getOrDefault("summary", "");
                    String title = (String) signal.getOrDefault("title", "");
                    String text = (title + " " + summary).toLowerCase();
                    return text.contains("expansion") || text.contains("acquisition") || text.contains("market entry") || text.contains("product launch") || text.contains("strategic partnership") || text.contains("announcement") || "STRATEGIC_ANNOUNCEMENT".equals(signal.get("category"));
                });
            case "growthMomentumScore":
                if (context.getPeriodStart() == null || context.getPeriodEnd() == null || context.getPeriodStart().equals(context.getPeriodEnd()) || context.getPeriodStart().isAfter(context.getPeriodEnd())) return false;

                boolean hasNumericGrowth = context.getTargetFacts().containsKey("revenueGrowth");

                boolean hasValidSignal = context.getExternalSignals().stream().anyMatch(signal -> {
                    String summary = (String) signal.getOrDefault("summary", "");
                    String title = (String) signal.getOrDefault("title", "");
                    String text = (title + " " + summary).toLowerCase();

                    if (text.trim().isEmpty()) return false;

                    boolean hasDate = signal.get("date") != null;
                    boolean hasProvenance = signal.get("source") != null;

                    return (text.contains("revenue growth") || text.contains("revenue") || text.contains("employee count") || text.contains("client count") || text.contains("growth")) && text.matches(".*\\d+.*") ||
                           (hasDate && text.contains("market expansion")) ||
                           (hasDate && text.contains("product launch")) ||
                           (hasDate && hasProvenance && (text.contains("funding") || text.contains("investment")));
                });

                return hasNumericGrowth || hasValidSignal;
            case "competitiveThreatScore":
                if (context.getDraftEvidence() == null || context.getDraftEvidence().isEmpty()) return false;

                return context.getDraftEvidence().stream().anyMatch(evidence -> {
                    String eventType = (String) evidence.get("eventType");
                    if (eventType == null) return false;

                    List<String> validTypes = List.of("HEAD_TO_HEAD_BID", "CUSTOMER_LOSS_TO_TARGET", "PRICING_PRESSURE", "PRODUCT_DISPLACEMENT", "DIRECT_ACCOUNT_ATTACK", "DIRECT_MARKET_ATTACK");
                    if (!validTypes.contains(eventType)) return false;

                    String refId = (String) evidence.get("referenceCompanyId");
                    String tgtId = (String) evidence.get("targetCompanyId");

                    // We don't have direct access to draft company IDs here, but the context is already built for this target.
                    // Actually, if we require them to be present in the evidence map:
                    if (refId == null || tgtId == null) return false;

                    boolean hasSource = evidence.get("source") != null || evidence.get("evidenceId") != null;
                    boolean hasDate = evidence.get("evidenceDate") != null || evidence.get("eventDate") != null;

                    return hasSource && hasDate;
                });
            default:
                return true;
        }
    }

    private String loadPrompt(String criterionKey) {
        try {
            String base = new String(new ClassPathResource("ai-prompts/competitor-scoring-base.prompt.md").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String specific = "";
            switch (criterionKey) {
                case "marketPositionScore":
                    specific = new String(new ClassPathResource("ai-prompts/competitor-market-position.prompt.md").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    break;
                case "competitiveCapabilityScore":
                    specific = new String(new ClassPathResource("ai-prompts/competitor-capability.prompt.md").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    break;
                case "strategicIntentScore":
                    specific = new String(new ClassPathResource("ai-prompts/competitor-strategic-intent.prompt.md").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    break;
                case "growthMomentumScore":
                    specific = new String(new ClassPathResource("ai-prompts/competitor-growth-momentum.prompt.md").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    break;
                case "competitiveThreatScore":
                    specific = new String(new ClassPathResource("ai-prompts/competitor-threat.prompt.md").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    break;
            }
            return base + "\n\n" + specific;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load prompt", e);
        }
    }
}

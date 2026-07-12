package com.apms.domain.score.service;

import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.score.draft.AutomaticSuggestion;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CompetitorComparisonService {

    private static final String RUBRIC_VERSION = "COMPETITOR_OVERLAP_RUBRIC_V1";
    
    private static final BigDecimal WEIGHT_PRODUCT_NAME = new BigDecimal("0.30");
    private static final BigDecimal WEIGHT_PRODUCT_CATEGORY = new BigDecimal("0.10");
    private static final BigDecimal WEIGHT_MARKET = new BigDecimal("0.25");
    private static final BigDecimal WEIGHT_INDUSTRY = new BigDecimal("0.20");
    private static final BigDecimal WEIGHT_TARGET_CUSTOMER = new BigDecimal("0.15");

    public AutomaticSuggestion suggestProductMarketOverlap(CompanyProfile target, CompanyProfile reference) {
        AutomaticSuggestion suggestion = new AutomaticSuggestion();
        suggestion.setCriterionKey("productMarketOverlapScore");
        suggestion.setRubricVersion(RUBRIC_VERSION);
        suggestion.setGeneratedAt(LocalDateTime.now());
        
        List<String> missingComponents = new ArrayList<>();
        List<String> calculationWarnings = new ArrayList<>();
        LinkedHashMap<String, BigDecimal> componentScores = new LinkedHashMap<>();
        LinkedHashMap<String, BigDecimal> componentWeights = new LinkedHashMap<>();
        
        // Extract data
        List<CompanyProfile.Product> targetProducts = getProducts(target);
        List<CompanyProfile.Product> referenceProducts = getProducts(reference);
        
        List<String> targetMarkets = getMarkets(target);
        List<String> referenceMarkets = getMarkets(reference);
        
        List<String> targetIndustries = getIndustries(target);
        List<String> referenceIndustries = getIndustries(reference);
        
        List<String> targetCustomers = getTargetCustomers(target);
        List<String> referenceCustomers = getTargetCustomers(reference);

        // Calculate components
        BigDecimal productNameOverlap = calculateProductOverlap(targetProducts, referenceProducts, true);
        processComponent("productNameOverlap", productNameOverlap, WEIGHT_PRODUCT_NAME, 
                componentScores, componentWeights, missingComponents);

        BigDecimal productCategoryOverlap = calculateProductOverlap(targetProducts, referenceProducts, false);
        processComponent("productCategoryOverlap", productCategoryOverlap, WEIGHT_PRODUCT_CATEGORY, 
                componentScores, componentWeights, missingComponents);

        BigDecimal marketOverlap = calculateStringListOverlap(targetMarkets, referenceMarkets);
        processComponent("marketOverlap", marketOverlap, WEIGHT_MARKET, 
                componentScores, componentWeights, missingComponents);

        BigDecimal industryOverlap = calculateStringListOverlap(targetIndustries, referenceIndustries);
        processComponent("industryOverlap", industryOverlap, WEIGHT_INDUSTRY, 
                componentScores, componentWeights, missingComponents);

        BigDecimal targetCustomerOverlap = calculateStringListOverlap(targetCustomers, referenceCustomers);
        processComponent("targetCustomerOverlap", targetCustomerOverlap, WEIGHT_TARGET_CUSTOMER, 
                componentScores, componentWeights, missingComponents);

        suggestion.setComponentScores(componentScores);
        suggestion.setComponentWeights(componentWeights);
        suggestion.setMissingComponents(missingComponents);
        suggestion.setCalculationWarnings(calculationWarnings);

        // Check coverage
        boolean hasProductName = componentScores.containsKey("productNameOverlap");
        int additionalComponents = componentScores.size() - (hasProductName ? 1 : 0);
        
        BigDecimal totalCoverage = BigDecimal.ZERO;
        for (BigDecimal weight : componentWeights.values()) {
            totalCoverage = totalCoverage.add(weight);
        }
        suggestion.setComponentCoverage(totalCoverage);

        if (!hasProductName || additionalComponents < 2) {
            suggestion.setSuggestedRawScore(null);
            suggestion.setSuggestionRationale("Insufficient data for automatic proposal. Product name overlap and at least two other components are required.");
            calculationWarnings.add("Minimum proposal coverage not met.");
        } else {
            // Calculate final score
            BigDecimal totalScore = BigDecimal.ZERO;
            for (Map.Entry<String, BigDecimal> entry : componentScores.entrySet()) {
                BigDecimal score = entry.getValue();
                BigDecimal weight = componentWeights.get(entry.getKey());
                totalScore = totalScore.add(score.multiply(weight));
            }
            // Do not silently reweight. If totalCoverage < 1.0, the score is naturally lower.
            // Wait, the rubric says "Missing overlap components are not silently reweighted without coverage warnings."
            // "If minimum coverage is not met: suggestedRawScore = null ... Do not pass a partial suggestion to RoleScoringEngine as an official criterion input."
            // The score is just the sum of score * weight. But since it's an overall overlap out of 100, if they miss a component, should the score be divided by coverage?
            // "missing components are not silently reweighted". If we don't reweight, a missing component just counts as 0 overlap, which penalizes the competitor score. That's what "not silently reweighted" means.
            
            suggestion.setSuggestedRawScore(totalScore.setScale(2, RoundingMode.HALF_UP));
            
            if (missingComponents.isEmpty()) {
                suggestion.setSuggestionRationale("Automatic suggestion based on full product-market overlap components.");
            } else {
                suggestion.setSuggestionRationale("Automatic suggestion based on partial product-market overlap. Missing components: " + String.join(", ", missingComponents));
                calculationWarnings.add("Coverage warning: partial data available.");
            }
        }
        
        return suggestion;
    }
    
    private void processComponent(String name, BigDecimal score, BigDecimal weight, 
            LinkedHashMap<String, BigDecimal> scores, LinkedHashMap<String, BigDecimal> weights, List<String> missing) {
        if (score == null) {
            missing.add(name);
        } else {
            scores.put(name, score);
            weights.put(name, weight);
        }
    }

    private BigDecimal calculateProductOverlap(List<CompanyProfile.Product> target, List<CompanyProfile.Product> reference, boolean useName) {
        if (target == null && reference == null) return null;
        if (target == null || reference == null) return null; // "one side empty and completeness unknown -> null"
        
        List<String> tStrings = target.stream()
                .map(p -> useName ? p.getName() : p.getCategory())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
                
        List<String> rStrings = reference.stream()
                .map(p -> useName ? p.getName() : p.getCategory())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
                
        return calculateStringListOverlap(tStrings, rStrings);
    }

    private BigDecimal calculateStringListOverlap(List<String> target, List<String> reference) {
        if (target == null || reference == null) {
            return null;
        }
        
        // If one side confirmed complete and genuinely empty -> 0
        if (target.isEmpty() || reference.isEmpty()) {
            return BigDecimal.ZERO;
        }

        Set<String> tSet = normalize(target);
        Set<String> rSet = normalize(reference);

        if (tSet.isEmpty() || rSet.isEmpty()) {
            return BigDecimal.ZERO;
        }

        Set<String> intersection = new HashSet<>(tSet);
        intersection.retainAll(rSet);

        Set<String> union = new HashSet<>(tSet);
        union.addAll(rSet);

        if (union.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal num = new BigDecimal(intersection.size());
        BigDecimal den = new BigDecimal(union.size());
        
        return num.divide(den, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
    }

    private Set<String> normalize(List<String> inputs) {
        return inputs.stream()
                .filter(Objects::nonNull)
                .map(s -> Normalizer.normalize(s, Normalizer.Form.NFC))
                .map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .map(s -> s.replaceAll("\\s+", " ")) // collapse repeated whitespace
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private List<CompanyProfile.Product> getProducts(CompanyProfile profile) {
        if (profile == null || profile.getBusiness() == null) return null;
        return profile.getBusiness().getProducts();
    }

    private List<String> getMarkets(CompanyProfile profile) {
        if (profile == null || profile.getBusiness() == null) return null;
        return profile.getBusiness().getMarkets();
    }

    private List<String> getIndustries(CompanyProfile profile) {
        if (profile == null || profile.getBusiness() == null) return null;
        return profile.getBusiness().getIndustries();
    }

    private List<String> getTargetCustomers(CompanyProfile profile) {
        if (profile == null || profile.getBusiness() == null) return null;
        return profile.getBusiness().getTargetCustomers();
    }
}

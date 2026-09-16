package com.apms.domain.profile.assessment.policy;

import com.apms.domain.contract.entity.PartnerContract;
import lombok.Builder;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class RelationshipCommercialScoringPolicy {

    public static final String POLICY_VERSION_V1 = "RELATIONSHIP_CLOSENESS_V1";
    public static final String POLICY_VERSION_V2 = "RELATIONSHIP_CLOSENESS_V2";
    public static final String POLICY_VERSION_V3 = "RELATIONSHIP_CLOSENESS_V3";
    public static final String POLICY_VERSION_V4 = "RELATIONSHIP_CLOSENESS_V4";
    public static final String POLICY_VERSION_V5 = "RELATIONSHIP_CLOSENESS_V5";
    public static final String POLICY_VERSION = POLICY_VERSION_V5;

    // =========================================================================
    // V3 Thresholds (Total Max 50 pts)
    // =========================================================================
    // 1. Contract Value VND Thresholds (Max 20 pts)
    public static final BigDecimal VALUE_BAND_1 = new BigDecimal("500000000");       // 500M
    public static final BigDecimal VALUE_BAND_2 = new BigDecimal("2000000000");      // 2B
    public static final BigDecimal VALUE_BAND_3 = new BigDecimal("10000000000");     // 10B
    public static final BigDecimal VALUE_BAND_4 = new BigDecimal("50000000000");     // 50B

    public static final int V3_SCORE_VALUE_NONE = 0;
    public static final int V3_SCORE_VALUE_UNDER_500M = 4;
    public static final int V3_SCORE_VALUE_500M_TO_2B = 8;
    public static final int V3_SCORE_VALUE_2B_TO_10B = 12;
    public static final int V3_SCORE_VALUE_10B_TO_50B = 16;
    public static final int V3_SCORE_VALUE_OVER_50B = 20;

    // 2. Approved Contract Count (Max 10 pts)
    public static final int V3_SCORE_COUNT_0 = 0;
    public static final int V3_SCORE_COUNT_1 = 2;
    public static final int V3_SCORE_COUNT_2_TO_3 = 5;
    public static final int V3_SCORE_COUNT_4_TO_6 = 8;
    public static final int V3_SCORE_COUNT_7_PLUS = 10;

    // 3. Relationship Duration (Max 10 pts)
    public static final int V3_SCORE_DURATION_UNDER_6M = 2;
    public static final int V3_SCORE_DURATION_6M_TO_11M = 4;
    public static final int V3_SCORE_DURATION_12M_TO_23M = 6;
    public static final int V3_SCORE_DURATION_24M_TO_35M = 8;
    public static final int V3_SCORE_DURATION_36M_PLUS = 10;

    // 4. Contract Recency (Max 10 pts)
    public static final int V3_SCORE_RECENCY_UP_TO_3M = 10;
    public static final int V3_SCORE_RECENCY_4M_TO_6M = 8;
    public static final int V3_SCORE_RECENCY_7M_TO_12M = 5;
    public static final int V3_SCORE_RECENCY_13M_TO_24M = 2;
    public static final int V3_SCORE_RECENCY_OVER_24M = 0;

    // =========================================================================
    // Legacy V2 Thresholds (Total Max 35 pts)
    // =========================================================================
    public static final int SCORE_COUNT_0 = 0;
    public static final int SCORE_COUNT_1 = 2;
    public static final int SCORE_COUNT_2_TO_3 = 4;
    public static final int SCORE_COUNT_4_TO_6 = 6;
    public static final int SCORE_COUNT_7_PLUS = 8;

    public static final int SCORE_DURATION_NONE = 0;
    public static final int SCORE_DURATION_UNDER_6M = 1;
    public static final int SCORE_DURATION_6M_TO_12M = 2;
    public static final int SCORE_DURATION_1Y_TO_2Y = 4;
    public static final int SCORE_DURATION_OVER_2Y = 6;

    public static final int SCORE_RECENCY_NONE = 0;
    public static final int SCORE_RECENCY_OVER_12M = 1;
    public static final int SCORE_RECENCY_7M_TO_12M = 2;
    public static final int SCORE_RECENCY_4M_TO_6M = 4;
    public static final int SCORE_RECENCY_UP_TO_3M = 6;

    public static final int SCORE_VALUE_NONE = 0;
    public static final int SCORE_VALUE_UNDER_500M = 3;
    public static final int SCORE_VALUE_500M_TO_2B = 6;
    public static final int SCORE_VALUE_2B_TO_10B = 9;
    public static final int SCORE_VALUE_10B_TO_50B = 12;
    public static final int SCORE_VALUE_OVER_50B = 15;

    @Getter
    @Builder
    public static class CommercialEvidenceResult {
        private final int approvedContractCount;
        private final int upcomingContractCount;
        private final LocalDate firstCooperationDate;
        private final LocalDate latestContractDate;
        private final Long relationshipDurationDays;
        private final Long relationshipDurationMonths;
        private final Long contractRecencyDays;
        private final Long contractRecencyMonths;
        private final BigDecimal totalContractValueVnd;
        private final Map<String, BigDecimal> valueByCurrency;
        private final String contractCurrencies;
        private final String contractValueStatus; // SCORABLE, UNSCORABLE_NON_VND, NO_CONTRACTS
        private final Integer contractValueScore; // null if UNSCORABLE_NON_VND
        private final Integer contractCountScore;
        private final Integer relationshipDurationScore; // null if no valid date
        private final Integer contractRecencyScore;      // null if no valid date
        private final Integer commercialScore; // sum of applicable commercial scores
        private final int scorableBase; // 100 or 85
        private final boolean normalizationApplied;
        private final String scoringPolicyVersion;
        private final boolean hasValidHistoricalDates;
        private final String commercialSuggestionStatus; // COMPLETE, PARTIAL, UNAVAILABLE, REFERENCE_ONLY
        private final Integer commercialAvailablePoints;     // e.g. 50, 30, etc. (null for V5)
    }

    public CommercialEvidenceResult evaluate(List<PartnerContract> approvedContracts, LocalDate evaluationDate) {
        return evaluate(approvedContracts, evaluationDate, POLICY_VERSION_V5);
    }

    public CommercialEvidenceResult evaluate(List<PartnerContract> approvedContracts, LocalDate evaluationDate, String policyVersion) {
        String effectivePolicy = policyVersion != null ? policyVersion : POLICY_VERSION_V5;
        boolean isV5 = POLICY_VERSION_V5.equals(effectivePolicy);
        boolean isV4 = POLICY_VERSION_V4.equals(effectivePolicy);
        boolean isV3 = POLICY_VERSION_V3.equals(effectivePolicy);
        LocalDate today = evaluationDate != null ? evaluationDate : LocalDate.now();

        if (approvedContracts == null || approvedContracts.isEmpty()) {
            return CommercialEvidenceResult.builder()
                    .approvedContractCount(0)
                    .upcomingContractCount(0)
                    .firstCooperationDate(null)
                    .latestContractDate(null)
                    .relationshipDurationDays(null)
                    .relationshipDurationMonths(null)
                    .contractRecencyDays(null)
                    .contractRecencyMonths(null)
                    .totalContractValueVnd(BigDecimal.ZERO)
                    .valueByCurrency(Collections.emptyMap())
                    .contractCurrencies("")
                    .contractValueStatus(isV5 ? "SCORABLE" : "NO_CONTRACTS")
                    .contractValueScore(isV5 ? null : 0)
                    .contractCountScore(0)
                    .relationshipDurationScore(null)
                    .contractRecencyScore(null)
                    .commercialScore(isV5 ? null : 0)
                    .scorableBase(isV5 ? 30 : 100)
                    .normalizationApplied(isV5)
                    .scoringPolicyVersion(effectivePolicy)
                    .hasValidHistoricalDates(false)
                    .commercialSuggestionStatus((isV5 || isV4) ? "REFERENCE_ONLY" : "UNAVAILABLE")
                    .commercialAvailablePoints(isV5 ? null : (isV4 ? 35 : 0))
                    .build();
        }

        int totalApproved = approvedContracts.size();
        int upcomingCount = 0;
        List<LocalDate> validHistoricalDates = new ArrayList<>();
        Map<String, BigDecimal> valueByCurrency = new HashMap<>();

        for (PartnerContract contract : approvedContracts) {
            LocalDate eff = contract.getEffectiveDate();
            LocalDate sgn = contract.getSignedDate();

            LocalDate contractDate = null;
            if (eff != null && !eff.isAfter(today)) {
                contractDate = eff;
            } else if (sgn != null && !sgn.isAfter(today)) {
                contractDate = sgn;
            }

            if (contractDate != null) {
                validHistoricalDates.add(contractDate);
            } else if ((eff != null && eff.isAfter(today)) || (sgn != null && sgn.isAfter(today))) {
                upcomingCount++;
            }

            if (contract.getTotalContractValue() != null && contract.getTotalContractValue().compareTo(BigDecimal.ZERO) > 0) {
                String cur = contract.getCurrency() != null ? contract.getCurrency().trim().toUpperCase() : "UNKNOWN";
                valueByCurrency.merge(cur, contract.getTotalContractValue(), BigDecimal::add);
            }
        }

        // 1. Contract Count Score
        int countScore = 0;
        int countAvailable = 0;
        if (isV5 || isV4) {
            countScore = 0;
            countAvailable = 0;
        } else if (isV3) {
            countAvailable = 10;
            if (totalApproved == 0) {
                countScore = V3_SCORE_COUNT_0;
            } else if (totalApproved == 1) {
                countScore = V3_SCORE_COUNT_1;
            } else if (totalApproved <= 3) {
                countScore = V3_SCORE_COUNT_2_TO_3;
            } else if (totalApproved <= 6) {
                countScore = V3_SCORE_COUNT_4_TO_6;
            } else {
                countScore = V3_SCORE_COUNT_7_PLUS;
            }
        } else {
            countAvailable = 8;
            if (totalApproved == 0) {
                countScore = SCORE_COUNT_0;
            } else if (totalApproved == 1) {
                countScore = SCORE_COUNT_1;
            } else if (totalApproved <= 3) {
                countScore = SCORE_COUNT_2_TO_3;
            } else if (totalApproved <= 6) {
                countScore = SCORE_COUNT_4_TO_6;
            } else {
                countScore = SCORE_COUNT_7_PLUS;
            }
        }

        // 2 & 3. Duration & Recency
        LocalDate firstDate = null;
        LocalDate latestDate = null;
        Long durationDays = null;
        Long durationMonths = null;
        Long recencyDays = null;
        Long recencyMonths = null;
        Integer durationScore = null;
        Integer recencyScore = null;
        int durationAvailable = 0;
        int recencyAvailable = 0;
        boolean hasDates = !validHistoricalDates.isEmpty();

        if (hasDates) {
            firstDate = Collections.min(validHistoricalDates);
            latestDate = Collections.max(validHistoricalDates);

            durationDays = Math.max(0, ChronoUnit.DAYS.between(firstDate, today));
            durationMonths = durationDays / 30;

            recencyDays = Math.max(0, ChronoUnit.DAYS.between(latestDate, today));
            recencyMonths = recencyDays / 30;

            if (isV5 || isV4) {
                durationAvailable = 0;
                recencyAvailable = 0;
                durationScore = null;
                recencyScore = null;
            } else if (isV3) {
                durationAvailable = 10;
                if (durationMonths < 6) {
                    durationScore = V3_SCORE_DURATION_UNDER_6M;
                } else if (durationMonths < 12) {
                    durationScore = V3_SCORE_DURATION_6M_TO_11M;
                } else if (durationMonths < 24) {
                    durationScore = V3_SCORE_DURATION_12M_TO_23M;
                } else if (durationMonths < 36) {
                    durationScore = V3_SCORE_DURATION_24M_TO_35M;
                } else {
                    durationScore = V3_SCORE_DURATION_36M_PLUS;
                }

                recencyAvailable = 10;
                if (recencyMonths <= 3) {
                    recencyScore = V3_SCORE_RECENCY_UP_TO_3M;
                } else if (recencyMonths <= 6) {
                    recencyScore = V3_SCORE_RECENCY_4M_TO_6M;
                } else if (recencyMonths <= 12) {
                    recencyScore = V3_SCORE_RECENCY_7M_TO_12M;
                } else if (recencyMonths <= 24) {
                    recencyScore = V3_SCORE_RECENCY_13M_TO_24M;
                } else {
                    recencyScore = V3_SCORE_RECENCY_OVER_24M;
                }
            } else {
                durationAvailable = 6;
                if (durationMonths < 6) {
                    durationScore = SCORE_DURATION_UNDER_6M;
                } else if (durationMonths < 12) {
                    durationScore = SCORE_DURATION_6M_TO_12M;
                } else if (durationMonths < 24) {
                    durationScore = SCORE_DURATION_1Y_TO_2Y;
                } else {
                    durationScore = SCORE_DURATION_OVER_2Y;
                }

                recencyAvailable = 6;
                if (recencyMonths <= 3) {
                    recencyScore = SCORE_RECENCY_UP_TO_3M;
                } else if (recencyMonths <= 6) {
                    recencyScore = SCORE_RECENCY_4M_TO_6M;
                } else if (recencyMonths <= 12) {
                    recencyScore = SCORE_RECENCY_7M_TO_12M;
                } else {
                    recencyScore = SCORE_RECENCY_OVER_12M;
                }
            }
        } else {
            if (!isV3 && !isV4 && !isV5) {
                durationScore = 0;
                recencyScore = 0;
            }
        }

        // 4. Currency and Contract Value Score
        Set<String> currencies = valueByCurrency.keySet();
        boolean hasNonVnd = currencies.stream().anyMatch(c -> !"VND".equalsIgnoreCase(c));
        boolean hasVnd = currencies.stream().anyMatch(c -> "VND".equalsIgnoreCase(c));

        String contractValueStatus;
        Integer contractValueScore;
        int valueAvailable = 0;
        int scorableBase;
        boolean normalizationApplied;
        BigDecimal totalVnd = valueByCurrency.getOrDefault("VND", BigDecimal.ZERO);

        if (isV5) {
            contractValueStatus = currencies.isEmpty() ? "SCORABLE" : (hasNonVnd ? "UNSCORABLE_NON_VND" : "SCORABLE");
            contractValueScore = null;
            valueAvailable = 0;
            scorableBase = 30;
            normalizationApplied = true;
        } else if (isV4) {
            contractValueStatus = currencies.isEmpty() ? "SCORABLE" : (hasNonVnd ? "UNSCORABLE_NON_VND" : "SCORABLE");
            contractValueScore = null;
            valueAvailable = 0;
            scorableBase = 100;
            normalizationApplied = false;
        } else if (currencies.isEmpty()) {
            contractValueStatus = "SCORABLE";
            contractValueScore = 0;
            valueAvailable = isV3 ? 20 : 15;
            scorableBase = 100;
            normalizationApplied = false;
        } else if (hasNonVnd) {
            contractValueStatus = "UNSCORABLE_NON_VND";
            contractValueScore = null;
            valueAvailable = 0;
            scorableBase = isV3 ? 100 : 85;
            normalizationApplied = !isV3;
        } else {
            contractValueStatus = "SCORABLE";
            valueAvailable = isV3 ? 20 : 15;
            scorableBase = 100;
            normalizationApplied = false;

            if (isV3) {
                if (totalVnd.compareTo(BigDecimal.ZERO) <= 0) {
                    contractValueScore = V3_SCORE_VALUE_NONE;
                } else if (totalVnd.compareTo(VALUE_BAND_1) < 0) {
                    contractValueScore = V3_SCORE_VALUE_UNDER_500M;
                } else if (totalVnd.compareTo(VALUE_BAND_2) < 0) {
                    contractValueScore = V3_SCORE_VALUE_500M_TO_2B;
                } else if (totalVnd.compareTo(VALUE_BAND_3) < 0) {
                    contractValueScore = V3_SCORE_VALUE_2B_TO_10B;
                } else if (totalVnd.compareTo(VALUE_BAND_4) < 0) {
                    contractValueScore = V3_SCORE_VALUE_10B_TO_50B;
                } else {
                    contractValueScore = V3_SCORE_VALUE_OVER_50B;
                }
            } else {
                if (totalVnd.compareTo(BigDecimal.ZERO) <= 0) {
                    contractValueScore = SCORE_VALUE_NONE;
                } else if (totalVnd.compareTo(VALUE_BAND_1) < 0) {
                    contractValueScore = SCORE_VALUE_UNDER_500M;
                } else if (totalVnd.compareTo(VALUE_BAND_2) < 0) {
                    contractValueScore = SCORE_VALUE_500M_TO_2B;
                } else if (totalVnd.compareTo(VALUE_BAND_3) < 0) {
                    contractValueScore = SCORE_VALUE_2B_TO_10B;
                } else if (totalVnd.compareTo(VALUE_BAND_4) < 0) {
                    contractValueScore = SCORE_VALUE_10B_TO_50B;
                } else {
                    contractValueScore = SCORE_VALUE_OVER_50B;
                }
            }
        }

        int maxPossible = isV3 ? 50 : 35;
        Integer availablePoints;
        String suggestionStatus;
        Integer totalCommercial;

        if (isV5) {
            availablePoints = null;
            suggestionStatus = "REFERENCE_ONLY";
            totalCommercial = null;
        } else if (isV4) {
            availablePoints = 35;
            suggestionStatus = "REFERENCE_ONLY";
            totalCommercial = 0;
        } else {
            availablePoints = countAvailable + valueAvailable + durationAvailable + recencyAvailable;
            if (totalApproved == 0) {
                suggestionStatus = "UNAVAILABLE";
            } else if (availablePoints == maxPossible) {
                suggestionStatus = "COMPLETE";
            } else {
                suggestionStatus = "PARTIAL";
            }
            totalCommercial = countScore
                    + (contractValueScore != null ? contractValueScore : 0)
                    + (durationScore != null ? durationScore : 0)
                    + (recencyScore != null ? recencyScore : 0);
        }

        String currenciesString = currencies.stream().sorted().collect(Collectors.joining(", "));

        return CommercialEvidenceResult.builder()
                .approvedContractCount(totalApproved)
                .upcomingContractCount(upcomingCount)
                .firstCooperationDate(firstDate)
                .latestContractDate(latestDate)
                .relationshipDurationDays(durationDays)
                .relationshipDurationMonths(durationMonths)
                .contractRecencyDays(recencyDays)
                .contractRecencyMonths(recencyMonths)
                .totalContractValueVnd(hasVnd ? totalVnd : null)
                .valueByCurrency(valueByCurrency)
                .contractCurrencies(currenciesString)
                .contractValueStatus(contractValueStatus)
                .contractValueScore(contractValueScore)
                .contractCountScore(countScore)
                .relationshipDurationScore(durationScore)
                .contractRecencyScore(recencyScore)
                .commercialScore(totalCommercial)
                .scorableBase(scorableBase)
                .normalizationApplied(normalizationApplied)
                .scoringPolicyVersion(effectivePolicy)
                .hasValidHistoricalDates(hasDates)
                .commercialSuggestionStatus(suggestionStatus)
                .commercialAvailablePoints(availablePoints)
                .build();
    }
}

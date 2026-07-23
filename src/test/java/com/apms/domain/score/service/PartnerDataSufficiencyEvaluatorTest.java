package com.apms.domain.score.service;

import com.apms.domain.score.draft.ApprovedSourceReference;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.PartnerCriterionContext;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class PartnerDataSufficiencyEvaluatorTest {

    private PartnerEvaluationContextProvider contextProvider;
    private PartnerDataSufficiencyEvaluator evaluator;

    @BeforeEach
    void setUp() {
        contextProvider = Mockito.mock(PartnerEvaluationContextProvider.class);
        evaluator = new PartnerDataSufficiencyEvaluator(contextProvider);
    }

    @Test
    void testIncompleteWhenNoPinnedSources() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setPinnedSourceReferences(new ArrayList<>());
        
        var result = evaluator.evaluate(draft);
        assertEquals(EvaluationCompletenessStatus.INCOMPLETE, result.getAggregateCompletenessStatus());
        assertFalse(result.isStaffMaySubmit());
    }

    @Test
    void testSufficientScenarioAllCriteria() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setPinnedSourceReferences(List.of(new ApprovedSourceReference()));
        
        for (String crit : com.apms.domain.score.registry.CanonicalRoleCriteria.PARTNER_CRITERIA) {
            PartnerCriterionContext ctx = PartnerCriterionContext.builder()
                .criterionKey(crit)
                .pinnedSources(new ArrayList<>())
                .build();
                
            if (crit.equals("businessValueContributionScore")) {
                ctx.getPinnedSources().add(createMetric("revenue_generated", java.math.BigDecimal.TEN));
            } else if (crit.equals("strategicAlignmentScore")) {
                ctx.getPinnedSources().add(createSource("PARTNER_CONTRACT_CLAUSE_VERSION"));
            } else if (crit.equals("operationalPerformanceScore")) {
                ctx.getPinnedSources().add(createMetric("sla_uptime_percentage", java.math.BigDecimal.TEN));
            } else if (crit.equals("capabilityAndComplementarityScore")) {
                ctx.getPinnedSources().add(createSource("COMPANY_PROFILE_VERSION"));
                ctx.getPinnedSources().add(createSource("PARTNER_CONTRACT_CLAUSE_VERSION"));
            } else if (crit.equals("relationshipQualityScore")) {
                ctx.getPinnedSources().add(createMetric("nps_score", java.math.BigDecimal.TEN));
            } else if (crit.equals("governanceAndRiskScore")) {
                ctx.getPinnedSources().add(createSource("PARTNER_CONTRACT_CLAUSE_VERSION"));
            }
            
            when(contextProvider.buildContext(eq(draft), eq(crit))).thenReturn(ctx);
        }

        var result = evaluator.evaluate(draft);
        assertEquals(EvaluationCompletenessStatus.COMPLETE, result.getAggregateCompletenessStatus());
        assertTrue(result.isStaffMaySubmit());
    }

    @Test
    void testPartialScenarioAllCriteria() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setPinnedSourceReferences(List.of(new ApprovedSourceReference()));
        
        for (String crit : com.apms.domain.score.registry.CanonicalRoleCriteria.PARTNER_CRITERIA) {
            PartnerCriterionContext ctx = PartnerCriterionContext.builder()
                .criterionKey(crit)
                .pinnedSources(new ArrayList<>())
                .build();
                
            if (crit.equals("strategicAlignmentScore")) {
                ctx.getPinnedSources().add(createSource("COMPANY_PROFILE_VERSION"));
            } else if (crit.equals("capabilityAndComplementarityScore")) {
                ctx.getPinnedSources().add(createSource("COMPANY_PROFILE_VERSION"));
            } else {
                ctx.getPinnedSources().add(createSource("ROLE_METRIC_EVIDENCE_VERSION"));
            }
            
            when(contextProvider.buildContext(eq(draft), eq(crit))).thenReturn(ctx);
        }

        var result = evaluator.evaluate(draft);
        assertEquals(EvaluationCompletenessStatus.PARTIAL, result.getAggregateCompletenessStatus());
        assertTrue(result.isStaffMaySubmit());
    }

    @Test
    void testInsufficientScenarioAllCriteria() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setPinnedSourceReferences(List.of(new ApprovedSourceReference()));
        
        for (String crit : com.apms.domain.score.registry.CanonicalRoleCriteria.PARTNER_CRITERIA) {
            PartnerCriterionContext ctx = PartnerCriterionContext.builder()
                .criterionKey(crit)
                .pinnedSources(new ArrayList<>())
                .build();
            // Empty sources for all criteria
            when(contextProvider.buildContext(eq(draft), eq(crit))).thenReturn(ctx);
        }

        var result = evaluator.evaluate(draft);
        assertEquals(EvaluationCompletenessStatus.INCOMPLETE, result.getAggregateCompletenessStatus());
        assertFalse(result.isStaffMaySubmit());
    }

    private Map<String, Object> createMetric(String key, java.math.BigDecimal value) {
        Map<String, Object> src = new HashMap<>();
        src.put("sourceType", "ROLE_METRIC_VERSION");
        src.put("metricKey", key);
        src.put("actualNumericValue", value);
        return src;
    }

    private Map<String, Object> createSource(String type) {
        Map<String, Object> src = new HashMap<>();
        src.put("sourceType", type);
        return src;
    }
}

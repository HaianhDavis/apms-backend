package com.apms.domain.score.service;

import com.apms.domain.score.draft.ApprovedSourceReference;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.CriterionReadinessResult;
import com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse;
import com.apms.domain.score.enums.ApprovedSourceType;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerDataSufficiencyEvaluatorTest {

    private CustomerDataSufficiencyEvaluator evaluator;
    private RoleEvaluationDraft draft;

    @BeforeEach
    void setUp() {
        evaluator = new CustomerDataSufficiencyEvaluator();
        draft = new RoleEvaluationDraft();
        draft.setId("draft-123");
        draft.setPinnedSourceReferences(new ArrayList<>());
    }

    @Test
    void testEmptySourcesReturnsIncomplete() {
        RoleEvaluationReadinessResponse response = evaluator.evaluate(draft);
        assertThat(response.getAggregateCompletenessStatus()).isEqualTo(EvaluationCompletenessStatus.INCOMPLETE);
        assertThat(response.isStaffMaySubmit()).isFalse();
    }

    @Test
    void testMissingFinancialEvidenceBlocksCompleteness() {
        draft.getPinnedSourceReferences().add(ApprovedSourceReference.builder()
                .sourceType(ApprovedSourceType.COMPANY_PROFILE_VERSION)
                .build());
        draft.getPinnedSourceReferences().add(ApprovedSourceReference.builder()
                .sourceType(ApprovedSourceType.ROLE_METRIC_EVIDENCE_VERSION)
                .build());

        RoleEvaluationReadinessResponse response = evaluator.evaluate(draft);
        assertThat(response.getAggregateCompletenessStatus()).isEqualTo(EvaluationCompletenessStatus.PARTIAL);

        CriterionReadinessResult revResult = response.getCriterionResults().get("revenueProfitabilityScore");
        assertThat(revResult.getSufficiencyStatus()).isEqualTo(PartnerDataSufficiencyEvaluator.SufficiencyStatus.PARTIAL);
    }

    @Test
    void testExplicitFinancialEvidenceAllowsComplete() {
        draft.getPinnedSourceReferences().add(ApprovedSourceReference.builder()
                .sourceType(ApprovedSourceType.ROLE_METRIC_VERSION)
                .criterionKey("revenue_metric")
                .build());
        draft.getPinnedSourceReferences().add(ApprovedSourceReference.builder()
                .sourceType(ApprovedSourceType.ROLE_METRIC_VERSION)
                .criterionKey("clv_metric")
                .build());
        draft.getPinnedSourceReferences().add(ApprovedSourceReference.builder()
                .sourceType(ApprovedSourceType.ROLE_METRIC_EVIDENCE_VERSION)
                .build());

        RoleEvaluationReadinessResponse response = evaluator.evaluate(draft);

        CriterionReadinessResult revResult = response.getCriterionResults().get("revenueProfitabilityScore");
        assertThat(revResult.getSufficiencyStatus()).isEqualTo(PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE);

        CriterionReadinessResult clvResult = response.getCriterionResults().get("customerLifetimeValueScore");
        assertThat(clvResult.getSufficiencyStatus()).isEqualTo(PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE);
    }
}

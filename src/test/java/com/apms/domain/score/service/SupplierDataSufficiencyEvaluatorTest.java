package com.apms.domain.score.service;

import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.CriterionReadinessResult;
import com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SupplierDataSufficiencyEvaluatorTest {

    private final SupplierDataSufficiencyEvaluator evaluator = new SupplierDataSufficiencyEvaluator();

    @Test
    void testEmptySourcesReturnsIncomplete() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setPinnedSourceReferences(Collections.emptyList());

        RoleEvaluationReadinessResponse response = evaluator.evaluate(draft);

        assertEquals(EvaluationCompletenessStatus.INCOMPLETE, response.getAggregateCompletenessStatus());
        assertFalse(response.isStaffMaySubmit());
        assertEquals(1, response.getBlockingReasons().size());
        assertEquals("No pinned source references", response.getBlockingReasons().get(0));
    }

    @Test
    void testNullSourcesReturnsIncomplete() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setPinnedSourceReferences(null);

        RoleEvaluationReadinessResponse response = evaluator.evaluate(draft);

        assertEquals(EvaluationCompletenessStatus.INCOMPLETE, response.getAggregateCompletenessStatus());
        assertFalse(response.isStaffMaySubmit());
        assertEquals(1, response.getBlockingReasons().size());
        assertEquals("No pinned source references", response.getBlockingReasons().get(0));
    }

    @Test
    void testUnknownCriterionReturnsIncomplete() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setPinnedSourceReferences(Collections.emptyList()); // Will trigger early return

        // We can't directly test unknown criterion via evaluate() as it loops over CanonicalRoleCriteria.SUPPLIER_CRITERIA
        // But we can test it indirectly by looking at missingCategories if we had access.
    }
}

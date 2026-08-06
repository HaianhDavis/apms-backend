package com.apms.domain.project.fieldapproval;

import com.apms.common.enums.FieldApprovalStatus;
import com.apms.common.enums.ReviewDecision;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
public class FieldApprovalReadinessTest {

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private FieldApprovalService service;

    private static class DummyDraft {
        String name;
        String emptyField = "";
        String nullField = null;
    }

    private List<FieldDefinition<DummyDraft>> definitions;

    @BeforeEach
    void setUp() {
        definitions = List.of(
                FieldDefinition.<DummyDraft>builder().canonicalPath("name").getter(d -> d.name).required(true).build(),
                FieldDefinition.<DummyDraft>builder().canonicalPath("emptyField").getter(d -> d.emptyField).required(true).build(),
                FieldDefinition.<DummyDraft>builder().canonicalPath("nullField").getter(d -> d.nullField).required(true).build()
        );
    }

    @Test
    void testApproveBlockedByPendingReview() {
        DummyDraft draft = new DummyDraft();
        draft.name = "Test Name";

        List<FieldApprovalRecord> approvals = new ArrayList<>();
        approvals.add(FieldApprovalRecord.builder().fieldPath("name").status(FieldApprovalStatus.PENDING_REVIEW).build());

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                service.validateFinalReviewReadiness(draft, approvals, definitions, ReviewDecision.APPROVE)
        );
        assertTrue(ex.getMessage().contains("PENDING_REVIEW"));
    }

    @Test
    void testApproveBlockedByRevisionRequired() {
        DummyDraft draft = new DummyDraft();
        draft.name = "Test Name";

        List<FieldApprovalRecord> approvals = new ArrayList<>();
        approvals.add(FieldApprovalRecord.builder().fieldPath("name").status(FieldApprovalStatus.REVISION_REQUIRED).build());

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                service.validateFinalReviewReadiness(draft, approvals, definitions, ReviewDecision.APPROVE)
        );
        assertTrue(ex.getMessage().contains("REVISION_REQUIRED"));
    }

    @Test
    void testApproveBlockedByStale() {
        DummyDraft draft = new DummyDraft();
        draft.name = "Test Name";

        List<FieldApprovalRecord> approvals = new ArrayList<>();
        approvals.add(FieldApprovalRecord.builder().fieldPath("name").status(FieldApprovalStatus.STALE).build());

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                service.validateFinalReviewReadiness(draft, approvals, definitions, ReviewDecision.APPROVE)
        );
        assertTrue(ex.getMessage().contains("STALE"));
    }

    @Test
    void testApproveBlockedByRequiredRejectedField() {
        DummyDraft draft = new DummyDraft();
        draft.name = "Test Name";

        List<FieldApprovalRecord> approvals = new ArrayList<>();
        approvals.add(FieldApprovalRecord.builder().fieldPath("name").status(FieldApprovalStatus.REJECTED).build());

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                service.validateFinalReviewReadiness(draft, approvals, definitions, ReviewDecision.APPROVE)
        );
        assertTrue(ex.getMessage().contains("REJECTED"));
    }

    @Test
    void testApproveBlockedByMissingRequiredValue() {
        DummyDraft draft = new DummyDraft();
        draft.name = "Test Name"; // not missing
        // nullField is required but null, emptyField is required but ""
        
        List<FieldApprovalRecord> approvals = new ArrayList<>();
        approvals.add(FieldApprovalRecord.builder().fieldPath("name").status(FieldApprovalStatus.APPROVED).build());
        approvals.add(FieldApprovalRecord.builder().fieldPath("nullField").status(FieldApprovalStatus.APPROVED).build());
        approvals.add(FieldApprovalRecord.builder().fieldPath("emptyField").status(FieldApprovalStatus.APPROVED).build());

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                service.validateFinalReviewReadiness(draft, approvals, definitions, ReviewDecision.APPROVE)
        );
        assertTrue(ex.getMessage().contains("missing/empty"));
    }

    @Test
    void testRequestRevisionBlockedWhenNoRevisionRequiredField() {
        DummyDraft draft = new DummyDraft();
        List<FieldApprovalRecord> approvals = new ArrayList<>();
        approvals.add(FieldApprovalRecord.builder().fieldPath("name").status(FieldApprovalStatus.APPROVED).build());

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                service.validateFinalReviewReadiness(draft, approvals, definitions, ReviewDecision.REQUEST_REVISION)
        );
        assertTrue(ex.getMessage().contains("without at least one field marked as REVISION_REQUIRED"));
    }

    @Test
    void testRequestRevisionBlockedWhenAnyFieldIsPendingReview() {
        DummyDraft draft = new DummyDraft();
        List<FieldApprovalRecord> approvals = new ArrayList<>();
        approvals.add(FieldApprovalRecord.builder().fieldPath("name").status(FieldApprovalStatus.REVISION_REQUIRED).build());
        approvals.add(FieldApprovalRecord.builder().fieldPath("nullField").status(FieldApprovalStatus.PENDING_REVIEW).build());

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                service.validateFinalReviewReadiness(draft, approvals, definitions, ReviewDecision.REQUEST_REVISION)
        );
        assertTrue(ex.getMessage().contains("is still PENDING_REVIEW"));
    }

    @Test
    void testSuccessfulApprove() {
        DummyDraft draft = new DummyDraft();
        draft.name = "Valid Name";
        draft.nullField = "Valid Null";
        draft.emptyField = "Valid Empty";

        List<FieldApprovalRecord> approvals = new ArrayList<>();
        approvals.add(FieldApprovalRecord.builder().fieldPath("name").status(FieldApprovalStatus.APPROVED).build());
        approvals.add(FieldApprovalRecord.builder().fieldPath("nullField").status(FieldApprovalStatus.APPROVED).build());
        approvals.add(FieldApprovalRecord.builder().fieldPath("emptyField").status(FieldApprovalStatus.APPROVED).build());

        assertDoesNotThrow(() ->
                service.validateFinalReviewReadiness(draft, approvals, definitions, ReviewDecision.APPROVE)
        );
    }

    @Test
    void testSuccessfulRequestRevision() {
        DummyDraft draft = new DummyDraft();
        List<FieldApprovalRecord> approvals = new ArrayList<>();
        approvals.add(FieldApprovalRecord.builder().fieldPath("name").status(FieldApprovalStatus.APPROVED).build());
        approvals.add(FieldApprovalRecord.builder().fieldPath("emptyField").status(FieldApprovalStatus.REVISION_REQUIRED).build());

        assertDoesNotThrow(() ->
                service.validateFinalReviewReadiness(draft, approvals, definitions, ReviewDecision.REQUEST_REVISION)
        );
    }
}

package com.apms.domain.project.fieldapproval;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.FieldApprovalStatus;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.project.dto.FieldReopenRequest;
import com.apms.domain.project.dto.FieldReviewDecisionItem;
import com.apms.domain.project.dto.FieldReviewRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class FieldApprovalService {

    private final AuditLogService auditLogService;

    public <T> void initializeFirstSubmission(T draft, List<FieldDefinition<T>> definitions,
                                              List<FieldApprovalRecord> fieldApprovals) {
        Map<String, FieldApprovalRecord> map = FieldApprovalUtils.toMap(fieldApprovals);
        for (FieldDefinition<T> def : definitions) {
            Object val = def.getGetter().apply(draft);
            boolean isCollection = def.isCollection();
            boolean isOrdered = def.isOrderedCollection();
            
            boolean isEmpty = false;
            if (val == null) {
                isEmpty = true;
            } else if (val instanceof String && !StringUtils.hasText((String) val)) {
                isEmpty = true;
            } else if (val instanceof Collection && ((Collection<?>) val).isEmpty()) {
                isEmpty = true;
            }

            if (def.isRequired() || !isEmpty) {
                if (!map.containsKey(def.getCanonicalPath())) {
                    FieldApprovalRecord newRecord = FieldApprovalRecord.builder()
                            .fieldPath(def.getCanonicalPath())
                            .status(FieldApprovalStatus.PENDING_REVIEW)
                            .changedInRevision(1)
                            .build();
                    map.put(def.getCanonicalPath(), newRecord);
                    fieldApprovals.add(newRecord);
                }
            }
        }
    }

    public <T> void handleResubmission(T draft, T oldDraft, List<FieldDefinition<T>> definitions,
                                       List<FieldApprovalRecord> fieldApprovals,
                                       Integer newRevisionNumber, List<String> changedFieldPaths) {
        Map<String, FieldApprovalRecord> map = FieldApprovalUtils.toMap(fieldApprovals);
        
        for (FieldDefinition<T> def : definitions) {
            Object oldVal = oldDraft != null ? def.getGetter().apply(oldDraft) : null;
            Object newVal = def.getGetter().apply(draft);

            boolean isCollection = def.isCollection();
            boolean isOrdered = def.isOrderedCollection();
            String hashOld = FieldValueHasher.hashValue(oldVal, isCollection && !isOrdered);
            String hashNew = FieldValueHasher.hashValue(newVal, isCollection && !isOrdered);

            if (!Objects.equals(hashOld, hashNew)) {
                changedFieldPaths.add(def.getCanonicalPath());
                
                FieldApprovalRecord record = map.get(def.getCanonicalPath());
                if (record == null) {
                    record = FieldApprovalRecord.builder().fieldPath(def.getCanonicalPath()).build();
                    map.put(def.getCanonicalPath(), record);
                    fieldApprovals.add(record);
                }
                
                if (record.getStatus() == FieldApprovalStatus.REVISION_REQUIRED) {
                    record.setPreviousStatus(record.getStatus());
                    record.setPreviousComment(record.getComment());
                }
                
                record.setStatus(FieldApprovalStatus.PENDING_REVIEW);
                record.setChangedInRevision(newRevisionNumber);
                record.setReviewedByAccountId(null);
                record.setReviewedAt(null);
                // Keep reviewedRevision intact (the last time it was reviewed)
            }
        }
    }

    public <T> void processBatchReview(T draft, FieldReviewRequest request, Long accountId,
                                       String entityType, String entityId,
                                       List<FieldApprovalRecord> fieldApprovals,
                                       List<FieldDefinition<T>> definitions,
                                       Integer currentRevisionNumber) {
        Map<String, FieldApprovalRecord> map = FieldApprovalUtils.toMap(fieldApprovals);
        
        Set<String> seenPaths = new HashSet<>();
        for (FieldReviewDecisionItem decision : request.getDecisions()) {
            if (!seenPaths.add(decision.getFieldPath())) {
                throw new BusinessValidationException("Duplicate field path in request: " + decision.getFieldPath());
            }
            
            FieldDefinition<T> def = definitions.stream()
                    .filter(d -> d.getCanonicalPath().equals(decision.getFieldPath()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessValidationException("Invalid field path: " + decision.getFieldPath()));
            
            FieldApprovalRecord record = map.get(decision.getFieldPath());
            if (record == null) {
                record = FieldApprovalRecord.builder().fieldPath(decision.getFieldPath()).build();
                map.put(decision.getFieldPath(), record);
                fieldApprovals.add(record);
            }
            
            FieldApprovalStatus newStatus = decision.getDecision();
            if (newStatus != FieldApprovalStatus.APPROVED && newStatus != FieldApprovalStatus.REVISION_REQUIRED && newStatus != FieldApprovalStatus.REJECTED) {
                throw new BusinessValidationException("Invalid decision status: " + newStatus);
            }
            
            if ((newStatus == FieldApprovalStatus.REVISION_REQUIRED || newStatus == FieldApprovalStatus.REJECTED) 
                && !StringUtils.hasText(decision.getComment())) {
                throw new BusinessValidationException("Comment is required for REVISION_REQUIRED or REJECTED");
            }
            
            FieldApprovalStatus oldStatus = record.getStatus();
            record.setStatus(newStatus);
            record.setComment(decision.getComment());
            record.setReviewedRevision(currentRevisionNumber);
            record.setReviewedByAccountId(accountId);
            record.setReviewedAt(LocalDateTime.now());
            record.setPendingValue(def.getGetter().apply(draft));
            
            if (newStatus == FieldApprovalStatus.APPROVED) {
                Object val = def.getGetter().apply(draft);
                record.setApprovedValueHash(FieldValueHasher.hashValue(val, def.isCollection() && !def.isOrderedCollection()));
            }

            AuditAction action = AuditAction.FIELD_APPROVED;
            if (newStatus == FieldApprovalStatus.REVISION_REQUIRED) action = AuditAction.FIELD_REVISION_REQUESTED;
            if (newStatus == FieldApprovalStatus.REJECTED) action = AuditAction.FIELD_REJECTED;

            auditLogService.log(accountId, action, entityType, entityId,
                    "Field " + decision.getFieldPath() + " changed from " + oldStatus + " to " + newStatus);
        }
    }

    public void processReopen(String fieldPath, FieldReopenRequest request, Long accountId,
                              String entityType, String entityId,
                              List<FieldApprovalRecord> fieldApprovals) {
        Map<String, FieldApprovalRecord> map = FieldApprovalUtils.toMap(fieldApprovals);
        
        FieldApprovalRecord record = map.get(fieldPath);
        if (record == null || (record.getStatus() != FieldApprovalStatus.APPROVED && record.getStatus() != FieldApprovalStatus.STALE && record.getStatus() != FieldApprovalStatus.REJECTED)) {
            throw new BusinessValidationException("Field cannot be reopened, current status is " + (record != null ? record.getStatus() : "null"));
        }
        
        if (!StringUtils.hasText(request.getReason())) {
            throw new BusinessValidationException("Reopen reason is required");
        }

        FieldApprovalStatus oldStatus = record.getStatus();
        record.setStatus(FieldApprovalStatus.REVISION_REQUIRED);
        record.setReopenedByAccountId(accountId);
        record.setReopenedAt(LocalDateTime.now());
        record.setReopenReason(request.getReason());
        
        // Also update the review fields so that the Staff knows why
        record.setPreviousStatus(oldStatus);
        record.setPreviousComment(record.getComment());
        record.setComment(request.getReason());

        auditLogService.log(accountId, AuditAction.FIELD_REOPENED, entityType, entityId,
                "Field " + fieldPath + " reopened from " + oldStatus);
    }

    public <T> void validateFinalReviewReadiness(T draft, List<FieldApprovalRecord> fieldApprovals,
                                                 List<FieldDefinition<T>> definitions,
                                                 com.apms.common.enums.ReviewDecision decision) {
        Map<String, FieldApprovalRecord> map = FieldApprovalUtils.toMap(fieldApprovals);
        if (decision == com.apms.common.enums.ReviewDecision.APPROVE) {
            for (FieldDefinition<T> def : definitions) {
                FieldApprovalRecord record = map.get(def.getCanonicalPath());
                if (record != null) {
                    if (record.getStatus() == FieldApprovalStatus.PENDING_REVIEW ||
                        record.getStatus() == FieldApprovalStatus.REVISION_REQUIRED ||
                        record.getStatus() == FieldApprovalStatus.STALE) {
                        throw new BusinessValidationException("Cannot approve submission. Field " + def.getCanonicalPath() + " is " + record.getStatus());
                    }
                    if (def.isRequired() && record.getStatus() == FieldApprovalStatus.REJECTED) {
                        throw new BusinessValidationException("Cannot approve submission. Required field " + def.getCanonicalPath() + " is REJECTED");
                    }
                }
                
                if (def.isRequired()) {
                    Object val = def.getGetter().apply(draft);
                    boolean isEmpty = (val == null) || (val instanceof String && !StringUtils.hasText((String) val)) || (val instanceof Collection && ((Collection<?>) val).isEmpty());
                    if (isEmpty) {
                        throw new BusinessValidationException("Cannot approve submission. Required field " + def.getCanonicalPath() + " is missing/empty");
                    }
                }
            }
        } else if (decision == com.apms.common.enums.ReviewDecision.REQUEST_REVISION) {
            boolean hasRevisionRequired = false;
            for (FieldApprovalRecord record : fieldApprovals) {
                if (record.getStatus() == FieldApprovalStatus.PENDING_REVIEW) {
                    throw new BusinessValidationException("Cannot request revision. Field " + record.getFieldPath() + " is still PENDING_REVIEW");
                }
                if (record.getStatus() == FieldApprovalStatus.REVISION_REQUIRED) {
                    hasRevisionRequired = true;
                }
            }
            if (!hasRevisionRequired) {
                throw new BusinessValidationException("Cannot request revision without at least one field marked as REVISION_REQUIRED");
            }
        }
    }
}

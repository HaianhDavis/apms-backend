package com.apms.domain.project.fieldapproval;

import com.apms.common.enums.FieldApprovalStatus;
import com.apms.common.exception.BusinessValidationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class FieldApprovalGuard {

    /**
     * Verifies that the new field values do not overwrite locked fields.
     * Blocks APPROVED, STALE, REJECTED (unless reopened).
     * Allows REVISION_REQUIRED.
     * Allows PENDING_REVIEW (only when draft is not IN_REVIEW).
     */
    public <T> void guardMutation(
            T oldDraft, T newDraft,
            List<FieldDefinition<T>> definitions,
            List<FieldApprovalRecord> fieldApprovals,
            boolean isDraftInReview,
            Integer currentRevisionNumber) {

        if (fieldApprovals == null || fieldApprovals.isEmpty()) {
            return; // no approvals exist yet, safe
        }
        
        Map<String, FieldApprovalRecord> map = FieldApprovalUtils.toMap(fieldApprovals);

        List<String> blockedFields = new ArrayList<>();

        for (FieldDefinition<T> def : definitions) {
            Object oldVal = def.getGetter().apply(oldDraft);
            Object newVal = def.getGetter().apply(newDraft);

            boolean isCollection = def.isCollection();
            boolean isOrdered = def.isOrderedCollection();
            String hashOld = FieldValueHasher.hashValue(oldVal, isCollection && !isOrdered);
            String hashNew = FieldValueHasher.hashValue(newVal, isCollection && !isOrdered);

            if (!Objects.equals(hashOld, hashNew)) {
                // Field value is changing
                FieldApprovalRecord record = map.get(def.getCanonicalPath());
                if (record != null && isDraftInReview) {
                    blockedFields.add(def.getCanonicalPath());
                }
            }
        }

        if (!blockedFields.isEmpty()) {
            throw new BusinessValidationException("Cannot modify locked fields: " + blockedFields + 
                    ". Draft is at revision " + currentRevisionNumber);
        }
    }
}

package com.apms.domain.score.enums;

import com.apms.domain.score.ScoreSnapshot;

public enum ScoreTraceabilityStatus {
    IMMUTABLE_COMPLETE,
    IMMUTABLE_WITHOUT_EVIDENCE,
    DRAFT_BASED,
    LEGACY_PARTIAL;

    public static ScoreTraceabilityStatus resolve(ScoreSnapshot snapshot) {
        if (com.apms.domain.company.enums.CompanyRole.COMPETITOR.equals(snapshot.getEvaluatedRole())) {
            return DRAFT_BASED;
        }

        if (snapshot.getApprovedRoleEvaluationVersionId() != null && snapshot.getApprovedRoleEvaluationVersionNumber() != null) {
            if (snapshot.getEvidenceRefsJson() != null && !snapshot.getEvidenceRefsJson().isBlank() && !snapshot.getEvidenceRefsJson().equals("{}")) {
                return IMMUTABLE_COMPLETE;
            } else {
                return IMMUTABLE_WITHOUT_EVIDENCE;
            }
        }

        return LEGACY_PARTIAL;
    }
}

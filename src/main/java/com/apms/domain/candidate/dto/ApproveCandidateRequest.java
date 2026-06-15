package com.apms.domain.candidate.dto;

import com.apms.common.enums.RelationshipType;
import lombok.Data;

@Data
public class ApproveCandidateRequest {
    /**
     * Optional override. If null, the system will use the suggestedRelationshipType
     * if it exists, otherwise it might require one depending on downstream business rules.
     */
    private RelationshipType relationshipTypeOverride;
}

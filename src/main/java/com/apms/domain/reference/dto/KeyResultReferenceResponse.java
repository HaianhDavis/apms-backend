package com.apms.domain.reference.dto;

import com.apms.common.enums.ProjectKeyResultType;
import com.apms.common.enums.RelationshipType;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class KeyResultReferenceResponse {
    private ProjectKeyResultType type;
    private String displayName;
    private String description;
    private List<RelationshipType> supportedRelationshipTypes;
}

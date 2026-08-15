package com.apms.domain.monitoring.dto;

import com.apms.common.enums.RelationshipType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RelationshipChangeProposalRequest {
    @NotNull(message = "New relationship type is required")
    private RelationshipType newRelationshipType;
    
    private String reason;
    
    @NotNull(message = "Effective date is required")
    private LocalDateTime effectiveAt;
}

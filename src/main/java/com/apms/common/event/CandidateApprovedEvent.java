package com.apms.common.event;

import com.apms.common.enums.RelationshipType;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CandidateApprovedEvent implements DomainEvent {

    private final String candidateId;
    private final String projectId;
    private final RelationshipType finalRelationshipType;
    private final Double confidenceScore;

    // Additional candidate details needed by downstream Profile/Graph services can be added here
}

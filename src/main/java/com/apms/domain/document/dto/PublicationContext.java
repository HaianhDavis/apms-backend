package com.apms.domain.document.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicationContext {
    private String sourceProjectId;
    private String sourceTaskId;
    private String sourceSubmissionId;
    private String sourceCandidateId;
    private String documentType;
    private String description;
    private String displayName;
}

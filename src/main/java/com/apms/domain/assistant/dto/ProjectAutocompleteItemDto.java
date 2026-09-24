package com.apms.domain.assistant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectAutocompleteItemDto {
    private Long projectId;
    private String projectName;
    private String projectType;
    private String status;
    private String targetCompany;
}

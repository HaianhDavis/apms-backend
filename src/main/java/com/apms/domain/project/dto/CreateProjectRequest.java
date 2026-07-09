package com.apms.domain.project.dto;

import com.apms.common.enums.RelationshipType;
import com.apms.common.enums.ProjectType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateProjectRequest {

    @NotBlank(message = "projectName is required")
    private String projectName;

    @NotNull(message = "projectType is required")
    private ProjectType projectType;

    /**
     * Required only when projectType = UPDATE_EXISTING_COMPANY.
     * Must be null for RESEARCH_NEW_COMPANY and RESEARCH_MULTIPLE_COMPANIES.
     */
    private String targetCompanyProfileId;

    /**
     * Always required.
     * For UPDATE_EXISTING_COMPANY: the system will copy it from the selected profile.
     * For RESEARCH_NEW_COMPANY: single company name.
     * For RESEARCH_MULTIPLE_COMPANIES: research scope description.
     */
    @NotBlank(message = "targetCompanyName is required")
    private String targetCompanyName;

    @Schema(description = "Optional for UPDATE_EXISTING_COMPANY. Required for RESEARCH_NEW_COMPANY.")
    private RelationshipType targetRelationshipType;

    private String description;
}

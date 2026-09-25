package com.apms.domain.project.dto;

import com.apms.common.enums.RelationshipType;
import com.apms.common.enums.ProjectStatus;
import lombok.Data;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;

@Data
public class UpdateProjectRequest {

    private String projectName;

    private String description;

    private String objective;

    private RelationshipType targetRelationshipType;

    private LocalDate plannedEndDate;

    private String targetCompanyName;

    private String targetCompanyTaxCode;

    private String targetCompanyProfileId;

    @Valid
    private List<CreateProjectKeyResultRequest> keyResults;
}

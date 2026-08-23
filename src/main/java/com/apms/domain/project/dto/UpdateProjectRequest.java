package com.apms.domain.project.dto;

import com.apms.common.enums.RelationshipType;
import com.apms.common.enums.ProjectStatus;
import lombok.Data;
import java.time.LocalDate;

@Data
public class UpdateProjectRequest {

    private String projectName;

    private String description;

    private String objective;

    private RelationshipType targetRelationshipType;

    private LocalDate plannedEndDate;
}

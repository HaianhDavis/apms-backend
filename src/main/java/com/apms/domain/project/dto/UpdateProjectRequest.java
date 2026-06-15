package com.apms.domain.project.dto;

import com.apms.common.enums.ProjectStatus;
import lombok.Data;

@Data
public class UpdateProjectRequest {

    private String projectName;

    private String description;

    private ProjectStatus status;
}

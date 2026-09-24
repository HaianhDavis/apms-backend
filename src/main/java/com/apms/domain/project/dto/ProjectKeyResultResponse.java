package com.apms.domain.project.dto;

import com.apms.common.enums.ProjectKeyResultType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProjectKeyResultResponse {
    private Long id;
    private ProjectKeyResultType type;
    private String name;
    private String description;
    private Integer weight;
    private Integer progress;
}

package com.apms.domain.project.dto;

import com.apms.common.enums.ProjectKeyResultType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateProjectKeyResultRequest {

    @NotNull(message = "Key Result type is required")
    private ProjectKeyResultType type;

    @NotNull(message = "Key Result weight is required")
    private Integer weight;
}

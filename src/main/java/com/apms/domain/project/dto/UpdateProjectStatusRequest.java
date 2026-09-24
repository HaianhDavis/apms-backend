package com.apms.domain.project.dto;

import com.apms.common.enums.ProjectStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateProjectStatusRequest {

    @NotNull(message = "status is required")
    @Schema(description = "Target status (ACTIVE, COMPLETED, CANCELLED, ARCHIVED)")
    private ProjectStatus status;

    @Schema(description = "Optional note explaining the status change")
    private String note;

    @Schema(description = "If true, bypasses unfinished task checks for completion")
    private Boolean force = false;
}

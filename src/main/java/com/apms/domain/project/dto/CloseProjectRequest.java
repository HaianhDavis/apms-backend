package com.apms.domain.project.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class CloseProjectRequest {

    @Schema(description = "Required if progress is less than 100%, optional otherwise. Explains why the project is being closed early.")
    private String reason;
}

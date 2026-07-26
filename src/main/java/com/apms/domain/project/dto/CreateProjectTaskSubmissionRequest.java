package com.apms.domain.project.dto;

import com.apms.common.enums.SubmissionType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateProjectTaskSubmissionRequest {
    @NotNull
    private SubmissionType submissionType;

    private String targetEntityType;
    private String targetEntityId;

    private String note;
}

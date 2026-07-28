package com.apms.domain.project.dto;

import com.apms.common.enums.TaskStatus;
import lombok.Data;

@Data
public class SaveProjectTaskDraftRequest {
    private String attachedCompanyProfileId;
    private String note;
    private TaskStatus status;
}

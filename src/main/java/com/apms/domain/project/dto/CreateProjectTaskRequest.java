package com.apms.domain.project.dto;

import com.apms.common.enums.TaskPriority;
import com.apms.common.enums.TaskType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreateProjectTaskRequest {
    @NotBlank
    private String title;
    
    private String description;
    
    private Long assignedToUserId;
    
    private TaskPriority priority;
    
    private LocalDateTime dueDate;

    @Schema(description = "Type of task (DOCUMENT_COLLECTION, COMPANY_DATA_PREPARATION, GENERAL_TASK)")
    private TaskType taskType;
}

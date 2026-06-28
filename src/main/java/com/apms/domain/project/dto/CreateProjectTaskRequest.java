package com.apms.domain.project.dto;

import com.apms.common.enums.TaskPriority;
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
}

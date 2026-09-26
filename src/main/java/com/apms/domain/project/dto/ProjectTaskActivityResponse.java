package com.apms.domain.project.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ProjectTaskActivityResponse {
    private Long id;
    private Long actorId;
    private String actorName;
    private String actorEmail;
    private String action;
    private String detail;
    private LocalDateTime occurredAt;
}

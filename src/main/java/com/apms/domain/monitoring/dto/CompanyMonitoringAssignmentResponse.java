package com.apms.domain.monitoring.dto;

import com.apms.common.enums.MonitoringFrequency;
import com.apms.common.enums.MonitoringStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class CompanyMonitoringAssignmentResponse {
    private Long id;
    private String companyProfileId;
    private String companyName;

    private Long assignedStaffId;
    private String assignedStaffName;
    private String assignedStaffEmail;

    private Long assignedByManagerId;

    private MonitoringFrequency frequency;
    private MonitoringStatus assignmentStatus;
    private String displayStatus; // UP_TO_DATE, DUE, OVERDUE, PAUSED

    private LocalDateTime lastReviewedAt;
    private LocalDateTime nextReviewAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

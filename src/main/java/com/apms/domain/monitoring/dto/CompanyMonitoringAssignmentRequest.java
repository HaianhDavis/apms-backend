package com.apms.domain.monitoring.dto;

import com.apms.common.enums.MonitoringFrequency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CompanyMonitoringAssignmentRequest {

    @NotBlank
    private String companyProfileId;

    @NotNull
    private Long assignedStaffId;

    @NotNull
    private MonitoringFrequency frequency;
}

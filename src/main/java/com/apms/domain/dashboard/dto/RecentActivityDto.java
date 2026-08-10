package com.apms.domain.dashboard.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class RecentActivityDto {
    private String id;
    private String type;
    private String description;
    private String companyId;
    private String companyProfileId;
    private String companyName;
    private LocalDateTime occurredAt;
}

package com.apms.domain.admin.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class IpWhitelistEntryDto {
    private Long id;
    private String ipAddress;
    private String description;
    private Boolean enabled;
    private LocalDateTime createdAt;
}

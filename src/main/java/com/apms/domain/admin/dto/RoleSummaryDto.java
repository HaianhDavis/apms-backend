package com.apms.domain.admin.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RoleSummaryDto {
    private String id;
    private String key;
    private String name;
    private String displayName;
    private String description;
    private long userCount;
    private int permissionCount;
}


package com.apms.domain.admin.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PermissionDto {
    private Long id;
    private String name;
    private String module;
    private String description;
}

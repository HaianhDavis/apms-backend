package com.apms.domain.admin.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PermissionDto {
    private String id;
    private String module;
    private String action;
    private boolean admin;
    private boolean director;
    private boolean manager;
    private boolean keymember;
    private boolean staff;
}


package com.apms.domain.admin.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SystemHealthResponse {

    private List<ServiceHealth> services;

    @Data
    @Builder
    public static class ServiceHealth {
        private String name;
        /** UP, DOWN, DEGRADED, NOT_CONFIGURED */
        private String status;
        private Long latencyMs;
        private String lastChecked;
        private String errorReason;
    }
}

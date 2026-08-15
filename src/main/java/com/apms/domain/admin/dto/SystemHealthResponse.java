package com.apms.domain.admin.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class SystemHealthResponse {
    private List<ServiceHealthDto> services;

    @Data
    @Builder
    public static class ServiceHealthDto {
        private String name;
        private String status;
        private Long latencyMs;
        private LocalDateTime lastChecked;
        private String errorReason;
    }
}

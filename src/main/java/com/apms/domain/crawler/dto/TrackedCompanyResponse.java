package com.apms.domain.crawler.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response DTO for tracked company information.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrackedCompanyResponse {

    private String id;
    private String companyName;
    private List<String> aliases;
    private List<String> subsidiaries;
    private List<String> products;
    private List<String> keyPeople;
    private String industry;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}


package com.apms.domain.crawler.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request DTO for creating or updating a tracked company.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrackedCompanyRequest {

    private String companyName;
    private List<String> aliases;
    private List<String> subsidiaries;
    private List<String> products;
    private List<String> keyPeople;
    private String industry;
}


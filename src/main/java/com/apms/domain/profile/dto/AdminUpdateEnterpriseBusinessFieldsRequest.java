package com.apms.domain.profile.dto;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Whitelist DTO for SYSTEM_ADMIN to edit Business Fields of the canonical Owner Enterprise.
 * Scoped strictly to industries, markets, targetCustomers, products, and optimistic locking versions.
 * Excludes businessModel (which is edited strictly in Overview).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUpdateEnterpriseBusinessFieldsRequest {

    private List<String> industries;
    private List<String> markets;
    private List<String> targetCustomers;

    @Valid
    private List<AdminEnterpriseProductRequest> products;

    private Integer expectedMajorVersion;
    private Integer expectedRevision;
}

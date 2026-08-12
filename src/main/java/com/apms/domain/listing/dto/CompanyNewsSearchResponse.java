package com.apms.domain.listing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyNewsSearchResponse {
    private String companyId;
    private String companyName;
    private LocalDateTime searchedAt;
    private int savedNew;
    private int alreadyExisting;
    private int rejected;
    private List<CompanyNewsSearchResult> results;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompanyNewsSearchResult {
        private CompanyNewsSearchItem item;
        private String status; // SAVED_NEW, ALREADY_EXISTED, REJECTED
        private String rejection; // UNTRUSTED_DOMAIN, UNKNOWN_DOMAIN, NO_COMPANY_MENTION, etc.
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompanyNewsSearchItem {
        private String id;
        private String title;
        private String summary;
        private String source;
        private String sourceDomain;
        private String url;
        private LocalDateTime publishedAt;
        private String category;
        private String relatedCompanyId;
        private String relatedCompanyName;
        private LocalDateTime lastCheckedAt;
    }
}

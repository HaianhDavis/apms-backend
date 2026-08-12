package com.apms.domain.profile;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Public-market information for the reference enterprise. This is deliberately
 * separate from CompanyProfile, which remains the approved Manager/Staff flow.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "owner_company_profile_snapshots")
public class OwnerCompanyProfileSnapshot {
    @Id
    private String id;
    private String companyProfileId;
    private String summary;
    private LocalDateTime fetchedAt;
    private String refreshStatus;
    @Builder.Default private List<CompanyProfile.CompanyMember> boardMembers = new ArrayList<>();
    @Builder.Default private List<Ownership> ownership = new ArrayList<>();
    @Builder.Default private List<FinancialReport> financialReports = new ArrayList<>();
    @Builder.Default private List<News> news = new ArrayList<>();
    @Builder.Default private List<DocumentItem> documents = new ArrayList<>();

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Ownership {
        private String holderName;
        private String representedBy;
        private Double ownershipPercent;
        private String ownershipType;
        private String sourceUrl;
    }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FinancialReport {
        private String reportType;
        private String periodType;
        private Integer reportYear;
        private String reportPeriod;
        private String itemsJson;
        private String sourceUrl;
    }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class News {
        private String title;
        private String summary;
        private String category;
        private String sourceName;
        private String sourceUrl;
        private LocalDateTime publishedAt;
    }
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DocumentItem {
        private String docType;
        private String docTitle;
        private String fileUrl;
        private Integer reportYear;
        private String reportPeriod;
        private LocalDateTime publishedAt;
    }
}

package com.apms.domain.externaldata.dto;

import com.apms.common.enums.ExternalDataCategory;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ExternalDataItemResponse {
    private String id;
    private String title;
    private String summary;
    private String source;
    private String url;
    private LocalDateTime publishedAt;
    private ExternalDataCategory category;
    private String sentiment;
    private String riskLevel;
    private String opportunityLevel;
    private String aiSummary;
    private List<String> topics;
    private String relatedCompanyName;
    private String relatedCompanyId;
    private Long projectId;
    private String companyProfileId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

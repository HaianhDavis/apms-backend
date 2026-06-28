package com.apms.domain.externaldata.dto;

import com.apms.common.enums.ExternalDataCategory;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

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
    private String relatedCompanyName;
    private String relatedCompanyId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

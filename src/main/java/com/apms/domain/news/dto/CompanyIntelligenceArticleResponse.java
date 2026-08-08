package com.apms.domain.news.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class CompanyIntelligenceArticleResponse {
    private String id;
    private String companyProfileId;

    private String title;
    private String summary;
    private String content;

    private boolean hasImage;
    private String externalImageUrl;

    private String sourceName;
    private String sourceUrl;
    private String author;

    private LocalDateTime publishedAt;
    private LocalDateTime capturedAt;

    private List<String> tags;

    private LocalDateTime approvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

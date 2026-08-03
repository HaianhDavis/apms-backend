package com.apms.domain.news.entity;

import com.apms.domain.news.enums.ConfidentialityLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Document(collection = "company_intelligence_articles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyIntelligenceArticle {
    @Id
    private String id;

    @Indexed
    private String companyProfileId;

    private String title;
    private String summary;
    private String content;

    private String imageStorageKey;
    private String externalImageUrl;

    private String sourceName;
    private String sourceUrl;
    private String author;

    private LocalDateTime publishedAt;
    private LocalDateTime capturedAt;

    private List<String> tags;

    @Builder.Default
    private ConfidentialityLevel confidentialityLevel = ConfidentialityLevel.CONFIDENTIAL;

    private Long sourceProjectId;
    private Long sourceTaskId;
    private Long sourceSubmissionId;

    @Indexed(unique = true, partialFilter = "{ 'sourceDraftId' : { $exists: true } }")
    private String sourceDraftId;

    private Long createdByAccountId;
    private Long approvedByAccountId;
    private LocalDateTime approvedAt;

    @Builder.Default
    private boolean isDeleted = false;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}

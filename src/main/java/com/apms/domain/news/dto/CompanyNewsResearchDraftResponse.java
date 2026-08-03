package com.apms.domain.news.dto;

import com.apms.domain.news.enums.NewsDraftStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class CompanyNewsResearchDraftResponse {
    private String id;
    private Long projectId;
    private Long taskId;
    private String targetCompanyProfileId;

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
    private String staffNotes;

    private NewsDraftStatus reviewStatus;
    private Long createdByAccountId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

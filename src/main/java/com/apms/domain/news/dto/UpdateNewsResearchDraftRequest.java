package com.apms.domain.news.dto;

import org.hibernate.validator.constraints.URL;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class UpdateNewsResearchDraftRequest {

    private String title;

    @URL(message = "Source URL must be a valid URL", regexp = "^https?://.*")
    private String sourceUrl;

    private LocalDateTime publishedAt;

    private String summary;
    private String content;

    private String imageStorageKey;
    private String externalImageUrl;

    private String sourceName;
    private String author;

    private List<String> tags;
    private String staffNotes;
}

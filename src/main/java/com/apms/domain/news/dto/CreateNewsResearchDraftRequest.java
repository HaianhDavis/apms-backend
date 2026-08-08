package com.apms.domain.news.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.URL;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class CreateNewsResearchDraftRequest {

    @NotBlank(message = "Title is required")
    private String title;

    @NotBlank(message = "Source URL is required")
    @URL(message = "Source URL must be a valid URL", regexp = "^https?://.*")
    private String sourceUrl;

    @NotNull(message = "Published At is required")
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

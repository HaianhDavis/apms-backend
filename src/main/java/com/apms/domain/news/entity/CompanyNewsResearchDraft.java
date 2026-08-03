package com.apms.domain.news.entity;

import com.apms.domain.news.enums.NewsDraftStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Document(collection = "company_news_research_drafts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@CompoundIndexes({
        @CompoundIndex(name = "idx_task_deleted", def = "{'taskId': 1, 'isDeleted': 1}")
})
public class CompanyNewsResearchDraft {
    @Id
    private String id;

    @Indexed
    private Long projectId;

    @Indexed
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

    @Builder.Default
    private NewsDraftStatus reviewStatus = NewsDraftStatus.DRAFT;

    private Long createdByAccountId;

    @Builder.Default
    private boolean isDeleted = false;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}

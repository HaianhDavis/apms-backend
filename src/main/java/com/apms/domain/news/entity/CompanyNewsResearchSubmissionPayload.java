package com.apms.domain.news.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Document(collection = "company_news_research_submission_payloads")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyNewsResearchSubmissionPayload {
    @Id
    private String id;

    @Indexed
    private Long submissionId;

    private Long projectId;
    private Long taskId;
    private String targetCompanyProfileId;

    private List<String> newsDraftIds;

    @CreatedDate
    private LocalDateTime createdAt;
}

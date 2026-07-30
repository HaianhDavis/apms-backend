package com.apms.domain.companymember;

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
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "company_member_research_drafts")
public class CompanyMemberResearchDraft {

    @Id
    private String id;

    @Indexed
    private Long projectId;

    @Indexed(unique = true)
    private Long taskId;

    private String companyProfileId;

    private Long createdByAccountId;

    private Long submissionId;

    @Builder.Default
    private List<CompanyMemberResearchItem> members = new ArrayList<>();

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}

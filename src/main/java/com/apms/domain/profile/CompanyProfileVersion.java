package com.apms.domain.profile;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@Document(collection = "company_profile_versions")
public class CompanyProfileVersion {

    @Id
    private String id;

    @Indexed
    private String companyProfileId;

    @Indexed
    private String companyId;

    private String version;

    private Map<String, Object> snapshot;

    private String createdFromProposalId;
    private Long createdFromProjectId;
    private Long createdFromTaskId;

    private List<String> sourceDocumentIds;
    private String changeSummary;

    private Long createdBy;

    @CreatedDate
    private LocalDateTime createdAt;
}

package com.apms.domain.reference.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "industry_catalogs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndustryCatalog {

    @Id
    private String id;

    private String name;

    @Indexed(unique = true)
    private String normalizedName;

    private LocalDateTime createdAt;
    private String createdBy;
    private String sourceCompanyProfileId;
    private String sourceCandidateId;

    @Builder.Default
    private String status = "ACTIVE";
}

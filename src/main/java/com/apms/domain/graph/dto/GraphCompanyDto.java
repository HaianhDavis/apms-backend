package com.apms.domain.graph.dto;

import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;
import java.util.List;

@Data
@Builder
public class GraphCompanyDto {
    private String companyId;
    private String name;
    private String industry;
    private ZonedDateTime createdAt;
    private ZonedDateTime updatedAt;

    private List<CompanyRelationshipDto> relationships;
}

package com.apms.domain.graph.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class GraphCompanyDto {
    private String companyId;
    private String name;
    private String industry;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private List<CompanyRelationshipDto> relationships;
}

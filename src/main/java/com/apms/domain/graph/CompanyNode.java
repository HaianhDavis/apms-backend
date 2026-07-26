package com.apms.domain.graph;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

import java.time.LocalDateTime;

@Node("Company")
@Data
public class CompanyNode {

    @Id
    private String companyId; // The universal UUID

    private String name;

    private String industry;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

package com.apms.domain.graph;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

import java.time.ZonedDateTime;

@Node("Company")
@Data
public class CompanyNode {

    @Id
    private String companyId; // The universal UUID

    private String name;

    private String industry;

    private ZonedDateTime createdAt;

    private ZonedDateTime updatedAt;
}

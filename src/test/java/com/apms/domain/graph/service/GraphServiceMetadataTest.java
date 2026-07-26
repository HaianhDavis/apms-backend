package com.apms.domain.graph.service;

import com.apms.ApmsIntegrationTestBase;
import com.apms.domain.graph.dto.CompanyRelationshipDto;
import com.apms.domain.graph.dto.GraphCompanyDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphServiceMetadataTest extends ApmsIntegrationTestBase {

    @Autowired
    private GraphService graphService;

    @Autowired
    private Neo4jClient neo4jClient;

    @BeforeEach
    void setUp() {
        neo4jClient.query("MATCH (n) DETACH DELETE n").run();
        
        // Create initial nodes
        neo4jClient.query("CREATE (c:Company {companyId: 'C1', name: 'Company 1', industry: 'Tech'})").run();
        neo4jClient.query("CREATE (c:Company {companyId: 'C2', name: 'Company 2', industry: 'Finance'})").run();
    }

    @Test
    void testCreateRelationshipWithMetadata() {
        CompanyRelationshipDto dto = CompanyRelationshipDto.builder()
                .sourceCompanyId("C1")
                .targetCompanyId("C2")
                .relationshipType("PARTNER_WITH")
                .startDate(LocalDate.of(2023, 1, 1))
                .endDate(LocalDate.of(2024, 1, 1))
                .status("ACTIVE")
                .metadata(Map.of("key1", "value1", "key2", 42))
                .build();

        graphService.createRelationship(dto);

        var result = neo4jClient.query("MATCH (c1:Company {companyId: 'C1'})-[r:PARTNER_WITH]->(c2:Company {companyId: 'C2'}) RETURN r.startDate as startDate, r.endDate as endDate, r.status as status, r.metadata as metadata")
                .fetch().one().orElseThrow();
        
        assertThat(result.get("startDate")).isEqualTo("2023-01-01");
        assertThat(result.get("endDate")).isEqualTo("2024-01-01");
        assertThat(result.get("status")).isEqualTo("ACTIVE");
        assertThat((String) result.get("metadata")).contains("key1", "value1", "key2", "42");
    }

    @Test
    void testCreateRelationshipWithoutMetadata() {
        CompanyRelationshipDto dto = CompanyRelationshipDto.builder()
                .sourceCompanyId("C1")
                .targetCompanyId("C2")
                .relationshipType("PARTNER_WITH")
                .build();

        graphService.createRelationship(dto);

        var result = neo4jClient.query("MATCH (c1:Company {companyId: 'C1'})-[r:PARTNER_WITH]->(c2:Company {companyId: 'C2'}) RETURN r.startDate as startDate, r.endDate as endDate, r.status as status, r.metadata as metadata")
                .fetch().one().orElseThrow();
        
        assertThat(result.get("startDate")).isEqualTo("");
        assertThat(result.get("endDate")).isEqualTo("");
        assertThat(result.get("status")).isEqualTo("");
        assertThat(result.get("metadata")).isEqualTo("");
    }

    @Test
    void testUpdateRelationshipMetadata() {
        // Create without metadata
        CompanyRelationshipDto dto = CompanyRelationshipDto.builder()
                .sourceCompanyId("C1")
                .targetCompanyId("C2")
                .relationshipType("PARTNER_WITH")
                .build();
        graphService.createRelationship(dto);

        // Update with metadata
        CompanyRelationshipDto updateDto = CompanyRelationshipDto.builder()
                .startDate(LocalDate.of(2024, 5, 1))
                .status("PENDING")
                .metadata(Map.of("updated", true))
                .build();
        graphService.updateRelationshipMetadata("C1", "C2", "PARTNER_WITH", updateDto);

        var result = neo4jClient.query("MATCH (c1:Company {companyId: 'C1'})-[r:PARTNER_WITH]->(c2:Company {companyId: 'C2'}) RETURN r.startDate as startDate, r.endDate as endDate, r.status as status, r.metadata as metadata")
                .fetch().one().orElseThrow();
        
        assertThat(result.get("startDate")).isEqualTo("2024-05-01");
        assertThat(result.get("endDate")).isEqualTo(""); // Should remain null
        assertThat(result.get("status")).isEqualTo("PENDING");
        assertThat((String) result.get("metadata")).contains("updated", "true");
    }

    @Test
    void testInvalidDateRange() {
        CompanyRelationshipDto dto = CompanyRelationshipDto.builder()
                .sourceCompanyId("C1")
                .targetCompanyId("C2")
                .relationshipType("PARTNER_WITH")
                .startDate(LocalDate.of(2024, 1, 1))
                .endDate(LocalDate.of(2023, 1, 1)) // Invalid: end before start
                .build();

        assertThatThrownBy(() -> graphService.createRelationship(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endDate cannot be before startDate");
    }
}

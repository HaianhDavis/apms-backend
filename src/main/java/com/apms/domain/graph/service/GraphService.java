package com.apms.domain.graph.service;

import com.apms.common.enums.RelationshipType;
import com.apms.common.event.CandidateApprovedEvent;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.graph.CompanyNode;
import com.apms.domain.graph.dto.CompanyRelationshipDto;
import com.apms.domain.graph.dto.GraphCompanyDto;
import com.apms.domain.graph.repository.neo4j.CompanyNodeRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.repository.sql.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GraphService {

    private final CompanyNodeRepository companyNodeRepository;
    private final Neo4jClient neo4jClient;
    private final CompanyCandidateRepository candidateRepository;
    private final CompanyProfileRepository profileRepository;
    private final ProjectRepository projectRepository;
    private final com.apms.domain.profile.service.OwnerOrganizationService ownerOrganizationService;

    // ─────────────────────────────────────────────
    // EVENT LISTENER
    // ─────────────────────────────────────────────

    @EventListener
    @Order(2)
    public void handleCandidateApprovedEvent(CandidateApprovedEvent event) {
        log.info("GraphService received CandidateApprovedEvent for candidateId: {}", event.getCandidateId());

        CompanyCandidate candidate = candidateRepository.findById(event.getCandidateId())
                .orElse(null);
        if (candidate == null) return;

        Project project = projectRepository.findById(Long.valueOf(event.getProjectId()))
                .orElse(null);
        if (project == null) return;

        // The ProfileService should have run before this.
        // If they run in unpredictable order, an @Order annotation on listeners is best.
        // For now, we attempt to find the newly created/updated profile.
        CompanyProfile profile = profileRepository.findByCandidateId(event.getCandidateId())
                .orElse(null);
        
        if (profile == null) {
            log.error("CompanyProfile not found for candidateId: {}. Ensure ProfileService runs first.", event.getCandidateId());
            return;
        }

        RelationshipType finalRelType = event.getFinalRelationshipType();
        
        // 1. Create or merge CompanyNode for the approved CompanyProfile
        mergeCompanyNode(profile);

        // 2. The relationship is from OwnerCompany to the target company (which is `profile`)
        if (finalRelType != null) {
            // Create relationship: OwnerCompany --[rel]-> TargetCompany (which is `profile.getCompanyId()`)
            createRelationship(
                    ownerOrganizationService.getOwnerCompanyId(),
                    profile.getCompanyId(),
                    finalRelType.name(),
                    candidate.getReview() != null ? candidate.getReview().getReviewedBy() : "SYSTEM",
                    String.valueOf(project.getId()),
                    candidate.getId(),
                    event.getConfidenceScore() != null ? event.getConfidenceScore() : 1.0
            );
        } else {
            log.warn("No finalRelType provided for candidate approval, cannot create relationship.");
        }
    }

    private void mergeCompanyNode(CompanyProfile profile) {
        String name = profile.getIdentity() != null && profile.getIdentity().getLegalName() != null ? profile.getIdentity().getLegalName() : "Unknown";
        String industry = profile.getBusiness() != null && profile.getBusiness().getIndustries() != null && !profile.getBusiness().getIndustries().isEmpty() 
                ? profile.getBusiness().getIndustries().get(0) : "Unknown";

        String cypher = """
            MERGE (c:Company {companyId: $companyId})
            ON CREATE SET c.name = $name, c.industry = $industry, c.createdAt = datetime()
            ON MATCH SET c.name = $name, c.industry = $industry, c.updatedAt = datetime()
            """;

        neo4jClient.query(cypher)
                .bindAll(Map.of(
                        "companyId", profile.getCompanyId(),
                        "name", name,
                        "industry", industry
                ))
                .run();
                
        log.info("Merged CompanyNode: companyId={}, name={}", profile.getCompanyId(), name);
    }

    private void createRelationship(String sourceCompanyId, String targetCompanyId, String relType, String confirmedBy, String projectId, String candidateId, double confidenceScore) {
        // Cypher requires relationship types to be static in the query string.
        // We dynamically build the query string with the enum name safely.
        if (!relType.matches("^[A-Z_]+$")) {
            throw new IllegalArgumentException("Invalid relationship type: " + relType);
        }

        String cypher = String.format("""
            MATCH (c1:Company {companyId: $sourceCompanyId})
            MATCH (c2:Company {companyId: $targetCompanyId})
            MERGE (c1)-[r:%s]->(c2)
            SET r.confidenceScore = $confidenceScore,
                r.confirmedBy = $confirmedBy,
                r.confirmedAt = datetime(),
                r.projectId = $projectId,
                r.candidateId = $candidateId
            """, relType);

        neo4jClient.query(cypher)
                .bindAll(Map.of(
                        "sourceCompanyId", sourceCompanyId,
                        "targetCompanyId", targetCompanyId,
                        "confirmedBy", confirmedBy,
                        "projectId", projectId,
                        "candidateId", candidateId,
                        "confidenceScore", confidenceScore
                ))
                .run();
                
        log.info("Created relationship ({})-[:{}]->({})", sourceCompanyId, relType, targetCompanyId);
    }

    // ─────────────────────────────────────────────
    // READ OPERATIONS
    // ─────────────────────────────────────────────

    public GraphCompanyDto getCompanyNodeWithRelationships(String companyId) {
        CompanyNode node = companyNodeRepository.findByCompanyId(companyId)
                .orElse(null);
        if (node == null) return null;

        List<CompanyRelationshipDto> relationships = getOutgoingRelationships(companyId);
        
        return GraphCompanyDto.builder()
                .companyId(node.getCompanyId())
                .name(node.getName())
                .industry(node.getIndustry())
                .createdAt(node.getCreatedAt())
                .updatedAt(node.getUpdatedAt())
                .relationships(relationships)
                .build();
    }

    public List<GraphCompanyDto> getNetwork() {
        return companyNodeRepository.findAllNodes().stream()
                .map(node -> GraphCompanyDto.builder()
                        .companyId(node.getCompanyId())
                        .name(node.getName())
                        .industry(node.getIndustry())
                        .createdAt(node.getCreatedAt())
                        .updatedAt(node.getUpdatedAt())
                        .build())
                .toList();
    }

    public List<GraphCompanyDto> getCompaniesByRelationshipType(String relType) {
        if (!relType.matches("^[A-Z_]+$")) {
            return List.of();
        }

        String cypher = String.format("MATCH (c:Company)-[:%s]->() RETURN DISTINCT c", relType);
        
        return neo4jClient.query(cypher)
                .fetchAs(CompanyNode.class)
                .mappedBy((typeSystem, record) -> {
                    var node = record.get("c").asNode();
                    CompanyNode c = new CompanyNode();
                    c.setCompanyId(node.get("companyId").asString());
                    c.setName(node.get("name").asString("Unknown"));
                    c.setIndustry(node.get("industry").asString("Unknown"));
                    return c;
                })
                .all().stream()
                .map(node -> GraphCompanyDto.builder()
                        .companyId(node.getCompanyId())
                        .name(node.getName())
                        .industry(node.getIndustry())
                        .build())
                .toList();
    }

    private List<CompanyRelationshipDto> getOutgoingRelationships(String companyId) {
        String cypher = """
            MATCH (c1:Company {companyId: $companyId})-[r]->(c2:Company)
            RETURN type(r) as relType, c2.companyId as targetCompanyId, 
                   r.confidenceScore as confidenceScore, r.confirmedBy as confirmedBy, 
                   r.projectId as projectId, r.candidateId as candidateId
            """;

        return (List<CompanyRelationshipDto>) neo4jClient.query(cypher)
                .bindAll(Map.of("companyId", companyId))
                .fetch()
                .all()
                .stream()
                .map(record -> CompanyRelationshipDto.builder()
                        .sourceCompanyId(companyId)
                        .targetCompanyId((String) record.get("targetCompanyId"))
                        .relationshipType((String) record.get("relType"))
                        .confidenceScore((Double) record.get("confidenceScore"))
                        .confirmedBy((String) record.get("confirmedBy"))
                        .projectId((String) record.get("projectId"))
                        .candidateId((String) record.get("candidateId"))
                        .build())
                .toList();
    }
}

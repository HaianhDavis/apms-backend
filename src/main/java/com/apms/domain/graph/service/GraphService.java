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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;

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
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─────────────────────────────────────────────
    // EVENT LISTENER
    // ─────────────────────────────────────────────

    @EventListener
    @Order(2)
    public void handleCandidateApprovedEvent(CandidateApprovedEvent event) {
        log.info("GraphService received CandidateApprovedEvent for candidateId: {}", event.getCandidateId());
        try {
            if (event.getProjectId() == null || event.getProjectId().isBlank()) {
                log.error("CandidateApprovedEvent has null/blank projectId for candidateId: {}. Skipping graph update.", event.getCandidateId());
                return;
            }

            Long projectId;
            try {
                projectId = Long.valueOf(event.getProjectId());
            } catch (NumberFormatException e) {
                log.error("CandidateApprovedEvent has non-numeric projectId='{}' for candidateId: {}. Skipping graph update.", event.getProjectId(), event.getCandidateId());
                return;
            }

            CompanyCandidate candidate = candidateRepository.findById(event.getCandidateId())
                    .orElse(null);
            if (candidate == null) return;

            Project project = projectRepository.findById(projectId)
                    .orElse(null);
            if (project == null) return;

            CompanyProfile profile = profileRepository.findByCandidateId(event.getCandidateId())
                    .orElse(null);
            if (profile == null) {
                log.error("CompanyProfile not found for candidateId: {}. Ensure ProfileService runs first.", event.getCandidateId());
                return;
            }

            RelationshipType finalRelType = event.getFinalRelationshipType();
            mergeCompanyNode(profile);

            if (finalRelType != null) {
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
        } catch (Exception e) {
            log.warn("Could not save approved candidate to Neo4j graph because database is unavailable: {}", e.getMessage());
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

    public void createRelationship(String sourceCompanyId, String targetCompanyId, String relType, String confirmedBy, String projectId, String candidateId, double confidenceScore) {
        createRelationship(CompanyRelationshipDto.builder()
                .sourceCompanyId(sourceCompanyId)
                .targetCompanyId(targetCompanyId)
                .relationshipType(relType)
                .confirmedBy(confirmedBy)
                .projectId(projectId)
                .candidateId(candidateId)
                .confidenceScore(confidenceScore)
                .build());
    }

    public void createRelationship(CompanyRelationshipDto dto) {
        if (!dto.getRelationshipType().matches("^[A-Z_]+$")) {
            throw new IllegalArgumentException("Invalid relationship type: " + dto.getRelationshipType());
        }

        if (dto.getStartDate() != null && dto.getEndDate() != null && dto.getEndDate().isBefore(dto.getStartDate())) {
            throw new IllegalArgumentException("endDate cannot be before startDate");
        }

        String metadataJson = null;
        if (dto.getMetadata() != null) {
            try {
                metadataJson = objectMapper.writeValueAsString(dto.getMetadata());
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize metadata", e);
            }
        }

        String cypher = String.format("""
            MATCH (c1:Company {companyId: $sourceCompanyId})
            MATCH (c2:Company {companyId: $targetCompanyId})
            MERGE (c1)-[r:%s]->(c2)
            SET r.confidenceScore = $confidenceScore,
                r.confirmedBy = $confirmedBy,
                r.confirmedAt = coalesce(r.confirmedAt, datetime()),
                r.projectId = $projectId,
                r.candidateId = $candidateId,
                r.startDate = $startDate,
                r.endDate = $endDate,
                r.status = $status,
                r.metadata = $metadata
            """, dto.getRelationshipType());

        neo4jClient.query(cypher)
                .bindAll(Map.of(
                        "sourceCompanyId", dto.getSourceCompanyId(),
                        "targetCompanyId", dto.getTargetCompanyId(),
                        "confirmedBy", dto.getConfirmedBy() != null ? dto.getConfirmedBy() : "SYSTEM",
                        "projectId", dto.getProjectId() != null ? dto.getProjectId() : "",
                        "candidateId", dto.getCandidateId() != null ? dto.getCandidateId() : "",
                        "confidenceScore", dto.getConfidenceScore() != null ? dto.getConfidenceScore() : 1.0,
                        "startDate", dto.getStartDate() != null ? dto.getStartDate().toString() : "",
                        "endDate", dto.getEndDate() != null ? dto.getEndDate().toString() : "",
                        "status", dto.getStatus() != null ? dto.getStatus() : "",
                        "metadata", metadataJson != null ? metadataJson : ""
                ))
                .run();

        log.info("Created/Updated relationship ({})-[:{}]->({})", dto.getSourceCompanyId(), dto.getRelationshipType(), dto.getTargetCompanyId());
    }

    public void updateRelationshipMetadata(String sourceCompanyId, String targetCompanyId, String relType, CompanyRelationshipDto metadataDto) {
        if (!relType.matches("^[A-Z_]+$")) {
            throw new IllegalArgumentException("Invalid relationship type: " + relType);
        }

        if (metadataDto.getStartDate() != null && metadataDto.getEndDate() != null && metadataDto.getEndDate().isBefore(metadataDto.getStartDate())) {
            throw new IllegalArgumentException("endDate cannot be before startDate");
        }

        String metadataJson = null;
        if (metadataDto.getMetadata() != null) {
            try {
                metadataJson = objectMapper.writeValueAsString(metadataDto.getMetadata());
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize metadata", e);
            }
        }

        String cypher = String.format("""
            MATCH (c1:Company {companyId: $sourceCompanyId})-[r:%s]->(c2:Company {companyId: $targetCompanyId})
            SET r.startDate = CASE WHEN $startDate <> '' THEN $startDate ELSE r.startDate END,
                r.endDate = CASE WHEN $endDate <> '' THEN $endDate ELSE r.endDate END,
                r.status = CASE WHEN $status <> '' THEN $status ELSE r.status END,
                r.metadata = CASE WHEN $metadata <> '' THEN $metadata ELSE r.metadata END
            """, relType);

        neo4jClient.query(cypher)
                .bindAll(Map.of(
                        "sourceCompanyId", sourceCompanyId,
                        "targetCompanyId", targetCompanyId,
                        "startDate", metadataDto.getStartDate() != null ? metadataDto.getStartDate().toString() : "",
                        "endDate", metadataDto.getEndDate() != null ? metadataDto.getEndDate().toString() : "",
                        "status", metadataDto.getStatus() != null ? metadataDto.getStatus() : "",
                        "metadata", metadataJson != null ? metadataJson : ""
                ))
                .run();

        log.info("Updated relationship metadata ({})-[:{}]->({})", sourceCompanyId, relType, targetCompanyId);
    }

    // ─────────────────────────────────────────────
    // READ OPERATIONS
    // ─────────────────────────────────────────────

    public GraphCompanyDto getCompanyNodeWithRelationships(String companyId) {
        try {
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
        } catch (Exception e) {
            log.warn("Neo4j is unavailable, returning empty node detail. Error: {}", e.getMessage());
            return null;
        }
    }

    public List<GraphCompanyDto> getNetwork() {
        try {
            return companyNodeRepository.findAllNodes().stream()
                    .map(node -> GraphCompanyDto.builder()
                            .companyId(node.getCompanyId())
                            .name(node.getName())
                            .industry(node.getIndustry())
                            .createdAt(node.getCreatedAt())
                            .updatedAt(node.getUpdatedAt())
                            .build())
                    .toList();
        } catch (Exception e) {
            log.warn("Neo4j is unavailable, returning empty network. Error: {}", e.getMessage());
            return List.of();
        }
    }

    public List<GraphCompanyDto> getCompaniesByRelationshipType(String relType) {
        if (!relType.matches("^[A-Z_]+$")) {
            return List.of();
        }

        try {
            String cypher = String.format("MATCH (c:Company)-[:%s]-(:Company) RETURN DISTINCT c", relType);
            
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
                    .map(node -> {
                        String name = resolveNodeName(node.getCompanyId(), node.getName());
                        return GraphCompanyDto.builder()
                                .companyId(node.getCompanyId())
                                .name(name)
                                .industry(node.getIndustry())
                                .build();
                    })
                    .toList();
        } catch (Exception e) {
            log.warn("Neo4j is unavailable, returning empty company list for relationship. Error: {}", e.getMessage());
            return List.of();
        }
    }

    private String resolveNodeName(String companyId, String currentName) {
        boolean isRawId = currentName == null || currentName.isBlank() 
                || "Unknown".equalsIgnoreCase(currentName) 
                || currentName.matches("^[0-9a-fA-F]{24}$");
        if (!isRawId) return currentName;

        try {
            return profileRepository.findByCompanyId(companyId)
                    .or(() -> profileRepository.findById(companyId))
                    .map(p -> {
                        if (p.getIdentity() != null) {
                            if (StringUtils.hasText(p.getIdentity().getTradeName())) return p.getIdentity().getTradeName();
                            if (StringUtils.hasText(p.getIdentity().getLegalName())) return p.getIdentity().getLegalName();
                        }
                        return p.getCompanyId();
                    })
                    .orElse(currentName);
        } catch (Exception e) {
            return currentName;
        }
    }

    private List<CompanyRelationshipDto> getOutgoingRelationships(String companyId) {
        try {
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
        } catch (Exception e) {
            log.warn("Neo4j is unavailable, returning empty outgoing relationships. Error: {}", e.getMessage());
            return List.of();
        }
>>>>>>> d4242dc (Update backend services, controllers, security configuration, and test suite)
    }
}

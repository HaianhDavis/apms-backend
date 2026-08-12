package com.apms.domain.graph.service;

import com.apms.common.enums.RelationshipType;
import com.apms.common.enums.CandidateStatus;
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
import org.springframework.boot.context.event.ApplicationReadyEvent;
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

    /**
     * Rebuilds graph data for profiles created before graph synchronization was
     * introduced. Nodes are always safe to merge; a relationship is restored
     * only when an approved candidate and an explicit project relationship exist.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void synchronizeExistingProfiles() {
        int nodesSynchronized = 0;
        int relationshipsSynchronized = 0;

        mergeOwnerNode();

        for (CompanyProfile profile : profileRepository.findAll()) {
            if (Boolean.TRUE.equals(profile.getIsDeleted()) || ownerOrganizationService.isOwnerCompany(profile.getId())) {
                continue;
            }

            mergeCompanyNode(profile);
            nodesSynchronized++;

            if (profile.getSourceRefs() == null || profile.getSourceRefs().getCandidateIds() == null) {
                continue;
            }

            for (String candidateId : profile.getSourceRefs().getCandidateIds()) {
                CompanyCandidate candidate = candidateRepository.findById(candidateId).orElse(null);
                if (candidate == null || candidate.getStatus() != CandidateStatus.APPROVED) {
                    continue;
                }

                Project project = null;
                try {
                    if (StringUtils.hasText(candidate.getProjectId())) {
                        project = projectRepository.findById(Long.valueOf(candidate.getProjectId())).orElse(null);
                    }
                } catch (NumberFormatException ex) {
                    log.warn("Skipping graph relationship sync for candidate {} with invalid project ID {}",
                            candidate.getId(), candidate.getProjectId());
                }
                RelationshipType relationshipType = candidate.getRelationshipTypeOverride() != null
                        ? candidate.getRelationshipTypeOverride()
                        : project != null ? project.getTargetRelationshipType() : null;
                if (relationshipType == null) {
                    continue;
                }

                createRelationship(
                        ownerOrganizationService.getOwnerCompanyId(),
                        profile.getCompanyId(),
                        relationshipType.name(),
                        candidate.getReview() != null ? candidate.getReview().getReviewedBy() : "SYSTEM_SYNC",
                        candidate.getProjectId(),
                        candidate.getId(),
                        candidate.getRelationshipConfidenceScore() != null ? candidate.getRelationshipConfidenceScore() : 1.0
                );
                relationshipsSynchronized++;
                break;
            }
        }

        log.info("Synchronized {} company graph nodes and {} FPT relationships", nodesSynchronized, relationshipsSynchronized);
    }

    private void mergeOwnerNode() {
        ownerOrganizationService.findOwnerCompanyProfile().ifPresent(profile -> {
            String name = profile.getIdentity() != null && StringUtils.hasText(profile.getIdentity().getLegalName())
                    ? profile.getIdentity().getLegalName() : "Owner Organization";
            String industry = profile.getBusiness() != null && profile.getBusiness().getIndustries() != null
                    && !profile.getBusiness().getIndustries().isEmpty()
                    ? profile.getBusiness().getIndustries().get(0) : "Unknown";

            neo4jClient.query("""
                    MERGE (c:Company {companyId: $ownerCompanyId})
                    ON CREATE SET c.name = $name, c.industry = $industry, c.createdAt = datetime()
                    ON MATCH SET c.name = $name, c.industry = $industry, c.updatedAt = datetime()
                    """)
                    .bindAll(Map.of(
                            "ownerCompanyId", ownerOrganizationService.getOwnerCompanyId(),
                            "name", name,
                            "industry", industry))
                    .run();
        });
    }

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
        String cypher = "MATCH (c:Company {companyId: $companyId}) RETURN c";
        CompanyNode node = neo4jClient.query(cypher)
                .bind(companyId).to("companyId")
                .fetchAs(CompanyNode.class)
                .mappedBy((typeSystem, record) -> {
                    var n = record.get("c").asNode();
                    CompanyNode company = new CompanyNode();
                    company.setCompanyId(n.get("companyId").asString());
                    company.setName(n.get("name").asString("Unknown"));
                    company.setIndustry(n.get("industry").asString("Unknown"));
                    return company;
                })
                .one()
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
        String cypher = "MATCH (c:Company) RETURN c";
        return neo4jClient.query(cypher)
                .fetchAs(CompanyNode.class)
                .mappedBy((typeSystem, record) -> {
                    var node = record.get("c").asNode();
                    CompanyNode company = new CompanyNode();
                    company.setCompanyId(node.get("companyId").asString());
                    company.setName(node.get("name").asString("Unknown"));
                    company.setIndustry(node.get("industry").asString("Unknown"));
                    return company;
                })
                .all().stream()
                // The network consumer needs both nodes and persisted edges. Returning
                // nodes alone made a populated ecosystem appear disconnected in the UI.
                .map(company -> GraphCompanyDto.builder()
                        .companyId(company.getCompanyId())
                        .name(company.getName())
                        .industry(company.getIndustry())
                        .relationships(getOutgoingRelationships(company.getCompanyId()))
                        .build())
                .toList();
    }

    public List<GraphCompanyDto> getCompaniesByRelationshipType(String relType) {
        if (!relType.matches("^[A-Z_]+$")) {
            return List.of();
        }

        // Relationships are modeled from the owner organization to the target company.
        // Returning the source node here would incorrectly surface FPT as its own partner,
        // competitor, supplier, or customer.
        String cypher = String.format("""
            MATCH (owner:Company)-[:%s]-(c:Company)
            WHERE owner.companyId IN $ownerCompanyIds
              AND NOT c.companyId IN $ownerCompanyIds
            RETURN DISTINCT c
            """, relType);

        return neo4jClient.query(cypher)
                .bind(ownerOrganizationService.getOwnerGraphCompanyIds()).to("ownerCompanyIds")
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
                   r.projectId as projectId, r.candidateId as candidateId,
                   r.startDate as startDate, r.endDate as endDate, r.status as status, r.metadata as metadata
            """;

        return neo4jClient.query(cypher)
                .bindAll(Map.of("companyId", companyId))
                .fetch()
                .all()
                .stream()
                .map(record -> {
                    Map<String, Object> parsedMetadata = null;
                    String metadataStr = (String) record.get("metadata");
                    if (StringUtils.hasText(metadataStr)) {
                        try {
                            parsedMetadata = objectMapper.readValue(metadataStr, new TypeReference<Map<String, Object>>() {});
                        } catch (JsonProcessingException e) {
                            log.error("Failed to parse metadata JSON", e);
                        }
                    }

                    String startDateStr = (String) record.get("startDate");
                    String endDateStr = (String) record.get("endDate");

                    return CompanyRelationshipDto.builder()
                        .sourceCompanyId(companyId)
                        .targetCompanyId((String) record.get("targetCompanyId"))
                        .relationshipType((String) record.get("relType"))
                        .confidenceScore((Double) record.get("confidenceScore"))
                        .confirmedBy((String) record.get("confirmedBy"))
                        .projectId((String) record.get("projectId"))
                        .candidateId((String) record.get("candidateId"))
                        .startDate(StringUtils.hasText(startDateStr) ? LocalDate.parse(startDateStr) : null)
                        .endDate(StringUtils.hasText(endDateStr) ? LocalDate.parse(endDateStr) : null)
                        .status((String) record.get("status"))
                        .metadata(parsedMetadata)
                        .build();
                })
                .toList();
    }
}

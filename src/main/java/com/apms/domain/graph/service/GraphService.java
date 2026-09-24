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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    public void mergeCompanyNode(CompanyProfile profile) {
        if (profile == null) {
            return;
        }
        String companyId = StringUtils.hasText(profile.getCompanyId()) ? profile.getCompanyId().trim() : profile.getId();
        if (!StringUtils.hasText(companyId)) {
            return;
        }

        String name = null;
        if (profile.getIdentity() != null && StringUtils.hasText(profile.getIdentity().getLegalName())) {
            name = profile.getIdentity().getLegalName().trim();
        }
        if (!StringUtils.hasText(name)) {
            name = "Unknown";
        }

        List<String> rawIndustries = profile.getBusiness() != null ? profile.getBusiness().getIndustries() : null;
        List<String> industries = normalizeIndustries(rawIndustries);
        String legacyIndustry = !industries.isEmpty() ? industries.get(0) : "Unknown";

        mergeCompanyNode(companyId.trim(), name, industries, legacyIndustry);
    }

    public void mergeCompanyNode(String companyId, String name, List<String> industries) {
        List<String> norm = normalizeIndustries(industries);
        String legacyIndustry = !norm.isEmpty() ? norm.get(0) : "Unknown";
        mergeCompanyNode(companyId, name, norm, legacyIndustry);
    }

    public void mergeCompanyNode(String companyId, String name, String industry) {
        if (!StringUtils.hasText(companyId)) return;
        List<String> industries = (StringUtils.hasText(industry) && !"Unknown".equalsIgnoreCase(industry.trim()))
                ? List.of(industry.trim())
                : Collections.emptyList();
        String legacyIndustry = StringUtils.hasText(industry) ? industry.trim() : "Unknown";
        mergeCompanyNode(companyId, name, industries, legacyIndustry);
    }

    public void mergeCompanyNode(String companyId, String name, List<String> industries, String legacyIndustry) {
        if (!StringUtils.hasText(companyId)) return;
        String canonicalName = StringUtils.hasText(name) ? name.trim() : "Unknown";
        List<String> canonicalIndustries = industries != null ? industries : Collections.emptyList();
        String canonicalLegacyIndustry = StringUtils.hasText(legacyIndustry) ? legacyIndustry.trim() : (!canonicalIndustries.isEmpty() ? canonicalIndustries.get(0) : "Unknown");

        String cypher = """
            MERGE (c:Company {companyId: $companyId})
            ON CREATE SET c.name = $name, c.industries = $industries, c.industry = $industry, c.createdAt = datetime()
            ON MATCH SET c.name = $name, c.industries = $industries, c.industry = $industry, c.updatedAt = datetime()
            """;

        neo4jClient.query(cypher)
                .bindAll(Map.of(
                        "companyId", companyId.trim(),
                        "name", canonicalName,
                        "industries", canonicalIndustries,
                        "industry", canonicalLegacyIndustry
                ))
                .run();

        log.info("Merged CompanyNode: companyId={}, name={}, industries={}", companyId.trim(), canonicalName, canonicalIndustries);
    }

    public static List<String> normalizeIndustries(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String s : raw) {
            if (s == null) continue;
            String trimmed = s.trim();
            if (trimmed.isEmpty()) continue;
            if (seen.add(trimmed.toLowerCase(java.util.Locale.ROOT))) {
                result.add(trimmed);
            }
        }
        return Collections.unmodifiableList(result);
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

        if (profileRepository != null && StringUtils.hasText(dto.getTargetCompanyId())) {
            CompanyProfile targetProfile = profileRepository.findByCompanyId(dto.getTargetCompanyId().trim())
                    .or(() -> profileRepository.findById(dto.getTargetCompanyId().trim()))
                    .orElse(null);
            if (targetProfile != null) {
                mergeCompanyNode(targetProfile);
            }
        }
        if (ownerOrganizationService != null && StringUtils.hasText(dto.getSourceCompanyId())) {
            ownerOrganizationService.findOwnerCompanyProfile().ifPresent(this::mergeCompanyNode);
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

    public void replaceRelationship(String sourceCompanyId, String targetCompanyId, String newRelType, String confirmedBy) {
        if (!newRelType.matches("^[A-Z_]+$")) {
            throw new IllegalArgumentException("Invalid relationship type: " + newRelType);
        }

        String canonicalSourceId = StringUtils.hasText(sourceCompanyId)
                ? sourceCompanyId.trim()
                : (ownerOrganizationService != null ? ownerOrganizationService.getOwnerCompanyId() : "");

        String canonicalTargetId = targetCompanyId != null ? targetCompanyId.trim() : "";
        List<String> targetIds = new ArrayList<>();
        if (StringUtils.hasText(canonicalTargetId)) {
            targetIds.add(canonicalTargetId);
        }

        if (profileRepository != null && StringUtils.hasText(targetCompanyId)) {
            CompanyProfile profile = profileRepository.findByCompanyId(targetCompanyId)
                    .or(() -> profileRepository.findById(targetCompanyId))
                    .orElse(null);
            if (profile != null) {
                mergeCompanyNode(profile);
                if (StringUtils.hasText(profile.getCompanyId())) {
                    String pCompanyId = profile.getCompanyId().trim();
                    if (!targetIds.contains(pCompanyId)) {
                        targetIds.add(pCompanyId);
                    }
                    canonicalTargetId = pCompanyId;
                }
                if (StringUtils.hasText(profile.getId()) && !targetIds.contains(profile.getId().trim())) {
                    targetIds.add(profile.getId().trim());
                }
            }
        }

        // Delete ONLY supported APMS business relationship edges between this exact pair in either direction
        String deleteCypher = """
            MATCH (c1:Company {companyId: $sourceCompanyId})
            MATCH (c2:Company)
            WHERE c2.companyId IN $targetIds
            MATCH (c1)-[r:PARTNER_WITH|COMPETITOR_OF|POTENTIAL_PARTNER_OF|SUPPLIER_OF|CUSTOMER_OF]-(c2)
            DELETE r
            """;

        neo4jClient.query(deleteCypher)
                .bindAll(Map.of(
                        "sourceCompanyId", canonicalSourceId,
                        "targetIds", targetIds
                ))
                .run();

        // Ensure owner node and target node exist, then create exactly ONE canonical target relationship
        String createCypher = String.format("""
            MERGE (c1:Company {companyId: $sourceCompanyId})
            MERGE (c2:Company {companyId: $targetCompanyId})
            MERGE (c1)-[r:%s]->(c2)
            SET r.confidenceScore = 1.0,
                r.confirmedBy = $confirmedBy,
                r.confirmedAt = coalesce(r.confirmedAt, datetime())
            """, newRelType);

        neo4jClient.query(createCypher)
                .bindAll(Map.of(
                        "sourceCompanyId", canonicalSourceId,
                        "targetCompanyId", canonicalTargetId,
                        "confirmedBy", StringUtils.hasText(confirmedBy) ? confirmedBy : "SYSTEM"
                ))
                .run();

        log.info("Replaced relationships between owner {} and target {} (targetIds: {}) with type {}",
                canonicalSourceId, canonicalTargetId, targetIds, newRelType);
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

    private boolean isVisible(String companyId) {
        return profileRepository.findByCompanyId(companyId)
                .map(p -> "APPROVED".equals(p.getReviewStatus()) && !Boolean.TRUE.equals(p.getIsHidden()))
                .orElse(false);
    }

    public GraphCompanyDto getCompanyNodeWithRelationships(String companyId) {
        if (!isVisible(companyId)) return null;
        CompanyNode node = companyNodeRepository.findByCompanyId(companyId)
                .orElse(null);
        if (node == null) return null;

        List<CompanyRelationshipDto> relationships = getOutgoingRelationships(companyId);

        List<String> industries = (node.getIndustries() != null && !node.getIndustries().isEmpty())
                ? node.getIndustries()
                : (StringUtils.hasText(node.getIndustry()) && !"Unknown".equalsIgnoreCase(node.getIndustry().trim())
                    ? List.of(node.getIndustry().trim())
                    : List.of());
        String legacyIndustry = StringUtils.hasText(node.getIndustry())
                ? node.getIndustry().trim()
                : (!industries.isEmpty() ? industries.get(0) : "Unknown");

        return GraphCompanyDto.builder()
                .companyId(node.getCompanyId())
                .name(node.getName())
                .industry(legacyIndustry)
                .industries(industries)
                .createdAt(node.getCreatedAt())
                .updatedAt(node.getUpdatedAt())
                .relationships(relationships)
                .build();
    }

    public List<GraphCompanyDto> getNetwork() {
        return companyNodeRepository.findAllNodes().stream()
                .filter(node -> isVisible(node.getCompanyId()))
                .map(node -> {
                    List<String> industries = (node.getIndustries() != null && !node.getIndustries().isEmpty())
                            ? node.getIndustries()
                            : (StringUtils.hasText(node.getIndustry()) && !"Unknown".equalsIgnoreCase(node.getIndustry().trim())
                                ? List.of(node.getIndustry().trim())
                                : List.of());
                    String legacyIndustry = StringUtils.hasText(node.getIndustry())
                            ? node.getIndustry().trim()
                            : (!industries.isEmpty() ? industries.get(0) : "Unknown");

                    return GraphCompanyDto.builder()
                            .companyId(node.getCompanyId())
                            .name(node.getName())
                            .industry(legacyIndustry)
                            .industries(industries)
                            .createdAt(node.getCreatedAt())
                            .updatedAt(node.getUpdatedAt())
                            .build();
                })
                .toList();
    }

    public List<CompanyRelationshipDto> getPairRelationships(String companyIdA, String companyIdB) {
        if (!isVisible(companyIdA) || !isVisible(companyIdB)) return List.of();
        
        String cypher = """
            MATCH (c1:Company {companyId: $companyIdA})-[r]-(c2:Company {companyId: $companyIdB})
            RETURN type(r) as relType, startNode(r).companyId as sourceCompanyId, endNode(r).companyId as targetCompanyId,
                   r.confidenceScore as confidenceScore, r.confirmedBy as confirmedBy,
                   r.projectId as projectId, r.candidateId as candidateId,
                   r.startDate as startDate, r.endDate as endDate, r.status as status, r.metadata as metadata
            """;

        return (List<CompanyRelationshipDto>) neo4jClient.query(cypher)
                .bindAll(Map.of("companyIdA", companyIdA, "companyIdB", companyIdB))
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
                        .sourceCompanyId((String) record.get("sourceCompanyId"))
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
                    if (node.containsKey("industries") && !node.get("industries").isNull()) {
                        try {
                            c.setIndustries(node.get("industries").asList(org.neo4j.driver.Value::asString));
                        } catch (Exception ignored) {}
                    }
                    return c;
                })
                .all().stream()
                .map(node -> {
                    List<String> industries = (node.getIndustries() != null && !node.getIndustries().isEmpty())
                            ? node.getIndustries()
                            : (StringUtils.hasText(node.getIndustry()) && !"Unknown".equalsIgnoreCase(node.getIndustry().trim())
                                ? List.of(node.getIndustry().trim())
                                : List.of());
                    String legacyIndustry = StringUtils.hasText(node.getIndustry())
                            ? node.getIndustry().trim()
                            : (!industries.isEmpty() ? industries.get(0) : "Unknown");

                    return GraphCompanyDto.builder()
                            .companyId(node.getCompanyId())
                            .name(node.getName())
                            .industry(legacyIndustry)
                            .industries(industries)
                            .build();
                })
                .filter(dto -> isVisible(dto.getCompanyId()))
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

        return (List<CompanyRelationshipDto>) neo4jClient.query(cypher)
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
                .filter(rel -> isVisible(rel.getTargetCompanyId()))
                .toList();
    }

    public String getCurrentRelationshipType(String sourceCompanyId, String targetCompanyId) {
        String cypher = """
            MATCH (:Company {companyId: $sourceCompanyId})-[r]->(:Company {companyId: $targetCompanyId})
            RETURN type(r) as relType
            """;
        java.util.List<String> types = neo4jClient.query(cypher)
                .bindAll(java.util.Map.of("sourceCompanyId", sourceCompanyId, "targetCompanyId", targetCompanyId))
                .fetchAs(String.class)
                .mappedBy((typeSystem, record) -> record.get("relType").asString())
                .all()
                .stream().toList();
        
        if (types.isEmpty()) {
            throw new com.apms.common.exception.BusinessConflictException("No relationship found between " + sourceCompanyId + " and " + targetCompanyId);
        }
        if (types.size() > 1) {
            throw new com.apms.common.exception.BusinessConflictException("Multiple relationships found between " + sourceCompanyId + " and " + targetCompanyId);
        }
        return types.get(0);
    }

    public void updateRelationship(String sourceCompanyId, String targetCompanyId, com.apms.common.enums.RelationshipType oldRelType, com.apms.common.enums.RelationshipType newRelType) {
        String oldRel = switch (oldRelType) {
            case PARTNER_WITH -> "PARTNER_WITH";
            case POTENTIAL_PARTNER_OF -> "POTENTIAL_PARTNER_OF";
            case COMPETITOR_OF -> "COMPETITOR_OF";
            case CUSTOMER_OF -> "CUSTOMER_OF";
            case SUPPLIER_OF -> "SUPPLIER_OF";
            default -> throw new IllegalArgumentException("Unsupported old relationship type");
        };
        String newRel = switch (newRelType) {
            case PARTNER_WITH -> "PARTNER_WITH";
            case POTENTIAL_PARTNER_OF -> "POTENTIAL_PARTNER_OF";
            case COMPETITOR_OF -> "COMPETITOR_OF";
            case CUSTOMER_OF -> "CUSTOMER_OF";
            case SUPPLIER_OF -> "SUPPLIER_OF";
            default -> throw new IllegalArgumentException("Unsupported new relationship type");
        };

        String cypher = String.format("""
            MATCH (c1:Company {companyId: $sourceCompanyId})-[old:%s]->(c2:Company {companyId: $targetCompanyId})
            WITH c1, c2, old, properties(old) AS props
            DELETE old
            MERGE (c1)-[newRel:%s]->(c2)
            SET newRel = props
            """, oldRel, newRel);

        neo4jClient.query(cypher)
                .bindAll(java.util.Map.of("sourceCompanyId", sourceCompanyId, "targetCompanyId", targetCompanyId))
                .run();
    }

    public void restoreRelationship(String sourceCompanyId, String targetCompanyId, com.apms.common.enums.RelationshipType fromRelType, com.apms.common.enums.RelationshipType toRelType) {
        updateRelationship(sourceCompanyId, targetCompanyId, fromRelType, toRelType);
    }

    public List<String> getTargetCompanyIdsByRelationship(String sourceCompanyId, String relType) {
        if (!relType.matches("^[A-Z_]+$")) return List.of();
        String cypher = String.format("MATCH (a:Company)-[:%s]->(b:Company) WHERE a.companyId = $ownerId RETURN b.companyId as targetId", relType);
        return neo4jClient.query(cypher)
                .bind(sourceCompanyId).to("ownerId")
                .fetchAs(String.class)
                .mappedBy((ts, record) -> record.get("targetId").asString())
                .all()
                .stream()
                .filter(id -> !sourceCompanyId.equals(id))
                .filter(this::isVisible)
                .distinct()
                .toList();
    }

    public List<String> getEcosystemCompanyIds(String sourceCompanyId) {
        String cypher = "MATCH (:Company {companyId: $ownerId})-[r:PARTNER_WITH|POTENTIAL_PARTNER_OF|COMPETITOR_OF|CUSTOMER_OF|SUPPLIER_OF]-(t:Company) RETURN DISTINCT t.companyId as targetId";
        return neo4jClient.query(cypher)
                .bindAll(java.util.Map.of("ownerId", sourceCompanyId))
                .fetchAs(String.class)
                .mappedBy((ts, record) -> record.get("targetId").asString())
                .all()
                .stream()
                .filter(id -> !sourceCompanyId.equals(id))
                .toList();
    }
}

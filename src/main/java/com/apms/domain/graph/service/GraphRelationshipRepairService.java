package com.apms.domain.graph.service;

import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.RelationshipType;
import com.apms.common.event.OwnerEnterpriseCreatedEvent;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.CompanyProfileVersion;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import com.apms.domain.profile.service.CompanyProfileOfficialEvaluator;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.project.Project;
import com.apms.domain.project.repository.sql.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class GraphRelationshipRepairService {

    private final ProjectRepository projectRepository;
    private final CompanyProfileRepository profileRepository;
    private final CompanyProfileVersionRepository versionRepository;
    private final OwnerOrganizationService ownerOrganizationService;
    private final Neo4jClient neo4jClient;
    private final GraphService graphService;
    private final CompanyProfileOfficialEvaluator companyProfileOfficialEvaluator;

    /**
     * Comparator ordering completed projects newest-first:
     * 1. closedAt (authoritative completion timestamp) descending
     * 2. updatedAt descending
     * 3. createdAt descending
     * 4. id descending (deterministic fallback)
     */
    private static final Comparator<Project> LATEST_COMPLETED_PROJECT_FIRST = (p1, p2) -> {
        if (p1 == p2) return 0;
        if (p1 == null) return 1;
        if (p2 == null) return -1;

        // 1. Authoritative closedAt (completed) timestamp
        LocalDateTime c1 = p1.getClosedAt();
        LocalDateTime c2 = p2.getClosedAt();
        if (c1 != null || c2 != null) {
            if (c1 == null) return 1;
            if (c2 == null) return -1;
            int cmp = c2.compareTo(c1);
            if (cmp != 0) return cmp;
        }

        // 2. Fallback to updatedAt
        LocalDateTime u1 = p1.getUpdatedAt();
        LocalDateTime u2 = p2.getUpdatedAt();
        if (u1 != null || u2 != null) {
            if (u1 == null) return 1;
            if (u2 == null) return -1;
            int cmp = u2.compareTo(u1);
            if (cmp != 0) return cmp;
        }

        // 3. Fallback to createdAt
        LocalDateTime cr1 = p1.getCreatedAt();
        LocalDateTime cr2 = p2.getCreatedAt();
        if (cr1 != null || cr2 != null) {
            if (cr1 == null) return 1;
            if (cr2 == null) return -1;
            int cmp = cr2.compareTo(cr1);
            if (cmp != 0) return cmp;
        }

        // 4. Deterministic fallback to id descending
        Long id1 = p1.getId() != null ? p1.getId() : 0L;
        Long id2 = p2.getId() != null ? p2.getId() : 0L;
        return id2.compareTo(id1);
    };

    /**
     * Targeted repair for a single completed relationship-change project by ID.
     * Resolves the target company and applies the latest authoritative completed project
     * for that company, preventing older projects from overwriting newer ones.
     */
    public boolean repairCompletedProjectRelationship(Long projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) {
            log.warn("Repair skipped: Project {} not found", projectId);
            return false;
        }
        if (project.getStatus() != ProjectStatus.COMPLETED || project.getTargetRelationshipType() == null) {
            log.warn("Repair skipped: Project {} is not a completed relationship-change project", projectId);
            return false;
        }
        String targetRef = project.getTargetCompanyProfileId();
        if (StringUtils.hasText(targetRef)) {
            return repairRelationshipForCompany(targetRef);
        }
        return repairProjectForCompany("", null, project);
    }

    /**
     * Reconciles all official company profiles with Neo4j.
     * 1. Dynamically resolves My Enterprise as the owner.
     * 2. Iterates all official company profiles in the system (excluding My Enterprise itself).
     * 3. For each profile:
     *    - Determine its authoritative relationship
     *    - Ensure the company node exists in Neo4j
     *    - Ensure the owner node exists in Neo4j
     *    - Create or update the relationship edge between owner and company
     *    - If relationship is NONE, remove any relationship edge between owner and company
     *    - Never create a self-relationship
     * 4. Startup execution: runs on ApplicationReadyEvent, safely checking if My Enterprise exists.
     */
    @EventListener(ApplicationReadyEvent.class)
    public int reconcileAllOfficialCompanyProfiles() {
        try {
            Optional<CompanyProfile> ownerOpt = ownerOrganizationService.findOwnerCompanyProfile();
            if (ownerOpt.isEmpty()) {
                log.info("Graph reconciliation skipped: No owner enterprise configured yet.");
                return 0;
            }

            CompanyProfile owner = ownerOpt.get();
            String ownerCompanyId = ownerOrganizationService.getOwnerCompanyId();
            if (!StringUtils.hasText(ownerCompanyId)) {
                log.warn("Graph reconciliation skipped: Owner enterprise has no universal companyId.");
                return 0;
            }

            // Ensure owner node exists in Neo4j
            graphService.mergeCompanyNode(owner);

            List<CompanyProfile> allProfiles = profileRepository.findAll();
            List<Project> allProjects = projectRepository.findAll();
            int reconciledCount = 0;

            for (CompanyProfile profile : allProfiles) {
                if (profile == null || Boolean.TRUE.equals(profile.getIsDeleted())) {
                    continue;
                }

                // Exclude owner enterprise
                if (Objects.equals(profile.getId(), owner.getId()) ||
                    (StringUtils.hasText(profile.getCompanyId()) && Objects.equals(profile.getCompanyId().trim(), ownerCompanyId.trim()))) {
                    continue;
                }

                // Check if profile is official
                if (companyProfileOfficialEvaluator != null && !companyProfileOfficialEvaluator.isOfficial(profile)) {
                    continue;
                }

                String targetCompanyId = resolveCanonicalId(profile.getId(), profile);
                if (!StringUtils.hasText(targetCompanyId)) {
                    continue;
                }

                // Ensure target company node exists in Neo4j
                graphService.mergeCompanyNode(profile);

                // Determine authoritative relationship
                RelationshipType relType = resolveAuthoritativeRelationship(profile, allProjects);
                if (relType != null) {
                    graphService.replaceRelationship(ownerCompanyId, targetCompanyId, relType.name(), "SYSTEM_RECONCILE");
                    reconciledCount++;
                } else {
                    // Relationship is NONE -> remove any existing relationship between owner and target
                    graphService.deleteRelationshipBetween(ownerCompanyId, targetCompanyId);
                }
            }

            log.info("Reconciled {} official company profile relationship(s) with Neo4j for owner enterprise {}", reconciledCount, ownerCompanyId);
            return reconciledCount;
        } catch (Exception e) {
            log.error("Error during graph reconciliation: {}", e.getMessage(), e);
            return 0;
        }
    }

    @EventListener(OwnerEnterpriseCreatedEvent.class)
    public void handleOwnerEnterpriseCreatedEvent(OwnerEnterpriseCreatedEvent event) {
        log.info("Handling OwnerEnterpriseCreatedEvent for owner companyId={}", event.getCompanyId());
        reconcileAllOfficialCompanyProfiles();
    }

    public int repairAllCompletedProjectRelationships() {
        return reconcileAllOfficialCompanyProfiles();
    }

    public RelationshipType resolveAuthoritativeRelationship(CompanyProfile profile) {
        return resolveAuthoritativeRelationship(profile, projectRepository.findAll());
    }

    public RelationshipType resolveAuthoritativeRelationship(CompanyProfile profile, List<Project> allProjects) {
        if (profile == null) return null;

        String pId = profile.getId() != null ? profile.getId().trim() : "";
        String cId = profile.getCompanyId() != null ? profile.getCompanyId().trim() : "";

        // 1. Check completed relationship projects
        List<Project> matchingProjects = new ArrayList<>();
        if (allProjects != null) {
            for (Project p : allProjects) {
                if (p.getStatus() != ProjectStatus.COMPLETED || p.getTargetRelationshipType() == null) {
                    continue;
                }
                String targetRef = p.getTargetCompanyProfileId();
                boolean matches = false;
                if (StringUtils.hasText(targetRef)) {
                    String clean = targetRef.trim();
                    if (clean.equals(pId) || clean.equals(cId)) {
                        matches = true;
                    }
                }
                if (!matches && profile.getSourceRefs() != null && profile.getSourceRefs().getProjectIds() != null) {
                    String pidStr = String.valueOf(p.getId());
                    if (profile.getSourceRefs().getProjectIds().contains(pidStr)) {
                        matches = true;
                    }
                }
                if (matches) {
                    matchingProjects.add(p);
                }
            }
        }

        if (!matchingProjects.isEmpty()) {
            matchingProjects.sort(LATEST_COMPLETED_PROJECT_FIRST);
            return matchingProjects.get(0).getTargetRelationshipType();
        }

        // 2. Check profile versions
        if (versionRepository != null) {
            try {
                List<CompanyProfileVersion> versions = versionRepository.findByCompanyProfileIdOrCompanyIdOrderByCreatedAtDesc(
                        profile.getId(), profile.getCompanyId() != null ? profile.getCompanyId() : profile.getId());
                for (CompanyProfileVersion ver : versions) {
                    if (ver.getAfterValues() != null && ver.getAfterValues().containsKey("relationship")) {
                        Object relObj = ver.getAfterValues().get("relationship");
                        if (relObj != null && StringUtils.hasText(relObj.toString())) {
                            String norm = GraphService.normalizeRelationshipType(relObj.toString());
                            if (norm != null) {
                                return RelationshipType.valueOf(norm);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 3. Fallback: check if existing Neo4j relationship exists between owner and target
        try {
            String ownerCompanyId = ownerOrganizationService.getOwnerCompanyId();
            if (StringUtils.hasText(ownerCompanyId) && (StringUtils.hasText(cId) || StringUtils.hasText(pId))) {
                List<String> targetIds = new ArrayList<>();
                if (StringUtils.hasText(cId)) targetIds.add(cId);
                if (StringUtils.hasText(pId)) targetIds.add(pId);

                String cypher = """
                    MATCH (:Company {companyId: $ownerCompanyId})-[r:PARTNER_WITH|COMPETITOR_OF|POTENTIAL_PARTNER_OF|SUPPLIER_OF|CUSTOMER_OF]->(c:Company)
                    WHERE c.companyId IN $targetIds
                    RETURN type(r) AS relType
                    LIMIT 1
                    """;
                List<String> types = neo4jClient.query(cypher)
                        .bind(ownerCompanyId).to("ownerCompanyId")
                        .bind(targetIds).to("targetIds")
                        .fetchAs(String.class)
                        .mappedBy((ts, rec) -> rec.get("relType").asString())
                        .all().stream().toList();
                if (!types.isEmpty()) {
                    String norm = GraphService.normalizeRelationshipType(types.get(0));
                    if (norm != null) {
                        return RelationshipType.valueOf(norm);
                    }
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * Targeted repair for a specific company by companyId or profile ID.
     * Gathers all completed relationship-change projects targeting this company,
     * resolves the latest authoritative completed project, and applies ONLY that relationship.
     */
    public boolean repairRelationshipForCompany(String targetCompanyId) {
        if (!StringUtils.hasText(targetCompanyId)) return false;

        String lookupId = targetCompanyId.trim();
        CompanyProfile profile = profileRepository.findByCompanyId(lookupId)
                .or(() -> profileRepository.findById(lookupId))
                .orElse(null);

        String canonicalCompanyId = resolveCanonicalId(lookupId, profile);

        List<Project> allProjects = projectRepository.findAll();
        List<Project> matchingProjects = new ArrayList<>();

        for (Project p : allProjects) {
            if (p.getStatus() != ProjectStatus.COMPLETED || p.getTargetRelationshipType() == null) {
                continue;
            }
            String pTarget = p.getTargetCompanyProfileId();
            boolean matches = false;
            if (StringUtils.hasText(pTarget)) {
                String pTargetClean = pTarget.trim();
                if (pTargetClean.equals(lookupId) || pTargetClean.equals(canonicalCompanyId)
                        || (profile != null && (pTargetClean.equals(profile.getId()) || pTargetClean.equals(profile.getCompanyId())))) {
                    matches = true;
                }
            }
            if (matches && matchingProjects.stream().noneMatch(existing -> existing.getId().equals(p.getId()))) {
                matchingProjects.add(p);
            }
        }

        // Also check profile sourceRefs if available
        if (profile != null && profile.getSourceRefs() != null && profile.getSourceRefs().getProjectIds() != null) {
            for (String pidStr : profile.getSourceRefs().getProjectIds()) {
                try {
                    Long pid = Long.parseLong(pidStr.trim());
                    allProjects.stream()
                            .filter(p -> p.getId().equals(pid) && p.getStatus() == ProjectStatus.COMPLETED && p.getTargetRelationshipType() != null)
                            .findFirst()
                            .ifPresent(p -> {
                                if (matchingProjects.stream().noneMatch(existing -> existing.getId().equals(p.getId()))) {
                                    matchingProjects.add(p);
                                }
                            });
                } catch (Exception ignored) {}
            }
        }

        if (!matchingProjects.isEmpty()) {
            matchingProjects.sort(LATEST_COMPLETED_PROJECT_FIRST);
            Project latestProject = matchingProjects.get(0);
            log.info("Company {} has {} matching completed project(s). Selected latest authoritative project ID={} (closedAt={}, updatedAt={}, targetRel={})",
                    canonicalCompanyId, matchingProjects.size(), latestProject.getId(), latestProject.getClosedAt(), latestProject.getUpdatedAt(), latestProject.getTargetRelationshipType());
            return repairProjectForCompany(canonicalCompanyId, profile, latestProject);
        }

        // Fallback: check version history
        if (profile != null && repairFromProfileVersions(profile)) {
            return true;
        }

        log.warn("Repair skipped: No completed relationship project or version found for company {}", lookupId);
        return false;
    }

    private String resolveCanonicalId(String targetRef, CompanyProfile profile) {
        if (profile != null && StringUtils.hasText(profile.getCompanyId())) {
            return profile.getCompanyId().trim();
        }
        if (profile != null && StringUtils.hasText(profile.getId())) {
            return profile.getId().trim();
        }
        return StringUtils.hasText(targetRef) ? targetRef.trim() : "";
    }

    private boolean repairProjectForCompany(String canonicalCompanyId, CompanyProfile profile, Project project) {
        if (project == null || project.getStatus() != ProjectStatus.COMPLETED || project.getTargetRelationshipType() == null) {
            return false;
        }

        RelationshipType targetRel = project.getTargetRelationshipType();
        List<String> targetIds = new ArrayList<>();
        if (StringUtils.hasText(canonicalCompanyId)) {
            targetIds.add(canonicalCompanyId.trim());
        }
        if (profile != null) {
            if (StringUtils.hasText(profile.getCompanyId()) && !targetIds.contains(profile.getCompanyId().trim())) {
                targetIds.add(profile.getCompanyId().trim());
            }
            if (StringUtils.hasText(profile.getId()) && !targetIds.contains(profile.getId().trim())) {
                targetIds.add(profile.getId().trim());
            }
        }
        if (StringUtils.hasText(project.getTargetCompanyProfileId()) && !targetIds.contains(project.getTargetCompanyProfileId().trim())) {
            targetIds.add(project.getTargetCompanyProfileId().trim());
        }

        String effectiveCanonicalId = StringUtils.hasText(canonicalCompanyId)
                ? canonicalCompanyId.trim()
                : (profile != null && StringUtils.hasText(profile.getCompanyId()) ? profile.getCompanyId().trim() : project.getTargetCompanyProfileId().trim());

        return executePairRepair(effectiveCanonicalId, targetIds, targetRel, project.getId());
    }

    private boolean repairFromProfileVersions(CompanyProfile profile) {
        if (profile == null || versionRepository == null) return false;

        try {
            List<CompanyProfileVersion> versions = versionRepository.findByCompanyProfileIdOrCompanyIdOrderByCreatedAtDesc(
                    profile.getId(), profile.getCompanyId() != null ? profile.getCompanyId() : profile.getId());
            for (CompanyProfileVersion ver : versions) {
                if (ver.getAfterValues() != null && ver.getAfterValues().containsKey("relationship")) {
                    Object relObj = ver.getAfterValues().get("relationship");
                    if (relObj != null && StringUtils.hasText(relObj.toString())) {
                        try {
                            RelationshipType targetRel = RelationshipType.valueOf(relObj.toString().trim());
                            Long projId = ver.getCreatedFromProjectId();
                            String canonicalId = StringUtils.hasText(profile.getCompanyId()) ? profile.getCompanyId().trim() : profile.getId().trim();
                            List<String> targetIds = new ArrayList<>();
                            targetIds.add(canonicalId);
                            if (StringUtils.hasText(profile.getId()) && !targetIds.contains(profile.getId().trim())) {
                                targetIds.add(profile.getId().trim());
                            }
                            return executePairRepair(canonicalId, targetIds, targetRel, projId);
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}

        return false;
    }

    private boolean executePairRepair(String canonicalTargetId, List<String> targetIds, RelationshipType targetRel, Long projectId) {
        String ownerCompanyId = ownerOrganizationService.getOwnerCompanyId();
        if (!StringUtils.hasText(canonicalTargetId) || !StringUtils.hasText(ownerCompanyId) || targetRel == null) {
            return false;
        }

        List<String> lookupTargetIds = new ArrayList<>();
        if (targetIds != null) {
            for (String tid : targetIds) {
                if (StringUtils.hasText(tid) && !lookupTargetIds.contains(tid.trim())) {
                    lookupTargetIds.add(tid.trim());
                }
            }
        }
        if (!lookupTargetIds.contains(canonicalTargetId.trim())) {
            lookupTargetIds.add(canonicalTargetId.trim());
        }

        // 1. Delete ONLY supported business relationship edges for that exact pair (in either direction)
        String deleteCypher = """
            MATCH (c1:Company {companyId: $sourceCompanyId})
            MATCH (c2:Company)
            WHERE c2.companyId IN $targetIds
            MATCH (c1)-[r:PARTNER_WITH|COMPETITOR_OF|POTENTIAL_PARTNER_OF|SUPPLIER_OF|CUSTOMER_OF]-(c2)
            DELETE r
            """;

        neo4jClient.query(deleteCypher)
                .bindAll(Map.of(
                        "sourceCompanyId", ownerCompanyId,
                        "targetIds", lookupTargetIds
                ))
                .run();

        // Ensure owner node and target node exist with canonical metadata
        if (ownerOrganizationService != null && graphService != null) {
            ownerOrganizationService.findOwnerCompanyProfile().ifPresent(graphService::mergeCompanyNode);
        }
        if (graphService != null && profileRepository != null && StringUtils.hasText(canonicalTargetId)) {
            CompanyProfile targetProfile = profileRepository.findByCompanyId(canonicalTargetId.trim())
                    .or(() -> profileRepository.findById(canonicalTargetId.trim()))
                    .orElse(null);
            if (targetProfile != null) {
                graphService.mergeCompanyNode(targetProfile);
            } else if (projectId != null && projectRepository != null) {
                projectRepository.findById(projectId).ifPresent(p -> {
                    if (StringUtils.hasText(p.getTargetCompanyName())) {
                        graphService.mergeCompanyNode(canonicalTargetId.trim(), p.getTargetCompanyName().trim(), "Unknown");
                    }
                });
            }
        }

        // 2. Create exactly ONE target relationship between the exact pair
        String createCypher = String.format("""
            MERGE (c1:Company {companyId: $sourceCompanyId})
            MERGE (c2:Company {companyId: $targetCompanyId})
            MERGE (c1)-[r:%s]->(c2)
            SET r.confidenceScore = 1.0,
                r.confirmedBy = $confirmedBy,
                r.confirmedAt = coalesce(r.confirmedAt, datetime()),
                r.projectId = $projectId
            """, targetRel.name());

        neo4jClient.query(createCypher)
                .bindAll(Map.of(
                        "sourceCompanyId", ownerCompanyId,
                        "targetCompanyId", canonicalTargetId,
                        "confirmedBy", "SYSTEM_REPAIR",
                        "projectId", projectId != null ? String.valueOf(projectId) : ""
                ))
                .run();

        log.info("Repaired Neo4j relationship: owner={}, target={} (targetIds: {}), targetRel={}, projectId={}. No profile version created.",
                ownerCompanyId, canonicalTargetId, lookupTargetIds, targetRel.name(), projectId);
        return true;
    }

    public boolean repairCompanyNodeMetadata(String companyId) {
        if (!StringUtils.hasText(companyId) || graphService == null || profileRepository == null) return false;
        CompanyProfile profile = profileRepository.findByCompanyId(companyId.trim())
                .or(() -> profileRepository.findById(companyId.trim()))
                .orElse(null);
        if (profile != null) {
            graphService.mergeCompanyNode(profile);
            return true;
        }
        return false;
    }
}


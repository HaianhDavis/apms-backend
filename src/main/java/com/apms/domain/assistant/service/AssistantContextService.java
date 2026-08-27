package com.apms.domain.assistant.service;

import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.assistant.dto.AiSourceReference;
import com.apms.domain.assistant.dto.AssistantContext;
import com.apms.domain.graph.dto.CompanyRelationshipDto;
import com.apms.domain.graph.service.GraphService;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles an AssistantContext from APPROVED data sources only.
 *
 * Approved sources:
 *   - MongoDB company_profiles
 *   - Neo4j company nodes + relationships
 *   - SQL Server score_snapshots
 *
 * Intentionally excluded:
 *   - raw_documents
 *   - ai_extraction_results
 *   - company_candidates (any status)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantContextService {

    private final CompanyProfileRepository companyProfileRepository;
    private final org.springframework.data.neo4j.core.Neo4jClient neo4jClient;

    /**
     * Assembles the full approved context for a given company profile.
     * If companyProfileId is blank, returns a minimal project-level context.
     *
     * @param projectId       SQL project ID (for source references)
     * @param companyProfileId MongoDB CompanyProfile.id (optional)
     * @return AssistantContext with contextText and sources populated
     */
    public AssistantContext buildContext(Long projectId, String companyProfileId) {
        List<AiSourceReference> sources = new ArrayList<>();
        StringBuilder contextBuilder = new StringBuilder();

        contextBuilder.append("APMS Approved Business Intelligence Data\n");
        contextBuilder.append("Project ID: ").append(projectId).append("\n\n");

        CompanyProfile profile = null;
        List<String> formattedRelationships = new ArrayList<>();

        if (StringUtils.hasText(companyProfileId)) {
            profile = companyProfileRepository.findById(companyProfileId)
                    .filter(p -> "APPROVED".equals(p.getReviewStatus()) && !Boolean.TRUE.equals(p.getIsHidden()))
                    .orElse(null);

            if (profile == null) {
                log.warn("CompanyProfile not found: {}", companyProfileId);
                contextBuilder.append("Company Profile: No approved profile found for ID: ")
                        .append(companyProfileId).append("\n");
            } else {
                sources.add(AiSourceReference.builder()
                        .type("company_profiles")
                        .id(profile.getId())
                        .title(resolveCompanyName(profile))
                        .build());

                contextBuilder.append(formatProfile(profile));

                // ── 2. Load Neo4j relationships ──────────────────────────────────
                if (StringUtils.hasText(profile.getCompanyId())) {
                    try {
                        String cypher = """
                            MATCH (c:Company)-[r]-(other:Company)
                            WHERE c.companyId = $companyId
                              AND r.projectId = $projectId
                            RETURN c.name AS cName, type(r) AS relType, other.name AS otherName, startNode(r) = c AS isOutgoing
                            """;
                        var records = neo4jClient.query(cypher)
                                .bindAll(java.util.Map.of("companyId", profile.getCompanyId(), "projectId", String.valueOf(projectId)))
                                .fetch().all();

                        for (var record : records) {
                            String cName = (String) record.get("cName");
                            String relType = (String) record.get("relType");
                            String otherName = (String) record.get("otherName");
                            boolean isOutgoing = (Boolean) record.get("isOutgoing");

                            if (isOutgoing) {
                                formattedRelationships.add(cName + " " + relType + " " + otherName);
                            } else {
                                formattedRelationships.add(otherName + " " + relType + " " + cName);
                            }
                        }

                        if (!formattedRelationships.isEmpty()) {
                            sources.add(AiSourceReference.builder()
                                    .type("neo4j_relationships")
                                    .id(profile.getCompanyId())
                                    .title("Approved relationship graph")
                                    .build());
                            contextBuilder.append("=== COMPANY RELATIONSHIPS (APPROVED NEO4J DATA) ===\n");
                            formattedRelationships.forEach(rel -> contextBuilder.append("  - ").append(rel).append("\n"));
                            contextBuilder.append("\n");
                        }
                    } catch (Exception e) {
                        log.warn("Could not load Neo4j relationships for companyId={}: {}", profile.getCompanyId(), e.getMessage());
                    }

                }
            }
        } else {
            contextBuilder.append("No specific company selected. Answering based on project context only.\n");
        }

        return AssistantContext.builder()
                .projectId(projectId)
                .companyProfile(profile)
                .formattedRelationships(formattedRelationships)
                .contextText(contextBuilder.toString())
                .sources(sources)
                .build();
    }

    // ─── Private formatters ───────────────────────────────────────────────────

    private String resolveCompanyName(CompanyProfile profile) {
        if (profile.getIdentity() == null) return "Unknown Company";
        String name = profile.getIdentity().getLegalName();
        if (!StringUtils.hasText(name)) name = profile.getIdentity().getTradeName();
        return StringUtils.hasText(name) ? name : "Unknown Company";
    }

    private String formatProfile(CompanyProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== COMPANY PROFILE (APPROVED SOURCE) ===\n");

        if (profile.getIdentity() != null) {
            sb.append("Legal Name: ").append(profile.getIdentity().getLegalName()).append("\n");
            sb.append("Trade Name: ").append(profile.getIdentity().getTradeName()).append("\n");
            sb.append("Tax Code: ").append(profile.getIdentity().getTaxCode()).append("\n");
        }

        if (profile.getBusiness() != null) {
            sb.append("Industries: ").append(profile.getBusiness().getIndustries()).append("\n");
            sb.append("Business Model: ").append(profile.getBusiness().getBusinessModel()).append("\n");
            sb.append("Markets: ").append(profile.getBusiness().getMarkets()).append("\n");
            sb.append("Target Customers: ").append(profile.getBusiness().getTargetCustomers()).append("\n");
            if (profile.getBusiness().getProducts() != null && !profile.getBusiness().getProducts().isEmpty()) {
                sb.append("Products/Services:\n");
                profile.getBusiness().getProducts().forEach(p ->
                        sb.append("  - ").append(p.getName())
                                .append(" [").append(p.getCategory()).append("]: ")
                                .append(p.getDescription()).append("\n")
                );
            }
        }

        if (profile.getCompanySize() != null) {
            sb.append("Employee Tier: ").append(profile.getCompanySize().getEmployeeTier()).append("\n");
            sb.append("Revenue Tier: ").append(profile.getCompanySize().getRevenueTier()).append("\n");
        }

        if (profile.getContact() != null) {
            sb.append("Website: ").append(profile.getContact().getWebsite()).append("\n");
        }

        if (profile.getInsights() != null) {
            sb.append("Strengths: ").append(profile.getInsights().getStrengths()).append("\n");
            sb.append("Weaknesses: ").append(profile.getInsights().getWeaknesses()).append("\n");
            sb.append("Opportunities: ").append(profile.getInsights().getOpportunities()).append("\n");
            sb.append("Threats: ").append(profile.getInsights().getThreats()).append("\n");
        }

        sb.append("\n");
        return sb.toString();
    }

}

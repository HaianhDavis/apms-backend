package com.apms.domain.assistant.service;

import com.apms.domain.assistant.dto.AiSourceReference;
import com.apms.domain.assistant.dto.AssistantContext;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.score.ScoreSnapshot;
import com.apms.domain.score.repository.sql.ScoreSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds AssistantContext for Business Owner queries.
 *
 * Context resolution order:
 *   1. Owner organization is always the root (companyId = OWNER_ORG_COMPANY_ID).
 *      For dev: "6a31a0000000000000000000" (APMS Demo Organization).
 *   2. Load bidirectional Neo4j relationships from the owner org node.
 *   3. Collect related companyIds from those relationships.
 *   4. Load MongoDB company_profiles by those companyIds.
 *   5. Load SQL score_snapshots for each related profile.
 *
 * If request.companyProfileId is provided (from UI):
 *   - Focus on that company immediately (no text matching needed).
 *
 * If not provided:
 *   - Try name matching from question as fallback.
 *   - 0 matches  -> full ecosystem context.
 *   - 1 match    -> focused company context.
 *   - >1 matches -> clarification response (not a technical exception, handled in service).
 *
 * Approved data only:
 *   - MongoDB company_profiles
 *   - Neo4j Company nodes + approved relationships
 *   - SQL score_snapshots
 *   NOT: raw_documents, ai_extraction_results, company_candidates
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OwnerAssistantContextService {

    private final CompanyProfileRepository companyProfileRepository;
    private final ScoreSnapshotRepository scoreSnapshotRepository;
    private final Neo4jClient neo4jClient;
    private final com.apms.domain.profile.service.OwnerOrganizationService ownerOrganizationService;

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * @param companyProfileId explicit company from UI (may be null)
     * @param question         free-text owner question
     */
    public AssistantContext buildContext(String companyProfileId, String question) {
        List<AiSourceReference> sources = new ArrayList<>();
        StringBuilder ctx = new StringBuilder();

        ctx.append("APMS Executive Business Intelligence Data\n");
        
        Optional<CompanyProfile> ownerProfileOpt = ownerOrganizationService.findOwnerCompanyProfile();
        String ownerName = ownerProfileOpt.map(p -> {
            if (p.getIdentity() != null && StringUtils.hasText(p.getIdentity().getTradeName())) {
                return p.getIdentity().getTradeName();
            } else if (p.getIdentity() != null && StringUtils.hasText(p.getIdentity().getLegalName())) {
                return p.getIdentity().getLegalName();
            } else {
                return p.getCompanyId();
            }
        }).orElse(ownerOrganizationService.getOwnerCompanyId());
        
        ctx.append("Owner Organization: ").append(ownerName).append("\n\n");

        // ── 1. Load Neo4j relationships from owner org ────────────────────────
        OwnerRelationshipResult ownerRels = loadOwnerOrgRelationships(sources, ctx);

        // ── 2. Load company_profiles for related companies ────────────────────
        List<CompanyProfile> relatedProfiles = loadRelatedProfiles(ownerRels.relatedCompanyIds, sources);

        // ── 3a. Explicit companyProfileId from UI ─────────────────────────────
        if (StringUtils.hasText(companyProfileId)) {
            CompanyProfile target = relatedProfiles.stream()
                    .filter(p -> companyProfileId.equals(p.getId()))
                    .findFirst()
                    .orElseGet(() -> companyProfileRepository.findById(companyProfileId).orElse(null));

            if (target != null) {
                return buildFocusedCompanyContext(target, ownerRels, sources, ctx);
            } else {
                log.warn("companyProfileId {} not found in approved data.", companyProfileId);
                ctx.append("Requested company not found in approved data.\n");
                return buildEcosystemContext(relatedProfiles, ownerRels, sources, ctx);
            }
        }

        // ── 3b. Try name matching from question ───────────────────────────────
        List<CompanyProfile> matched = resolveCompaniesFromQuestion(question, relatedProfiles);

        if (matched.size() > 1) {
            // Signal clarification needed — callers catch this and respond gracefully
            String names = matched.stream()
                    .map(p -> p.getIdentity() != null ? resolveCompanyName(p) : "Unknown")
                    .collect(Collectors.joining(", "));
            throw new ClarificationRequiredException(
                    "I found multiple companies matching your question: " + names
                    + ". Could you please clarify which company you mean?");
        }

        if (matched.size() == 1) {
            return buildFocusedCompanyContext(matched.get(0), ownerRels, sources, ctx);
        }

        // ── 3c. Ecosystem-level context ───────────────────────────────────────
        return buildEcosystemContext(relatedProfiles, ownerRels, sources, ctx);
    }

    // ── Private builders ──────────────────────────────────────────────────────

    private AssistantContext buildFocusedCompanyContext(
            CompanyProfile profile,
            OwnerRelationshipResult ownerRels,
            List<AiSourceReference> sources,
            StringBuilder ctx) {

        sources.add(AiSourceReference.builder()
                .type("company_profiles")
                .id(profile.getId())
                .title(resolveCompanyName(profile))
                .build());

        ctx.append(formatProfile(profile));

        // Focused relationships: only those touching this company
        String targetCompanyId = profile.getCompanyId();
        List<String> focusedRels = ownerRels.formattedRelationships.stream()
                .filter(r -> r.contains(resolveCompanyName(profile))
                        || (StringUtils.hasText(targetCompanyId) && r.contains(targetCompanyId)))
                .toList();

        if (!focusedRels.isEmpty()) {
            ctx.append("=== RELATIONSHIPS TO OWNER ORGANIZATION ===\n");
            focusedRels.forEach(r -> ctx.append("  - ").append(r).append("\n"));
            ctx.append("\n");
        } else if (!ownerRels.formattedRelationships.isEmpty()) {
            ctx.append("=== ALL OWNER ORGANIZATION RELATIONSHIPS ===\n");
            ownerRels.formattedRelationships.forEach(r -> ctx.append("  - ").append(r).append("\n"));
            ctx.append("\n");
        }

        ScoreSnapshot latestScore = null;
        if (StringUtils.hasText(profile.getCompanyId())) {
            latestScore = getLatestScore(profile.getCompanyId());
            if (latestScore != null) {
                sources.add(AiSourceReference.builder()
                        .type("score_snapshots")
                        .id(String.valueOf(latestScore.getScoreSnapshotId()))
                        .title("Latest score (" + latestScore.getRuleVersion() + ")")
                        .build());
                ctx.append(formatScore(resolveCompanyName(profile), latestScore));
            }
        }

        return AssistantContext.builder()
                .companyProfile(profile)
                .formattedRelationships(ownerRels.formattedRelationships)
                .latestScore(latestScore)
                .contextText(ctx.toString())
                .sources(sources)
                .build();
    }

    private AssistantContext buildEcosystemContext(
            List<CompanyProfile> relatedProfiles,
            OwnerRelationshipResult ownerRels,
            List<AiSourceReference> sources,
            StringBuilder ctx) {

        ctx.append("=== ECOSYSTEM EXECUTIVE SUMMARY ===\n\n");

        if (relatedProfiles.isEmpty()) {
            ctx.append("No approved company profiles found in the owner organization's business ecosystem.\n");
        } else {
            sources.add(AiSourceReference.builder()
                    .type("company_profiles")
                    .id(ownerOrganizationService.getOwnerCompanyId())
                    .title("Ecosystem profiles")
                    .build());
            relatedProfiles.forEach(p -> ctx.append(formatProfile(p)));
        }

        // Scores for related companies
        boolean hasScores = false;
        StringBuilder scoresCtx = new StringBuilder();
        for (CompanyProfile p : relatedProfiles) {
            if (!StringUtils.hasText(p.getCompanyId())) continue;
            ScoreSnapshot s = getLatestScore(p.getCompanyId());
            if (s != null) {
                hasScores = true;
                scoresCtx.append(formatScore(resolveCompanyName(p), s));
            }
        }

        if (hasScores) {
            sources.add(AiSourceReference.builder()
                    .type("score_snapshots")
                    .id(ownerOrganizationService.getOwnerCompanyId())
                    .title("Ecosystem score snapshots")
                    .build());
            ctx.append("=== ECOSYSTEM SCORES ===\n");
            ctx.append(scoresCtx);
        }

        return AssistantContext.builder()
                .formattedRelationships(ownerRels.formattedRelationships)
                .contextText(ctx.toString())
                .sources(sources)
                .build();
    }

    // ── Neo4j helper ──────────────────────────────────────────────────────────

    /**
     * Loads all bidirectional relationships from the owner org node.
     * Collects the companyIds of all connected companies for subsequent profile loading.
     * Does NOT filter by projectId — owner-level view is across all approved relationships.
     */
    private OwnerRelationshipResult loadOwnerOrgRelationships(List<AiSourceReference> sources, StringBuilder ctx) {
        List<String> formattedRelationships = new ArrayList<>();
        Set<String> relatedCompanyIds = new LinkedHashSet<>();

        try {
            String cypher = """
                MATCH (owner:Company)-[r]-(other:Company)
                WHERE owner.companyId = $ownerCompanyId
                RETURN owner.name AS ownerName, type(r) AS relType, other.name AS otherName,
                       other.companyId AS otherCompanyId, startNode(r) = owner AS isOutgoing
                """;
            var records = neo4jClient.query(cypher)
                    .bindAll(Map.of("ownerCompanyId", ownerOrganizationService.getOwnerCompanyId()))
                    .fetch().all();

            for (var record : records) {
                String ownerName = (String) record.get("ownerName");
                String relType = (String) record.get("relType");
                String otherName = (String) record.get("otherName");
                String otherCompanyId = (String) record.get("otherCompanyId");
                boolean isOutgoing = (Boolean) record.get("isOutgoing");

                String formatted = isOutgoing
                        ? (ownerName + " " + relType + " " + otherName)
                        : (otherName + " " + relType + " " + ownerName);
                formattedRelationships.add(formatted);

                if (StringUtils.hasText(otherCompanyId)) {
                    relatedCompanyIds.add(otherCompanyId);
                }
            }

            if (!formattedRelationships.isEmpty()) {
                sources.add(AiSourceReference.builder()
                        .type("neo4j_relationships")
                        .id(ownerOrganizationService.getOwnerCompanyId())
                        .title("Approved relationship graph")
                        .build());
                ctx.append("=== OWNER ORGANIZATION RELATIONSHIPS (APPROVED NEO4J DATA) ===\n");
                formattedRelationships.forEach(r -> ctx.append("  - ").append(r).append("\n"));
                ctx.append("\n");
            }

        } catch (Exception e) {
            log.warn("Could not load Neo4j relationships for owner org {}: {}", ownerOrganizationService.getOwnerCompanyId(), e.getMessage());
        }

        return new OwnerRelationshipResult(formattedRelationships, new ArrayList<>(relatedCompanyIds));
    }

    private List<CompanyProfile> loadRelatedProfiles(List<String> relatedCompanyIds, List<AiSourceReference> sources) {
        if (relatedCompanyIds.isEmpty()) return List.of();
        List<CompanyProfile> profiles = new ArrayList<>();
        for (String companyId : relatedCompanyIds) {
            companyProfileRepository.findByCompanyId(companyId).ifPresent(profiles::add);
        }
        return profiles;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private ScoreSnapshot getLatestScore(String companyId) {
        List<ScoreSnapshot> snapshots = scoreSnapshotRepository.findByCompanyIdOrderByCreatedAtDesc(companyId);
        return snapshots.isEmpty() ? null : snapshots.get(0);
    }

    private List<CompanyProfile> resolveCompaniesFromQuestion(String question, List<CompanyProfile> profiles) {
        String normalizedQ = removeAccents(question.toLowerCase().replaceAll("\\s+", " "));
        List<CompanyProfile> matched = new ArrayList<>();
        for (CompanyProfile p : profiles) {
            if (p.getIdentity() == null) continue;
            String legal = normalize(p.getIdentity().getLegalName());
            String trade = normalize(p.getIdentity().getTradeName());
            if ((StringUtils.hasText(legal) && normalizedQ.contains(legal)) ||
                (StringUtils.hasText(trade) && normalizedQ.contains(trade))) {
                matched.add(p);
            }
        }
        return matched;
    }

    private String normalize(String s) {
        if (!StringUtils.hasText(s)) return "";
        return removeAccents(s.toLowerCase().replaceAll("\\s+", " "));
    }

    private String removeAccents(String str) {
        if (!StringUtils.hasText(str)) return str;
        String normalized = Normalizer.normalize(str, Normalizer.Form.NFD);
        String result = Pattern.compile("\\p{InCombiningDiacriticalMarks}+").matcher(normalized).replaceAll("");
        return result.replace("đ", "d").replace("Đ", "D");
    }

    private String resolveCompanyName(CompanyProfile profile) {
        if (profile.getIdentity() == null) return "Unknown Company";
        String name = profile.getIdentity().getLegalName();
        if (!StringUtils.hasText(name)) name = profile.getIdentity().getTradeName();
        return StringUtils.hasText(name) ? name : "Unknown Company";
    }

    private String formatProfile(CompanyProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(resolveCompanyName(profile)).append(" ---\n");
        if (profile.getIdentity() != null) {
            sb.append("Trade Name: ").append(profile.getIdentity().getTradeName()).append("\n");
        }
        if (profile.getBusiness() != null) {
            sb.append("Industries: ").append(profile.getBusiness().getIndustries()).append("\n");
            sb.append("Business Model: ").append(profile.getBusiness().getBusinessModel()).append("\n");
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

    private String formatScore(String companyName, ScoreSnapshot score) {
        return "--- Score: " + companyName + " ---\n"
                + "Total Score: " + score.getTotalScore() + "\n"
                + "Partner Fit: " + score.getPartnerFitScore() + "\n"
                + "Competition Level: " + score.getCompetitionLevel() + "\n"
                + "Risk Level: " + score.getRiskLevel() + "\n"
                + "Relationship Strength: " + score.getRelationshipStrength() + "\n\n";
    }

    // ── Inner result type ─────────────────────────────────────────────────────

    record OwnerRelationshipResult(List<String> formattedRelationships, List<String> relatedCompanyIds) {}
}

package com.apms.domain.assistant.service;

import com.apms.common.enums.ExternalDataCategory;
import com.apms.domain.assistant.dto.AiNavigationAction;
import com.apms.domain.assistant.dto.AiSourceReference;
import com.apms.domain.assistant.dto.AssistantContext;
import com.apms.domain.assistant.dto.OwnerContextResult;
import com.apms.domain.assistant.dto.OwnerIntent;
import com.apms.domain.dashboard.dto.OwnerInsightDto;
import com.apms.domain.dashboard.dto.RelationshipClosenessDistributionDto;
import com.apms.domain.dashboard.dto.RelationshipClosenessOverviewDto;
import com.apms.domain.dashboard.service.DashboardService;
import com.apms.domain.dashboard.service.OwnerInsightsService;
import com.apms.domain.externaldata.ExternalDataItem;
import com.apms.domain.externaldata.repository.mongo.ExternalDataRepository;
import com.apms.domain.graph.dto.CompanyRelationshipDto;
import com.apms.domain.graph.dto.GraphCompanyDto;
import com.apms.domain.graph.service.GraphService;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.closeness.CompanyRelationshipClosenessRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.service.OwnerOrganizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OwnerAssistantContextService {

    private final CompanyProfileRepository companyProfileRepository;
    private final Neo4jClient neo4jClient;
    private final OwnerOrganizationService ownerOrganizationService;
    private final DashboardService dashboardService;
    private final OwnerInsightsService ownerInsightsService;
    private final GraphService graphService;
    private final CompanyRelationshipClosenessRepository closenessRepository;
    private final ExternalDataRepository externalDataRepository;

    public OwnerContextResult buildContext(String companyProfileId, String question) {
        OwnerIntent intent = detectOwnerIntent(question);
        
        if (intent == OwnerIntent.INTERNAL_NEWS_PROTECTED) {
            return OwnerContextResult.builder()
                    .intent(intent)
                    .deterministic(true)
                    .directAnswer("Internal News is protected data and is not accessible through the AI Assistant.\nPlease view it directly through the authorized Internal News section.\n")
                    .context(AssistantContext.builder().contextText("").sources(List.of()).build())
                    .build();
        }

        CompanyProfile ownerProfile = ownerOrganizationService.resolveApprovedOwnerProfile();
        String ownerBusinessCompanyId = ownerProfile.getCompanyId();
        String ownerMongoId = ownerProfile.getId();

        List<AiSourceReference> sources = new ArrayList<>();
        StringBuilder ctx = new StringBuilder();
        ctx.append("APMS Executive Business Intelligence Data\n");
        ctx.append("Owner Organization: ").append(resolveCompanyName(ownerProfile)).append("\n\n");

        CompanyProfile pageContextTarget = null;
        if (StringUtils.hasText(companyProfileId)) {
            pageContextTarget = companyProfileRepository.findById(companyProfileId).orElse(null);
        }

        CompanyProfile targetProfile = null;
        List<CompanyProfile> compareTargets = new ArrayList<>();
        boolean targetRequired = isCompanyTargetRequired(intent);
        boolean targetOptional = intent == OwnerIntent.RELATIONSHIP_CLOSENESS || intent == OwnerIntent.RISKS || intent == OwnerIntent.OPPORTUNITIES;
        
        if (intent == OwnerIntent.COMPANY_COMPARE) {
            compareTargets = resolveCompareTargets(question, pageContextTarget);
            if (compareTargets.size() < 2) {
                throw new ClarificationRequiredException("Please specify two companies to compare (e.g., 'Compare FPT and CMC').");
            }
            return buildCompareContext(compareTargets, sources, ctx, ownerBusinessCompanyId, ownerMongoId);
        } else if (targetRequired || targetOptional) {
            targetProfile = resolveSingleTarget(question, pageContextTarget, targetRequired);
            if (targetProfile == null && targetRequired) {
                return OwnerContextResult.builder()
                        .intent(intent)
                        .deterministic(true)
                        .directAnswer("I could not find an approved company profile matching the company you asked about. Please check the name or navigate to a company profile.")
                        .context(AssistantContext.builder().contextText("").sources(List.of()).build())
                        .build();
            }
        }

        // Handle deterministic intents
        if (isDeterministic(intent, targetProfile)) {
            return handleDeterministic(intent, ownerBusinessCompanyId, ownerMongoId, targetProfile, sources, ctx);
        }

        // Handle Gemini-assisted intents
        return handleGeminiAssisted(intent, ownerBusinessCompanyId, ownerMongoId, targetProfile, sources, ctx);
    }

    private OwnerContextResult handleDeterministic(OwnerIntent intent, String ownerBusinessCompanyId, String ownerMongoId, CompanyProfile targetProfile, List<AiSourceReference> sources, StringBuilder ctx) {
        String directAnswer = null;
        List<AiNavigationAction> navigationActions = new ArrayList<>();

        if (intent == OwnerIntent.PARTNERS || intent == OwnerIntent.POTENTIAL_PARTNERS || 
            intent == OwnerIntent.COMPETITORS || intent == OwnerIntent.CUSTOMERS || intent == OwnerIntent.SUPPLIERS) {
            
            String relType = switch (intent) {
                case PARTNERS -> "PARTNER_WITH";
                case POTENTIAL_PARTNERS -> "POTENTIAL_PARTNER_OF";
                case COMPETITORS -> "COMPETITOR_OF";
                case CUSTOMERS -> "CUSTOMER_OF";
                case SUPPLIERS -> "SUPPLIER_OF";
                default -> "";
            };
            String title = switch (intent) {
                case PARTNERS -> "Current Partners";
                case POTENTIAL_PARTNERS -> "Potential Partners";
                case COMPETITORS -> "Competitors";
                case CUSTOMERS -> "Customers";
                case SUPPLIERS -> "Suppliers";
                default -> "";
            };
            
            List<String> targetCompanyIds = graphService.getTargetCompanyIdsByRelationship(ownerBusinessCompanyId, relType);
                    
            List<CompanyProfile> targetProfiles = loadRelatedProfiles(targetCompanyIds);
            
            if (targetProfiles.isEmpty()) {
                directAnswer = "There are currently no companies recorded as " + relType.replace("_", " ").toLowerCase() + " in APMS.";
            } else {
                StringBuilder sb = new StringBuilder(title + "\n\n");
                int count = 1;
                for (CompanyProfile profile : targetProfiles) {
                    sb.append(count++).append(". ").append(resolveCompanyName(profile)).append("\n");
                }
                sb.append("\nTotal: ").append(targetProfiles.size());
                directAnswer = sb.toString();
            }
        } else if (intent == OwnerIntent.COMPANY_RELATIONSHIP && targetProfile != null) {
            String targetBusinessId = targetProfile.getCompanyId();
            if (StringUtils.hasText(targetBusinessId) && StringUtils.hasText(ownerBusinessCompanyId)) {
                List<CompanyRelationshipDto> pairs = graphService.getPairRelationships(ownerBusinessCompanyId, targetBusinessId);
                if (pairs.isEmpty()) {
                    directAnswer = "No approved relationship between your company and " + resolveCompanyName(targetProfile) + " is currently recorded in APMS.";
                } else {
                    CompanyRelationshipDto pair = pairs.get(0);
                    String prettyType = pair.getRelationshipType().replace("_", " ").toLowerCase();
                    String capsType = Arrays.stream(prettyType.split(" ")).map(w -> StringUtils.capitalize(w)).collect(Collectors.joining(" "));
                    directAnswer = resolveCompanyName(targetProfile) + " is currently recorded as a " + 
                                   capsType + " of your company in APMS.\n\n" +
                                   "Relationship: " + capsType;
                }
            } else {
                directAnswer = "Could not resolve relationship graph identifiers for the requested companies.";
            }
            navigationActions.add(buildNavigationAction(targetProfile));
        } else if (intent == OwnerIntent.ECOSYSTEM_OVERVIEW) {
            var summary = dashboardService.getSummary();
            directAnswer = "Business Ecosystem Overview\n\n" +
                           "Total Related Companies: " + summary.getTotalRelatedCompanies() + "\n\n" +
                           "Partners: " + summary.getPartnerCount() + "\n" +
                           "Potential Partners: " + summary.getPotentialPartnerCount() + "\n" +
                           "Competitors: " + summary.getCompetitorCount() + "\n" +
                           "Customers: " + summary.getCustomerCount() + "\n" +
                           "Suppliers: " + summary.getSupplierCount() + "\n";
        } else if (intent == OwnerIntent.RELATIONSHIP_CLOSENESS) {
            if (targetProfile != null) {
                var closeness = closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileId(ownerMongoId, targetProfile.getId()).orElse(null);
                if (closeness == null) {
                    directAnswer = "The relationship with " + resolveCompanyName(targetProfile) + " is currently UNRATED.";
                } else {
                    directAnswer = "The relationship with " + resolveCompanyName(targetProfile) + " is rated at " + closeness.getStars() + " stars.";
                }
                navigationActions.add(buildNavigationAction(targetProfile));
            } else {
                List<String> ecosystemCompanyIds = getCanonicalEcosystemCompanyIds(ownerBusinessCompanyId);
                List<CompanyProfile> ecosystemProfiles = loadRelatedProfiles(ecosystemCompanyIds);
                List<String> targetMongoIds = ecosystemProfiles.stream().map(CompanyProfile::getId).toList();
                
                var closenessRecords = closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileIdIn(ownerMongoId, targetMongoIds);
                Map<String, Integer> closenessMap = closenessRecords.stream()
                        .collect(Collectors.toMap(com.apms.domain.profile.closeness.CompanyRelationshipCloseness::getTargetCompanyProfileId, com.apms.domain.profile.closeness.CompanyRelationshipCloseness::getStars));
                
                Map<Integer, List<String>> byStars = new HashMap<>();
                List<String> unrated = new ArrayList<>();
                for (CompanyProfile p : ecosystemProfiles) {
                    Integer stars = closenessMap.get(p.getId());
                    if (stars == null) {
                        unrated.add(resolveCompanyName(p));
                    } else {
                        byStars.computeIfAbsent(stars, k -> new ArrayList<>()).add(resolveCompanyName(p));
                    }
                }
                
                StringBuilder sb = new StringBuilder("Relationships Needing Attention\n\n");
                for (int i = 5; i >= 1; i--) {
                    if (byStars.containsKey(i)) {
                        String label = switch(i) {
                            case 1 -> "Contact Only";
                            case 2 -> "Weak";
                            case 3 -> "Established";
                            case 4 -> "Close";
                            case 5 -> "Strategic";
                            default -> "Unknown";
                        };
                        for (String n : byStars.get(i)) {
                            sb.append(n).append("\nCloseness: ").append(label).append(" (").append(i).append("/5)\n\n");
                        }
                    }
                }
                if (!unrated.isEmpty()) {
                    sb.append("Unrated:\n");
                    unrated.forEach(n -> sb.append("- ").append(n).append("\n"));
                }
                directAnswer = sb.toString();
            }
        } else if (intent == OwnerIntent.COMPANY_PROFILE && targetProfile != null) {
            directAnswer = buildStructuredCompanyProfileText(targetProfile);
            navigationActions.add(buildNavigationAction(targetProfile));
            sources.add(AiSourceReference.builder().type("company_profiles").id(targetProfile.getId()).title(resolveCompanyName(targetProfile)).build());
        } else if (intent == OwnerIntent.COMPANY_PUBLIC_NEWS && targetProfile != null) {
            var news = externalDataRepository.findByCategoryAndRelatedCompanyId(ExternalDataCategory.NEWS, targetProfile.getCompanyId());
            if (news.isEmpty()) {
                directAnswer = "No recent public updates for " + resolveCompanyName(targetProfile) + " are currently stored in APMS.";
            } else {
                StringBuilder sb = new StringBuilder("Recent Public Updates for " + resolveCompanyName(targetProfile) + "\n\n");
                int count = 1;
                for (ExternalDataItem item : news) {
                    sb.append(count++).append(". ").append(item.getTitle()).append("\n");
                    sb.append("Published: ").append(item.getPublishedAt() != null ? item.getPublishedAt().toString() : "Unknown").append("\n");
                    sb.append("Source: ").append(StringUtils.hasText(item.getUrl()) ? item.getUrl() : "Unknown").append("\n\n");
                    if (StringUtils.hasText(item.getSummary())) {
                        sb.append(item.getSummary()).append("\n\n");
                    }
                }
                directAnswer = sb.toString();
                sources.add(AiSourceReference.builder().type("external_data").id(targetProfile.getId()).title("Public News").build());
            }
            navigationActions.add(buildNavigationAction(targetProfile));
        } else if (intent == OwnerIntent.OUT_OF_SCOPE) {
            directAnswer = "I can help with your APMS business ecosystem, company relationships, risks, opportunities, company intelligence, and strategic insights.\nThis question is outside the available Owner AI scope.";
        } else if (intent == OwnerIntent.GREETING) {
            directAnswer = "Hello! I can help you with your business ecosystem, partners, competitors, relationships, risks, opportunities, public company information, and strategic insights.";
        } else if (intent == OwnerIntent.RISKS && targetProfile != null) {
            StringBuilder sb = new StringBuilder("Risks for " + resolveCompanyName(targetProfile) + ":\n\n");
            if (targetProfile.getInsights() != null) {
                if (targetProfile.getInsights().getWeaknesses() != null && !targetProfile.getInsights().getWeaknesses().isEmpty()) {
                    sb.append("Weaknesses:\n");
                    targetProfile.getInsights().getWeaknesses().forEach(w -> sb.append("- ").append(w).append("\n"));
                    sb.append("\n");
                }
                if (targetProfile.getInsights().getThreats() != null && !targetProfile.getInsights().getThreats().isEmpty()) {
                    sb.append("Threats:\n");
                    targetProfile.getInsights().getThreats().forEach(t -> sb.append("- ").append(t).append("\n"));
                    sb.append("\n");
                }
            }
            if (sb.toString().equals("Risks for " + resolveCompanyName(targetProfile) + ":\n\n")) {
                directAnswer = "No specific risks (weaknesses or threats) are currently documented for " + resolveCompanyName(targetProfile) + " in APMS.";
            } else {
                directAnswer = sb.toString().trim();
            }
            navigationActions.add(buildNavigationAction(targetProfile));
        } else if (intent == OwnerIntent.OPPORTUNITIES && targetProfile != null) {
            StringBuilder sb = new StringBuilder("Opportunities with " + resolveCompanyName(targetProfile) + ":\n\n");
            if (targetProfile.getInsights() != null && targetProfile.getInsights().getOpportunities() != null && !targetProfile.getInsights().getOpportunities().isEmpty()) {
                targetProfile.getInsights().getOpportunities().forEach(o -> sb.append("- ").append(o).append("\n"));
                directAnswer = sb.toString().trim();
            } else {
                directAnswer = "No specific opportunities are currently documented for " + resolveCompanyName(targetProfile) + " in APMS.";
            }
            navigationActions.add(buildNavigationAction(targetProfile));
        }

        if (directAnswer != null) {
            return OwnerContextResult.builder()
                    .intent(intent)
                    .deterministic(true)
                    .directAnswer(directAnswer)
                    .navigationActions(navigationActions)
                    .context(AssistantContext.builder().contextText(ctx.toString()).sources(sources).companyProfile(targetProfile).build())
                    .build();
        }
        
        return handleGeminiAssisted(intent, ownerBusinessCompanyId, ownerMongoId, targetProfile, sources, ctx);
    }

    private OwnerContextResult handleGeminiAssisted(OwnerIntent intent, String ownerBusinessCompanyId, String ownerMongoId, CompanyProfile targetProfile, List<AiSourceReference> sources, StringBuilder ctx) {
        List<AiNavigationAction> navActions = new ArrayList<>();
        
        if (targetProfile != null) {
            navActions.add(buildNavigationAction(targetProfile));
            ctx.append(buildStructuredCompanyProfileText(targetProfile)).append("\n");
            sources.add(AiSourceReference.builder().type("company_profiles").id(targetProfile.getId()).title(resolveCompanyName(targetProfile)).build());
        }
        // P1 intents
        if (intent == OwnerIntent.STRATEGIC_RECOMMENDATION) {
            ctx.append("ANALYSIS TYPE: STRATEGIC_RECOMMENDATION\n\n");
            var insights = ownerInsightsService.getInsights(null, null, null, null, org.springframework.data.domain.PageRequest.of(0, 10));
            if (!insights.isEmpty()) {
                ctx.append("OWNER ECOSYSTEM INSIGHTS\n\n");
                int count = 1;
                for (var insight : insights.getContent()) {
                    ctx.append(count++).append(".\n");
                    if (insight.getCompanyName() != null) {
                        ctx.append("Company: ").append(insight.getCompanyName()).append("\n");
                    }

                    ctx.append("Issue: ").append(insight.getTitle()).append("\n");
                    ctx.append("\n");
                }
                sources.add(AiSourceReference.builder().type("owner_insights").id("insights").title("Owner Insights").build());
            } else {
                return OwnerContextResult.builder().intent(intent).deterministic(true)
                        .directAnswer("APMS currently does not contain enough approved actionable insights to form a strategic recommendation.")
                        .navigationActions(navActions)
                        .context(AssistantContext.builder().contextText("").sources(sources).build()).build();
            }
        } else if (intent == OwnerIntent.RELATIONSHIP_ATTENTION) {
            ctx.append("ANALYSIS TYPE: RELATIONSHIP_ATTENTION\n\n");
            var gc = graphService.getCompanyNodeWithRelationships(ownerBusinessCompanyId);
            boolean hasData = false;
            if (gc != null && gc.getRelationships() != null) {
                for (var rel : gc.getRelationships()) {
                    String relType = rel.getRelationshipType();
                    if (List.of("PARTNER_WITH", "POTENTIAL_PARTNER_OF", "COMPETITOR_OF", "CUSTOMER_OF", "SUPPLIER_OF").contains(relType)) {
                        String targetId = rel.getTargetCompanyId();
                        var targetOpt = companyProfileRepository.findByCompanyId(targetId);
                        if (targetOpt.isEmpty()) continue;
                        CompanyProfile tp = targetOpt.get();
                        
                        ctx.append("Company: ").append(resolveCompanyName(tp)).append("\n");
                        ctx.append("Relationship: ").append(relType).append("\n");
                        
                        closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileId(ownerMongoId, tp.getId()).ifPresent(cl -> {
                            Integer stars = cl.getOwnerStars() != null ? cl.getOwnerStars() : cl.getStars();
                            if (stars != null) {
                                ctx.append("Closeness: ").append(stars).append("/5 - ").append(getClosenessLabel(stars)).append("\n");
                            }
                        });
                        
                        var tpInsights = ownerInsightsService.getInsights(null, tp.getId(), null, null, org.springframework.data.domain.PageRequest.of(0, 3));
                        if (!tpInsights.isEmpty()) {
                            ctx.append("Owner Insights:\n");
                            tpInsights.getContent().forEach(i -> ctx.append("- ").append(i.getTitle()).append("\n"));
                        }
                        ctx.append("\n");
                        hasData = true;
                    }
                }
            }
            if (!hasData) {
                return OwnerContextResult.builder().intent(intent).deterministic(true)
                        .directAnswer("APMS currently does not contain enough approved ecosystem relationships to determine which need attention.")
                        .navigationActions(navActions)
                        .context(AssistantContext.builder().contextText("").sources(sources).build()).build();
            }
        } else if (intent == OwnerIntent.PARTNER_PRIORITY) {
            ctx.append("ANALYSIS TYPE: PARTNER_PRIORITY\n\n");
            var gc = graphService.getCompanyNodeWithRelationships(ownerBusinessCompanyId);
            boolean hasData = false;
            if (gc != null && gc.getRelationships() != null) {
                for (var rel : gc.getRelationships()) {
                    if ("PARTNER_WITH".equals(rel.getRelationshipType())) {
                        String targetId = rel.getTargetCompanyId();
                        var targetOpt = companyProfileRepository.findByCompanyId(targetId);
                        if (targetOpt.isEmpty()) continue;
                        CompanyProfile tp = targetOpt.get();
                        
                        ctx.append("PARTNER: ").append(resolveCompanyName(tp)).append("\n");
                        ctx.append("Relationship: Partner\n");
                        
                        closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileId(ownerMongoId, tp.getId()).ifPresent(cl -> {
                            Integer stars = cl.getOwnerStars() != null ? cl.getOwnerStars() : cl.getStars();
                            if (stars != null) {
                                ctx.append("Closeness: ").append(stars).append("/5 - ").append(getClosenessLabel(stars)).append("\n");
                            }
                        });
                        
                        if (tp.getInsights() != null) {
                            if (tp.getInsights().getStrengths() != null && !tp.getInsights().getStrengths().isEmpty()) {
                                ctx.append("Strengths:\n");
                                tp.getInsights().getStrengths().forEach(i -> ctx.append("* ").append(i).append("\n"));
                            }
                            if (tp.getInsights().getWeaknesses() != null && !tp.getInsights().getWeaknesses().isEmpty()) {
                                ctx.append("Weaknesses:\n");
                                tp.getInsights().getWeaknesses().forEach(i -> ctx.append("* ").append(i).append("\n"));
                            }
                            if (tp.getInsights().getOpportunities() != null && !tp.getInsights().getOpportunities().isEmpty()) {
                                ctx.append("Opportunities:\n");
                                tp.getInsights().getOpportunities().forEach(i -> ctx.append("* ").append(i).append("\n"));
                            }
                            if (tp.getInsights().getThreats() != null && !tp.getInsights().getThreats().isEmpty()) {
                                ctx.append("Threats:\n");
                                tp.getInsights().getThreats().forEach(i -> ctx.append("* ").append(i).append("\n"));
                            }
                        }
                        
                        var signals = externalDataRepository.findTop5ByRelatedCompanyIdInOrderByPublishedAtDesc(List.of(targetId));
                        if (!signals.isEmpty()) {
                            ctx.append("Recent Signals:\n");
                            signals.forEach(s -> ctx.append("- ").append(s.getTitle()).append("\n"));
                        }
                        
                        ctx.append("\n");
                        hasData = true;
                    }
                }
            }
            if (!hasData) {
                return OwnerContextResult.builder().intent(intent).deterministic(true)
                        .directAnswer("APMS currently does not contain enough approved partners to recommend a priority.")
                        .navigationActions(navActions)
                        .context(AssistantContext.builder().contextText("").sources(sources).build()).build();
            }
        } else if (intent == OwnerIntent.RELATIONSHIP_STRENGTHEN) {
            if (targetProfile == null) {
                return OwnerContextResult.builder().intent(intent).deterministic(true)
                        .directAnswer("APMS currently does not contain enough approved evidence to determine whether this relationship should be strengthened.")
                        .navigationActions(navActions)
                        .context(AssistantContext.builder().contextText("").sources(sources).build()).build();
            }
            
            ctx.append("ANALYSIS TYPE: RELATIONSHIP_STRENGTHEN\n\n");
            ctx.append("TARGET COMPANY\nName: ").append(resolveCompanyName(targetProfile)).append("\n\n");
            
            String relType = graphService.getCurrentRelationshipType(ownerBusinessCompanyId, targetProfile.getCompanyId());
            if (relType != null) {
                ctx.append("CURRENT RELATIONSHIP\nType: ").append(relType).append("\n\n");
            }
            
            closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileId(ownerMongoId, targetProfile.getId()).ifPresent(cl -> {
                Integer stars = cl.getOwnerStars() != null ? cl.getOwnerStars() : cl.getStars();
                if (stars != null) {
                    ctx.append("RELATIONSHIP CLOSENESS\nStars: ").append(stars).append("/5\n");
                    ctx.append("Level: ").append(getClosenessLabel(stars)).append("\n\n");
                }
            });
            
            if (targetProfile.getInsights() != null) {
                ctx.append("SWOT\n");
                if (targetProfile.getInsights().getStrengths() != null && !targetProfile.getInsights().getStrengths().isEmpty()) {
                    ctx.append("Strengths:\n");
                    targetProfile.getInsights().getStrengths().forEach(i -> ctx.append("* ").append(i).append("\n"));
                }
                if (targetProfile.getInsights().getWeaknesses() != null && !targetProfile.getInsights().getWeaknesses().isEmpty()) {
                    ctx.append("Weaknesses:\n");
                    targetProfile.getInsights().getWeaknesses().forEach(i -> ctx.append("* ").append(i).append("\n"));
                }
                if (targetProfile.getInsights().getOpportunities() != null && !targetProfile.getInsights().getOpportunities().isEmpty()) {
                    ctx.append("Opportunities:\n");
                    targetProfile.getInsights().getOpportunities().forEach(i -> ctx.append("* ").append(i).append("\n"));
                }
                if (targetProfile.getInsights().getThreats() != null && !targetProfile.getInsights().getThreats().isEmpty()) {
                    ctx.append("Threats:\n");
                    targetProfile.getInsights().getThreats().forEach(i -> ctx.append("* ").append(i).append("\n"));
                }
                ctx.append("\n");
            }
            
            var signals = externalDataRepository.findTop5ByRelatedCompanyIdInOrderByPublishedAtDesc(List.of(targetProfile.getCompanyId()));
            if (!signals.isEmpty()) {
                ctx.append("RECENT PUBLIC / EXTERNAL SIGNALS\n");
                signals.forEach(s -> ctx.append("- ").append(s.getTitle()).append(" [").append(s.getCategory()).append("]\n"));
                ctx.append("\n");
            }
        }

        if (intent == OwnerIntent.RISKS) {
            ctx.append("ANALYSIS TYPE: ECOSYSTEM_RISK_ANALYSIS\n\n");
            var gc = graphService.getCompanyNodeWithRelationships(ownerBusinessCompanyId);
            boolean hasData = false;
            if (gc != null && gc.getRelationships() != null) {
                ctx.append("RISK EVIDENCE\n\n");
                for (var rel : gc.getRelationships()) {
                    String relType = rel.getRelationshipType();
                    if (List.of("PARTNER_WITH", "POTENTIAL_PARTNER_OF", "COMPETITOR_OF", "CUSTOMER_OF", "SUPPLIER_OF").contains(relType)) {
                        String targetId = rel.getTargetCompanyId();
                        var targetOpt = companyProfileRepository.findByCompanyId(targetId);
                        if (targetOpt.isEmpty()) continue;
                        CompanyProfile tp = targetOpt.get();
                        
                        boolean compHasData = false;
                        StringBuilder sb = new StringBuilder();
                        sb.append("COMPANY: ").append(resolveCompanyName(tp)).append("\n");
                        sb.append("Relationship: ").append(relType).append("\n");
                        
                        var closenessOpt = closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileId(ownerMongoId, tp.getId());
                        if (closenessOpt.isPresent()) {
                            Integer stars = closenessOpt.get().getOwnerStars() != null ? closenessOpt.get().getOwnerStars() : closenessOpt.get().getStars();
                            if (stars != null) {
                                sb.append("Closeness: ").append(stars).append("/5 - ").append(getClosenessLabel(stars)).append("\n");
                            }
                        } else {
                            sb.append("Closeness: Unrated\n");
                        }
                        
                        if (tp.getInsights() != null) {
                            if (tp.getInsights().getWeaknesses() != null && !tp.getInsights().getWeaknesses().isEmpty()) {
                                sb.append("\nSWOT Weaknesses:\n");
                                tp.getInsights().getWeaknesses().forEach(i -> sb.append("* ").append(i).append("\n"));
                                compHasData = true;
                            }
                            if (tp.getInsights().getThreats() != null && !tp.getInsights().getThreats().isEmpty()) {
                                sb.append("\nSWOT Threats:\n");
                                tp.getInsights().getThreats().forEach(i -> sb.append("* ").append(i).append("\n"));
                                compHasData = true;
                            }
                        }
                        
                        var risks = externalDataRepository.findTop5ByCategoryAndRelatedCompanyIdInOrderByPublishedAtDesc(ExternalDataCategory.RISK, List.of(targetId));
                        if (!risks.isEmpty()) {
                            sb.append("\nExternal Risk Signals:\n");
                            risks.forEach(r -> sb.append("* ").append(r.getTitle()).append("\n"));
                            compHasData = true;
                        }
                        
                        var tpInsights = ownerInsightsService.getInsights(null, tp.getId(), null, null, org.springframework.data.domain.PageRequest.of(0, 3));
                        if (!tpInsights.isEmpty()) {
                            sb.append("\nOwner Insights:\n");
                            tpInsights.getContent().forEach(i -> sb.append("* ").append(i.getTitle()).append("\n"));
                            compHasData = true;
                        }
                        
                        if (compHasData) {
                            ctx.append(sb.toString()).append("\n\n");
                            hasData = true;
                        }
                    }
                }
            }
            if (!hasData) {
                return OwnerContextResult.builder().intent(intent).deterministic(true)
                        .directAnswer("APMS currently does not contain enough approved risk evidence to identify major ecosystem risks.")
                        .navigationActions(navActions)
                        .context(AssistantContext.builder().contextText("").sources(sources).build()).build();
            }
        } else if (intent == OwnerIntent.OPPORTUNITIES) {
            ctx.append("ANALYSIS TYPE: ECOSYSTEM_OPPORTUNITY_ANALYSIS\n\n");
            var gc = graphService.getCompanyNodeWithRelationships(ownerBusinessCompanyId);
            boolean hasData = false;
            if (gc != null && gc.getRelationships() != null) {
                ctx.append("OPPORTUNITY CANDIDATES\n\n");
                for (var rel : gc.getRelationships()) {
                    String relType = rel.getRelationshipType();
                    if (List.of("PARTNER_WITH", "POTENTIAL_PARTNER_OF").contains(relType)) {
                        String targetId = rel.getTargetCompanyId();
                        var targetOpt = companyProfileRepository.findByCompanyId(targetId);
                        if (targetOpt.isEmpty()) continue;
                        CompanyProfile tp = targetOpt.get();
                        
                        boolean compHasData = false;
                        StringBuilder sb = new StringBuilder();
                        sb.append("COMPANY: ").append(resolveCompanyName(tp)).append("\n");
                        sb.append("Relationship: ").append(relType).append("\n");
                        
                        var closenessOpt = closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileId(ownerMongoId, tp.getId());
                        if (closenessOpt.isPresent()) {
                            Integer stars = closenessOpt.get().getOwnerStars() != null ? closenessOpt.get().getOwnerStars() : closenessOpt.get().getStars();
                            if (stars != null) {
                                sb.append("Closeness: ").append(stars).append("/5 - ").append(getClosenessLabel(stars)).append("\n");
                            }
                        } else {
                            sb.append("Closeness: Unrated\n");
                        }
                        
                        if (tp.getInsights() != null) {
                            if (tp.getInsights().getOpportunities() != null && !tp.getInsights().getOpportunities().isEmpty()) {
                                sb.append("\nSWOT Opportunities:\n");
                                tp.getInsights().getOpportunities().forEach(i -> sb.append("* ").append(i).append("\n"));
                                compHasData = true;
                            }
                        }
                        
                        var opps = externalDataRepository.findTop5ByCategoryAndRelatedCompanyIdInOrderByPublishedAtDesc(ExternalDataCategory.OPPORTUNITY, List.of(targetId));
                        if (!opps.isEmpty()) {
                            sb.append("\nExternal Opportunity Signals:\n");
                            opps.forEach(r -> sb.append("* ").append(r.getTitle()).append("\n"));
                            compHasData = true;
                        }
                        
                        var tpInsights = ownerInsightsService.getInsights(null, tp.getId(), null, null, org.springframework.data.domain.PageRequest.of(0, 3));
                        if (!tpInsights.isEmpty()) {
                            sb.append("\nOwner Insights:\n");
                            tpInsights.getContent().forEach(i -> sb.append("* ").append(i.getTitle()).append("\n"));
                            compHasData = true;
                        }
                        
                        if (compHasData) {
                            ctx.append(sb.toString()).append("\n\n");
                            hasData = true;
                        }
                    }
                }
            }
            if (!hasData) {
                return OwnerContextResult.builder().intent(intent).deterministic(true)
                        .directAnswer("APMS currently does not contain enough approved opportunity evidence to recommend strategic opportunities.")
                        .navigationActions(navActions)
                        .context(AssistantContext.builder().contextText("").sources(sources).build()).build();
            }
        } else if (intent == OwnerIntent.RECENT_SIGNALS || intent == OwnerIntent.OWNER_INSIGHTS) {
            List<String> ecosystemCompanyIds = new ArrayList<>(getCanonicalEcosystemCompanyIds(ownerBusinessCompanyId));
            ecosystemCompanyIds.add(ownerBusinessCompanyId);
            if (intent == OwnerIntent.RECENT_SIGNALS) {
                var allRecent = externalDataRepository.findTop5ByRelatedCompanyIdInOrderByPublishedAtDesc(ecosystemCompanyIds);
                if (allRecent.isEmpty()) {
                    return OwnerContextResult.builder().intent(intent).deterministic(true)
                            .directAnswer("No recent external signals are currently stored for your business ecosystem in APMS.")
                            .context(AssistantContext.builder().contextText("").sources(List.of()).build()).build();
                }
                ctx.append("=== RECENT SIGNALS ===\n");
                allRecent.forEach(r -> ctx.append("- ").append(r.getTitle()).append(" [").append(r.getCategory()).append("] (").append(r.getRelatedCompanyName()).append(")\n"));
            }
            if (intent == OwnerIntent.OWNER_INSIGHTS) {
                var insights = ownerInsightsService.getInsights(null, null, null, null, org.springframework.data.domain.PageRequest.of(0, 10));
                if (insights.isEmpty()) {
                    return OwnerContextResult.builder().intent(intent).deterministic(true)
                            .directAnswer("No actionable Owner insights are currently available in APMS.")
                            .context(AssistantContext.builder().contextText("").sources(List.of()).build()).build();
                }
                ctx.append("=== ACTIONABLE INSIGHTS ===\n");
                insights.getContent().forEach(i -> ctx.append("- [").append(i.getType()).append("] ").append(i.getTitle()).append("\n"));
                sources.add(AiSourceReference.builder().type("owner_insights").id("insights").title("Owner Insights").build());
            }
        }

        return OwnerContextResult.builder()
                .intent(intent)
                .deterministic(false)
                .navigationActions(navActions)
                .context(AssistantContext.builder().contextText(ctx.toString()).sources(sources).companyProfile(targetProfile).build())
                .build();
    }
    
    private OwnerContextResult buildCompareContext(List<CompanyProfile> targets, List<AiSourceReference> sources, StringBuilder ctx, String ownerBusinessCompanyId, String ownerMongoId) {
        List<AiNavigationAction> navs = new ArrayList<>();
        ctx.append("=== COMPANY COMPARISON ===\n");
        for (CompanyProfile p : targets) {
            navs.add(buildNavigationAction(p));
            
            ctx.append("--- ").append(resolveCompanyName(p)).append(" ---\n");
            
            String relType = graphService.getCurrentRelationshipType(ownerBusinessCompanyId, p.getCompanyId());
            ctx.append("Relationship:\n").append(relType != null ? relType : "None").append("\n\n");
            
            var closenessOpt = closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileId(ownerMongoId, p.getId());
            if (closenessOpt.isPresent()) {
                Integer stars = closenessOpt.get().getOwnerStars() != null ? closenessOpt.get().getOwnerStars() : closenessOpt.get().getStars();
                if (stars != null) {
                    ctx.append("Closeness:\n").append(stars).append("/5 - ").append(getClosenessLabel(stars)).append("\n\n");
                }
            } else {
                ctx.append("Closeness:\nUnrated\n\n");
            }
            
            if (p.getInsights() != null) {
                if (p.getInsights().getStrengths() != null && !p.getInsights().getStrengths().isEmpty()) {
                    ctx.append("Strengths:\n");
                    p.getInsights().getStrengths().forEach(s -> ctx.append("* ").append(s).append("\n"));
                    ctx.append("\n");
                }
                if (p.getInsights().getWeaknesses() != null && !p.getInsights().getWeaknesses().isEmpty()) {
                    ctx.append("Weaknesses:\n");
                    p.getInsights().getWeaknesses().forEach(s -> ctx.append("* ").append(s).append("\n"));
                    ctx.append("\n");
                }
                if (p.getInsights().getOpportunities() != null && !p.getInsights().getOpportunities().isEmpty()) {
                    ctx.append("Opportunities:\n");
                    p.getInsights().getOpportunities().forEach(s -> ctx.append("* ").append(s).append("\n"));
                    ctx.append("\n");
                }
                if (p.getInsights().getThreats() != null && !p.getInsights().getThreats().isEmpty()) {
                    ctx.append("Threats:\n");
                    p.getInsights().getThreats().forEach(s -> ctx.append("* ").append(s).append("\n"));
                    ctx.append("\n");
                }
            }
            
            var signals = externalDataRepository.findTop5ByRelatedCompanyIdInOrderByPublishedAtDesc(List.of(p.getCompanyId()));
            if (!signals.isEmpty()) {
                ctx.append("Recent Signals:\n");
                signals.forEach(s -> ctx.append("* ").append(s.getTitle()).append(" [").append(s.getCategory()).append("]\n"));
                ctx.append("\n");
            }
            
            var insights = ownerInsightsService.getInsights(null, p.getId(), null, null, org.springframework.data.domain.PageRequest.of(0, 3));
            if (!insights.isEmpty()) {
                ctx.append("Owner Insights:\n");
                insights.getContent().forEach(i -> ctx.append("* ").append(i.getTitle()).append("\n"));
                ctx.append("\n");
            }
            
            sources.add(AiSourceReference.builder().type("company_profiles").id(p.getId()).title(resolveCompanyName(p)).build());
        }
        return OwnerContextResult.builder()
                .intent(OwnerIntent.COMPANY_COMPARE)
                .deterministic(false)
                .navigationActions(navs)
                .context(AssistantContext.builder().contextText(ctx.toString()).sources(sources).build())
                .build();
    }

    private AiNavigationAction buildNavigationAction(CompanyProfile profile) {
        return AiNavigationAction.builder()
                .type("COMPANY_PROFILE")
                .label("View " + resolveCompanyName(profile) + " Profile")
                .companyProfileId(profile.getId())
                .companyId(profile.getCompanyId())
                .companyName(resolveCompanyName(profile))
                .build();
    }

    private boolean isDeterministic(OwnerIntent intent, CompanyProfile targetProfile) {
        if (intent == OwnerIntent.RISKS && targetProfile != null) return true;
        if (intent == OwnerIntent.OPPORTUNITIES && targetProfile != null) return true;
        
        return intent == OwnerIntent.ECOSYSTEM_OVERVIEW ||
               intent == OwnerIntent.PARTNERS ||
               intent == OwnerIntent.POTENTIAL_PARTNERS ||
               intent == OwnerIntent.COMPETITORS ||
               intent == OwnerIntent.CUSTOMERS ||
               intent == OwnerIntent.SUPPLIERS ||
               intent == OwnerIntent.COMPANY_RELATIONSHIP ||
               intent == OwnerIntent.INTERNAL_NEWS_PROTECTED ||
               intent == OwnerIntent.OUT_OF_SCOPE ||
               intent == OwnerIntent.GREETING ||
               intent == OwnerIntent.RELATIONSHIP_CLOSENESS ||
               intent == OwnerIntent.COMPANY_PROFILE ||
               intent == OwnerIntent.COMPANY_PUBLIC_NEWS;
    }

    private OwnerIntent detectOwnerIntent(String question) {
        String lower = question.toLowerCase();
        String trimmed = lower.replaceAll("[^a-z ]", "").trim();
        
        if (trimmed.equals("hi") || trimmed.equals("hello") || trimmed.equals("hey") || trimmed.equals("good morning") || trimmed.equals("thanks") || trimmed.equals("thank you")) {
            return OwnerIntent.GREETING;
        }
        
        if (lower.contains("internal news") || lower.contains("internal information") || lower.contains("confidential")) {
            return OwnerIntent.INTERNAL_NEWS_PROTECTED;
        }
        
        if (lower.contains("weather") || lower.contains("recipe") || lower.contains("sports") || lower.contains("swot is")) {
            return OwnerIntent.OUT_OF_SCOPE;
        }
        
        if (lower.contains("relationship") && (lower.contains("need attention") || lower.contains("review") || lower.contains("weak") || lower.contains("concerning") || lower.contains("attention"))) {
            return OwnerIntent.RELATIONSHIP_ATTENTION;
        }
        if ((lower.contains("strengthen") || lower.contains("get closer") || lower.contains("invest more") || lower.contains("worth developing")) && lower.contains("relationship")) {
            return OwnerIntent.RELATIONSHIP_STRENGTHEN;
        }
        if (lower.contains("partner") && (lower.contains("prioritize") || lower.contains("priority") || lower.contains("focus on"))) {
            return OwnerIntent.PARTNER_PRIORITY;
        }
        if (lower.contains("strateg") || lower.contains("focus on") || lower.contains("priorities") || lower.contains("prioritize")) {
            return OwnerIntent.STRATEGIC_RECOMMENDATION;
        }

        // Factual Intents
        if (lower.contains("how close") || lower.contains("relationship closeness") || lower.contains("closeness") || lower.contains("relationship strength")) {
            return OwnerIntent.RELATIONSHIP_CLOSENESS;
        }
        if (lower.contains("relationship with") || lower.contains("connected to") || lower.contains("relationship do we have")) {
            return OwnerIntent.COMPANY_RELATIONSHIP;
        }
        if (lower.contains("compare") || (lower.contains("difference") && lower.contains("and"))) {
            return OwnerIntent.COMPANY_COMPARE;
        }
        if (lower.contains("potential partner")) {
            return OwnerIntent.POTENTIAL_PARTNERS;
        }
        if (lower.contains("partner")) {
            return OwnerIntent.PARTNERS;
        }
        if (lower.contains("competitor")) {
            return OwnerIntent.COMPETITORS;
        }
        if (lower.contains("customer")) {
            return OwnerIntent.CUSTOMERS;
        }
        if (lower.contains("supplier")) {
            return OwnerIntent.SUPPLIERS;
        }
        if (lower.contains("weak") || lower.contains("close") || lower.contains("unrated") || lower.contains("rated") || lower.contains("closeness")) {
            return OwnerIntent.RELATIONSHIP_CLOSENESS;
        }
        if (lower.contains("risk")) {
            return OwnerIntent.RISKS;
        }
        if (lower.contains("opportunit")) {
            return OwnerIntent.OPPORTUNITIES;
        }
        if (lower.contains("signal") || lower.contains("recent external")) {
            return OwnerIntent.RECENT_SIGNALS;
        }
        if (lower.contains("insight") || lower.contains("recommendation") || lower.contains("review") || lower.contains("focus on")) {
            return OwnerIntent.OWNER_INSIGHTS;
        }
        if (lower.contains("ecosystem")) {
            return OwnerIntent.ECOSYSTEM_OVERVIEW;
        }
        if (lower.contains("public news") || lower.contains("public update")) {
            return OwnerIntent.COMPANY_PUBLIC_NEWS;
        }
        if (lower.contains("strateg") || lower.contains("attention to")) {
            return OwnerIntent.STRATEGIC_RECOMMENDATION;
        }
        
        return OwnerIntent.COMPANY_PROFILE;
    }

    private List<String> getCanonicalEcosystemCompanyIds(String ownerCompanyId) {
        return graphService.getEcosystemCompanyIds(ownerCompanyId);
    }

    private List<CompanyProfile> loadRelatedProfiles(List<String> relatedCompanyIds) {
        if (relatedCompanyIds.isEmpty()) return List.of();
        List<CompanyProfile> profiles = new ArrayList<>();
        for (String companyId : relatedCompanyIds) {
            companyProfileRepository.findByCompanyId(companyId).ifPresent(profiles::add);
        }
        return profiles;
    }

    private boolean isCompanyTargetRequired(OwnerIntent intent) {
        return intent == OwnerIntent.COMPANY_PROFILE || 
               intent == OwnerIntent.COMPANY_PUBLIC_NEWS || 
               intent == OwnerIntent.COMPANY_RELATIONSHIP || 
               intent == OwnerIntent.RELATIONSHIP_STRENGTHEN ||
               intent == OwnerIntent.COMPANY_COMPARE;
    }

    private CompanyProfile resolveSingleTarget(String question, CompanyProfile pageContextTarget, boolean targetRequired) {
        String lowerQ = question.toLowerCase();
        boolean isContextual = lowerQ.contains("this company") || lowerQ.contains("this organization") || 
                               lowerQ.contains("the current company") || lowerQ.contains("this business");
                               
        String extracted = extractCompanyKeyword(question);
        
        if (StringUtils.hasText(extracted) && !isContextual) {
            CompanyProfile explicitMatch = rankAndSelectMatch(extracted);
            if (explicitMatch != null) return explicitMatch;
            
            if (targetRequired) {
                throw new ClarificationRequiredException("I could not find an approved company profile matching \"" + extracted + "\" in APMS.");
            } else {
                return null;
            }
        }
        
        if (isContextual) {
            if (pageContextTarget != null) return pageContextTarget;
            if (targetRequired) {
                throw new ClarificationRequiredException("Please specify the company name or open a company profile first.");
            } else {
                return null;
            }
        }
        
        if (pageContextTarget != null && (!StringUtils.hasText(extracted) || extracted.equals("about"))) {
            return pageContextTarget;
        }
        
        return null;
    }

    private String extractCompanyKeyword(String question) {
        String lower = question.toLowerCase();
        String keyword = lower.replace("find", "")
                              .replace("search for", "")
                              .replace("what do we know about", "")
                              .replace("company profile", "")
                              .replace("compare", "")
                              .replace("what relationship do we have with", "")
                              .replace("relationship do we have with", "")
                              .replace("should we strengthen our relationship with", "")
                              .replace("what is our relationship with", "")
                              .replace("how close is our relationship with", "")
                              .replace("what public news do we have about", "")
                              .replace("show recent public updates about", "")
                              .replace("show recent public updates", "")
                              .replace("what risks should i know about", "")
                              .replace("what opportunities do we have with", "")
                              .replace("tell me about", "")
                              .replace("public news", "")
                              .replace("about", "")
                              .replace("with", "")
                              .replace("?", "")
                              .replace(".", "")
                              .trim();
        if (keyword.startsWith("what ")) {
            keyword = keyword.substring(5).trim();
        }
        return keyword;
    }

    private CompanyProfile rankAndSelectMatch(String keyword) {
        if (!StringUtils.hasText(keyword) || keyword.length() < 2) return null;
        
        // Use page request to get potential matches
        org.springframework.data.domain.Page<CompanyProfile> page = companyProfileRepository.searchByName(keyword, org.springframework.data.domain.PageRequest.of(0, 10));
        if (page == null) return null;
        List<CompanyProfile> candidates = page.stream().filter(p -> "APPROVED".equals(p.getReviewStatus())).toList();
        
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);
        
        // Ranked matching
        CompanyProfile exactInsensitiveTradeMatch = null;
        CompanyProfile exactInsensitiveLegalMatch = null;
        CompanyProfile uniquePartialTradeMatch = null;
        CompanyProfile uniquePartialLegalMatch = null;
        
        int partialTradeCount = 0;
        int partialLegalCount = 0;
        
        String lowerKeyword = keyword.toLowerCase();
        
        for (CompanyProfile p : candidates) {
            if (p.getIdentity() == null) continue;
            String trade = p.getIdentity().getTradeName();
            String legal = p.getIdentity().getLegalName();
            
            if (StringUtils.hasText(trade) && trade.toLowerCase().equals(lowerKeyword)) {
                exactInsensitiveTradeMatch = p;
            }
            if (StringUtils.hasText(legal) && legal.toLowerCase().equals(lowerKeyword)) {
                exactInsensitiveLegalMatch = p;
            }
            if (StringUtils.hasText(trade) && trade.toLowerCase().contains(lowerKeyword)) {
                uniquePartialTradeMatch = p;
                partialTradeCount++;
            }
            if (StringUtils.hasText(legal) && legal.toLowerCase().contains(lowerKeyword)) {
                uniquePartialLegalMatch = p;
                partialLegalCount++;
            }
        }
        
        if (exactInsensitiveTradeMatch != null) return exactInsensitiveTradeMatch;
        if (exactInsensitiveLegalMatch != null) return exactInsensitiveLegalMatch;
        if (partialTradeCount == 1) return uniquePartialTradeMatch;
        if (partialLegalCount == 1) return uniquePartialLegalMatch;
        
        // If multiple partials remain, ambiguity clarification required.
        throw new ClarificationRequiredException("I found multiple approved company profiles matching \"" + keyword + "\". Please specify the company you mean.");
    }

    private List<CompanyProfile> resolveCompareTargets(String question, CompanyProfile pageContext) {
        String lower = question.toLowerCase();
        String cleaned = lower.replace("compare", "")
                              .replace("which is a better strategic partner,", "")
                              .replace("should we prioritize", "")
                              .replace("which should we focus on,", "")
                              .replace("for partnership", "")
                              .replace("?", "")
                              .replace(".", "").trim();
                              
        String separator = null;
        if (cleaned.contains(" and ")) separator = " and ";
        else if (cleaned.contains(" vs. ")) separator = " vs. ";
        else if (cleaned.contains(" vs ")) separator = " vs ";
        else if (cleaned.contains(" or ")) separator = " or ";
        else if (cleaned.contains(" with ")) separator = " with ";
        
        if (separator != null) {
            String[] parts = cleaned.split(separator);
            if (parts.length == 2) {
                CompanyProfile p1 = rankAndSelectMatch(parts[0].trim());
                CompanyProfile p2 = rankAndSelectMatch(parts[1].trim());
                List<CompanyProfile> res = new ArrayList<>();
                if (p1 != null) res.add(p1);
                if (p2 != null) res.add(p2);
                return res;
            }
        }
        return List.of();
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

    private String buildStructuredCompanyProfileText(CompanyProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append(resolveCompanyName(profile)).append("\n\n");
        if (profile.getIdentity() != null) {
            sb.append("Legal Name:\n").append(profile.getIdentity().getLegalName()).append("\n\n");
        }
        if (profile.getBusiness() != null && profile.getBusiness().getIndustries() != null && !profile.getBusiness().getIndustries().isEmpty()) {
            sb.append("Industries:\n");
            for (String ind : profile.getBusiness().getIndustries()) {
                sb.append("- ").append(ind).append("\n");
            }
            sb.append("\n");
        }
        if (profile.getBusiness() != null && StringUtils.hasText(profile.getBusiness().getBusinessModel())) {
            sb.append("Business Model:\n").append(profile.getBusiness().getBusinessModel()).append("\n\n");
        }
        if (profile.getInsights() != null) {
            if (profile.getInsights().getStrengths() != null && !profile.getInsights().getStrengths().isEmpty()) {
                sb.append("Strengths:\n");
                for (String s : profile.getInsights().getStrengths()) {
                    sb.append("- ").append(s).append("\n");
                }
                sb.append("\n");
            }
            if (profile.getInsights().getWeaknesses() != null && !profile.getInsights().getWeaknesses().isEmpty()) {
                sb.append("Weaknesses:\n");
                for (String w : profile.getInsights().getWeaknesses()) {
                    sb.append("- ").append(w).append("\n");
                }
                sb.append("\n");
            }
        }
        return sb.toString().trim();
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
        if (profile.getFinancial() != null) {
            if (profile.getFinancial().getRevenue() != null) {
                sb.append("Financial Revenue: ").append(profile.getFinancial().getRevenue()).append(" ").append(profile.getFinancial().getRevenueCurrency()).append("\n");
            }
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

    private String getClosenessLabel(Integer stars) {
        if (stars == null) return "Unrated";
        return switch(stars) {
            case 1 -> "Contact Only";
            case 2 -> "Weak";
            case 3 -> "Developing";
            case 4 -> "Close";
            case 5 -> "Strategic";
            default -> "Unknown";
        };
    }
}

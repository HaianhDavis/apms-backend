package com.apms.domain.profile.service;

import com.apms.common.enums.ProjectType;
import com.apms.common.event.CandidateApprovedEvent;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.dto.FinancialReportRequest;
import com.apms.domain.profile.dto.ProfileResponse;
import com.apms.domain.profile.dto.ProfileSourcesResponse;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.dto.UpdateCompanyProfileRequest;
import com.apms.domain.profile.dto.UpdateOwnerCompanyProfileRequest;
import com.apms.domain.project.Project;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.common.enums.AuditAction;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.security.UserDetailsImpl;
import com.apms.domain.crawler.repository.TrackedCompanyRepository;
import com.apms.domain.crawler.service.TrackedCompanyCache;
import com.apms.domain.crawler.domain.TrackedCompany;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final CompanyProfileRepository profileRepository;
    private final CompanyCandidateRepository candidateRepository;
    private final ProjectRepository projectRepository;
    private final MongoTemplate mongoTemplate;
    private final Neo4jClient neo4jClient;
    private final AuditLogService auditLogService;
    private final OwnerOrganizationService ownerOrganizationService;
    private final TrackedCompanyRepository trackedCompanyRepository;
    private final TrackedCompanyCache trackedCompanyCache;
    private final com.apms.domain.project.service.ProjectTargetProfileResolver projectTargetProfileResolver;
    private final com.apms.domain.user.repository.sql.AccountRepository accountRepository;
    private final CompanyProfileVersionService versionService;
    private final com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository profileVersionRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    // ─────────────────────────────────────────────
    // EVENT LISTENER
    // ─────────────────────────────────────────────

    /**
     * Listens to CandidateApprovedEvent to create or update the official CompanyProfile.
     * Note: In a fully distributed environment, this could be an async listener or message queue consumer.
     */
    @EventListener
    @Order(1)
    @Transactional // Ensures it participates in the overarching transaction if one exists
    public void handleCandidateApprovedEvent(CandidateApprovedEvent event) {
        log.info("Received CandidateApprovedEvent for candidateId: {}", event.getCandidateId());

        CompanyCandidate candidate = candidateRepository.findById(event.getCandidateId())
                .orElseThrow(() -> new ResourceNotFoundException("Candidate not found: " + event.getCandidateId()));

        Project project = projectRepository.findById(Long.valueOf(event.getProjectId()))
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + event.getProjectId()));

        CompanyProfile approvedProfile = project.getProjectType() == ProjectType.UPDATE_EXISTING_COMPANY
                ? updateExistingProfile(project, candidate)
                : createNewProfile(project, candidate);
        projectTargetProfileResolver.backfillPartnerContractTasks(project, approvedProfile.getCompanyId());

        // Add to TrackedCompany if not exists
        if (candidate.getIdentity() != null) {
            String legalName = candidate.getIdentity().getLegalName();
            String tradeName = candidate.getIdentity().getTradeName();

            String primaryName = null;
            if (StringUtils.hasText(legalName)) {
                primaryName = legalName.trim();
            } else if (StringUtils.hasText(tradeName)) {
                primaryName = tradeName.trim();
            }

            if (StringUtils.hasText(primaryName)) {
                if (!trackedCompanyRepository.existsByCompanyNameIgnoreCase(primaryName)) {
                    java.util.List<String> aliases = new java.util.ArrayList<>();
                    if (StringUtils.hasText(tradeName) && !tradeName.trim().equalsIgnoreCase(primaryName)) {
                        aliases.add(tradeName.trim());
                    }

                    TrackedCompany newTracked = TrackedCompany.builder()
                            .companyName(primaryName)
                            .aliases(aliases)
                            .isActive(true)
                            .build();
                    trackedCompanyRepository.save(newTracked);
                    trackedCompanyCache.forceRefresh();
                    log.info("Added new TrackedCompany from Candidate: {} with aliases {}", primaryName, aliases);
                }
            }
        }

        log.info("Candidate {} source documents remain as research/evidence only; they are not published to Company Profile documents.", candidate.getId());
    }

    private CompanyProfile createNewProfile(Project project, CompanyCandidate candidate) {
        java.util.Optional<CompanyProfile> existingProfile = profileRepository.findByCandidateId(candidate.getId());
        if (existingProfile.isPresent()) {
            CompanyProfile profile = existingProfile.get();
            log.info("Reusing existing CompanyProfile {} for candidate {}", profile.getCompanyId(), candidate.getId());
            addSourceRefs(profile, project, candidate);
            profile = profileRepository.save(profile);
            linkProjectToProfile(project, profile);
            linkCandidateToProfile(candidate, profile);
            return profile;
        }

        // Check if Project already owns a linked profile shell
        CompanyProfile profile = null;
        if (StringUtils.hasText(project.getTargetCompanyProfileId())) {
            profile = profileRepository.findByCompanyId(project.getTargetCompanyProfileId())
                    .or(() -> profileRepository.findById(project.getTargetCompanyProfileId()))
                    .filter(p -> !Boolean.TRUE.equals(p.getIsDeleted()))
                    .orElse(null);
        }
        if (profile == null && StringUtils.hasText(project.getTargetCompanyTaxCode())) {
            String normTax = project.getTargetCompanyTaxCode().replaceAll("[\\s\\-]", "").trim();
            profile = profileRepository.findByIdentityTaxCode(normTax)
                    .filter(p -> !Boolean.TRUE.equals(p.getIsDeleted()))
                    .orElse(null);
        }
        if (profile == null && project.getId() != null) {
            java.util.List<CompanyProfile> byProj = profileRepository.findByProjectId(String.valueOf(project.getId()));
            if (!byProj.isEmpty()) {
                profile = byProj.stream().filter(p -> !Boolean.TRUE.equals(p.getIsDeleted())).findFirst().orElse(null);
            }
        }

        String companyId = profile != null ? profile.getCompanyId() : UUID.randomUUID().toString();
        log.info("Applying approved candidate {} to CompanyProfile companyId: {}", candidate.getId(), companyId);

        CompanyProfile.Identity identity = mapIdentity(candidate.getIdentity());
        if (identity == null) {
            identity = (profile != null && profile.getIdentity() != null) ? profile.getIdentity() : CompanyProfile.Identity.builder().build();
        }
        
        // Authoritative project target identity wins for legalName and taxCode
        if (StringUtils.hasText(project.getTargetCompanyTaxCode())) {
            identity.setTaxCode(project.getTargetCompanyTaxCode().replaceAll("[\\s\\-]", "").trim());
            log.info("Populated authoritative taxCode from Project target identity: {}", identity.getTaxCode());
        } else if (profile != null && profile.getIdentity() != null && StringUtils.hasText(profile.getIdentity().getTaxCode())) {
            identity.setTaxCode(profile.getIdentity().getTaxCode());
        }
        if (StringUtils.hasText(project.getTargetCompanyName())) {
            identity.setLegalName(project.getTargetCompanyName());
            log.info("Populated authoritative legalName from Project target identity: {}", project.getTargetCompanyName());
        } else if (profile != null && profile.getIdentity() != null && StringUtils.hasText(profile.getIdentity().getLegalName())) {
            identity.setLegalName(profile.getIdentity().getLegalName());
        }

        Long canonicalManagerId = findCanonicalManager(project);

        if (profile == null) {
            profile = CompanyProfile.builder()
                    .companyId(companyId)
                    .majorVersion(1)
                    .revision(0)
                    .version(CompanyProfileVersionHelper.formatLegacyVersion(1, 0))
                    .metadata(CompanyProfile.Metadata.builder()
                            .createdBy("SYSTEM")
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build())
                    .build();
        } else {
            if (profile.getMetadata() == null) {
                profile.setMetadata(CompanyProfile.Metadata.builder().createdAt(LocalDateTime.now()).build());
            }
            profile.getMetadata().setUpdatedAt(LocalDateTime.now());
            profile.getMetadata().setLastModifiedBy("SYSTEM");
        }

        profile.setIdentity(identity);
        profile.setBusiness(mapBusiness(candidate.getBusiness()));
        profile.setCompanySize(mapCompanySize(candidate.getCompanySize()));
        profile.setContact(mapContact(candidate.getContact()));
        profile.setInsights(mapInsights(candidate.getInsights()));
        profile.setFinancial(candidate.getFinancial());
        profile.setMarket(candidate.getMarket());
        profile.setInnovation(candidate.getInnovation());
        profile.setRisk(candidate.getRisk());
        profile.setCompliance(candidate.getCompliance());
        profile.setReviewStatus("APPROVED");
        profile.setIsHidden(true);
        if (canonicalManagerId != null) {
            profile.setResponsibleManagerId(canonicalManagerId);
        }

        addSourceRefs(profile, project, candidate);

        profile = profileRepository.save(profile);
        linkProjectToProfile(project, profile);
        linkCandidateToProfile(candidate, profile);

        boolean hasExistingVersion = profileVersionRepository.existsByCompanyProfileId(profile.getId());
        if (!hasExistingVersion) {
            versionService.createAndSaveVersion(
                    profile,
                    com.apms.domain.profile.enums.CompanyProfileChangeSource.INITIAL_PROFILE_CREATION,
                    null,
                    null,
                    null,
                    "Initial approved official profile",
                    "Initial profile creation (" + profile.getVersionLabel() + ")",
                    null,
                    project.getId(),
                    null,
                    candidate.getSourceDocumentIds(),
                    canonicalManagerId
            );
        }

        log.info("Successfully updated CompanyProfile for companyId: {}, version: {}", companyId, profile.getVersionLabel());
        return profile;
    }

    private CompanyProfile updateExistingProfile(Project project, CompanyCandidate candidate) {
        String targetProfileId = project.getTargetCompanyProfileId();
        if (!StringUtils.hasText(targetProfileId)) {
            log.error("UPDATE_EXISTING_COMPANY project missing targetCompanyProfileId");
            throw new ResourceNotFoundException("UPDATE_EXISTING_COMPANY project missing targetCompanyProfileId");
        }

        CompanyProfile profile = profileRepository.findById(targetProfileId)
                .or(() -> profileRepository.findByCompanyId(targetProfileId))
                .orElseThrow(() -> new ResourceNotFoundException("Target CompanyProfile not found: " + targetProfileId));

        log.info("Updating EXISTING CompanyProfile with companyId: {}", profile.getCompanyId());

        // Enrich/Update data - simple overwrite for now
        // In a real system, you might merge based on confidence scores or manual review flags
        CompanyProfile.Identity newIdentity = candidate.getIdentity() != null ? mapIdentity(candidate.getIdentity()) : null;
        if (newIdentity == null && profile.getIdentity() == null) {
            newIdentity = CompanyProfile.Identity.builder().build();
        }
        
        if (newIdentity != null) {
            // Project target identity is authoritative; fallback to existing profile identity
            if (StringUtils.hasText(project.getTargetCompanyTaxCode())) {
                newIdentity.setTaxCode(project.getTargetCompanyTaxCode());
            } else if (StringUtils.hasText(profile.getIdentity() != null ? profile.getIdentity().getTaxCode() : null)) {
                newIdentity.setTaxCode(profile.getIdentity().getTaxCode());
            }

            if (StringUtils.hasText(project.getTargetCompanyName())) {
                newIdentity.setLegalName(project.getTargetCompanyName());
            } else if (StringUtils.hasText(profile.getIdentity() != null ? profile.getIdentity().getLegalName() : null)) {
                newIdentity.setLegalName(profile.getIdentity().getLegalName());
            }
            profile.setIdentity(newIdentity);
        }
        
        if (profile.getIdentity() == null) {
            profile.setIdentity(CompanyProfile.Identity.builder().build());
        }
        
        // Safe propagation from Project
        if (!StringUtils.hasText(profile.getIdentity().getTaxCode()) && StringUtils.hasText(project.getTargetCompanyTaxCode())) {
            profile.getIdentity().setTaxCode(project.getTargetCompanyTaxCode());
            log.info("Populated missing profile taxCode from Project target identity");
        }
        if (!StringUtils.hasText(profile.getIdentity().getLegalName()) && StringUtils.hasText(project.getTargetCompanyName())) {
            profile.getIdentity().setLegalName(project.getTargetCompanyName());
        }
        if (candidate.getBusiness() != null) profile.setBusiness(mapBusiness(candidate.getBusiness()));
        if (candidate.getCompanySize() != null) profile.setCompanySize(mapCompanySize(candidate.getCompanySize()));
        if (candidate.getContact() != null) profile.setContact(mapContact(candidate.getContact()));
        if (candidate.getInsights() != null) profile.setInsights(mapInsights(candidate.getInsights()));
        if (candidate.getFinancial() != null) profile.setFinancial(candidate.getFinancial());
        if (candidate.getMarket() != null) profile.setMarket(candidate.getMarket());
        if (candidate.getInnovation() != null) profile.setInnovation(candidate.getInnovation());
        if (candidate.getRisk() != null) profile.setRisk(candidate.getRisk());
        if (candidate.getCompliance() != null) profile.setCompliance(candidate.getCompliance());

        boolean wasApproved = "APPROVED".equals(profile.getReviewStatus());
        profile.setReviewStatus("APPROVED");
        if (!wasApproved) {
            profile.setIsHidden(true);
        }

        boolean alreadyApplied = profileVersionRepository.existsByCompanyProfileIdAndCreatedFromProjectId(profile.getId(), project.getId());
        if (!alreadyApplied) {
            profile.incrementMajorVersion();
        }

        profile.getMetadata().setUpdatedAt(LocalDateTime.now());
        profile.getMetadata().setLastModifiedBy("SYSTEM");

        Long canonicalManagerId = profile.getResponsibleManagerId();
        if (canonicalManagerId == null) {
            canonicalManagerId = findCanonicalManager(project);
            if (canonicalManagerId != null) {
                profile.setResponsibleManagerId(canonicalManagerId);
            }
        }

        addSourceRefs(profile, project, candidate);

        profile = profileRepository.save(profile);
        linkCandidateToProfile(candidate, profile);

        if (!alreadyApplied) {
            versionService.createAndSaveVersion(
                    profile,
                    com.apms.domain.profile.enums.CompanyProfileChangeSource.PROJECT_PROFILE_UPDATE,
                    null,
                    null,
                    null,
                    "Official profile generation updated from project " + project.getProjectName(),
                    "Applied UPDATE_EXISTING_COMPANY project " + project.getId() + " (" + profile.getVersionLabel() + ")",
                    null,
                    project.getId(),
                    null,
                    candidate.getSourceDocumentIds(),
                    canonicalManagerId
            );
        }

        log.info("Successfully updated CompanyProfile for companyId: {}, new version: {}", profile.getCompanyId(), profile.getVersionLabel());
        return profile;
    }

    private void addSourceRefs(CompanyProfile profile, Project project, CompanyCandidate candidate) {
        if (profile.getSourceRefs() == null) {
            profile.setSourceRefs(new CompanyProfile.SourceRefs());
        }
        profile.getSourceRefs().getProjectIds().add(String.valueOf(project.getId()));
        profile.getSourceRefs().getCandidateIds().add(candidate.getId());

        if (StringUtils.hasText(candidate.getImportJobId())) {
            profile.getSourceRefs().getImportJobIds().add(candidate.getImportJobId());
        }
        profile.getSourceRefs().getRawDocumentIds().addAll(resolveSourceDocumentIds(candidate));
    }

    private void linkProjectToProfile(Project project, CompanyProfile profile) {
        if (!StringUtils.hasText(project.getTargetCompanyProfileId())) {
            project.setTargetCompanyProfileId(profile.getCompanyId());
            projectRepository.save(project);
            log.info("Linked project {} to CompanyProfile companyId {}", project.getId(), profile.getCompanyId());
        }
    }

    private void linkCandidateToProfile(CompanyCandidate candidate, CompanyProfile profile) {
        CompanyCandidate.Lifecycle lifecycle = candidate.getLifecycle();
        if (lifecycle == null) {
            lifecycle = new CompanyCandidate.Lifecycle();
            candidate.setLifecycle(lifecycle);
        }
        lifecycle.setStatus(candidate.getStatus());
        lifecycle.setConvertedCompanyProfileId(profile.getCompanyId());
        candidateRepository.save(candidate);
    }

    private Long findCanonicalManager(Project project) {
        if (project.getMembers() == null) return null;
        java.util.List<com.apms.domain.project.ProjectMember> activeManagers = project.getMembers().stream()
                .filter(m -> m.getProjectRole() == com.apms.common.enums.ProjectRole.LEADER 
                        && Boolean.TRUE.equals(m.getAccount().getIsActive()))
                .toList();
        
        if (activeManagers.size() == 1) {
            return activeManagers.get(0).getAccountId();
        } else if (activeManagers.isEmpty()) {
            com.apms.domain.user.Account creator = project.getCreatedByAccount();
            if (creator != null && Boolean.TRUE.equals(creator.getIsActive()) && 
                creator.getRoles().contains(com.apms.common.enums.SystemRole.BUSINESS_DEVELOPMENT_MANAGER)) {
                return creator.getId();
            }
        }
        return null;
    }

    private java.util.List<String> resolveSourceDocumentIds(CompanyCandidate candidate) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        if (candidate.getSourceDocumentIds() != null) {
            candidate.getSourceDocumentIds().stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .forEach(ids::add);
        }
        if (StringUtils.hasText(candidate.getRawDocumentId())) {
            ids.add(candidate.getRawDocumentId().trim());
        }
        return new java.util.ArrayList<>(ids);
    }

    // ─────────────────────────────────────────────
    // READ OPERATIONS
    // ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<ProfileResponse> getAllProfiles(Pageable pageable) {
        return profileRepository.findAll(newestFirst(pageable)).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ProfileResponse getProfileByCompanyId(String companyId) {
        CompanyProfile profile = profileRepository.findByCompanyId(companyId)
                .or(() -> profileRepository.findById(companyId))
                .orElseThrow(() -> new ResourceNotFoundException("CompanyProfile not found for companyId: " + companyId));

        if (Boolean.TRUE.equals(profile.getIsDeleted())) {
            throw new ResourceNotFoundException("CompanyProfile not found for companyId: " + companyId);
        }

        return toResponse(profile);
    }

    @Transactional(readOnly = true)
    public ProfileResponse getApprovedProfileResponse(String companyProfileId) {
        CompanyProfile profile = profileRepository.findById(companyProfileId)
                .orElseThrow(() -> new ResourceNotFoundException("CompanyProfile not found for ID: " + companyProfileId));

        if (Boolean.TRUE.equals(profile.getIsDeleted())) {
            throw new ResourceNotFoundException("CompanyProfile not found for ID: " + companyProfileId);
        }

        if (!"APPROVED".equals(profile.getReviewStatus())) {
            throw new com.apms.common.exception.BusinessValidationException("CompanyProfile must be approved.");
        }
        
        if (Boolean.TRUE.equals(profile.getIsHidden())) {
            throw new com.apms.common.exception.BusinessValidationException("CompanyProfile is not published.");
        }

        return toResponse(profile);
    }

    @Transactional(readOnly = true)
    public Page<ProfileResponse> searchCompanyProfiles(String keyword, String industry, String market, String reviewStatus, String relationshipType, boolean excludeOwner, Pageable pageable) {
        return searchCompanyProfiles(keyword, industry, market, reviewStatus, relationshipType, excludeOwner, null, null, null, pageable);
    }

    @Transactional(readOnly = true)
    public Page<ProfileResponse> searchBusinessFacingProfiles(String keyword, String industry, String market, String relationshipType, boolean excludeOwner, Pageable pageable) {
        return searchCompanyProfiles(keyword, industry, market, "APPROVED", relationshipType, excludeOwner, null, com.apms.common.enums.ProfileVisibility.PUBLISHED, null, pageable);
    }

    /**
     * Get unique industries across all profiles that are not deleted.
     */
    @Transactional(readOnly = true)
    public java.util.List<String> getDistinctIndustries() {
        return mongoTemplate.findDistinct("business.industries", CompanyProfile.class, String.class);
    }

    /**
     * Searches CompanyProfiles, optionally restricted to a set of accessible companyIds.
     *
     * @param allowedCompanyIds the only companyIds the caller may see, or {@code null} for unrestricted access.
     */
    @Transactional(readOnly = true)
    public Page<ProfileResponse> searchCompanyProfiles(String keyword, String industry, String market, String reviewStatus, String relationshipType, boolean excludeOwner, Set<String> allowedCompanyIds, com.apms.common.enums.ProfileVisibility visibility, Long managerId, Pageable pageable) {
        Pageable effectivePageable = newestFirst(pageable);
        Criteria criteria = Criteria.where("isDeleted").ne(true);

        if (visibility != null) {
            if (visibility == com.apms.common.enums.ProfileVisibility.HIDDEN) {
                criteria.and("isHidden").is(true);
            } else {
                criteria.and("isHidden").ne(true);
            }
        }

        if (StringUtils.hasText(keyword)) {
            criteria.orOperator(
                    Criteria.where("identity.legalName").regex(keyword, "i"),
                    Criteria.where("identity.tradeName").regex(keyword, "i")
            );
        }
        if (StringUtils.hasText(industry)) {
            criteria.and("business.industries").is(industry);
        }
        if (StringUtils.hasText(market)) {
            criteria.and("business.markets").is(market);
        }
        if (StringUtils.hasText(reviewStatus)) {
            criteria.and("reviewStatus").is(reviewStatus);
        }

        if (managerId != null) {
            criteria.orOperator(
                    Criteria.where("metadata.createdBy").is(managerId.toString()),
                    Criteria.where("responsibleManagerId").is(managerId)
            );
        }

        String ownerCompanyId = ownerOrganizationService.getOwnerCompanyId();

        List<String> neo4jCompanyIds = null;
        if (StringUtils.hasText(relationshipType)) {
            // 1. Validate relationshipType
            java.util.List<String> validTypes = java.util.List.of("PARTNER_WITH", "COMPETITOR_OF", "POTENTIAL_PARTNER_OF", "SUPPLIER_OF", "CUSTOMER_OF");
            if (!validTypes.contains(relationshipType)) {
                return Page.empty(pageable);
            }

            // 2. Query Neo4j for relationships with the owner company
            String cypher = String.format("MATCH (:Company {companyId: '%s'})-[:%s]->(c:Company) RETURN DISTINCT c.companyId AS companyId", ownerCompanyId, relationshipType);
            neo4jCompanyIds = new java.util.ArrayList<>(neo4jClient.query(cypher)
                    .fetchAs(String.class)
                    .mappedBy((typeSystem, record) -> record.get("companyId").asString())
                    .all());

            if (neo4jCompanyIds.isEmpty()) {
                return Page.empty(pageable);
            }
        }


        if (allowedCompanyIds != null) {
            // Scoped caller (Staff): intersect the allowed set with any other companyId filter.
            Set<String> companyIdFilter = new LinkedHashSet<>(allowedCompanyIds);
            if (excludeOwner) {
                companyIdFilter.remove(ownerCompanyId);
            }
            if (neo4jCompanyIds != null) {
                companyIdFilter.retainAll(neo4jCompanyIds);
            }
            if (companyIdFilter.isEmpty()) {
                return Page.empty(pageable);
            }
            criteria.and("companyId").in(companyIdFilter);
        } else if (excludeOwner) {
            if (neo4jCompanyIds != null) {
                Set<String> filter = new LinkedHashSet<>(neo4jCompanyIds);
                filter.remove(ownerCompanyId);
                if (filter.isEmpty()) {
                    return Page.empty(pageable);
                }
                criteria.and("companyId").in(filter);
            } else {
                criteria.and("companyId").ne(ownerCompanyId);
            }
        } else if (neo4jCompanyIds != null) {
            criteria.and("companyId").in(neo4jCompanyIds);
        }

        Query query = new Query(criteria);
        long total = mongoTemplate.count(query, CompanyProfile.class);
        query.with(effectivePageable);
        java.util.List<CompanyProfile> profiles = mongoTemplate.find(query, CompanyProfile.class);

        return new PageImpl<>(profiles, effectivePageable, total).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<ProfileResponse> searchProfilesByName(String name, boolean excludeOwner, Pageable pageable) {
        return searchProfilesByName(name, excludeOwner, null, null, null, pageable);
    }

    @Transactional(readOnly = true)
    public Page<ProfileResponse> searchBusinessFacingProfilesByName(String name, boolean excludeOwner, Pageable pageable) {
        return searchProfilesByName(name, excludeOwner, null, null, com.apms.common.enums.ProfileVisibility.PUBLISHED, pageable);
    }

    /**
     * Searches CompanyProfiles by name, optionally restricted to a set of accessible companyIds.
     *
     * @param allowedCompanyIds the only companyIds the caller may see, or {@code null} for unrestricted access.
     */
    @Transactional(readOnly = true)
    public Page<ProfileResponse> searchProfilesByName(String name, boolean excludeOwner, Set<String> allowedCompanyIds, String reviewStatus, com.apms.common.enums.ProfileVisibility visibility, Pageable pageable) {
        Pageable effectivePageable = newestFirst(pageable);
        Criteria criteria = Criteria.where("isDeleted").ne(true);

        if (StringUtils.hasText(reviewStatus)) {
            criteria.and("reviewStatus").is(reviewStatus);
        }

        if (visibility != null) {
            if (visibility == com.apms.common.enums.ProfileVisibility.HIDDEN) {
                criteria.and("isHidden").is(true);
            } else {
                criteria.and("isHidden").ne(true);
            }
        }

        if (StringUtils.hasText(name)) {
            criteria.orOperator(
                    Criteria.where("identity.legalName").regex(name, "i"),
                    Criteria.where("identity.tradeName").regex(name, "i")
            );
        }

        if (allowedCompanyIds != null) {
            Set<String> companyIdFilter = new LinkedHashSet<>(allowedCompanyIds);
            if (excludeOwner) {
                companyIdFilter.remove(ownerOrganizationService.getOwnerCompanyId());
            }
            if (companyIdFilter.isEmpty()) {
                return Page.empty(pageable);
            }
            criteria.and("companyId").in(companyIdFilter);
        } else if (excludeOwner) {
            criteria.and("companyId").ne(ownerOrganizationService.getOwnerCompanyId());
        }
        Query query = new Query(criteria).with(effectivePageable);
        java.util.List<CompanyProfile> profiles = mongoTemplate.find(query, CompanyProfile.class);
        long total = mongoTemplate.count(new Query(criteria), CompanyProfile.class);
        return new PageImpl<>(profiles, effectivePageable, total).map(this::toResponse);
    }

    private Pageable newestFirst(Pageable pageable) {
        Sort defaultSort = Sort.by(
                Sort.Order.desc("metadata.updatedAt"),
                Sort.Order.desc("metadata.createdAt"),
                Sort.Order.desc("_id")
        );
        Sort sort = pageable.getSort().isSorted() ? pageable.getSort().and(defaultSort) : defaultSort;
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    @Transactional(readOnly = true)
    public ProfileSourcesResponse getProfileSources(String companyId) {
        CompanyProfile profile = profileRepository.findByCompanyId(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("CompanyProfile not found for companyId: " + companyId));

        if (Boolean.TRUE.equals(profile.getIsDeleted())) {
            throw new ResourceNotFoundException("CompanyProfile not found for companyId: " + companyId);
        }

        return ProfileSourcesResponse.builder()
                .companyId(profile.getCompanyId())
                .projectIds(profile.getSourceRefs().getProjectIds())
                .importJobIds(profile.getSourceRefs().getImportJobIds())
                .rawDocumentIds(profile.getSourceRefs().getRawDocumentIds())
                .candidateIds(profile.getSourceRefs().getCandidateIds())
                .build();
    }

    // ─────────────────────────────────────────────
    // WRITE OPERATIONS
    // ─────────────────────────────────────────────

    @Transactional
    public ProfileResponse updateProfile(String companyId, UpdateCompanyProfileRequest request) {
        UserDetailsImpl currentUser = null;
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl) {
                currentUser = (UserDetailsImpl) auth.getPrincipal();
            }
        } catch (Exception ignored) {}
        return updateProfile(companyId, request, currentUser);
    }

    @Transactional
    public ProfileResponse updateProfile(String companyId, UpdateCompanyProfileRequest request, UserDetailsImpl currentUser) {
        CompanyProfile profile = profileRepository.findByCompanyId(companyId)
                .or(() -> profileRepository.findById(companyId))
                .orElseThrow(() -> new ResourceNotFoundException("CompanyProfile not found: " + companyId));

        if (Boolean.TRUE.equals(profile.getIsDeleted())) {
            throw new ResourceNotFoundException("CompanyProfile not found: " + companyId);
        }

        if (currentUser != null) {
            boolean isAdmin = currentUser.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
            boolean isManager = currentUser.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_BUSINESS_DEVELOPMENT_MANAGER"));
            if (!isAdmin && !isManager) {
                throw new org.springframework.security.access.AccessDeniedException("You are not responsible for this company profile.");
            }
            if (isManager && !isAdmin) {
                if (profile.getResponsibleManagerId() == null || !profile.getResponsibleManagerId().equals(currentUser.getId())) {
                    throw new org.springframework.security.access.AccessDeniedException("You are not responsible for this company profile.");
                }
            }
        } else {
            throw new org.springframework.security.access.AccessDeniedException("You are not responsible for this company profile.");
        }

        if (!"APPROVED".equals(profile.getReviewStatus())) {
            throw new com.apms.common.exception.BusinessValidationException("Only APPROVED company profiles can be edited.");
        }

        // Optimistic concurrency / Stale write check
        CompanyProfileVersionHelper.VersionState currentVersion = CompanyProfileVersionHelper.resolveVersion(profile);
        if (request.getExpectedMajorVersion() == null || request.getExpectedRevision() == null
                || request.getExpectedMajorVersion() != currentVersion.majorVersion()
                || request.getExpectedRevision() != currentVersion.revision()) {
            throw new com.apms.common.exception.BusinessConflictException("The company profile has changed since you opened it. Refresh before saving.");
        }

        java.util.Map<String, Object> beforeSnapshot = versionService.createSnapshotMap(profile);
        CompanyProfile originalProfile = null;
        if (objectMapper != null && beforeSnapshot != null) {
            try {
                originalProfile = objectMapper.convertValue(beforeSnapshot, CompanyProfile.class);
            } catch (Exception e) {
                log.warn("Failed to create pre-edit backup of CompanyProfile {}: {}", profile.getId(), e.getMessage());
            }
        }

        // Apply Identity
        if (profile.getIdentity() == null) profile.setIdentity(new CompanyProfile.Identity());
        if (request.getLegalName() != null) profile.getIdentity().setLegalName(request.getLegalName());
        if (request.getTradeName() != null) profile.getIdentity().setTradeName(request.getTradeName());
        if (request.getTaxCode() != null) profile.getIdentity().setTaxCode(request.getTaxCode());
        if (request.getRegistrationNumber() != null) profile.getIdentity().setRegistrationNumber(request.getRegistrationNumber());

        // Apply Contact
        if (profile.getContact() == null) profile.setContact(new CompanyProfile.Contact());
        if (request.getWebsite() != null) profile.getContact().setWebsite(request.getWebsite());
        if (request.getEmails() != null) profile.getContact().setEmails(request.getEmails());
        if (request.getPhones() != null) profile.getContact().setPhones(request.getPhones());
        if (request.getHeadOfficeAddress() != null) {
            CompanyProfile.Address addr = CompanyProfile.Address.builder()
                    .type("HEADQUARTERS")
                    .fullAddress(request.getHeadOfficeAddress())
                    .build();
            profile.getContact().setAddresses(java.util.List.of(addr));
        }

        // Apply Company Size
        if (profile.getCompanySize() == null) profile.setCompanySize(new CompanyProfile.CompanySize());
        if (request.getEmployeeTier() != null) profile.getCompanySize().setEmployeeTier(request.getEmployeeTier());
        if (request.getEmployeeCount() != null) profile.getCompanySize().setEmployeeCount(request.getEmployeeCount());
        if (request.getRevenueTier() != null) profile.getCompanySize().setRevenueTier(request.getRevenueTier());

        // Apply Business
        if (profile.getBusiness() == null) profile.setBusiness(new CompanyProfile.Business());
        if (request.getIndustries() != null) profile.getBusiness().setIndustries(request.getIndustries());
        if (request.getMarkets() != null) profile.getBusiness().setMarkets(request.getMarkets());
        if (request.getTargetCustomers() != null) profile.getBusiness().setTargetCustomers(request.getTargetCustomers());
        if (request.getProductsServices() != null) {
            java.util.List<CompanyProfile.Product> prods = request.getProductsServices().stream()
                    .map(name -> CompanyProfile.Product.builder().name(name).build())
                    .collect(java.util.stream.Collectors.toList());
            profile.getBusiness().setProducts(prods);
        }
        if (request.getBusinessModel() != null) profile.getBusiness().setBusinessModel(request.getBusinessModel());

        // Apply Leadership
        if (request.getCompanyMembers() != null) {
            profile.setCompanyMembers(request.getCompanyMembers());
        }

        // Apply Tags
        if (request.getTags() != null) profile.setTags(request.getTags());

        java.util.Map<String, Object> afterSnapshot = versionService.createSnapshotMap(profile);

        // Detect actual changes
        java.util.List<String> changedFieldPaths = new java.util.ArrayList<>();
        java.util.Map<String, Object> beforeValues = new java.util.HashMap<>();
        java.util.Map<String, Object> afterValues = new java.util.HashMap<>();

        String[] potentialPaths = new String[]{
                "identity.legalName", "identity.tradeName", "identity.taxCode", "identity.registrationNumber",
                "contact.website", "contact.emails", "contact.phones", "contact.addresses",
                "companySize.employeeTier", "companySize.employeeCount", "companySize.revenueTier",
                "business.industries", "business.markets", "business.targetCustomers", "business.products",
                "business.businessModel",
                "companyMembers", "tags"
        };

        for (String path : potentialPaths) {
            Object bVal = extractValueByPath(beforeSnapshot, path);
            Object aVal = extractValueByPath(afterSnapshot, path);
            if (!java.util.Objects.equals(bVal, aVal)) {
                changedFieldPaths.add(path);
                beforeValues.put(path, bVal);
                afterValues.put(path, aVal);
            }
        }

        if (changedFieldPaths.isEmpty()) {
            return toResponse(profile);
        }

        profile.incrementMinorVersion();
        if (profile.getMetadata() == null) {
            profile.setMetadata(new CompanyProfile.Metadata());
        }
        profile.getMetadata().setUpdatedAt(LocalDateTime.now());
        profile.getMetadata().setLastModifiedBy(currentUser != null ? String.valueOf(currentUser.getId()) : "SYSTEM");

        profileRepository.save(profile);

        try {
            versionService.createAndSaveVersion(
                    profile,
                    com.apms.domain.profile.enums.CompanyProfileChangeSource.MANAGER_MANUAL_EDIT,
                    changedFieldPaths,
                    beforeValues,
                    afterValues,
                    request.getChangeNote(),
                    "Manager Manual Edit (" + profile.getVersionLabel() + ")",
                    null,
                    null,
                    null,
                    null,
                    currentUser != null ? currentUser.getId() : null
            );
        } catch (Exception ex) {
            log.error("Failed to persist CompanyProfileVersion for profile {}: {}", profile.getId(), ex.getMessage(), ex);
            if (originalProfile != null) {
                try {
                    profileRepository.save(originalProfile);
                    log.info("Successfully rolled back profile {} to pre-edit state", profile.getId());
                } catch (Exception rollbackEx) {
                    log.error("CRITICAL: Failed to rollback profile {} after version creation failure: {}", profile.getId(), rollbackEx.getMessage(), rollbackEx);
                }
            }
            throw ex;
        }

        if (currentUser != null) {
            try {
                auditLogService.log(
                        currentUser.getId(),
                        AuditAction.COMPANY_PROFILE_UPDATED,
                        "CompanyProfile",
                        companyId,
                        "Profile updated by Manager (" + profile.getVersionLabel() + ", " + changedFieldPaths.size() + " fields changed)"
                );
            } catch (Exception e) {
                log.warn("Failed to write audit log for manager profile update on company {}: {}", companyId, e.getMessage());
            }
        }

        return toResponse(profile);
    }

    private Object extractValueByPath(java.util.Map<String, Object> map, String path) {
        if (map == null || path == null) return null;
        String[] parts = path.split("\\.");
        Object current = map;
        for (String part : parts) {
            if (current instanceof java.util.Map) {
                current = ((java.util.Map<?, ?>) current).get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    public ProfileResponse updateVisibility(String companyId, com.apms.domain.profile.dto.UpdateProfileVisibilityRequest request, Long actorId) {
        CompanyProfile profile = profileRepository.findByCompanyId(companyId)
                .or(() -> profileRepository.findById(companyId))
                .orElseThrow(() -> new ResourceNotFoundException("Company profile not found"));

        if (Boolean.TRUE.equals(profile.getIsDeleted())) {
            throw new ResourceNotFoundException("Company profile not found");
        }

        if (actorId != null) {
            boolean isAdmin = false;
            try {
                org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl user) {
                    isAdmin = user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
                }
            } catch (Exception ignored) {}

            boolean isResponsibleManager = profile.getResponsibleManagerId() != null && profile.getResponsibleManagerId().equals(actorId);

            if (!isAdmin && !isResponsibleManager) {
                throw new org.springframework.security.access.AccessDeniedException("You are not authorized to manage visibility for this company profile.");
            }
        }

        boolean newIsHidden = request.getVisibility() == com.apms.common.enums.ProfileVisibility.HIDDEN;

        if (!newIsHidden) {
            validateMinimumPublishability(profile);
        }

        if (Boolean.valueOf(newIsHidden).equals(profile.getIsHidden())) {
            return toResponse(profile);
        }

        profile.setIsHidden(newIsHidden);
        profile = profileRepository.save(profile);

        AuditAction action = newIsHidden ? AuditAction.COMPANY_PROFILE_HIDDEN : AuditAction.COMPANY_PROFILE_PUBLISHED;
        auditLogService.log(actorId, action, "CompanyProfile", companyId, "Profile visibility updated to " + request.getVisibility());

        return toResponse(profile);
    }

    public void validateMinimumPublishability(CompanyProfile profile) {
        if (Boolean.TRUE.equals(profile.getIsDeleted())) {
            throw new com.apms.common.exception.BusinessValidationException("Deleted profiles cannot be published.");
        }
        if (profile.getIdentity() == null || !StringUtils.hasText(profile.getIdentity().getLegalName())) {
            throw new com.apms.common.exception.BusinessValidationException("Profile requires a non-blank Legal Name before it can be published.");
        }
        if (profile.getIdentity() == null || !StringUtils.hasText(profile.getIdentity().getTaxCode())) {
            throw new com.apms.common.exception.BusinessValidationException("Profile requires a non-blank Tax Code before it can be published.");
        }

        String relType = resolveRelationshipType(profile.getCompanyId());
        String ownerCompanyId = ownerOrganizationService.getOwnerCompanyId();
        boolean isOwner = ownerCompanyId != null && profile.getCompanyId() != null && profile.getCompanyId().equals(ownerCompanyId);
        if (!isOwner && !isSupportedRelationship(relType)) {
            throw new com.apms.common.exception.BusinessValidationException("Profile requires a valid supported Relationship before it can be published.");
        }
    }

    public boolean isPublishable(CompanyProfile p, String relType, boolean canManageVisibility) {
        if (p == null || Boolean.TRUE.equals(p.getIsDeleted())) return false;
        if (!canManageVisibility) return false;
        if (p.getIdentity() == null) return false;
        if (!StringUtils.hasText(p.getIdentity().getLegalName())) return false;
        if (!StringUtils.hasText(p.getIdentity().getTaxCode())) return false;

        String ownerCompanyId = ownerOrganizationService.getOwnerCompanyId();
        boolean isOwner = ownerCompanyId != null && p.getCompanyId() != null && p.getCompanyId().equals(ownerCompanyId);
        if (!isOwner && !isSupportedRelationship(relType)) {
            return false;
        }
        return true;
    }

    public static boolean isSupportedRelationship(String rel) {
        if (!StringUtils.hasText(rel)) return false;
        String normalized = rel.trim().toUpperCase();
        for (com.apms.common.enums.RelationshipType type : com.apms.common.enums.RelationshipType.values()) {
            if (type.name().equals(normalized)) return true;
        }
        return "PARTNER".equals(normalized) || "COMPETITOR".equals(normalized)
                || "SUPPLIER".equals(normalized) || "CUSTOMER".equals(normalized)
                || "POTENTIAL_PARTNER".equals(normalized);
    }

    /**
     * SYSTEM_ADMIN updates the Owner Organization's profile.
     * The Owner Company is resolved by the backend ({@link OwnerOrganizationService}),
     * never taken from the request body. Only whitelisted fields are applied.
     */
    @Transactional
    public ProfileResponse updateOwnerCompanyProfile(UpdateOwnerCompanyProfileRequest request) {
        CompanyProfile profile = ownerOrganizationService.getRequiredOwnerCompanyProfile();

        if (profile.getIdentity() == null) profile.setIdentity(new CompanyProfile.Identity());
        if (StringUtils.hasText(request.getLegalName())) profile.getIdentity().setLegalName(request.getLegalName());
        if (StringUtils.hasText(request.getTradeName())) profile.getIdentity().setTradeName(request.getTradeName());
        if (StringUtils.hasText(request.getTaxCode())) profile.getIdentity().setTaxCode(request.getTaxCode());
        if (StringUtils.hasText(request.getRegistrationNumber())) profile.getIdentity().setRegistrationNumber(request.getRegistrationNumber());
        if (StringUtils.hasText(request.getStockTicker())) profile.getIdentity().setStockTicker(request.getStockTicker());
        if (StringUtils.hasText(request.getStockExchange())) profile.getIdentity().setStockExchange(request.getStockExchange());

        if (profile.getBusiness() == null) profile.setBusiness(new CompanyProfile.Business());
        if (request.getIndustries() != null) profile.getBusiness().setIndustries(request.getIndustries());
        if (StringUtils.hasText(request.getBusinessModel())) profile.getBusiness().setBusinessModel(request.getBusinessModel());
        if (request.getMarkets() != null) profile.getBusiness().setMarkets(request.getMarkets());
        if (request.getTargetCustomers() != null) profile.getBusiness().setTargetCustomers(request.getTargetCustomers());
        if (request.getProducts() != null) {
            profile.getBusiness().setProducts(request.getProducts().stream()
                    .map(product -> CompanyProfile.Product.builder()
                            .name(product.getName())
                            .category(product.getCategory())
                            .description(product.getDescription())
                            .build())
                    .toList());
        }

        if (request.getInsights() != null) {
            UpdateOwnerCompanyProfileRequest.SwotRequest swot = request.getInsights();
            if (profile.getInsights() == null) profile.setInsights(new CompanyProfile.Insights());
            if (swot.getStrengths() != null) profile.getInsights().setStrengths(swot.getStrengths());
            if (swot.getWeaknesses() != null) profile.getInsights().setWeaknesses(swot.getWeaknesses());
            if (swot.getOpportunities() != null) profile.getInsights().setOpportunities(swot.getOpportunities());
            if (swot.getThreats() != null) profile.getInsights().setThreats(swot.getThreats());
        }

        if (request.getCompanyMembers() != null) {
            profile.setCompanyMembers(request.getCompanyMembers().stream()
                    .map(member -> CompanyProfile.CompanyMember.builder()
                            .fullName(member.getFullName())
                            .position(member.getPosition())
                            .imageUrl(member.getImageUrl())
                            .sourceUrl(member.getSourceUrl())
                            .notes(member.getNotes())
                            .build())
                    .toList());
        }

        if (profile.getCompanySize() == null) profile.setCompanySize(new CompanyProfile.CompanySize());
        if (StringUtils.hasText(request.getEmployeeTier())) profile.getCompanySize().setEmployeeTier(request.getEmployeeTier());
        if (request.getEmployeeCount() != null) profile.getCompanySize().setEmployeeCount(request.getEmployeeCount());
        if (StringUtils.hasText(request.getRevenueTier())) profile.getCompanySize().setRevenueTier(request.getRevenueTier());

        if (profile.getContact() == null) profile.setContact(new CompanyProfile.Contact());
        if (StringUtils.hasText(request.getWebsite())) profile.getContact().setWebsite(request.getWebsite());

        List<String> emails = new java.util.ArrayList<>();
        if (StringUtils.hasText(request.getEmail())) emails.add(request.getEmail().trim());
        profile.getContact().setEmails(emails);

        List<String> phones = new java.util.ArrayList<>();
        if (StringUtils.hasText(request.getPhone())) phones.add(request.getPhone().trim());
        profile.getContact().setPhones(phones);

        List<CompanyProfile.Address> addresses = new java.util.ArrayList<>();
        if (StringUtils.hasText(request.getAddress())) {
            addresses.add(CompanyProfile.Address.builder()
                    .type("HEADQUARTERS")
                    .fullAddress(request.getAddress().trim())
                    .build());
        }
        profile.getContact().setAddresses(addresses);

        if (request.getTags() != null) profile.setTags(request.getTags());

        profile.incrementMinorVersion();

        if (profile.getMetadata() == null) {
            profile.setMetadata(CompanyProfile.Metadata.builder()
                    .createdBy("SYSTEM")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build());
        } else {
            profile.getMetadata().setUpdatedAt(LocalDateTime.now());
        }

        Long currentUserId = getCurrentUserId();
        profile.getMetadata().setLastModifiedBy(currentUserId != null ? String.valueOf(currentUserId) : "SYSTEM");

        profileRepository.save(profile);

        if (currentUserId != null) {
            auditLogService.log(currentUserId, AuditAction.COMPANY_PROFILE_UPDATED, "CompanyProfile",
                    profile.getCompanyId(), "Owner company profile updated by SYSTEM_ADMIN");
        }

        return toResponse(profile);
    }

    /**
     * SYSTEM_ADMIN creates or updates one financial statement (reportType + reportYear)
     * of the Owner Organization. The record is identified by (reportType, reportYear);
     * saving the same pair replaces the previous value and never touches other years.
     */
    @Transactional
    public ProfileResponse upsertOwnerFinancialReport(FinancialReportRequest request) {
        CompanyProfile profile = ownerOrganizationService.getRequiredOwnerCompanyProfile();
        validateFinancialReport(request);

        List<CompanyProfile.FinancialReport> reports = new ArrayList<>(
                profile.getFinancialReports() != null ? profile.getFinancialReports() : List.of());
        reports.removeIf(existing -> existing.getReportType() != null
                && existing.getReportType().equalsIgnoreCase(request.getReportType())
                && Objects.equals(existing.getReportYear(), request.getReportYear()));
        reports.add(toFinancialReport(request));

        profile.setFinancialReports(reports);
        return saveOwnerProfileWithAudit(profile);
    }

    /**
     * SYSTEM_ADMIN deletes one financial statement (reportType + reportYear) of the
     * Owner Organization. Other years and report types are preserved.
     */
    @Transactional
    public ProfileResponse deleteOwnerFinancialReport(String reportType, int reportYear) {
        CompanyProfile profile = ownerOrganizationService.getRequiredOwnerCompanyProfile();
        List<CompanyProfile.FinancialReport> reports = new ArrayList<>(
                profile.getFinancialReports() != null ? profile.getFinancialReports() : List.of());
        boolean removed = reports.removeIf(existing -> existing.getReportType() != null
                && existing.getReportType().equalsIgnoreCase(reportType)
                && Objects.equals(existing.getReportYear(), reportYear));
        if (!removed) {
            throw new BusinessValidationException("Không tìm thấy báo cáo tài chính " + reportType + " năm " + reportYear);
        }

        profile.setFinancialReports(reports);
        return saveOwnerProfileWithAudit(profile);
    }

    private CompanyProfile.FinancialReport toFinancialReport(FinancialReportRequest request) {
        return CompanyProfile.FinancialReport.builder()
                .reportType(request.getReportType().trim().toUpperCase())
                .periodType(StringUtils.hasText(request.getPeriodType()) ? request.getPeriodType() : "YEAR")
                .reportYear(request.getReportYear())
                .reportPeriod(StringUtils.hasText(request.getReportPeriod())
                        ? request.getReportPeriod()
                        : String.valueOf(request.getReportYear()))
                .itemsJson(request.getItemsJson())
                .sourceUrl(request.getSourceUrl())
                .build();
    }

    /**
     * Validates a financial report payload. Beyond basic presence checks, the
     * itemsJson must be a parseable single-period FinancialDocument whose values
     * are numeric, so the frontend table can always render it.
     */
    private void validateFinancialReport(FinancialReportRequest request) {
        String reportType = request.getReportType() == null ? "" : request.getReportType().trim().toUpperCase();
        Set<String> allowed = Set.of("SUMMARY", "BALANCE_SHEET", "INCOME_STATEMENT", "CASH_FLOW", "RATIOS", "PLAN", "CHISO");
        if (!allowed.contains(reportType)) {
            throw new BusinessValidationException("Loại báo cáo tài chính không hợp lệ: " + request.getReportType());
        }
        if (request.getReportYear() == null) {
            throw new BusinessValidationException("Năm tài chính không được để trống");
        }
        if (request.getReportYear() < 1900 || request.getReportYear() > 2100) {
            throw new BusinessValidationException("Năm tài chính không hợp lệ: " + request.getReportYear());
        }
        if (!StringUtils.hasText(request.getItemsJson())) {
            throw new BusinessValidationException("Dữ liệu báo cáo tài chính không được để trống");
        }
        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode root = mapper.readTree(request.getItemsJson());
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new BusinessValidationException("Dữ liệu báo cáo tài chính không hợp lệ: thiếu bảng dữ liệu");
            }
            JsonNode table = data.get(0).path("data");
            if (!table.isArray() || table.isEmpty()) {
                throw new BusinessValidationException("Dữ liệu báo cáo tài chính không hợp lệ: thiếu kỳ báo cáo");
            }
            boolean hasPeriod = false;
            for (JsonNode period : table) {
                if (period.path("time").isValueNode()) {
                    hasPeriod = true;
                }
                JsonNode values = period.path("data");
                if (values.isArray()) {
                    for (JsonNode item : values) {
                        JsonNode value = item.get("value");
                        if (value != null && !value.isNull() && !value.isNumber()) {
                            throw new BusinessValidationException(
                                    "Dữ liệu báo cáo tài chính không hợp lệ: giá trị phải là số");
                        }
                    }
                }
            }
            if (!hasPeriod) {
                throw new BusinessValidationException("Dữ liệu báo cáo tài chính không hợp lệ: thiếu kỳ báo cáo");
            }
        } catch (BusinessValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessValidationException("Dữ liệu báo cáo tài chính không hợp lệ: " + e.getMessage());
        }
    }

    private ProfileResponse saveOwnerProfileWithAudit(CompanyProfile profile) {
        profile.incrementMinorVersion();

        if (profile.getMetadata() == null) {
            profile.setMetadata(CompanyProfile.Metadata.builder()
                    .createdBy("SYSTEM")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build());
        } else {
            profile.getMetadata().setUpdatedAt(LocalDateTime.now());
        }

        Long currentUserId = getCurrentUserId();
        profile.getMetadata().setLastModifiedBy(currentUserId != null ? String.valueOf(currentUserId) : "SYSTEM");

        profileRepository.save(profile);

        if (currentUserId != null) {
            auditLogService.log(currentUserId, AuditAction.COMPANY_PROFILE_UPDATED, "CompanyProfile",
                    profile.getCompanyId(), "Owner company financial report updated by SYSTEM_ADMIN");
        }

        return toResponse(profile);
    }

    @Transactional
    public void deleteProfile(String companyId) {
        CompanyProfile profile = profileRepository.findByCompanyId(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("CompanyProfile not found"));

        if (!Boolean.TRUE.equals(profile.getIsDeleted())) {
            profile.setIsDeleted(true);
            profile.getMetadata().setDeletedAt(LocalDateTime.now());
            profileRepository.save(profile);

            Long currentUserId = getCurrentUserId();
            if (currentUserId != null) {
                auditLogService.log(currentUserId, AuditAction.COMPANY_PROFILE_ARCHIVED, "CompanyProfile", companyId, "Profile archived/soft-deleted");
            }
        }
    }

    private Long getCurrentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl) {
                return ((UserDetailsImpl) auth.getPrincipal()).getId();
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ─────────────────────────────────────────────
    // MAPPERS
    // ─────────────────────────────────────────────

    private ProfileResponse toResponse(CompanyProfile p) {
        com.apms.common.enums.ProfileVisibility visibility = Boolean.TRUE.equals(p.getIsHidden())
                ? com.apms.common.enums.ProfileVisibility.HIDDEN
                : com.apms.common.enums.ProfileVisibility.PUBLISHED;

        CompanyProfileVersionHelper.VersionState versionState = CompanyProfileVersionHelper.resolveVersion(p);
        int major = versionState.majorVersion();
        int rev = versionState.revision();
        String versionLabel = versionState.versionLabel();
        String legacyVersion = p.getVersion() != null ? p.getVersion() : versionState.legacyVersion();

        boolean canEdit = false;
        boolean canManageVisibility = false;
        try {
            org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl user) {
                boolean isAdmin = user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
                boolean isManager = user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_BUSINESS_DEVELOPMENT_MANAGER"));
                boolean isResponsibleManager = isManager && p.getResponsibleManagerId() != null && p.getResponsibleManagerId().equals(user.getId());

                if (isAdmin || isResponsibleManager) {
                    canManageVisibility = true;
                    if ("APPROVED".equals(p.getReviewStatus())) {
                        canEdit = true;
                    }
                }
            }
        } catch (Exception ignored) {}

        String relType = resolveRelationshipType(p.getCompanyId());
        boolean canPublish = isPublishable(p, relType, canManageVisibility);

        return ProfileResponse.builder()
                .id(p.getId())
                .companyId(p.getCompanyId())
                .identity(p.getIdentity())
                .business(p.getBusiness())
                .companySize(p.getCompanySize())
                .contact(p.getContact())
                .insights(p.getInsights())
                .financial(p.getFinancial())
                .market(p.getMarket())
                .innovation(p.getInnovation())
                .risk(p.getRisk())
                .compliance(p.getCompliance())
                .companyMembers(p.getCompanyMembers())
                .reviewStatus(p.getReviewStatus())
                .visibility(visibility)
                .isHidden(p.getIsHidden())
                .relationshipType(relType)
                .tags(p.getTags())
                .metadata(p.getMetadata())
                .version(legacyVersion)
                .majorVersion(major)
                .revision(rev)
                .versionLabel(versionLabel)
                .responsibleManagerId(p.getResponsibleManagerId())
                .canEditProfile(canEdit)
                .canManageVisibility(canManageVisibility)
                .canPublish(canPublish)
                .build();
    }

    public void transferResponsibility(String companyId, Long targetManagerId, UserDetailsImpl currentUser) {
        CompanyProfile profile = findProfileByCompanyIdOrThrow(companyId);

        com.apms.domain.user.Account target = accountRepository.findById(targetManagerId)
                .orElseThrow(() -> new ResourceNotFoundException("Target manager not found with id: " + targetManagerId));
        
        if (!Boolean.TRUE.equals(target.getIsActive()) || 
            !target.getRoles().contains(com.apms.common.enums.SystemRole.BUSINESS_DEVELOPMENT_MANAGER)) {
            throw new BusinessValidationException("Target account must be an active BUSINESS_DEVELOPMENT_MANAGER");
        }

        if (profile.getResponsibleManagerId() == null) {
            if (!currentUser.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"))) {
                throw new org.springframework.security.access.AccessDeniedException("Only SYSTEM_ADMIN can assign responsibility to a legacy profile");
            }
        } else {
            if (!currentUser.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN")) &&
                !currentUser.getId().equals(profile.getResponsibleManagerId())) {
                throw new org.springframework.security.access.AccessDeniedException("Only the current responsible Manager or SYSTEM_ADMIN can transfer responsibility");
            }
        }

        profile.setResponsibleManagerId(target.getId());
        profileRepository.save(profile);
        
        auditLogService.log(
                currentUser.getId(),
                com.apms.common.enums.AuditAction.COMPANY_PROFILE_RESPONSIBILITY_TRANSFERRED,
                "CompanyProfile",
                profile.getId(),
                "Responsibility transferred to manager: " + target.getId()
        );
    }

    private CompanyProfile findProfileByCompanyIdOrThrow(String companyId) {
        return profileRepository.findByCompanyId(companyId)
                .or(() -> profileRepository.findById(companyId))
                .orElseThrow(() -> new ResourceNotFoundException("Company profile not found: " + companyId));
    }

    private String resolveRelationshipType(String companyId) {
        if (!StringUtils.hasText(companyId)) return null;
        try {
            String ownerCompanyId = ownerOrganizationService.getOwnerCompanyId();
            if (companyId.equals(ownerCompanyId)) return null; // It's the owner

            String cypher = "MATCH (:Company {companyId: $ownerCompanyId})-[r]->(:Company {companyId: $companyId}) RETURN type(r) LIMIT 1";
            java.util.List<String> types = new java.util.ArrayList<>(neo4jClient.query(cypher)
                    .bind(ownerCompanyId).to("ownerCompanyId")
                    .bind(companyId).to("companyId")
                    .fetchAs(String.class)
                    .all());
            if (types != null && !types.isEmpty()) {
                return types.get(0);
            }
        } catch (Exception e) {
            log.debug("Failed to resolve Neo4j relationship for companyId {}: {}", companyId, e.getMessage());
        }

        // Fallback: check linked projects for targetRelationshipType
        try {
            java.util.Optional<CompanyProfile> profileOpt = profileRepository.findByCompanyId(companyId)
                    .or(() -> profileRepository.findById(companyId));
            if (profileOpt.isPresent() && profileOpt.get().getSourceRefs() != null && profileOpt.get().getSourceRefs().getProjectIds() != null) {
                for (String pid : profileOpt.get().getSourceRefs().getProjectIds()) {
                    try {
                        Long pId = Long.parseLong(pid);
                        java.util.Optional<Project> projOpt = projectRepository.findById(pId);
                        if (projOpt.isPresent() && projOpt.get().getTargetRelationshipType() != null) {
                            return projOpt.get().getTargetRelationshipType().name();
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private CompanyProfile.Identity mapIdentity(CompanyCandidate.Identity i) {
        if (i == null) return null;
        return CompanyProfile.Identity.builder()
                .legalName(i.getLegalName())
                .tradeName(i.getTradeName())
                .taxCode(i.getTaxCode())
                .registrationNumber(i.getRegistrationNumber())
                .build();
    }

    private CompanyProfile.Business mapBusiness(CompanyCandidate.Business b) {
        if (b == null) return null;
        return CompanyProfile.Business.builder()
                .industries(b.getIndustries())
                .businessModel(b.getBusinessModel())
                .products(b.getProducts() != null ? b.getProducts().stream()
                        .map(p -> CompanyProfile.Product.builder()
                                .name(p.getName())
                                .category(p.getCategory())
                                .description(p.getDescription())
                                .build())
                        .toList() : null)
                .markets(b.getMarkets())
                .targetCustomers(b.getTargetCustomers())
                .build();
    }

    private CompanyProfile.CompanySize mapCompanySize(CompanyCandidate.CompanySize s) {
        if (s == null) return null;
        return CompanyProfile.CompanySize.builder()
                .employeeTier(s.getEmployeeTier())
                .employeeCount(s.getEmployeeCount())
                .revenueTier(s.getRevenueTier())
                .build();
    }

    private CompanyProfile.Contact mapContact(CompanyCandidate.Contact c) {
        if (c == null) return null;
        return CompanyProfile.Contact.builder()
                .website(c.getWebsite())
                .emails(c.getEmails())
                .phones(c.getPhones())
                .addresses(c.getAddresses() != null ? c.getAddresses().stream()
                        .map(a -> CompanyProfile.Address.builder()
                                .type(a.getType())
                                .fullAddress(a.getFullAddress())
                                .city(a.getCity())
                                .country(a.getCountry())
                                .build())
                        .toList() : null)
                .build();
    }

    private CompanyProfile.Insights mapInsights(CompanyCandidate.Insights i) {
        if (i == null) return null;
        return CompanyProfile.Insights.builder()
                .strengths(i.getStrengths())
                .weaknesses(i.getWeaknesses())
                .opportunities(i.getOpportunities())
                .threats(i.getThreats())
                .build();
    }

    public boolean checkDuplicateByTaxCode(String taxCode) {
        if (!StringUtils.hasText(taxCode)) {
            return false;
        }
        return profileRepository.existsByIdentityTaxCode(taxCode.trim());
    }
}

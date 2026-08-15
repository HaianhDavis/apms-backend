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

        String newCompanyId = UUID.randomUUID().toString();
        log.info("Creating NEW CompanyProfile with companyId: {}", newCompanyId);

        CompanyProfile profile = CompanyProfile.builder()
                .companyId(newCompanyId)
                .identity(mapIdentity(candidate.getIdentity()))
                .business(mapBusiness(candidate.getBusiness()))
                .companySize(mapCompanySize(candidate.getCompanySize()))
                .contact(mapContact(candidate.getContact()))
                .insights(mapInsights(candidate.getInsights()))
                .financial(candidate.getFinancial())
                .market(candidate.getMarket())
                .innovation(candidate.getInnovation())
                .risk(candidate.getRisk())
                .compliance(candidate.getCompliance())
                .reviewStatus("APPROVED")
                .metadata(CompanyProfile.Metadata.builder()
                        .createdBy("SYSTEM")
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build())
                .build();

        addSourceRefs(profile, project, candidate);

        profile = profileRepository.save(profile);
        linkProjectToProfile(project, profile);
        linkCandidateToProfile(candidate, profile);
        log.info("Successfully created CompanyProfile for companyId: {}", newCompanyId);
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
        if (candidate.getIdentity() != null) profile.setIdentity(mapIdentity(candidate.getIdentity()));
        if (candidate.getBusiness() != null) profile.setBusiness(mapBusiness(candidate.getBusiness()));
        if (candidate.getCompanySize() != null) profile.setCompanySize(mapCompanySize(candidate.getCompanySize()));
        if (candidate.getContact() != null) profile.setContact(mapContact(candidate.getContact()));
        if (candidate.getInsights() != null) profile.setInsights(mapInsights(candidate.getInsights()));
        if (candidate.getFinancial() != null) profile.setFinancial(candidate.getFinancial());
        if (candidate.getMarket() != null) profile.setMarket(candidate.getMarket());
        if (candidate.getInnovation() != null) profile.setInnovation(candidate.getInnovation());
        if (candidate.getRisk() != null) profile.setRisk(candidate.getRisk());
        if (candidate.getCompliance() != null) profile.setCompliance(candidate.getCompliance());

        profile.setReviewStatus("APPROVED");
        profile.setVersion(profile.getVersion() + 1);
        profile.getMetadata().setUpdatedAt(LocalDateTime.now());
        profile.getMetadata().setLastModifiedBy("SYSTEM");

        addSourceRefs(profile, project, candidate);

        profile = profileRepository.save(profile);
        linkCandidateToProfile(candidate, profile);
        log.info("Successfully updated CompanyProfile for companyId: {}, new version: {}", profile.getCompanyId(), profile.getVersion());
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

        return toResponse(profile);
    }

    @Transactional(readOnly = true)
    public Page<ProfileResponse> searchCompanyProfiles(String keyword, String industry, String market, String reviewStatus, String relationshipType, boolean excludeOwner, Pageable pageable) {
        return searchCompanyProfiles(keyword, industry, market, reviewStatus, relationshipType, excludeOwner, null, pageable);
    }

    /**
     * Searches CompanyProfiles, optionally restricted to a set of accessible companyIds.
     *
     * @param allowedCompanyIds the only companyIds the caller may see, or {@code null} for unrestricted access.
     */
    @Transactional(readOnly = true)
    public Page<ProfileResponse> searchCompanyProfiles(String keyword, String industry, String market, String reviewStatus, String relationshipType, boolean excludeOwner, Set<String> allowedCompanyIds, Pageable pageable) {
        Pageable effectivePageable = newestFirst(pageable);
        Criteria criteria = Criteria.where("isDeleted").ne(true);

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

        List<String> neo4jCompanyIds = null;
        if (StringUtils.hasText(relationshipType)) {
            // 1. Validate relationshipType
            java.util.List<String> validTypes = java.util.List.of("PARTNER_WITH", "COMPETITOR_OF", "POTENTIAL_PARTNER_OF", "SUPPLIER_OF", "CUSTOMER_OF");
            if (!validTypes.contains(relationshipType)) {
                return Page.empty(pageable);
            }

            // 2. Query Neo4j
            String cypher = String.format("MATCH (c:CompanyNode)-[:%s]-(:CompanyNode) RETURN DISTINCT c.companyId AS companyId", relationshipType);
            neo4jCompanyIds = new java.util.ArrayList<>(neo4jClient.query(cypher)
                    .fetchAs(String.class)
                    .mappedBy((typeSystem, record) -> record.get("companyId").asString())
                    .all());

            if (neo4jCompanyIds.isEmpty()) {
                return Page.empty(pageable);
            }
        }

        String ownerCompanyId = ownerOrganizationService.getOwnerCompanyId();

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
        return searchProfilesByName(name, excludeOwner, null, pageable);
    }

    /**
     * Searches CompanyProfiles by name, optionally restricted to a set of accessible companyIds.
     *
     * @param allowedCompanyIds the only companyIds the caller may see, or {@code null} for unrestricted access.
     */
    @Transactional(readOnly = true)
    public Page<ProfileResponse> searchProfilesByName(String name, boolean excludeOwner, Set<String> allowedCompanyIds, Pageable pageable) {
        Pageable effectivePageable = newestFirst(pageable);
        Criteria criteria = Criteria.where("isDeleted").ne(true);

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
        CompanyProfile profile = profileRepository.findByCompanyId(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("CompanyProfile not found"));

        if (Boolean.TRUE.equals(profile.getIsDeleted())) {
            throw new ResourceNotFoundException("CompanyProfile not found");
        }

        if (profile.getIdentity() == null) profile.setIdentity(new CompanyProfile.Identity());
        if (StringUtils.hasText(request.getLegalName())) profile.getIdentity().setLegalName(request.getLegalName());
        if (StringUtils.hasText(request.getTradeName())) profile.getIdentity().setTradeName(request.getTradeName());

        if (profile.getBusiness() == null) profile.setBusiness(new CompanyProfile.Business());
        if (request.getIndustries() != null) profile.getBusiness().setIndustries(request.getIndustries());
        if (request.getMarkets() != null) profile.getBusiness().setMarkets(request.getMarkets());

        if (profile.getCompanySize() == null) profile.setCompanySize(new CompanyProfile.CompanySize());
        if (request.getEmployeeTier() != null) profile.getCompanySize().setEmployeeTier(request.getEmployeeTier());
        if (request.getEmployeeCount() != null) profile.getCompanySize().setEmployeeCount(request.getEmployeeCount());
        if (request.getRevenueTier() != null) profile.getCompanySize().setRevenueTier(request.getRevenueTier());

        if (profile.getContact() == null) profile.setContact(new CompanyProfile.Contact());
        if (StringUtils.hasText(request.getWebsite())) profile.getContact().setWebsite(request.getWebsite());
        if (request.getEmails() != null) profile.getContact().setEmails(request.getEmails());
        if (request.getPhones() != null) profile.getContact().setPhones(request.getPhones());

        if (request.getTags() != null) profile.setTags(request.getTags());

        profile.setVersion(profile.getVersion() + 1);
        profile.getMetadata().setUpdatedAt(LocalDateTime.now());

        Long currentUserId = getCurrentUserId();
        profile.getMetadata().setLastModifiedBy(currentUserId != null ? String.valueOf(currentUserId) : "SYSTEM");

        profileRepository.save(profile);

        if (currentUserId != null) {
            auditLogService.log(currentUserId, AuditAction.COMPANY_PROFILE_UPDATED, "CompanyProfile", companyId, "Profile updated manually");
        }

        return toResponse(profile);
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

        profile.setVersion(profile.getVersion() + 1);

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
        profile.setVersion(profile.getVersion() + 1);

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
                .financialReports(p.getFinancialReports())
                .reviewStatus(p.getReviewStatus())
                .relationshipType(resolveRelationshipType(p.getCompanyId()))
                .tags(p.getTags())
                .metadata(p.getMetadata())
                .version(p.getVersion())
                .stockTicker(p.getIdentity() != null ? p.getIdentity().getStockTicker() : null)
                .stockExchange(p.getIdentity() != null ? p.getIdentity().getStockExchange() : null)
                .build();
    }

    private String resolveRelationshipType(String companyId) {
        if (!StringUtils.hasText(companyId)) return "PARTNER_WITH";
        try {
            String cypher = "MATCH (c:Company {companyId: $companyId})-[r]-(:Company) RETURN type(r) LIMIT 1";
            java.util.List<String> types = new java.util.ArrayList<>(neo4jClient.query(cypher)
                    .bind(companyId).to("companyId")
                    .fetchAs(String.class)
                    .all());
            if (types != null && !types.isEmpty()) {
                return types.get(0);
            }
        } catch (Exception e) {
            log.debug("Failed to resolve Neo4j relationship for companyId {}: {}", companyId, e.getMessage());
        }
        return "PARTNER_WITH";
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
}

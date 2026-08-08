package com.apms.domain.profile.service;

import com.apms.common.enums.ProjectType;
import com.apms.common.event.CandidateApprovedEvent;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.dto.ProfileResponse;
import com.apms.domain.profile.dto.ProfileSourcesResponse;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.dto.UpdateCompanyProfileRequest;
import com.apms.domain.project.Project;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.common.enums.AuditAction;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.security.UserDetailsImpl;
import com.apms.domain.crawler.repository.TrackedCompanyRepository;
import com.apms.domain.crawler.service.TrackedCompanyCache;
import com.apms.domain.crawler.domain.TrackedCompany;
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
    private final com.apms.domain.document.service.CompanyDocumentPublisher companyDocumentPublisher;

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

        if (project.getProjectType() == ProjectType.UPDATE_EXISTING_COMPANY) {
            updateExistingProfile(project, candidate);
        } else {
            createNewProfile(project, candidate);
        }

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
        
        // Publish documents
        String profileId = project.getTargetCompanyProfileId();
        if (StringUtils.hasText(profileId) && StringUtils.hasText(candidate.getRawDocumentId())) {
            companyDocumentPublisher.publishApprovedDocument(
                profileId,
                candidate.getRawDocumentId(),
                null, // System or extracted from event
                LocalDateTime.now(),
                com.apms.domain.document.dto.PublicationContext.builder()
                    .sourceProjectId(String.valueOf(project.getId()))
                    .sourceCandidateId(candidate.getId())
                    .build()
            );
        }
    }

    private void createNewProfile(Project project, CompanyCandidate candidate) {
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
        if (!StringUtils.hasText(project.getTargetCompanyProfileId())) {
            project.setTargetCompanyProfileId(profile.getCompanyId());
            projectRepository.save(project);
            log.info("Linked project {} to new CompanyProfile companyId {}", project.getId(), profile.getCompanyId());
        }
        log.info("Successfully created CompanyProfile for companyId: {}", newCompanyId);
    }

    private void updateExistingProfile(Project project, CompanyCandidate candidate) {
        String targetProfileId = project.getTargetCompanyProfileId();
        if (!StringUtils.hasText(targetProfileId)) {
            log.error("UPDATE_EXISTING_COMPANY project missing targetCompanyProfileId");
            return;
        }

        CompanyProfile profile = profileRepository.findById(targetProfileId)
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

        profileRepository.save(profile);
        log.info("Successfully updated CompanyProfile for companyId: {}, new version: {}", profile.getCompanyId(), profile.getVersion());
    }

    private void addSourceRefs(CompanyProfile profile, Project project, CompanyCandidate candidate) {
        profile.getSourceRefs().getProjectIds().add(String.valueOf(project.getId()));
        profile.getSourceRefs().getCandidateIds().add(candidate.getId());

        if (StringUtils.hasText(candidate.getImportJobId())) {
            profile.getSourceRefs().getImportJobIds().add(candidate.getImportJobId());
        }
        if (StringUtils.hasText(candidate.getRawDocumentId())) {
            profile.getSourceRefs().getRawDocumentIds().add(candidate.getRawDocumentId());
        }
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
        Pageable effectivePageable = newestFirst(pageable);
        Criteria criteria = Criteria.where("isDeleted").ne(true);

        if (excludeOwner) {
            criteria.and("companyId").ne(ownerOrganizationService.getOwnerCompanyId());
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

        if (StringUtils.hasText(relationshipType)) {
            // 1. Validate relationshipType
            java.util.List<String> validTypes = java.util.List.of("PARTNER_WITH", "COMPETITOR_OF", "POTENTIAL_PARTNER_OF", "SUPPLIER_OF", "CUSTOMER_OF");
            if (!validTypes.contains(relationshipType)) {
                return Page.empty(pageable);
            }

            // 2. Query Neo4j
            String cypher = String.format("MATCH (c:CompanyNode)-[:%s]-(:CompanyNode) RETURN DISTINCT c.companyId AS companyId", relationshipType);
            java.util.List<String> neo4jCompanyIds = new java.util.ArrayList<>(neo4jClient.query(cypher)
                    .fetchAs(String.class)
                    .mappedBy((typeSystem, record) -> record.get("companyId").asString())
                    .all());

            if (neo4jCompanyIds.isEmpty()) {
                return Page.empty(pageable);
            }

            // 3. Add to Mongo criteria
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
        Pageable effectivePageable = newestFirst(pageable);
        Criteria criteria = Criteria.where("isDeleted").ne(true);

        if (excludeOwner) {
            criteria.and("companyId").ne(ownerOrganizationService.getOwnerCompanyId());
        }

        if (StringUtils.hasText(name)) {
            criteria.orOperator(
                    Criteria.where("identity.legalName").regex(name, "i"),
                    Criteria.where("identity.tradeName").regex(name, "i")
            );
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
                .reviewStatus(p.getReviewStatus())
                .tags(p.getTags())
                .metadata(p.getMetadata())
                .version(p.getVersion())
                .build();
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

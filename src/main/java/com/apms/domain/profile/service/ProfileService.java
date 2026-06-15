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
import com.apms.domain.project.Project;
import com.apms.domain.project.repository.sql.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
                .reviewStatus("VERIFIED")
                .metadata(CompanyProfile.Metadata.builder()
                        .createdBy("SYSTEM")
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build())
                .build();

        addSourceRefs(profile, project, candidate);

        profileRepository.save(profile);
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
        return profileRepository.findAll(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ProfileResponse getProfileByCompanyId(String companyId) {
        CompanyProfile profile = profileRepository.findByCompanyId(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("CompanyProfile not found for companyId: " + companyId));
        return toResponse(profile);
    }

    @Transactional(readOnly = true)
    public Page<ProfileResponse> searchProfilesByName(String name, Pageable pageable) {
        return profileRepository.searchByName(name, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ProfileSourcesResponse getProfileSources(String companyId) {
        CompanyProfile profile = profileRepository.findByCompanyId(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("CompanyProfile not found for companyId: " + companyId));
        
        return ProfileSourcesResponse.builder()
                .companyId(profile.getCompanyId())
                .projectIds(profile.getSourceRefs().getProjectIds())
                .importJobIds(profile.getSourceRefs().getImportJobIds())
                .rawDocumentIds(profile.getSourceRefs().getRawDocumentIds())
                .candidateIds(profile.getSourceRefs().getCandidateIds())
                .build();
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
                .reviewStatus(p.getReviewStatus())
                .tags(p.getTags())
                .metadata(p.getMetadata())
                .version(p.getVersion())
                .build();
    }

    private CompanyProfile.Identity mapIdentity(CompanyCandidate.Identity i) {
        if (i == null) return null;
        return CompanyProfile.Identity.builder()
                .name(i.getName())
                .registrationNumber(i.getRegistrationNumber())
                .taxId(i.getTaxId())
                .legalForm(i.getLegalForm())
                .foundedYear(i.getFoundedYear())
                .build();
    }

    private CompanyProfile.Business mapBusiness(CompanyCandidate.Business b) {
        if (b == null) return null;
        return CompanyProfile.Business.builder()
                .industry(b.getIndustry())
                .subIndustry(b.getSubIndustry())
                .description(b.getDescription())
                .coreProducts(b.getCoreProducts())
                .marketPosition(b.getMarketPosition())
                .build();
    }

    private CompanyProfile.CompanySize mapCompanySize(CompanyCandidate.CompanySize s) {
        if (s == null) return null;
        return CompanyProfile.CompanySize.builder()
                .employeeCountRange(s.getEmployeeCountRange())
                .estimatedRevenueRange(s.getEstimatedRevenueRange())
                .physicalLocationsCount(s.getPhysicalLocationsCount())
                .build();
    }

    private CompanyProfile.Contact mapContact(CompanyCandidate.Contact c) {
        if (c == null) return null;
        return CompanyProfile.Contact.builder()
                .website(c.getWebsite())
                .primaryEmail(c.getPrimaryEmail())
                .primaryPhone(c.getPrimaryPhone())
                .headquartersAddress(c.getHeadquartersAddress())
                .keyExecutives(c.getKeyExecutives())
                .build();
    }

    private CompanyProfile.Insights mapInsights(CompanyCandidate.Insights i) {
        if (i == null) return null;
        return CompanyProfile.Insights.builder()
                .strengths(i.getStrengths())
                .weaknesses(i.getWeaknesses())
                .opportunities(i.getOpportunities())
                .threats(i.getThreats())
                .strategicValue(i.getStrategicValue())
                .build();
    }
}

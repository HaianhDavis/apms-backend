package com.apms.domain.profile.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.config.OwnerOrganizationProperties;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.OwnerOrganization;
import com.apms.domain.profile.dto.CreateOwnerEnterpriseRequest;
import com.apms.domain.profile.dto.OwnerProfileReadinessResponse;
import com.apms.domain.profile.enums.CompanyProfileChangeSource;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import com.apms.domain.profile.repository.mongo.OwnerOrganizationRepository;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OwnerOrganizationService {

    private final OwnerOrganizationProperties properties;
    private final CompanyProfileRepository companyProfileRepository;
    private final CompanyProfileVersionRepository versionRepository;
    private final OwnerOrganizationRepository ownerOrganizationRepository;
    private final CompanyProfileVersionService versionService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    /**
     * Gets the explicit CompanyProfile document ID of the APMS Owner Organization.
     * Returns null if no owner organization has been configured yet.
     */
    public String getOwnerCompanyProfileId() {
        return findOwnerCompanyProfile()
                .map(CompanyProfile::getId)
                .orElseGet(() -> {
                    if (properties != null && StringUtils.hasText(properties.getCompanyProfileId())) {
                        return properties.getCompanyProfileId().trim();
                    }
                    return null;
                });
    }

    /**
     * Gets the universal companyId (UUID) of the APMS Owner Organization.
     * This is the stable identity used across Neo4j nodes (CompanyNode.companyId)
     * and CompanyProfile.companyId.
     */
    public String getOwnerCompanyId() {
        return findOwnerCompanyProfile()
                .map(p -> StringUtils.hasText(p.getCompanyId()) ? p.getCompanyId().trim() : p.getId())
                .orElseGet(() -> {
                    if (properties != null && StringUtils.hasText(properties.getCompanyProfileId())) {
                        return properties.getCompanyProfileId().trim();
                    }
                    return null;
                });
    }

    /**
     * Resolves the Owner Organization's profile from:
     * 1. OwnerOrganization mapping in MongoDB ("CURRENT_OWNER")
     * 2. CompanyProfile flagged with isOwnerEnterprise = true
     * 3. Fallback to properties.companyProfileId if that profile exists in DB
     */
    public Optional<CompanyProfile> findOwnerCompanyProfile() {
        // 1. Check OwnerOrganization mapping collection
        if (ownerOrganizationRepository != null) {
            Optional<OwnerOrganization> ownerOrgOpt = ownerOrganizationRepository.findById("CURRENT_OWNER");
            if (ownerOrgOpt.isPresent() && StringUtils.hasText(ownerOrgOpt.get().getOwnerCompanyProfileId())) {
                Optional<CompanyProfile> profileOpt = companyProfileRepository.findById(ownerOrgOpt.get().getOwnerCompanyProfileId().trim());
                if (profileOpt.isPresent() && !Boolean.TRUE.equals(profileOpt.get().getIsDeleted())) {
                    return profileOpt;
                }
            }
        }

        // 2. Check profile flagged with isOwnerEnterprise = true
        Optional<CompanyProfile> ownerFlagged = companyProfileRepository.findFirstByIsOwnerEnterpriseTrue();
        if (ownerFlagged.isPresent() && !Boolean.TRUE.equals(ownerFlagged.get().getIsDeleted())) {
            return ownerFlagged;
        }

        // 3. Fallback to properties.companyProfileId ONLY if it exists in MongoDB
        if (properties != null && StringUtils.hasText(properties.getCompanyProfileId())) {
            Optional<CompanyProfile> legacyOpt = companyProfileRepository.findById(properties.getCompanyProfileId().trim());
            if (legacyOpt.isPresent() && !Boolean.TRUE.equals(legacyOpt.get().getIsDeleted())) {
                return legacyOpt;
            }
        }

        return Optional.empty();
    }

    /**
     * Returns true if the owner enterprise has already been configured.
     */
    public boolean hasOwnerEnterprise() {
        return findOwnerCompanyProfile().isPresent();
    }

    /**
     * Gets the Owner Organization's profile, throwing an exception if not found.
     */
    public CompanyProfile getRequiredOwnerCompanyProfile() {
        return findOwnerCompanyProfile()
                .orElseThrow(() -> {
                    String id = getOwnerCompanyId();
                    return new BusinessValidationException(
                            id != null
                                    ? "Owner CompanyProfile not found for ID: " + id
                                    : "Owner CompanyProfile has not been configured yet."
                    );
                });
    }

    /**
     * Checks if the given company ID belongs to the Owner Organization.
     */
    public boolean isOwnerCompany(String companyId) {
        if (companyId == null || companyId.isBlank()) {
            return false;
        }
        Optional<CompanyProfile> ownerOpt = findOwnerCompanyProfile();
        if (ownerOpt.isEmpty()) {
            if (properties != null && StringUtils.hasText(properties.getCompanyProfileId())) {
                return properties.getCompanyProfileId().trim().equals(companyId.trim());
            }
            return false;
        }
        CompanyProfile owner = ownerOpt.get();
        String cleanId = companyId.trim();
        return cleanId.equals(owner.getId()) || (owner.getCompanyId() != null && cleanId.equals(owner.getCompanyId()));
    }

    /**
     * Validates that the specified target company is NOT the Owner Organization.
     * Throws BusinessValidationException if it is.
     */
    public void validateTargetIsNotOwner(String targetCompanyProfileId) {
        if (isOwnerCompany(targetCompanyProfileId)) {
            throw new BusinessValidationException("Cannot use the APMS Owner Organization as a project target company.");
        }
    }

    /**
     * Resolves the Owner Organization's profile, ensuring it is approved.
     */
    public CompanyProfile resolveApprovedOwnerProfile() {
        CompanyProfile profile = getRequiredOwnerCompanyProfile();
        if (!"APPROVED".equals(profile.getReviewStatus())) {
            throw new BusinessValidationException("Owner CompanyProfile is not approved.");
        }
        return profile;
    }

    /**
     * Checks if the owner profile is fully ready for factual comparison.
     */
    public OwnerProfileReadinessResponse checkReadiness() {
        Optional<CompanyProfile> profileOpt = findOwnerCompanyProfile();

        List<String> completed = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        boolean ready = true;

        if (profileOpt.isEmpty()) {
            missing.add("CompanyProfile");
            return OwnerProfileReadinessResponse.builder()
                    .companyProfileId(getOwnerCompanyId())
                    .approved(false)
                    .completedSections(completed)
                    .missingSections(missing)
                    .readyForComparison(false)
                    .build();
        }

        CompanyProfile profile = profileOpt.get();
        completed.add("CompanyProfile");

        boolean isApproved = "APPROVED".equals(profile.getReviewStatus());
        if (isApproved) {
            completed.add("ReviewStatus:APPROVED");
        } else {
            missing.add("ReviewStatus:APPROVED");
            ready = false;
        }

        if (profile.getIdentity() != null && StringUtils.hasText(profile.getIdentity().getLegalName())) {
            completed.add("Identity.LegalName");
        } else {
            missing.add("Identity.LegalName");
            ready = false;
        }

        if (profile.getBusiness() != null) {
            if (!CollectionUtils.isEmpty(profile.getBusiness().getIndustries())) {
                completed.add("Business.Industries");
            } else {
                missing.add("Business.Industries");
                ready = false;
            }
            if (!CollectionUtils.isEmpty(profile.getBusiness().getProducts())) {
                completed.add("Business.Products");
            } else {
                missing.add("Business.Products");
                ready = false;
            }
            if (!CollectionUtils.isEmpty(profile.getBusiness().getMarkets())) {
                completed.add("Business.Markets");
            } else {
                missing.add("Business.Markets");
                ready = false;
            }
            if (!CollectionUtils.isEmpty(profile.getBusiness().getTargetCustomers())) {
                completed.add("Business.TargetCustomers");
            } else {
                missing.add("Business.TargetCustomers");
            }
        } else {
            missing.add("Business");
            ready = false;
        }

        if (profile.getVersion() != null) {
            completed.add("CompanyProfile.Version");
            boolean hasVersionSnapshot = versionRepository.findByCompanyProfileIdAndVersion(profile.getId(), profile.getVersion()).isPresent();
            if (hasVersionSnapshot) {
                completed.add("CompanyProfileVersionSnapshot");
            } else {
                missing.add("CompanyProfileVersionSnapshot");
                ready = false;
            }
        } else {
            missing.add("CompanyProfile.Version");
            ready = false;
        }

        // Optional factual sections
        if (profile.getFinancial() != null) completed.add("FinancialInfo"); else missing.add("FinancialInfo");
        if (profile.getMarket() != null) completed.add("MarketInfo"); else missing.add("MarketInfo");
        if (profile.getInnovation() != null) completed.add("InnovationInfo"); else missing.add("InnovationInfo");
        if (profile.getRisk() != null) completed.add("RiskInfo"); else missing.add("RiskInfo");
        if (profile.getCompliance() != null) completed.add("ComplianceInfo"); else missing.add("ComplianceInfo");

        return OwnerProfileReadinessResponse.builder()
                .companyProfileId(profile.getId())
                .profileVersion(profile.getVersion())
                .approved(isApproved)
                .completedSections(completed)
                .missingSections(missing)
                .readyForComparison(ready)
                .build();
    }

    /**
     * Creates and permanently persists the initial My Enterprise profile for the system.
     * Enforces that exactly one owner enterprise may exist.
     */
    @Transactional
    public CompanyProfile createOwnerEnterprise(CreateOwnerEnterpriseRequest request, UserDetailsImpl currentUser) {
        if (findOwnerCompanyProfile().isPresent()) {
            throw new BusinessValidationException("An owner enterprise is already configured for this system. Exactly one owner enterprise is supported.");
        }

        if (!StringUtils.hasText(request.getLegalName())) {
            throw new BusinessValidationException("Legal name is required");
        }
        if (!StringUtils.hasText(request.getTradeName())) {
            throw new BusinessValidationException("Trade name is required");
        }
        if (!StringUtils.hasText(request.getTaxCode())) {
            throw new BusinessValidationException("Tax code is required");
        }

        String trimmedTaxCode = request.getTaxCode().trim();
        if (companyProfileRepository.existsByIdentityTaxCode(trimmedTaxCode)) {
            throw new BusinessValidationException("A company profile with tax code " + trimmedTaxCode + " already exists.");
        }

        String actor = currentUser != null ? currentUser.getUsername() : "SYSTEM_ADMIN";
        Long actorId = currentUser != null ? currentUser.getId() : null;
        LocalDateTime now = LocalDateTime.now();

        CompanyProfile.Identity identity = CompanyProfile.Identity.builder()
                .legalName(request.getLegalName().trim())
                .tradeName(request.getTradeName().trim())
                .taxCode(trimmedTaxCode)
                .registrationNumber(StringUtils.hasText(request.getRegistrationNumber()) ? request.getRegistrationNumber().trim() : null)
                .stockTicker(StringUtils.hasText(request.getStockTicker()) ? request.getStockTicker().trim() : null)
                .stockExchange(StringUtils.hasText(request.getStockExchange()) ? request.getStockExchange().trim() : null)
                .build();

        List<CompanyProfile.Product> productObjects = new ArrayList<>();
        if (request.getProducts() != null) {
            for (String p : request.getProducts()) {
                if (StringUtils.hasText(p)) {
                    productObjects.add(new CompanyProfile.Product(p.trim()));
                }
            }
        }

        List<String> industries = request.getIndustries() != null
                ? request.getIndustries().stream().filter(StringUtils::hasText).map(String::trim).toList()
                : new ArrayList<>();

        List<String> markets = request.getMarkets() != null
                ? request.getMarkets().stream().filter(StringUtils::hasText).map(String::trim).toList()
                : new ArrayList<>();

        List<String> targetCustomers = request.getTargetCustomers() != null
                ? request.getTargetCustomers().stream().filter(StringUtils::hasText).map(String::trim).toList()
                : new ArrayList<>();

        CompanyProfile.Business business = CompanyProfile.Business.builder()
                .industries(industries)
                .businessModel(StringUtils.hasText(request.getBusinessModel()) ? request.getBusinessModel().trim() : null)
                .foundedYear(request.getFoundedYear())
                .companyDescription(StringUtils.hasText(request.getCompanyDescription()) ? request.getCompanyDescription().trim() : null)
                .products(productObjects)
                .markets(markets)
                .targetCustomers(targetCustomers)
                .build();

        CompanyProfile.CompanySize companySize = CompanyProfile.CompanySize.builder()
                .employeeCount(request.getEmployeeCount())
                .employeeTier(request.getEmployeeTier())
                .revenueTier(request.getRevenueTier())
                .build();

        List<CompanyProfile.Address> addresses = new ArrayList<>();
        if (request.getAddresses() != null && !request.getAddresses().isEmpty()) {
            for (String addr : request.getAddresses()) {
                if (StringUtils.hasText(addr)) {
                    addresses.add(CompanyProfile.Address.builder().fullAddress(addr.trim()).build());
                }
            }
        } else if (StringUtils.hasText(request.getAddress())) {
            addresses.add(CompanyProfile.Address.builder().fullAddress(request.getAddress().trim()).build());
        }

        List<String> emails = request.getEmails() != null
                ? request.getEmails().stream().filter(StringUtils::hasText).map(String::trim).toList()
                : new ArrayList<>();

        List<String> phones = request.getPhones() != null
                ? request.getPhones().stream().filter(StringUtils::hasText).map(String::trim).toList()
                : new ArrayList<>();

        CompanyProfile.Contact contact = CompanyProfile.Contact.builder()
                .website(StringUtils.hasText(request.getWebsite()) ? request.getWebsite().trim() : null)
                .emails(emails)
                .phones(phones)
                .addresses(addresses)
                .address(addresses.isEmpty() ? null : addresses.get(0).getFullAddress())
                .build();

        CompanyProfile.Metadata metadata = CompanyProfile.Metadata.builder()
                .createdAt(now)
                .createdBy(actor)
                .updatedAt(now)
                .lastModifiedBy(actor)
                .build();

        CompanyProfile profile = CompanyProfile.builder()
                .companyId(UUID.randomUUID().toString())
                .identity(identity)
                .business(business)
                .companySize(companySize)
                .contact(contact)
                .reviewStatus("APPROVED")
                .isOwnerEnterprise(true)
                .isDeleted(false)
                .isHidden(false)
                .version("1.00")
                .majorVersion(1)
                .revision(0)
                .metadata(metadata)
                .companyMembers(new ArrayList<>())
                .financialReports(new ArrayList<>())
                .build();

        CompanyProfile savedProfile = companyProfileRepository.save(profile);

        if (ownerOrganizationRepository != null) {
            OwnerOrganization ownerOrg = OwnerOrganization.builder()
                    .id("CURRENT_OWNER")
                    .ownerCompanyProfileId(savedProfile.getId())
                    .configuredAt(now)
                    .configuredBy(actor)
                    .build();
            ownerOrganizationRepository.save(ownerOrg);
        }

        if (versionService != null) {
            try {
                Map<String, Object> afterSnapshot = versionService.createSnapshotMap(savedProfile);
                versionService.createAndSaveVersion(
                        savedProfile,
                        CompanyProfileChangeSource.INITIAL_PROFILE_CREATION,
                        List.of("all"),
                        null,
                        afterSnapshot,
                        "Initial setup of My Enterprise",
                        "Created first-time Owner Enterprise profile",
                        null,
                        null,
                        null,
                        null,
                        actorId
                );
            } catch (Exception e) {
                log.warn("Failed to create initial version snapshot for owner profile: {}", e.getMessage());
            }
        }

        if (eventPublisher != null) {
            try {
                eventPublisher.publishEvent(new com.apms.common.event.OwnerEnterpriseCreatedEvent(
                        savedProfile.getId(), savedProfile.getCompanyId()));
            } catch (Exception e) {
                log.warn("Failed to publish OwnerEnterpriseCreatedEvent: {}", e.getMessage());
            }
        }

        return savedProfile;
    }
}

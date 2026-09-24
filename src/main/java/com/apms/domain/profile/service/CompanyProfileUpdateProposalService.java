package com.apms.domain.profile.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SystemRole;
import com.apms.common.exception.BusinessConflictException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.notification.service.NotificationService;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.CompanyProfileUpdateProposal;
import com.apms.domain.profile.CompanyProfileVersion;
import com.apms.domain.profile.dto.CompanyProfileUpdateProposalResponse;
import com.apms.domain.profile.dto.CreateCompanyProfileUpdateProposalRequest;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileUpdateProposalRepository;
import com.apms.domain.profile.validation.CompanyProfileFieldValidator;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import com.apms.domain.project.fieldapproval.ProfileProposalFieldAccessor;
import com.apms.domain.project.fieldapproval.FieldApprovalService;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyProfileUpdateProposalService {

    private final CompanyProfileUpdateProposalRepository proposalRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final ProjectRepository projectRepository;
    private final AuditLogService auditLogService;
    private final FieldApprovalService fieldApprovalService;
    private final com.apms.domain.graph.service.GraphService graphService;
    private final com.apms.domain.profile.service.OwnerOrganizationService ownerOrganizationService;
    private final com.apms.domain.user.repository.sql.UserProfileRepository userProfileRepository;
    private final CompanyProfileVersionService versionService;
    private final com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository versionRepository;
    private final NotificationService notificationService;

    @Transactional
    public CompanyProfileUpdateProposalResponse createProposal(Long projectId, Long taskId, CreateCompanyProfileUpdateProposalRequest request) {
        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) {
            throw new AccessDeniedException("Unauthorized");
        }

        if (!hasRole(currentUser, SystemRole.SYSTEM_ADMIN) &&
            !projectRepository.existsByIdAndMembersAccountId(projectId, currentUser.getId())) {
            throw new AccessDeniedException("Must be a project member to submit proposals");
        }

        if (!companyProfileRepository.existsById(request.getCompanyProfileId())) {
            throw new ResourceNotFoundException("Target CompanyProfile does not exist");
        }

        if (request.getProposedContact() != null) {
            CompanyProfileFieldValidator.validateProposedContact(request.getProposedContact());
        }

        CompanyProfileUpdateProposal proposal = CompanyProfileUpdateProposal.builder()
                .origin(com.apms.common.enums.ProposalOrigin.PROJECT)
                .projectId(projectId)
                .taskId(taskId)
                .companyProfileId(request.getCompanyProfileId())
                .proposedIdentity(request.getProposedIdentity())
                .proposedBusiness(request.getProposedBusiness())
                .proposedCompanySize(request.getProposedCompanySize())
                .proposedContact(request.getProposedContact())
                .proposedInsights(request.getProposedInsights())
                .proposedFinancial(request.getProposedFinancial())
                .proposedMarket(request.getProposedMarket())
                .proposedInnovation(request.getProposedInnovation())
                .proposedRisk(request.getProposedRisk())
                .proposedCompliance(request.getProposedCompliance())
                .proposedCompanyMembers(request.getProposedCompanyMembers())
                .sourceDocumentIds(request.getSourceDocumentIds())
                .extractionId(request.getExtractionId())
                .changeSummary(request.getChangeSummary())
                .status(SubmissionStatus.DRAFT)
                .submittedBy(currentUser.getId())
                .build();

        proposal = proposalRepository.save(proposal);

        auditLogService.log(currentUser.getId(), AuditAction.PROFILE_UPDATE_PROPOSAL_CREATED, "CompanyProfileUpdateProposal", proposal.getId(), "Proposal created for profile: " + request.getCompanyProfileId());

        return toResponse(proposal);
    }

    @Transactional
    public CompanyProfileUpdateProposalResponse createMonitoringProposal(CreateCompanyProfileUpdateProposalRequest request) {
        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) {
            throw new AccessDeniedException("Unauthorized");
        }

        if (!companyProfileRepository.existsById(request.getCompanyProfileId())) {
            throw new ResourceNotFoundException("Target CompanyProfile does not exist");
        }

        if (request.getProposedContact() != null) {
            CompanyProfileFieldValidator.validateProposedContact(request.getProposedContact());
        }

        java.util.List<CompanyProfileUpdateProposal> existingSubmitted = proposalRepository.findByCompanyProfileIdAndStatusIn(
                request.getCompanyProfileId(),
                java.util.List.of(SubmissionStatus.SUBMITTED, SubmissionStatus.IN_REVIEW)
        );
        boolean hasActiveSubmitted = existingSubmitted.stream()
                .anyMatch(p -> p.getOrigin() == com.apms.common.enums.ProposalOrigin.MONITORING);
        if (hasActiveSubmitted) {
            throw new BusinessConflictException("A monitoring proposal is already awaiting manager review. Cancel the current proposal before submitting a new one.");
        }

        java.util.Map<String, Object> origValues = request.getOriginalValues();
        if (origValues == null || origValues.isEmpty()) {
            origValues = new java.util.HashMap<>();
            CompanyProfile companyProfile = companyProfileRepository.findById(request.getCompanyProfileId()).orElse(null);
            if (companyProfile != null && request.getChangedFieldPaths() != null) {
                java.util.Map<String, Object> snapshot = versionService.createSnapshotMap(companyProfile);
                for (String path : request.getChangedFieldPaths()) {
                    origValues.put(path, extractValueByPath(snapshot, path));
                }
            }
        }

        java.util.List<CompanyProfileUpdateProposal> existingDrafts = proposalRepository.findByCompanyProfileIdAndStatusIn(
                request.getCompanyProfileId(),
                java.util.List.of(SubmissionStatus.DRAFT)
        );
        CompanyProfileUpdateProposal proposal = existingDrafts.stream()
                .filter(p -> p.getOrigin() == com.apms.common.enums.ProposalOrigin.MONITORING &&
                        (p.getSubmittedBy() == null || currentUser.getId().equals(p.getSubmittedBy())))
                .findFirst()
                .orElse(null);

        if (proposal != null) {
            proposal.setProposedIdentity(request.getProposedIdentity());
            proposal.setProposedBusiness(request.getProposedBusiness());
            proposal.setProposedCompanySize(request.getProposedCompanySize());
            proposal.setProposedContact(request.getProposedContact());
            proposal.setProposedInsights(request.getProposedInsights());
            proposal.setProposedFinancial(request.getProposedFinancial());
            proposal.setProposedMarket(request.getProposedMarket());
            proposal.setProposedInnovation(request.getProposedInnovation());
            proposal.setProposedRisk(request.getProposedRisk());
            proposal.setProposedCompliance(request.getProposedCompliance());
            proposal.setProposedCompanyMembers(request.getProposedCompanyMembers());
            proposal.setProposedRelationship(request.getProposedRelationship());
            proposal.setChangedFieldPaths(request.getChangedFieldPaths());
            proposal.setOriginalValues(origValues);
            proposal.setFieldEvidence(request.getFieldEvidence());
            proposal.setSourceDocumentIds(request.getSourceDocumentIds());
            proposal.setExtractionId(request.getExtractionId());
            proposal.setChangeSummary(request.getChangeSummary());
            proposal.setUpdatedAt(java.time.LocalDateTime.now());
            if (proposal.getSubmittedBy() == null) {
                proposal.setSubmittedBy(currentUser.getId());
            }
        } else {
            proposal = CompanyProfileUpdateProposal.builder()
                    .origin(com.apms.common.enums.ProposalOrigin.MONITORING)
                    .companyProfileId(request.getCompanyProfileId())
                    .proposedIdentity(request.getProposedIdentity())
                    .proposedBusiness(request.getProposedBusiness())
                    .proposedCompanySize(request.getProposedCompanySize())
                    .proposedContact(request.getProposedContact())
                    .proposedInsights(request.getProposedInsights())
                    .proposedFinancial(request.getProposedFinancial())
                    .proposedMarket(request.getProposedMarket())
                    .proposedInnovation(request.getProposedInnovation())
                    .proposedRisk(request.getProposedRisk())
                    .proposedCompliance(request.getProposedCompliance())
                    .proposedCompanyMembers(request.getProposedCompanyMembers())
                    .proposedRelationship(request.getProposedRelationship())
                    .changedFieldPaths(request.getChangedFieldPaths())
                    .originalValues(origValues)
                    .fieldEvidence(request.getFieldEvidence())
                    .sourceDocumentIds(request.getSourceDocumentIds())
                    .extractionId(request.getExtractionId())
                    .changeSummary(request.getChangeSummary())
                    .status(SubmissionStatus.DRAFT)
                    .submittedBy(currentUser.getId())
                    .build();
        }

        proposal = proposalRepository.save(proposal);

        auditLogService.log(currentUser.getId(), AuditAction.PROFILE_UPDATE_PROPOSAL_CREATED, "CompanyProfileUpdateProposal", proposal.getId(), "Monitoring Proposal created for profile: " + request.getCompanyProfileId());

        return toResponse(proposal);
    }

    @Transactional
    public CompanyProfileUpdateProposalResponse withdrawMonitoringProposal(String id, Long currentUserId) {
        CompanyProfileUpdateProposal proposal = proposalRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Proposal not found"));

        if (proposal.getOrigin() != com.apms.common.enums.ProposalOrigin.MONITORING) {
            throw new com.apms.common.exception.BusinessValidationException("Only MONITORING proposals can be withdrawn via this endpoint");
        }

        if (proposal.getStatus() == SubmissionStatus.APPROVED ||
            proposal.getStatus() == SubmissionStatus.REJECTED ||
            proposal.getStatus() == SubmissionStatus.WITHDRAWN) {
            throw new BusinessConflictException("Proposal is already in terminal status: " + proposal.getStatus());
        }

        UserDetailsImpl currentUser = getCurrentUser();
        boolean isAdmin = currentUser != null && hasRole(currentUser, SystemRole.SYSTEM_ADMIN);
        if (!isAdmin && (proposal.getSubmittedBy() == null || !proposal.getSubmittedBy().equals(currentUserId))) {
            throw new AccessDeniedException("Only the submitting staff member can withdraw this proposal");
        }

        proposal.setStatus(SubmissionStatus.WITHDRAWN);
        proposal = proposalRepository.save(proposal);

        auditLogService.log(currentUserId, AuditAction.PROFILE_UPDATE_PROPOSAL_REJECTED, "CompanyProfileUpdateProposal", proposal.getId(), "Monitoring Proposal withdrawn by submitter");

        return toResponse(proposal);
    }

    @Transactional
    public void cancelProposalSubmission(String id) {
        CompanyProfileUpdateProposal proposal = proposalRepository.findById(id).orElse(null);
        if (proposal != null && proposal.getStatus() == SubmissionStatus.IN_REVIEW) {
            boolean hadManagerReview = proposal.getFieldApprovals() != null && proposal.getFieldApprovals().stream()
                    .anyMatch(f -> f.getReviewedByAccountId() != null
                            || f.getStatus() == com.apms.common.enums.FieldApprovalStatus.APPROVED
                            || f.getStatus() == com.apms.common.enums.FieldApprovalStatus.REVISION_REQUIRED
                            || f.getStatus() == com.apms.common.enums.FieldApprovalStatus.REJECTED);

            if (hadManagerReview) {
                proposal.setStatus(SubmissionStatus.REVISION_REQUESTED);
            } else {
                proposal.setStatus(SubmissionStatus.DRAFT);
                proposal.setFieldApprovals(new java.util.ArrayList<>());
            }
            proposalRepository.save(proposal);
        }
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

    private <T> T updateObject(com.fasterxml.jackson.databind.ObjectMapper mapper, T existing, Object proposed, Class<T> clazz) {
        if (proposed == null) return existing;
        if (existing == null) return mapper.convertValue(proposed, clazz);
        try {
            return mapper.readerForUpdating(existing).readValue(mapper.writeValueAsString(proposed));
        } catch (Exception e) {
            log.error("Failed to merge proposed update", e);
            return existing;
        }
    }

    private java.util.Map<String, Object> filterMapByPaths(java.util.Map<String, Object> map, String prefix, java.util.List<String> allowedPaths) {
        if (map == null || allowedPaths == null || allowedPaths.isEmpty()) return map;
        java.util.Map<String, Object> filtered = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, Object> entry : map.entrySet()) {
            String fullPath = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (allowedPaths.contains(fullPath)) {
                filtered.put(entry.getKey(), entry.getValue());
            } else if (entry.getValue() instanceof java.util.Map) {
                boolean hasChild = allowedPaths.stream().anyMatch(p -> p.startsWith(fullPath + "."));
                if (hasChild) {
                    java.util.Map<String, Object> childFiltered = filterMapByPaths((java.util.Map<String, Object>) entry.getValue(), fullPath, allowedPaths);
                    if (!childFiltered.isEmpty()) {
                        filtered.put(entry.getKey(), childFiltered);
                    }
                }
            }
        }
        return filtered;
    }

    @Transactional
    public CompanyProfileUpdateProposalResponse approveMonitoringProposal(String id, Long approverId, String reviewComment) {
        CompanyProfileUpdateProposal proposal = proposalRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Proposal not found"));

        if (proposal.getOrigin() != com.apms.common.enums.ProposalOrigin.MONITORING) {
            throw new com.apms.common.exception.BusinessValidationException("Only MONITORING proposals can be approved via this endpoint");
        }

        if (proposal.getStatus() == SubmissionStatus.APPROVED) {
            return toResponse(proposal);
        }

        if (proposal.getStatus() != SubmissionStatus.DRAFT && proposal.getStatus() != SubmissionStatus.SUBMITTED && proposal.getStatus() != SubmissionStatus.IN_REVIEW) {
            throw new com.apms.common.exception.BusinessValidationException("Proposal is already in terminal status: " + proposal.getStatus());
        }

        if (versionRepository.existsByCreatedFromProposalId(proposal.getId())) {
            proposal.setStatus(SubmissionStatus.APPROVED);
            proposal.setReviewedBy(approverId);
            if (reviewComment != null) {
                proposal.setReviewComment(reviewComment);
            }
            proposal = proposalRepository.save(proposal);
            return toResponse(proposal);
        }

        CompanyProfile companyProfile = companyProfileRepository.findById(proposal.getCompanyProfileId())
                .orElseThrow(() -> new ResourceNotFoundException("CompanyProfile not found"));

        java.util.Map<String, Object> beforeSnapshot = versionService.createSnapshotMap(companyProfile);

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

        java.util.Map<String, Object> propIdentity = proposal.getProposedIdentity();
        java.util.Map<String, Object> propBusiness = proposal.getProposedBusiness();
        java.util.Map<String, Object> propCompanySize = proposal.getProposedCompanySize();
        java.util.Map<String, Object> propContact = proposal.getProposedContact();
        java.util.Map<String, Object> propFinancial = proposal.getProposedFinancial();
        java.util.Map<String, Object> propMarket = proposal.getProposedMarket();
        java.util.Map<String, Object> propInnovation = proposal.getProposedInnovation();
        java.util.Map<String, Object> propRisk = proposal.getProposedRisk();
        java.util.Map<String, Object> propCompliance = proposal.getProposedCompliance();

        if (proposal.getChangedFieldPaths() != null && !proposal.getChangedFieldPaths().isEmpty()) {
            java.util.List<String> paths = proposal.getChangedFieldPaths();
            propIdentity = filterMapByPaths(propIdentity, "identity", paths);
            propBusiness = filterMapByPaths(propBusiness, "business", paths);
            propCompanySize = filterMapByPaths(propCompanySize, "companySize", paths);
            propContact = filterMapByPaths(propContact, "contact", paths);
            propFinancial = filterMapByPaths(propFinancial, "financial", paths);
            propMarket = filterMapByPaths(propMarket, "market", paths);
            propInnovation = filterMapByPaths(propInnovation, "innovation", paths);
            propRisk = filterMapByPaths(propRisk, "risk", paths);
            propCompliance = filterMapByPaths(propCompliance, "compliance", paths);
        }

        companyProfile.setIdentity(updateObject(mapper, companyProfile.getIdentity(), propIdentity, CompanyProfile.Identity.class));
        
        if (companyProfile.getIdentity() != null) {
            if ("".equals(companyProfile.getIdentity().getTaxCode())) {
                companyProfile.getIdentity().setTaxCode(null);
            }
            if ("".equals(companyProfile.getIdentity().getRegistrationNumber())) {
                companyProfile.getIdentity().setRegistrationNumber(null);
            }
        }
        
        companyProfile.setBusiness(updateObject(mapper, companyProfile.getBusiness(), propBusiness, CompanyProfile.Business.class));
        companyProfile.setCompanySize(updateObject(mapper, companyProfile.getCompanySize(), propCompanySize, CompanyProfile.CompanySize.class));
        companyProfile.setContact(updateObject(mapper, companyProfile.getContact(), propContact, CompanyProfile.Contact.class));
        companyProfile.setFinancial(updateObject(mapper, companyProfile.getFinancial(), propFinancial, com.apms.domain.company.model.FinancialInfo.class));
        companyProfile.setMarket(updateObject(mapper, companyProfile.getMarket(), propMarket, com.apms.domain.company.model.MarketInfo.class));
        companyProfile.setInnovation(updateObject(mapper, companyProfile.getInnovation(), propInnovation, com.apms.domain.company.model.InnovationInfo.class));
        companyProfile.setRisk(updateObject(mapper, companyProfile.getRisk(), propRisk, com.apms.domain.company.model.RiskInfo.class));
        companyProfile.setCompliance(updateObject(mapper, companyProfile.getCompliance(), propCompliance, com.apms.domain.company.model.ComplianceInfo.class));

        if (proposal.getProposedCompanyMembers() != null) {
            companyProfile.setCompanyMembers(proposal.getProposedCompanyMembers().stream()
                    .map(m -> mapper.convertValue(m, CompanyProfile.CompanyMember.class))
                    .collect(java.util.stream.Collectors.toList()));
        }

        if (proposal.getProposedRelationship() != null && !proposal.getProposedRelationship().isEmpty()) {
            String ownerCompanyId = ownerOrganizationService.getOwnerCompanyId();
            String rawRel = proposal.getProposedRelationship().toUpperCase().replace(" ", "_");
            String relType = "PARTNER_WITH"; // Default
            switch (rawRel) {
                case "PARTNER": relType = "PARTNER_WITH"; break;
                case "COMPETITOR": relType = "COMPETITOR_OF"; break;
                case "SUPPLIER": relType = "SUPPLIER_OF"; break;
                case "CUSTOMER": relType = "CUSTOMER_OF"; break;
                case "POTENTIAL_PARTNER": relType = "POTENTIAL_PARTNER_OF"; break;
                default: relType = rawRel;
            }
            String confirmedBy = String.valueOf(approverId);
            graphService.replaceRelationship(ownerCompanyId, companyProfile.getCompanyId(), relType, confirmedBy);
        }

        // Ensure source document ids are added to the profile
        if (proposal.getSourceDocumentIds() != null && !proposal.getSourceDocumentIds().isEmpty()) {
            if (companyProfile.getSourceRefs() == null) {
                companyProfile.setSourceRefs(new CompanyProfile.SourceRefs());
            }
            if (companyProfile.getSourceRefs().getRawDocumentIds() == null) {
                companyProfile.getSourceRefs().setRawDocumentIds(new java.util.HashSet<>());
            }
            for (String docId : proposal.getSourceDocumentIds()) {
                companyProfile.getSourceRefs().getRawDocumentIds().add(docId);
            }
        }

        java.util.Map<String, Object> afterSnapshot = versionService.createSnapshotMap(companyProfile);

        java.util.Map<String, Object> beforeValues = new java.util.HashMap<>();
        java.util.Map<String, Object> afterValues = new java.util.HashMap<>();
        boolean dataChanged = false;

        if (proposal.getChangedFieldPaths() != null && !proposal.getChangedFieldPaths().isEmpty()) {
            for (String path : proposal.getChangedFieldPaths()) {
                Object bVal = extractValueByPath(beforeSnapshot, path);
                Object aVal = extractValueByPath(afterSnapshot, path);
                if (!CompanyProfileDiffHelper.areValuesSemanticallyEqual(path, bVal, aVal)) {
                    dataChanged = true;
                    beforeValues.put(path, CompanyProfileDiffHelper.normalizeForHistory(path, bVal));
                    afterValues.put(path, CompanyProfileDiffHelper.normalizeForHistory(path, aVal));
                }
            }
        } else {
            dataChanged = !java.util.Objects.equals(beforeSnapshot, afterSnapshot);
        }

        if (dataChanged) {
            companyProfile.incrementMinorVersion();
            if (companyProfile.getMetadata() == null) {
                companyProfile.setMetadata(new CompanyProfile.Metadata());
            }
            companyProfile.getMetadata().setUpdatedAt(LocalDateTime.now());
            companyProfile.getMetadata().setLastModifiedBy(String.valueOf(approverId));

            companyProfileRepository.save(companyProfile);

            CompanyProfileVersion version = versionService.createAndSaveVersion(
                    companyProfile,
                    com.apms.domain.profile.enums.CompanyProfileChangeSource.MONITORING_PROPOSAL_APPROVED,
                    proposal.getChangedFieldPaths(),
                    beforeValues,
                    afterValues,
                    reviewComment,
                    "Approved monitoring proposal " + proposal.getId(),
                    proposal.getId(),
                    proposal.getProjectId(),
                    proposal.getTaskId(),
                    proposal.getSourceDocumentIds(),
                    approverId
            );

            if (version != null) {
                String versionIdentity = StringUtils.hasText(version.getId()) ? version.getId() : companyProfile.getVersionLabel();
                notificationService.notifyCompanyProfileUpdated(companyProfile, versionIdentity, approverId);
            }
        } else {
            companyProfileRepository.save(companyProfile);
        }

        if ((proposal.getOriginalValues() == null || proposal.getOriginalValues().isEmpty()) && beforeValues != null && !beforeValues.isEmpty()) {
            proposal.setOriginalValues(beforeValues);
        }

        proposal.setStatus(SubmissionStatus.APPROVED);
        proposal.setReviewedBy(approverId);
        if (reviewComment != null) {
            proposal.setReviewComment(reviewComment);
        }
        proposalRepository.save(proposal);

        auditLogService.log(approverId, AuditAction.PROFILE_UPDATE_PROPOSAL_APPROVED, "CompanyProfileUpdateProposal", proposal.getId(), "Monitoring Proposal approved");

        return toResponse(proposal);
    }

    @Transactional
    public CompanyProfileUpdateProposalResponse rejectMonitoringProposal(String id, Long approverId, String reviewComment) {
        CompanyProfileUpdateProposal proposal = proposalRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Proposal not found"));

        if (proposal.getOrigin() != com.apms.common.enums.ProposalOrigin.MONITORING) {
            throw new com.apms.common.exception.BusinessValidationException("Only MONITORING proposals can be rejected via this endpoint");
        }

        if (proposal.getStatus() != SubmissionStatus.DRAFT && proposal.getStatus() != SubmissionStatus.SUBMITTED && proposal.getStatus() != SubmissionStatus.IN_REVIEW) {
            throw new com.apms.common.exception.BusinessValidationException("Proposal is already processed");
        }

        proposal.setStatus(SubmissionStatus.REJECTED);
        proposal.setReviewedBy(approverId);
        if (reviewComment != null) {
            proposal.setReviewComment(reviewComment);
        }
        proposalRepository.save(proposal);

        auditLogService.log(approverId, AuditAction.PROFILE_UPDATE_PROPOSAL_REJECTED, "CompanyProfileUpdateProposal", proposal.getId(), "Monitoring Proposal rejected");

        return toResponse(proposal);
    }

    @Transactional
    public CompanyProfileUpdateProposalResponse submitProposal(String id, Long submitterId) {
        CompanyProfileUpdateProposal proposal = proposalRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Proposal not found"));

        if (proposal.getStatus() == SubmissionStatus.IN_REVIEW) {
            // Might be a retry of a successful Mongo save that failed in SQL.
            return toResponse(proposal);
        }

        if (proposal.getStatus() != SubmissionStatus.DRAFT) {
            throw new com.apms.common.exception.BusinessValidationException("Only DRAFT proposals can be submitted");
        }

        boolean isFirstSubmission = (proposal.getFieldApprovals() == null || proposal.getFieldApprovals().isEmpty());

        if (isFirstSubmission) {
            proposal.setRevisionNumber(1);
            proposal.setFieldApprovals(new java.util.ArrayList<>());
            fieldApprovalService.initializeFirstSubmission(proposal, ProfileProposalFieldAccessor.getAllDefinitions(), proposal.getFieldApprovals());
        } else {
            // Resubmission
            proposal.setRevisionNumber(proposal.getRevisionNumber() != null ? proposal.getRevisionNumber() + 1 : 1);
            if (proposal.getChangedFieldPaths() != null) {
                java.util.Map<String, com.apms.domain.project.fieldapproval.FieldApprovalRecord> map = com.apms.domain.project.fieldapproval.FieldApprovalUtils.toMap(proposal.getFieldApprovals());
                for (String path : proposal.getChangedFieldPaths()) {
                    com.apms.domain.project.fieldapproval.FieldApprovalRecord record = map.get(path);
                    if (record == null) {
                        record = com.apms.domain.project.fieldapproval.FieldApprovalRecord.builder().fieldPath(path).build();
                        proposal.getFieldApprovals().add(record);
                        map.put(path, record);
                    }
                    if (record.getStatus() == com.apms.common.enums.FieldApprovalStatus.REVISION_REQUIRED) {
                        record.setPreviousStatus(record.getStatus());
                        record.setPreviousComment(record.getComment());
                    }
                    record.setStatus(com.apms.common.enums.FieldApprovalStatus.PENDING_REVIEW);
                    record.setChangedInRevision(proposal.getRevisionNumber());
                    record.setReviewedByAccountId(null);
                    record.setReviewedAt(null);
                }
            }
        }

        proposal.setChangedFieldPaths(new java.util.ArrayList<>());
        proposal.setLastSubmittedAt(LocalDateTime.now());
        proposal.setLastSubmittedByAccountId(submitterId);

        proposal.setStatus(SubmissionStatus.IN_REVIEW);

        proposal = proposalRepository.save(proposal);
        return toResponse(proposal);
    }

    @Transactional
    public void reviewFields(String proposalId, com.apms.domain.project.dto.FieldReviewRequest request, Long reviewerId) {
        CompanyProfileUpdateProposal proposal = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new ResourceNotFoundException("Proposal not found"));
        if (!java.util.Objects.equals(proposal.getRevisionNumber(), request.getExpectedRevisionNumber())) {
            throw new com.apms.common.exception.BusinessValidationException("Revision mismatch. Expected: " + request.getExpectedRevisionNumber() + ", Actual: " + proposal.getRevisionNumber());
        }
        if (!java.util.Objects.equals(proposal.getDocumentVersion(), request.getExpectedDocumentVersion())) {
            throw new com.apms.common.exception.BusinessValidationException("Document version mismatch. Expected: " + request.getExpectedDocumentVersion() + ", Actual: " + proposal.getDocumentVersion());
        }

        fieldApprovalService.processBatchReview(
                proposal, request, reviewerId,
                "CompanyProfileUpdateProposal", proposalId,
                proposal.getFieldApprovals(),
                com.apms.domain.project.fieldapproval.ProfileProposalFieldAccessor.getAllDefinitions(),
                proposal.getRevisionNumber()
        );

        proposalRepository.save(proposal);
    }

    @Transactional
    public void reopenField(String proposalId, String fieldPath, com.apms.domain.project.dto.FieldReopenRequest request, Long reviewerId) {
        CompanyProfileUpdateProposal proposal = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new ResourceNotFoundException("Proposal not found"));
        if (!java.util.Objects.equals(proposal.getRevisionNumber(), request.getExpectedRevisionNumber())) {
            throw new com.apms.common.exception.BusinessValidationException("Revision mismatch.");
        }
        if (!java.util.Objects.equals(proposal.getDocumentVersion(), request.getExpectedDocumentVersion())) {
            throw new com.apms.common.exception.BusinessValidationException("Document version mismatch.");
        }

        fieldApprovalService.processReopen(
                fieldPath, request, reviewerId,
                "CompanyProfileUpdateProposal", proposalId,
                proposal.getFieldApprovals()
        );

        proposalRepository.save(proposal);
    }

    @Transactional(readOnly = true)
    public void validateFinalReviewReadiness(String proposalId, com.apms.common.enums.ReviewDecision decision) {
        CompanyProfileUpdateProposal proposal = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new ResourceNotFoundException("Proposal not found"));
        fieldApprovalService.validateFinalReviewReadiness(proposal, proposal.getFieldApprovals(), com.apms.domain.project.fieldapproval.ProfileProposalFieldAccessor.getAllDefinitions(), decision);
    }

    @Transactional(readOnly = true)
    public com.apms.domain.project.dto.ReviewSummaryResponse getReviewSummary(String proposalId, Integer submittedRevisionNumber) {
        CompanyProfileUpdateProposal proposal = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new ResourceNotFoundException("Proposal not found"));

        boolean readyForApproval = true;
        java.util.List<String> blockingFields = new java.util.ArrayList<>();
        if (proposal.getFieldApprovals() != null) {
            for (com.apms.domain.project.fieldapproval.FieldApprovalRecord record : proposal.getFieldApprovals()) {
                if (record.getStatus() == com.apms.common.enums.FieldApprovalStatus.PENDING_REVIEW || record.getStatus() == com.apms.common.enums.FieldApprovalStatus.REVISION_REQUIRED) {
                    readyForApproval = false;
                    blockingFields.add(record.getFieldPath());
                }
            }
        }

        java.util.Map<String, com.apms.domain.project.dto.FieldApprovalResponse> mappedApprovals = new java.util.HashMap<>();
        if (proposal.getFieldApprovals() != null) {
            proposal.getFieldApprovals().forEach(v -> {
                mappedApprovals.put(v.getFieldPath(), com.apms.domain.project.dto.FieldApprovalResponse.builder()
                        .status(v.getStatus())
                        .reviewedRevision(v.getReviewedRevision())
                        .comment(v.getComment())
                        .previousComment(v.getPreviousComment())
                        .previousStatus(v.getPreviousStatus())
                        .changedInRevision(v.getChangedInRevision())
                        .staleReason(v.getStaleReason())
                        .pendingValue(v.getPendingValue())
                        .pendingEvidenceIds(v.getPendingEvidenceIds())
                        .build());
            });
        }

        return com.apms.domain.project.dto.ReviewSummaryResponse.builder()
                .revisionNumber(proposal.getRevisionNumber())
                .documentVersion(proposal.getDocumentVersion())
                .submittedRevisionNumber(submittedRevisionNumber)
                .fieldApprovals(mappedApprovals)
                .changedFieldPaths(proposal.getChangedFieldPaths())
                .readyForApproval(readyForApproval)
                .blockingFields(blockingFields)
                .build();
    }

    @Transactional(readOnly = true)
    public CompanyProfileUpdateProposalResponse getProposal(String id) {
        CompanyProfileUpdateProposal proposal = proposalRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Proposal not found"));
        return toResponse(proposal);
    }

    @Transactional(readOnly = true)
    public java.util.List<CompanyProfileUpdateProposalResponse> getPendingProposalsByCompany(String companyProfileId) {
        java.util.List<CompanyProfileUpdateProposal> proposals = proposalRepository.findByCompanyProfileIdAndStatusIn(
                companyProfileId, 
                java.util.Arrays.asList(SubmissionStatus.DRAFT, SubmissionStatus.SUBMITTED, SubmissionStatus.IN_REVIEW)
        );
        return proposals.stream().map(this::toResponse).collect(java.util.stream.Collectors.toList());
    }

    @Transactional(readOnly = true)
    public CompanyProfileUpdateProposalResponse getProposalDetails(String id) {
        CompanyProfileUpdateProposal proposal = proposalRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Proposal not found"));

        UserDetailsImpl currentUser = getCurrentUser();
        if (currentUser == null) {
            throw new AccessDeniedException("Unauthorized");
        }

        if (!hasRole(currentUser, SystemRole.SYSTEM_ADMIN)) {
            if (proposal.getOrigin() == com.apms.common.enums.ProposalOrigin.MONITORING) {
                if (!hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_MANAGER) && 
                    !hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_STAFF)) {
                    throw new AccessDeniedException("Access denied to this monitoring proposal");
                }
            } else {
                if (!projectRepository.existsByIdAndMembersAccountId(proposal.getProjectId(), currentUser.getId())) {
                    throw new AccessDeniedException("Access denied to this proposal");
                }
            }
        }

        return toResponse(proposal);
    }

    private CompanyProfileUpdateProposalResponse toResponse(CompanyProfileUpdateProposal proposal) {
        String reviewerName = null;
        if (proposal.getReviewedBy() != null) {
            reviewerName = userProfileRepository.findByAccountId(proposal.getReviewedBy())
                    .map(p -> p.getFirstName() + " " + p.getLastName())
                    .orElse(null);
        }

        java.util.Map<String, Object> originalValues = proposal.getOriginalValues();
        if ((originalValues == null || originalValues.isEmpty()) && proposal.getStatus() == SubmissionStatus.APPROVED) {
            java.util.Optional<com.apms.domain.profile.CompanyProfileVersion> versionOpt = versionRepository.findFirstByCreatedFromProposalId(proposal.getId());
            if (versionOpt.isPresent() && versionOpt.get().getBeforeValues() != null) {
                originalValues = versionOpt.get().getBeforeValues();
            }
        }
        if ((originalValues == null || originalValues.isEmpty()) && proposal.getChangedFieldPaths() != null && !proposal.getChangedFieldPaths().isEmpty() && proposal.getCompanyProfileId() != null) {
            CompanyProfile companyProfile = companyProfileRepository.findById(proposal.getCompanyProfileId()).orElse(null);
            if (companyProfile != null) {
                java.util.Map<String, Object> snapshot = versionService.createSnapshotMap(companyProfile);
                originalValues = new java.util.HashMap<>();
                for (String path : proposal.getChangedFieldPaths()) {
                    originalValues.put(path, extractValueByPath(snapshot, path));
                }
            }
        }

        return CompanyProfileUpdateProposalResponse.builder()
                .id(proposal.getId())
                .projectId(proposal.getProjectId())
                .taskId(proposal.getTaskId())
                .revisionNumber(proposal.getRevisionNumber())
                .companyProfileId(proposal.getCompanyProfileId())
                .proposedIdentity(proposal.getProposedIdentity())
                .proposedBusiness(proposal.getProposedBusiness())
                .proposedCompanySize(proposal.getProposedCompanySize())
                .proposedContact(proposal.getProposedContact())
                .proposedInsights(proposal.getProposedInsights())
                .proposedFinancial(proposal.getProposedFinancial())
                .proposedMarket(proposal.getProposedMarket())
                .proposedInnovation(proposal.getProposedInnovation())
                .proposedRisk(proposal.getProposedRisk())
                .proposedCompliance(proposal.getProposedCompliance())
                .proposedCompanyMembers(proposal.getProposedCompanyMembers())
                .proposedRelationship(proposal.getProposedRelationship())
                .changedFieldPaths(proposal.getChangedFieldPaths())
                .originalValues(originalValues)
                .fieldEvidence(proposal.getFieldEvidence())
                .sourceDocumentIds(proposal.getSourceDocumentIds())
                .extractionId(proposal.getExtractionId())
                .status(proposal.getStatus())
                .submittedBy(proposal.getSubmittedBy())
                .reviewedBy(proposal.getReviewedBy())
                .reviewedByName(reviewerName)
                .reviewComment(proposal.getReviewComment())
                .changeSummary(proposal.getChangeSummary())
                .createdAt(proposal.getCreatedAt())
                .updatedAt(proposal.getUpdatedAt())
                .build();
    }

    private UserDetailsImpl getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl) {
            return (UserDetailsImpl) auth.getPrincipal();
        }
        return null;
    }

    private boolean hasRole(UserDetailsImpl user, SystemRole role) {
        String roleName = "ROLE_" + role.name();
        return user.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(roleName));
    }
}

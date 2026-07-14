package com.apms.domain.contract.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.RelationshipType;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.contract.dto.*;
import com.apms.domain.contract.entity.PartnerContract;
import com.apms.domain.contract.entity.PartnerContractVersion;
import com.apms.domain.contract.enums.ContractLifecycleStatus;
import com.apms.domain.contract.enums.ContractReviewStatus;
import com.apms.domain.contract.repository.sql.PartnerContractRepository;
import com.apms.domain.contract.repository.sql.PartnerContractVersionRepository;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Currency;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PartnerContractService {

    private final PartnerContractRepository contractRepository;
    private final PartnerContractVersionRepository versionRepository;
    private final ProjectRepository projectRepository;
    private final ProjectTaskRepository taskRepository;
    private final RawDocumentRepository documentRepository;
    private final OwnerOrganizationService ownerOrganizationService;
    private final AuditLogService auditService;

    @Transactional
    public PartnerContractResponse createDraft(Long projectId, CreatePartnerContractRequest request, Long accountId) {
        validateProjectAccess(projectId, accountId, true);
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessValidationException("Project not found."));

        if (project.getTargetRelationshipType() != RelationshipType.PARTNER_WITH) {
            throw new BusinessValidationException("Project scope must resolve to PARTNER_WITH.");
        }

        String partnerCompanyId = project.getTargetCompanyProfileId();
        if (!StringUtils.hasText(partnerCompanyId)) {
            throw new BusinessValidationException("Project target company is missing.");
        }

        String referenceCompanyId = ownerOrganizationService.getOwnerCompanyId();
        if (referenceCompanyId.equals(partnerCompanyId)) {
            throw new BusinessValidationException("Reference and partner company cannot be the same.");
        }

        if (request.getSourceTaskId() != null) {
            ProjectTask task = taskRepository.findById(request.getSourceTaskId())
                    .orElseThrow(() -> new BusinessValidationException("Source task not found."));
            if (!task.getProject().getId().equals(projectId)) {
                throw new BusinessValidationException("Task does not belong to the project.");
            }
        }

        if (StringUtils.hasText(request.getRawDocumentId())) {
            RawDocument doc = documentRepository.findById(request.getRawDocumentId())
                    .orElseThrow(() -> new BusinessValidationException("RawDocument not found."));
            if (Boolean.TRUE.equals(doc.getIsHidden())) {
                throw new BusinessValidationException("RawDocument is unavailable.");
            }
            if (!projectId.toString().equals(doc.getProjectId())) {
                throw new BusinessValidationException("RawDocument does not belong to the project.");
            }
            if (StringUtils.hasText(doc.getTaskId()) && request.getSourceTaskId() != null) {
                if (!request.getSourceTaskId().toString().equals(doc.getTaskId())) {
                    throw new BusinessValidationException("RawDocument does not belong to the specified task.");
                }
            }
        }

        validateDates(request.getSignedDate(), request.getEffectiveDate(), request.getExpiryDate());
        validateCurrency(request.getCurrency());
        if (request.getTotalContractValue() != null && request.getTotalContractValue().signum() < 0) {
            throw new BusinessValidationException("Contract value cannot be negative.");
        }

        PartnerContract contract = PartnerContract.builder()
                .referenceCompanyId(referenceCompanyId)
                .partnerCompanyId(partnerCompanyId)
                .sourceProjectId(projectId)
                .sourceTaskId(request.getSourceTaskId())
                .rawDocumentId(request.getRawDocumentId())
                .contractNumber(request.getContractNumber())
                .contractTitle(request.getContractTitle())
                .contractType(request.getContractType())
                .reviewStatus(ContractReviewStatus.DRAFT)
                .signedDate(request.getSignedDate())
                .effectiveDate(request.getEffectiveDate())
                .expiryDate(request.getExpiryDate())
                .currency(request.getCurrency() != null ? request.getCurrency().toUpperCase() : null)
                .totalContractValue(request.getTotalContractValue())
                .createdByAccountId(accountId)
                .build();

        contract = contractRepository.save(contract);
        auditService.log(accountId, AuditAction.PARTNER_CONTRACT_CREATED, "PartnerContract", contract.getId().toString(), "Draft created");

        return PartnerContractMapper.toResponse(contract);
    }

    @Transactional(readOnly = true)
    public PartnerContractResponse getContract(Long contractId, Long accountId) {
        PartnerContract contract = getContractEntity(contractId);
        validateProjectAccess(contract.getSourceProjectId(), accountId, false);
        return PartnerContractMapper.toResponse(contract);
    }

    @Transactional
    public PartnerContractResponse updateContract(Long contractId, UpdatePartnerContractRequest request, Long accountId) {
        PartnerContract contract = getContractEntity(contractId);
        validateProjectAccess(contract.getSourceProjectId(), accountId, true);

        if (contract.getReviewStatus() != ContractReviewStatus.DRAFT && contract.getReviewStatus() != ContractReviewStatus.CHANGES_REQUESTED) {
            throw new BusinessValidationException("Only DRAFT or CHANGES_REQUESTED contracts can be edited.");
        }

        if (StringUtils.hasText(request.getRawDocumentId())) {
            RawDocument doc = documentRepository.findById(request.getRawDocumentId())
                    .orElseThrow(() -> new BusinessValidationException("RawDocument not found."));
            if (Boolean.TRUE.equals(doc.getIsHidden())) {
                throw new BusinessValidationException("RawDocument is unavailable.");
            }
            if (!contract.getSourceProjectId().toString().equals(doc.getProjectId())) {
                throw new BusinessValidationException("RawDocument does not belong to the project.");
            }
            if (StringUtils.hasText(doc.getTaskId()) && contract.getSourceTaskId() != null) {
                if (!contract.getSourceTaskId().toString().equals(doc.getTaskId())) {
                    throw new BusinessValidationException("RawDocument does not belong to the specified task.");
                }
            }
        }

        validateDates(request.getSignedDate(), request.getEffectiveDate(), request.getExpiryDate());
        validateCurrency(request.getCurrency());
        if (request.getTotalContractValue() != null && request.getTotalContractValue().signum() < 0) {
            throw new BusinessValidationException("Contract value cannot be negative.");
        }

        contract.setRawDocumentId(request.getRawDocumentId());
        contract.setContractNumber(request.getContractNumber());
        contract.setContractTitle(request.getContractTitle());
        contract.setContractType(request.getContractType());
        contract.setSignedDate(request.getSignedDate());
        contract.setEffectiveDate(request.getEffectiveDate());
        contract.setExpiryDate(request.getExpiryDate());
        contract.setCurrency(request.getCurrency() != null ? request.getCurrency().toUpperCase() : null);
        contract.setTotalContractValue(request.getTotalContractValue());
        contract.setUpdatedByAccountId(accountId);

        contract = contractRepository.save(contract);
        auditService.log(accountId, AuditAction.PARTNER_CONTRACT_UPDATED, "PartnerContract", contract.getId().toString(), "Contract details updated");

        return PartnerContractMapper.toResponse(contract);
    }

    @Transactional
    public PartnerContractResponse submitForReview(Long contractId, Long accountId) {
        PartnerContract contract = getContractEntity(contractId);
        validateProjectAccess(contract.getSourceProjectId(), accountId, true);

        if (contract.getReviewStatus() != ContractReviewStatus.DRAFT && contract.getReviewStatus() != ContractReviewStatus.CHANGES_REQUESTED) {
            throw new BusinessValidationException("Only DRAFT or CHANGES_REQUESTED contracts can be submitted.");
        }

        // Submit-readiness validation
        if (!StringUtils.hasText(contract.getContractNumber())) {
            throw new BusinessValidationException("Contract number is required for submission.");
        }
        if (contract.getEffectiveDate() == null) {
            throw new BusinessValidationException("Effective date is required for submission.");
        }

        contract.setReviewStatus(ContractReviewStatus.IN_REVIEW);
        contract.setUpdatedByAccountId(accountId);

        contract = contractRepository.save(contract);
        auditService.log(accountId, AuditAction.PARTNER_CONTRACT_SUBMITTED, "PartnerContract", contract.getId().toString(), "Submitted for review");

        return PartnerContractMapper.toResponse(contract);
    }

    @Transactional
    public void reviewContract(Long contractId, ReviewPartnerContractRequest request, Long accountId) {
        PartnerContract contract = getContractEntity(contractId);
        validateProjectAccess(contract.getSourceProjectId(), accountId, true);

        if (contract.getReviewStatus() == ContractReviewStatus.APPROVED) {
            // Idempotency: if already approved, ignore
            return;
        }

        if (contract.getReviewStatus() != ContractReviewStatus.IN_REVIEW) {
            throw new BusinessValidationException("Contract must be IN_REVIEW to be reviewed.");
        }

        if ("APPROVE".equalsIgnoreCase(request.getDecision())) {
            contract.setReviewStatus(ContractReviewStatus.APPROVED);
            contract.setApprovedByAccountId(accountId);
            contract.setApprovedAt(LocalDateTime.now());
            contract.setCurrentVersion(contract.getCurrentVersion() + 1);
            contract = contractRepository.save(contract);

            PartnerContractVersion version = PartnerContractVersion.builder()
                    .contractId(contract.getId())
                    .referenceCompanyId(contract.getReferenceCompanyId())
                    .partnerCompanyId(contract.getPartnerCompanyId())
                    .sourceProjectId(contract.getSourceProjectId())
                    .sourceTaskId(contract.getSourceTaskId())
                    .rawDocumentId(contract.getRawDocumentId())
                    .contractNumber(contract.getContractNumber())
                    .contractTitle(contract.getContractTitle())
                    .contractType(contract.getContractType())
                    .reviewStatus(contract.getReviewStatus())
                    .lifecycleStatus(contract.getLifecycleStatus())
                    .signedDate(contract.getSignedDate())
                    .effectiveDate(contract.getEffectiveDate())
                    .expiryDate(contract.getExpiryDate())
                    .currency(contract.getCurrency())
                    .totalContractValue(contract.getTotalContractValue())
                    .createdByAccountId(contract.getCreatedByAccountId())
                    .createdAt(contract.getCreatedAt())
                    .updatedByAccountId(contract.getUpdatedByAccountId())
                    .updatedAt(contract.getUpdatedAt())
                    .approvedByAccountId(contract.getApprovedByAccountId())
                    .approvedAt(contract.getApprovedAt())
                    .version(contract.getCurrentVersion())
                    .build();

            versionRepository.save(version);
            auditService.log(accountId, AuditAction.PARTNER_CONTRACT_APPROVED, "PartnerContract", contract.getId().toString(), "Contract approved, version " + contract.getCurrentVersion() + " created");

        } else if ("REQUEST_CHANGES".equalsIgnoreCase(request.getDecision())) {
            if (!StringUtils.hasText(request.getComment())) {
                throw new BusinessValidationException("Comment is required when requesting changes.");
            }
            contract.setReviewStatus(ContractReviewStatus.CHANGES_REQUESTED);
            contract.setUpdatedByAccountId(accountId);
            contractRepository.save(contract);
            auditService.log(accountId, AuditAction.PARTNER_CONTRACT_CHANGES_REQUESTED, "PartnerContract", contract.getId().toString(), "Changes requested: " + request.getComment());
        } else {
            throw new BusinessValidationException("Invalid decision: " + request.getDecision());
        }
    }

    @Transactional
    public PartnerContractResponse updateLifecycle(Long contractId, UpdateContractLifecycleRequest request, Long accountId) {
        PartnerContract contract = getContractEntity(contractId);
        validateProjectAccess(contract.getSourceProjectId(), accountId, true);

        if (request.getLifecycleStatus() == null) {
            throw new BusinessValidationException("Lifecycle status is required.");
        }

        validateLifecycleTransition(contract.getLifecycleStatus(), request.getLifecycleStatus());

        contract.setLifecycleStatus(request.getLifecycleStatus());
        contract.setUpdatedByAccountId(accountId);
        contract = contractRepository.save(contract);
        auditService.log(accountId, AuditAction.PARTNER_CONTRACT_LIFECYCLE_CHANGED, "PartnerContract", contract.getId().toString(), "Lifecycle changed to " + request.getLifecycleStatus());

        return PartnerContractMapper.toResponse(contract);
    }

    @Transactional
    public PartnerContractResponse startRevision(Long contractId, Long accountId) {
        PartnerContract contract = getContractEntity(contractId);
        validateProjectAccess(contract.getSourceProjectId(), accountId, true);

        if (contract.getReviewStatus() != ContractReviewStatus.APPROVED) {
            throw new BusinessValidationException("Only APPROVED contracts can start a new revision.");
        }

        contract.setReviewStatus(ContractReviewStatus.DRAFT);
        contract.setUpdatedByAccountId(accountId);
        contract = contractRepository.save(contract);
        auditService.log(accountId, AuditAction.PARTNER_CONTRACT_REVISION_STARTED, "PartnerContract", contract.getId().toString(), "New revision started");

        return PartnerContractMapper.toResponse(contract);
    }

    @Transactional(readOnly = true)
    public List<PartnerContractVersionResponse> getVersions(Long contractId, Long accountId) {
        PartnerContract contract = getContractEntity(contractId);
        validateProjectAccess(contract.getSourceProjectId(), accountId, false);
        return versionRepository.findByContractIdOrderByVersionDesc(contractId)
                .stream()
                .map(PartnerContractMapper::toVersionResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PartnerContractVersionResponse getVersion(Long contractId, Integer versionNum, Long accountId) {
        PartnerContract contract = getContractEntity(contractId);
        validateProjectAccess(contract.getSourceProjectId(), accountId, false);
        return versionRepository.findByContractIdOrderByVersionDesc(contractId)
                .stream()
                .filter(v -> v.getVersion().equals(versionNum))
                .findFirst()
                .map(PartnerContractMapper::toVersionResponse)
                .orElseThrow(() -> new BusinessValidationException("Version not found."));
    }

    private PartnerContract getContractEntity(Long contractId) {
        return contractRepository.findById(contractId)
                .orElseThrow(() -> new BusinessValidationException("PartnerContract not found."));
    }

    private void validateProjectAccess(Long projectId, Long accountId, boolean isWrite) {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.apms.security.UserDetailsImpl) {
            com.apms.security.UserDetailsImpl user = (com.apms.security.UserDetailsImpl) auth.getPrincipal();
            boolean isAdmin = user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN"));
            if (isAdmin) return;

            if (!isWrite) {
                boolean isOwner = user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_BUSINESS_OWNER"));
                if (isOwner) return;
            }
        }

        if (!projectRepository.existsByIdAndMembersAccountId(projectId, accountId)) {
            throw new org.springframework.security.access.AccessDeniedException("Must be a project member to access this contract.");
        }
    }

    private void validateDates(java.time.LocalDate signedDate, java.time.LocalDate effectiveDate, java.time.LocalDate expiryDate) {
        if (signedDate != null && effectiveDate != null && effectiveDate.isBefore(signedDate)) {
            throw new BusinessValidationException("Effective date cannot be before signed date.");
        }
        if (effectiveDate != null && expiryDate != null && !expiryDate.isAfter(effectiveDate)) {
            throw new BusinessValidationException("Expiry date cannot be before or equal to effective date.");
        }
    }

    private void validateCurrency(String currencyStr) {
        if (!StringUtils.hasText(currencyStr)) return;
        try {
            Currency.getInstance(currencyStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessValidationException("Invalid currency code: " + currencyStr);
        }
    }

    private void validateLifecycleTransition(ContractLifecycleStatus current, ContractLifecycleStatus next) {
        if (current == next) return;

        if (current == null) {
            if (next != ContractLifecycleStatus.PENDING_EFFECTIVE && next != ContractLifecycleStatus.ACTIVE) {
                throw new BusinessValidationException("Initial lifecycle status must be PENDING_EFFECTIVE or ACTIVE.");
            }
            return;
        }

        switch (current) {
            case PENDING_EFFECTIVE:
                if (next != ContractLifecycleStatus.ACTIVE && next != ContractLifecycleStatus.TERMINATED) {
                    throw new BusinessValidationException("PENDING_EFFECTIVE can only transition to ACTIVE or TERMINATED.");
                }
                break;
            case ACTIVE:
                if (next != ContractLifecycleStatus.EXPIRED && next != ContractLifecycleStatus.TERMINATED) {
                    throw new BusinessValidationException("ACTIVE can only transition to EXPIRED or TERMINATED.");
                }
                break;
            case EXPIRED:
                throw new BusinessValidationException("EXPIRED contract cannot change lifecycle status.");
            case TERMINATED:
                throw new BusinessValidationException("TERMINATED contract cannot change lifecycle status.");
        }
    }
}

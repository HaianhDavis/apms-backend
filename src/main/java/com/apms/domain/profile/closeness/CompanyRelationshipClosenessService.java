package com.apms.domain.profile.closeness;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.SystemRole;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.closeness.dto.RelationshipClosenessResponse;
import com.apms.domain.profile.closeness.dto.UpdateRelationshipClosenessRequest;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.security.UserDetailsImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class CompanyRelationshipClosenessService {

    private final CompanyRelationshipClosenessRepository closenessRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final OwnerOrganizationService ownerOrganizationService;
    private final ProjectRepository projectRepository;
    private final AuditLogService auditLogService;
    private final TransactionTemplate sqlTransactionTemplate;

    public CompanyRelationshipClosenessService(
            CompanyRelationshipClosenessRepository closenessRepository,
            CompanyProfileRepository companyProfileRepository,
            OwnerOrganizationService ownerOrganizationService,
            ProjectRepository projectRepository,
            AuditLogService auditLogService,
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager) {
        this.closenessRepository = closenessRepository;
        this.companyProfileRepository = companyProfileRepository;
        this.ownerOrganizationService = ownerOrganizationService;
        this.projectRepository = projectRepository;
        this.auditLogService = auditLogService;
        this.sqlTransactionTemplate = new TransactionTemplate(transactionManager);
        this.sqlTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static class ClosenessUpsertResult {
        final CompanyRelationshipCloseness entity;
        final Integer previousStars;
        final boolean created;

        ClosenessUpsertResult(CompanyRelationshipCloseness entity, Integer previousStars, boolean created) {
            this.entity = entity;
            this.previousStars = previousStars;
            this.created = created;
        }
    }

    @Transactional(readOnly = true)
    public RelationshipClosenessResponse getCloseness(String targetCompanyProfileId, UserDetailsImpl currentUser) {
        validateTargetProfile(targetCompanyProfileId);
        validateAccess(targetCompanyProfileId, currentUser, false, false);

        String ownerId = ownerOrganizationService.getOwnerCompanyProfileId();
        return closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileId(ownerId, targetCompanyProfileId)
                .map(this::toResponse)
                .orElseGet(() -> unratedResponse(targetCompanyProfileId));
    }

    public RelationshipClosenessResponse updateCloseness(String targetCompanyProfileId, UpdateRelationshipClosenessRequest request, UserDetailsImpl currentUser) {
        validateTargetProfile(targetCompanyProfileId);
        validateAccess(targetCompanyProfileId, currentUser, true, false);

        String ownerId = ownerOrganizationService.getOwnerCompanyProfileId();
        
        int maxRetries = 5;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            java.util.concurrent.atomic.AtomicBoolean wasCreate = new java.util.concurrent.atomic.AtomicBoolean(false);
            try {
                ClosenessUpsertResult result = sqlTransactionTemplate.execute(status -> {
                    Optional<CompanyRelationshipCloseness> existingOpt = closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileId(ownerId, targetCompanyProfileId);
                    
                    if (existingOpt.isPresent()) {
                        return applyUpdate(existingOpt.get(), request, currentUser.getId());
                    } else {
                        wasCreate.set(true);
                        CompanyRelationshipCloseness newEntity = new CompanyRelationshipCloseness();
                        newEntity.setOwnerCompanyProfileId(ownerId);
                        newEntity.setTargetCompanyProfileId(targetCompanyProfileId);
                        return applyCreate(newEntity, request, currentUser.getId());
                    }
                });
                
                AuditAction action = result.created ? AuditAction.RELATIONSHIP_CLOSENESS_CREATED : AuditAction.RELATIONSHIP_CLOSENESS_UPDATED;
                String detail = String.format("Target: %s, Previous Stars: %s, New Stars: %d", targetCompanyProfileId, result.previousStars != null ? result.previousStars.toString() : "none", request.getStars());
                auditLogService.log(currentUser.getId(), action, "CompanyRelationshipCloseness", String.valueOf(result.entity.getId()), detail);

                return toResponse(result.entity);
            } catch (DataIntegrityViolationException e) {
                boolean rowExists = Boolean.TRUE.equals(sqlTransactionTemplate.execute(status -> 
                        closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileId(ownerId, targetCompanyProfileId).isPresent()));
                
                if (!wasCreate.get() || !rowExists || attempt == maxRetries) {
                    throw e;
                }
                log.warn("Concurrent insert detected for owner-target pair: {}-{}, retrying... (attempt {})", ownerId, targetCompanyProfileId, attempt);
            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                if (attempt == maxRetries) {
                    throw e;
                }
                log.warn("Concurrent update detected for owner-target pair: {}-{}, retrying... (attempt {})", ownerId, targetCompanyProfileId, attempt);
            }
        }
        throw new IllegalStateException("Should not reach here");
    }

    private ClosenessUpsertResult applyCreate(CompanyRelationshipCloseness entity, UpdateRelationshipClosenessRequest request, Long accountId) {
        entity.setStars(request.getStars());
        if (request.getNote() != null) {
            String trimmed = request.getNote().trim();
            if (trimmed.length() > 1000) {
                throw new BusinessValidationException("Note cannot exceed 1000 characters");
            }
            entity.setNote(trimmed);
        } else {
            entity.setNote(null);
        }
        entity.setRatedByAccountId(accountId);
        entity = closenessRepository.saveAndFlush(entity);
        return new ClosenessUpsertResult(entity, null, true);
    }

    private ClosenessUpsertResult applyUpdate(CompanyRelationshipCloseness entity, UpdateRelationshipClosenessRequest request, Long accountId) {
        Integer oldStars = entity.getStars();
        entity.setStars(request.getStars());
        if (request.getNote() != null) {
            String trimmed = request.getNote().trim();
            if (trimmed.length() > 1000) {
                throw new BusinessValidationException("Note cannot exceed 1000 characters");
            }
            entity.setNote(trimmed);
        } else {
            entity.setNote(null);
        }
        entity.setRatedByAccountId(accountId);
        entity = closenessRepository.saveAndFlush(entity);
        return new ClosenessUpsertResult(entity, oldStars, false);
    }

    @Transactional
    public void deleteCloseness(String targetCompanyProfileId, UserDetailsImpl currentUser) {
        validateTargetProfile(targetCompanyProfileId);
        validateAccess(targetCompanyProfileId, currentUser, false, true);

        String ownerId = ownerOrganizationService.getOwnerCompanyProfileId();
        Optional<CompanyRelationshipCloseness> opt = closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileId(ownerId, targetCompanyProfileId);
        
        if (opt.isPresent()) {
            CompanyRelationshipCloseness entity = opt.get();
            Integer oldStars = entity.getStars();
            Long id = entity.getId();
            
            closenessRepository.delete(entity);
            closenessRepository.flush();

            String detail = String.format("Target: %s, Previous Stars: %d, Cleared", targetCompanyProfileId, oldStars);
            auditLogService.log(currentUser.getId(), AuditAction.RELATIONSHIP_CLOSENESS_CLEARED, "CompanyRelationshipCloseness", String.valueOf(id), detail);
        }
    }

    private void validateTargetProfile(String targetCompanyProfileId) {
        if (ownerOrganizationService.isOwnerCompany(targetCompanyProfileId)) {
            throw new BusinessValidationException("Cannot rate closeness with the Owner Organization itself.");
        }

        CompanyProfile target = companyProfileRepository.findById(targetCompanyProfileId)
                .orElseThrow(() -> new ResourceNotFoundException("Target CompanyProfile not found: " + targetCompanyProfileId));

        if (Boolean.TRUE.equals(target.getIsHidden()) || Boolean.TRUE.equals(target.getIsDeleted())) {
            throw new BusinessValidationException("Target CompanyProfile is hidden or deleted.");
        }
    }

    private void validateAccess(String targetCompanyProfileId, UserDetailsImpl user, boolean isPut, boolean isDelete) {
        if (hasRole(user, SystemRole.SYSTEM_ADMIN)) {
            throw new org.springframework.security.access.AccessDeniedException("System Admin cannot access internal relationship closeness data.");
        }

        if (hasRole(user, SystemRole.BUSINESS_OWNER)) {
            return; // Owner can do everything
        }

        if (isDelete) {
            // Only BUSINESS_OWNER can delete (since Admin is rejected above)
            throw new org.springframework.security.access.AccessDeniedException("Only Business Owner can delete relationship closeness records.");
        }

        if (hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_MANAGER)) {
            // Can GET and PUT if in scope
            List<ProjectStatus> allowedStatuses = isPut 
                    ? List.of(ProjectStatus.DRAFT, ProjectStatus.ACTIVE)
                    : List.of(ProjectStatus.DRAFT, ProjectStatus.ACTIVE, ProjectStatus.COMPLETED);
                    
            if (!isInScope(targetCompanyProfileId, user.getId(), allowedStatuses)) {
                throw new org.springframework.security.access.AccessDeniedException("Target company is not within your project scope.");
            }
            return;
        }

        if (hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_STAFF)) {
            if (isPut) {
                throw new org.springframework.security.access.AccessDeniedException("Business Development Staff cannot update relationship closeness.");
            }
            // Can GET if in scope
            List<ProjectStatus> allowedStatuses = List.of(ProjectStatus.DRAFT, ProjectStatus.ACTIVE, ProjectStatus.COMPLETED);
            if (!isInScope(targetCompanyProfileId, user.getId(), allowedStatuses)) {
                throw new org.springframework.security.access.AccessDeniedException("Target company is not within your project scope.");
            }
            return;
        }

        throw new org.springframework.security.access.AccessDeniedException("You do not have permission to access relationship closeness.");
    }

    private boolean isInScope(String targetCompanyProfileId, Long accountId, List<ProjectStatus> allowedStatuses) {
        return projectRepository.existsByTargetCompanyProfileIdAndMembersAccountIdAndStatusIn(targetCompanyProfileId, accountId, allowedStatuses);
    }

    private boolean hasRole(UserDetailsImpl user, SystemRole role) {
        String roleName = "ROLE_" + role.name();
        return user.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(roleName));
    }

    private RelationshipClosenessResponse toResponse(CompanyRelationshipCloseness entity) {
        return RelationshipClosenessResponse.builder()
                .targetCompanyProfileId(entity.getTargetCompanyProfileId())
                .stars(entity.getStars())
                .label(RelationshipClosenessLevel.fromStars(entity.getStars()).name())
                .note(entity.getNote())
                .ratedByAccountId(entity.getRatedByAccountId())
                .ratedAt(entity.getRatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private RelationshipClosenessResponse unratedResponse(String targetCompanyProfileId) {
        return RelationshipClosenessResponse.builder()
                .targetCompanyProfileId(targetCompanyProfileId)
                .stars(null)
                .label("UNRATED")
                .note(null)
                .ratedByAccountId(null)
                .ratedAt(null)
                .updatedAt(null)
                .build();
    }
}

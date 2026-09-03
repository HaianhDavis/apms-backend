package com.apms.domain.monitoring.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.MonitoringFrequency;
import com.apms.common.enums.MonitoringReviewResult;
import com.apms.common.enums.MonitoringStatus;
import com.apms.common.enums.ProposalOrigin;
import com.apms.common.enums.SubmissionStatus;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.monitoring.dto.*;
import com.apms.domain.monitoring.model.CompanyMonitoringAssignment;
import com.apms.domain.monitoring.model.CompanyMonitoringReview;
import com.apms.domain.monitoring.repository.CompanyMonitoringAssignmentRepository;
import com.apms.domain.monitoring.repository.CompanyMonitoringReviewRepository;
import com.apms.domain.monitoring.repository.CompanyRelationshipChangeProposalRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.CompanyProfileUpdateProposal;
import com.apms.domain.profile.repository.mongo.CompanyProfileUpdateProposalRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.common.enums.SystemRole;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CompanyMonitoringService {

    private final CompanyMonitoringAssignmentRepository assignmentRepository;
    private final CompanyMonitoringReviewRepository reviewRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final AccountRepository accountRepository;
    private final CompanyProfileUpdateProposalRepository proposalRepository;
    private final CompanyRelationshipChangeProposalRepository relationshipChangeProposalRepository;
    private final AuditLogService auditLogService;

    @Transactional
    public CompanyMonitoringAssignmentResponse assignMonitor(CompanyMonitoringAssignmentRequest request, Long currentManagerId) {
        CompanyProfile companyProfile = companyProfileRepository.findById(request.getCompanyProfileId())
                .orElseThrow(() -> new IllegalArgumentException("Company Profile not found"));

        Account manager = accountRepository.findById(currentManagerId)
                .orElseThrow(() -> new IllegalArgumentException("Manager account not found"));

        enforceResponsibleManager(companyProfile, manager);

        Account staff = accountRepository.findById(request.getAssignedStaffId())
                .orElseThrow(() -> new IllegalArgumentException("Staff account not found"));

        if (!staff.getRoles().contains(SystemRole.BUSINESS_DEVELOPMENT_STAFF)) {
            throw new IllegalArgumentException("Assigned account must have BUSINESS_DEVELOPMENT_STAFF role");
        }

        CompanyMonitoringAssignment assignment = assignmentRepository.findByCompanyProfileId(request.getCompanyProfileId())
                .orElse(null);

        if (assignment != null) {
            // Update existing assignment
            assignment.setAssignedStaff(staff);
            assignment.setAssignedByManager(manager);
            assignment.setFrequency(request.getFrequency());
            assignment.setStatus(MonitoringStatus.ACTIVE);
            assignment.setNextReviewAt(calculateNextReviewAt(assignment.getLastReviewedAt(), request.getFrequency()));
            assignment = assignmentRepository.save(assignment);
            auditLogService.log(manager.getId(), AuditAction.MONITORING_REASSIGNED, "CompanyMonitoringAssignment", assignment.getId().toString(), "Reassigned monitoring for company: " + request.getCompanyProfileId());
        } else {
            // Create new assignment
            assignment = CompanyMonitoringAssignment.builder()
                    .companyProfileId(request.getCompanyProfileId())
                    .assignedStaff(staff)
                    .assignedByManager(manager)
                    .frequency(request.getFrequency())
                    .status(MonitoringStatus.ACTIVE)
                    .nextReviewAt(calculateNextReviewAt(null, request.getFrequency()))
                    .build();
            assignment = assignmentRepository.save(assignment);
            auditLogService.log(manager.getId(), AuditAction.MONITORING_ASSIGNED, "CompanyMonitoringAssignment", assignment.getId().toString(), "Assigned monitoring for company: " + request.getCompanyProfileId());
        }

        return mapToResponse(assignment, companyProfile);
    }

    @Transactional
    public CompanyMonitoringAssignmentResponse updateAssignment(Long assignmentId, CompanyMonitoringUpdateRequest request, Long currentManagerId) {
        CompanyMonitoringAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found"));

        Account manager = accountRepository.findById(currentManagerId)
                .orElseThrow(() -> new IllegalArgumentException("Manager account not found"));

        CompanyProfile companyProfile = companyProfileRepository.findById(assignment.getCompanyProfileId())
                .orElseThrow(() -> new IllegalArgumentException("Company Profile not found"));
                
        enforceResponsibleManager(companyProfile, manager);

        Account staff = accountRepository.findById(request.getAssignedStaffId())
                .orElseThrow(() -> new IllegalArgumentException("Staff account not found"));

        if (!staff.getRoles().contains(SystemRole.BUSINESS_DEVELOPMENT_STAFF)) {
            throw new IllegalArgumentException("Assigned account must have BUSINESS_DEVELOPMENT_STAFF role");
        }

        assignment.setAssignedStaff(staff);
        assignment.setFrequency(request.getFrequency());
        assignment.setNextReviewAt(calculateNextReviewAt(assignment.getLastReviewedAt(), request.getFrequency()));

        assignment = assignmentRepository.save(assignment);

        auditLogService.log(manager.getId(), AuditAction.MONITORING_FREQUENCY_CHANGED, "CompanyMonitoringAssignment", assignment.getId().toString(), "Updated assignment for company: " + assignment.getCompanyProfileId());

        return mapToResponse(assignment, companyProfile);
    }

    @Transactional
    public CompanyMonitoringAssignmentResponse updateStatus(Long assignmentId, MonitoringStatus status, Long currentManagerId) {
        CompanyMonitoringAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found"));

        Account manager = accountRepository.findById(currentManagerId)
                .orElseThrow(() -> new IllegalArgumentException("Manager account not found"));

        CompanyProfile companyProfile = companyProfileRepository.findById(assignment.getCompanyProfileId())
                .orElseThrow(() -> new IllegalArgumentException("Company Profile not found"));
                
        enforceResponsibleManager(companyProfile, manager);

        assignment.setStatus(status);
        assignment = assignmentRepository.save(assignment);

        AuditAction action = status == MonitoringStatus.ACTIVE ? AuditAction.MONITORING_RESUMED : AuditAction.MONITORING_PAUSED;
        auditLogService.log(manager.getId(), action, "CompanyMonitoringAssignment", assignment.getId().toString(), "Status changed to " + status + " for assignment " + assignmentId);

        return mapToResponse(assignment, companyProfile);
    }

    @Transactional
    public CompanyMonitoringReviewResponse submitReview(Long assignmentId, CompanyMonitoringReviewRequest request, Long currentStaffId) {
        CompanyMonitoringAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found"));

        if (!assignment.getAssignedStaff().getId().equals(currentStaffId)) {
            throw new IllegalArgumentException("You are not assigned to this monitoring");
        }

        Account staff = accountRepository.findById(currentStaffId)
                .orElseThrow(() -> new IllegalArgumentException("Staff account not found"));

        LocalDateTime now = LocalDateTime.now();
        String submittedUpdateProposalStatus = null;

        if (request.getResult() == MonitoringReviewResult.UPDATE_PROPOSED) {
            if (request.getUpdateProposalId() == null) {
                throw new IllegalArgumentException("Update proposal ID is required for UPDATE_PROPOSED result");
            }

            CompanyProfileUpdateProposal proposal = proposalRepository.findById(request.getUpdateProposalId())
                    .orElseThrow(() -> new IllegalArgumentException("Update proposal not found"));

            if (!proposal.getCompanyProfileId().equals(assignment.getCompanyProfileId())) {
                throw new IllegalArgumentException("Update proposal does not target the assigned company");
            }

            if (proposal.getOrigin() != ProposalOrigin.MONITORING) {
                throw new IllegalArgumentException("Update proposal must come from company monitoring");
            }

            List<CompanyProfileUpdateProposal> existingSubmitted = proposalRepository.findByCompanyProfileIdAndStatusIn(
                    assignment.getCompanyProfileId(),
                    List.of(SubmissionStatus.SUBMITTED, SubmissionStatus.IN_REVIEW)
            );
            boolean hasOtherSubmitted = existingSubmitted.stream()
                    .anyMatch(p -> p.getOrigin() == ProposalOrigin.MONITORING && !p.getId().equals(request.getUpdateProposalId()));
            if (hasOtherSubmitted) {
                throw new com.apms.common.exception.BusinessConflictException("A monitoring proposal is already awaiting manager review. Cancel the current proposal before submitting a new one.");
            }

            proposal.setStatus(SubmissionStatus.SUBMITTED);
            proposal.setLastSubmittedAt(now);
            proposal.setLastSubmittedByAccountId(currentStaffId);
            if (proposal.getSubmittedBy() == null) {
                proposal.setSubmittedBy(currentStaffId);
            }
            proposalRepository.save(proposal);
            submittedUpdateProposalStatus = proposal.getStatus().name();

            // Note: Official profile remains unchanged here
        }

        CompanyMonitoringReview review = CompanyMonitoringReview.builder()
                .assignment(assignment)
                .companyProfileId(assignment.getCompanyProfileId())
                .reviewedBy(staff)
                .reviewedAt(now)
                .result(request.getResult())
                .updateProposalId(request.getUpdateProposalId())
                .note(request.getNote())
                .build();

        review = reviewRepository.save(review);

        assignment.setLastReviewedAt(now);
        assignment.setNextReviewAt(calculateNextReviewAt(now, assignment.getFrequency()));
        assignmentRepository.save(assignment);

        AuditAction action = request.getResult() == MonitoringReviewResult.NO_CHANGE ? AuditAction.MONITORING_REVIEW_COMPLETED : AuditAction.MONITORING_UPDATE_PROPOSED;
        auditLogService.log(staff.getId(), action, "CompanyMonitoringAssignment", assignment.getId().toString(), "Submitted review for assignment " + assignmentId);

        CompanyProfile companyProfile = companyProfileRepository.findById(review.getCompanyProfileId()).orElse(null);
        Map<String, CompanyProfile> profilesByKey = companyProfile != null ? profileKeyMap(List.of(companyProfile)) : Map.of();
        Map<String, String> updateProposalStatuses = StringUtils.hasText(review.getUpdateProposalId()) && submittedUpdateProposalStatus != null
                ? Map.of(review.getUpdateProposalId(), submittedUpdateProposalStatus)
                : loadUpdateProposalStatuses(List.of(review));
        Map<Long, String> relationshipProposalStatuses = loadRelationshipProposalStatuses(List.of(review));
        return mapReviewToResponse(review, profilesByKey, updateProposalStatuses, relationshipProposalStatuses);
    }

    @Transactional(readOnly = true)
    public Page<CompanyMonitoringReviewResponse> getMonitoringHistory(Long currentUserId, Pageable pageable) {
        Account currentUser = accountRepository.findById(currentUserId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        Pageable effectivePageable = newestReviewsFirst(pageable);

        Page<CompanyMonitoringReview> reviews;
        if (currentUser.getRoles().contains(SystemRole.SYSTEM_ADMIN)) {
            reviews = reviewRepository.findAll(effectivePageable);
        } else if (currentUser.getRoles().contains(SystemRole.BUSINESS_DEVELOPMENT_STAFF)) {
            reviews = reviewRepository.findByReviewedById(currentUserId, effectivePageable);
        } else {
            List<CompanyProfile> managedProfiles = companyProfileRepository.findByResponsibleManagerId(currentUserId);
            Set<String> managedCompanyKeys = managedProfiles.stream()
                    .flatMap(profile -> java.util.stream.Stream.of(profile.getId(), profile.getCompanyId()))
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toSet());

            if (managedCompanyKeys.isEmpty()) {
                return Page.empty(effectivePageable);
            }

            reviews = reviewRepository.findByCompanyProfileIdIn(managedCompanyKeys, effectivePageable);
        }

        Map<String, CompanyProfile> profilesByKey = loadProfilesByReviewCompanyKeys(reviews.getContent());
        Map<String, String> updateProposalStatuses = loadUpdateProposalStatuses(reviews.getContent());
        Map<Long, String> relationshipProposalStatuses = loadRelationshipProposalStatuses(reviews.getContent());

        return reviews.map(review -> mapReviewToResponse(
                review,
                profilesByKey,
                updateProposalStatuses,
                relationshipProposalStatuses
        ));
    }

    @Transactional(readOnly = true)
    public CompanyMonitoringAssignmentResponse getAssignment(Long id) {
        CompanyMonitoringAssignment assignment = assignmentRepository.findById(id)
                .orElseThrow(() -> new com.apms.common.exception.ResourceNotFoundException("Assignment not found"));
        CompanyProfile companyProfile = companyProfileRepository.findById(assignment.getCompanyProfileId()).orElse(null);
        return mapToResponse(assignment, companyProfile);
    }

    @Transactional(readOnly = true)
    public Optional<CompanyMonitoringAssignmentResponse> getAssignmentByCompany(String companyProfileId) {
        return assignmentRepository.findByCompanyProfileId(companyProfileId)
                .map(assignment -> {
                    CompanyProfile companyProfile = companyProfileRepository.findById(assignment.getCompanyProfileId()).orElse(null);
                    return mapToResponse(assignment, companyProfile);
                });
    }

    @Transactional(readOnly = true)
    public Page<CompanyMonitoringAssignmentResponse> getMyAssignments(Long staffId, Pageable pageable) {
        return assignmentRepository.findByAssignedStaffId(staffId, pageable)
                .map(assignment -> {
                    CompanyProfile companyProfile = companyProfileRepository.findById(assignment.getCompanyProfileId()).orElse(null);
                    return mapToResponse(assignment, companyProfile);
                });
    }

    @Transactional(readOnly = true)
    public Page<CompanyMonitoringAssignmentResponse> getAllAssignments(Pageable pageable) {
        return assignmentRepository.findAll(pageable)
                .map(assignment -> {
                    CompanyProfile companyProfile = companyProfileRepository.findById(assignment.getCompanyProfileId()).orElse(null);
                    return mapToResponse(assignment, companyProfile);
                });
    }

    @Transactional(readOnly = true)
    public Page<CompanyMonitoringAssignmentResponse> getDueOrOverdueAssignments(Pageable pageable) {
        LocalDateTime now = LocalDateTime.now();
        return assignmentRepository.findDueOrOverdueActiveAssignments(now, pageable)
                .map(assignment -> {
                    CompanyProfile companyProfile = companyProfileRepository.findById(assignment.getCompanyProfileId()).orElse(null);
                    return mapToResponse(assignment, companyProfile);
                });
    }

    public LocalDateTime calculateNextReviewAt(LocalDateTime baseTime, MonitoringFrequency frequency) {
        LocalDateTime time = baseTime != null ? baseTime : LocalDateTime.now();
        return switch (frequency) {
            case MONTHLY -> time.plusMonths(1);
            case QUARTERLY -> time.plusMonths(3);
            case SEMI_ANNUALLY -> time.plusMonths(6);
        };
    }

    private CompanyMonitoringAssignmentResponse mapToResponse(CompanyMonitoringAssignment assignment, CompanyProfile companyProfile) {
        String companyName = companyProfile != null && companyProfile.getIdentity() != null ?
                (companyProfile.getIdentity().getTradeName() != null ? companyProfile.getIdentity().getTradeName() : companyProfile.getIdentity().getLegalName())
                : "Unknown Company";

        String displayStatus = calculateDisplayStatus(assignment);
        
        String latestReviewResult = null;
        String latestProposalStatus = null;
        String latestProposalId = null;

        java.util.Optional<CompanyMonitoringReview> latestReviewOpt = reviewRepository.findTopByAssignmentIdOrderByReviewedAtDesc(assignment.getId());
        
        if (latestReviewOpt.isPresent()) {
            CompanyMonitoringReview latestReview = latestReviewOpt.get();
            latestReviewResult = latestReview.getResult() != null ? latestReview.getResult().name() : null;
            
            if (latestReview.getUpdateProposalId() != null) {
                latestProposalId = latestReview.getUpdateProposalId();
                java.util.Optional<CompanyProfileUpdateProposal> proposalOpt = proposalRepository.findById(latestProposalId);
                if (proposalOpt.isPresent()) {
                    latestProposalStatus = proposalOpt.get().getStatus().name();
                }
            }
        }

        return CompanyMonitoringAssignmentResponse.builder()
                .id(assignment.getId())
                .companyProfileId(assignment.getCompanyProfileId())
                .companyName(companyName)
                .assignedStaffId(assignment.getAssignedStaff().getId())
                .assignedStaffName(assignment.getAssignedStaff().getEmail()) // assuming email
                .assignedStaffEmail(assignment.getAssignedStaff().getEmail())
                .assignedByManagerId(assignment.getAssignedByManager().getId())
                .frequency(assignment.getFrequency())
                .assignmentStatus(assignment.getStatus())
                .displayStatus(displayStatus)
                .latestReviewResult(latestReviewResult)
                .latestProposalStatus(latestProposalStatus)
                .latestProposalId(latestProposalId)
                .lastReviewedAt(assignment.getLastReviewedAt())
                .nextReviewAt(assignment.getNextReviewAt())
                .createdAt(assignment.getCreatedAt())
                .updatedAt(assignment.getUpdatedAt())
                .build();
    }

    private CompanyMonitoringReviewResponse mapReviewToResponse(
            CompanyMonitoringReview review,
            Map<String, CompanyProfile> profilesByKey,
            Map<String, String> updateProposalStatuses,
            Map<Long, String> relationshipProposalStatuses) {
        CompanyProfile companyProfile = profilesByKey.get(review.getCompanyProfileId());
        String proposalStatus = null;
        if (StringUtils.hasText(review.getUpdateProposalId())) {
            proposalStatus = updateProposalStatuses.get(review.getUpdateProposalId());
        } else if (review.getRelationshipChangeProposalId() != null) {
            proposalStatus = relationshipProposalStatuses.get(review.getRelationshipChangeProposalId());
        }

        return CompanyMonitoringReviewResponse.builder()
                .id(review.getId())
                .monitoringAssignmentId(review.getAssignment().getId())
                .companyProfileId(review.getCompanyProfileId())
                .companyName(resolveCompanyName(companyProfile))
                .reviewedById(review.getReviewedBy().getId())
                .reviewedByName(review.getReviewedBy().getEmail())
                .reviewedByEmail(review.getReviewedBy().getEmail())
                .reviewedAt(review.getReviewedAt())
                .result(review.getResult())
                .updateProposalId(review.getUpdateProposalId())
                .relationshipChangeProposalId(review.getRelationshipChangeProposalId())
                .proposalStatus(proposalStatus)
                .note(review.getNote())
                .build();
    }

    private Pageable newestReviewsFirst(Pageable pageable) {
        Sort sort = Sort.by(Sort.Direction.DESC, "reviewedAt");
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, 20, sort);
        }
        if (pageable.getSort().isSorted()) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    private Map<String, CompanyProfile> loadProfilesByReviewCompanyKeys(List<CompanyMonitoringReview> reviews) {
        Set<String> companyKeys = reviews.stream()
                .map(CompanyMonitoringReview::getCompanyProfileId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        if (companyKeys.isEmpty()) {
            return Map.of();
        }

        Map<String, CompanyProfile> profilesByKey = new HashMap<>();
        profilesByKey.putAll(profileKeyMap(companyProfileRepository.findAllById(companyKeys)));

        Set<String> unresolvedKeys = companyKeys.stream()
                .filter(key -> !profilesByKey.containsKey(key))
                .collect(Collectors.toSet());
        if (!unresolvedKeys.isEmpty()) {
            profilesByKey.putAll(profileKeyMap(companyProfileRepository.findByCompanyIdIn(unresolvedKeys)));
        }

        return profilesByKey;
    }

    private Map<String, CompanyProfile> profileKeyMap(Collection<CompanyProfile> profiles) {
        Map<String, CompanyProfile> profilesByKey = new HashMap<>();
        for (CompanyProfile profile : profiles) {
            if (StringUtils.hasText(profile.getId())) {
                profilesByKey.put(profile.getId(), profile);
            }
            if (StringUtils.hasText(profile.getCompanyId())) {
                profilesByKey.put(profile.getCompanyId(), profile);
            }
        }
        return profilesByKey;
    }

    private Map<String, String> loadUpdateProposalStatuses(List<CompanyMonitoringReview> reviews) {
        List<String> proposalIds = reviews.stream()
                .map(CompanyMonitoringReview::getUpdateProposalId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (proposalIds.isEmpty()) {
            return Map.of();
        }

        return proposalRepository.findAllById(proposalIds).stream()
                .filter(proposal -> StringUtils.hasText(proposal.getId()))
                .filter(proposal -> proposal.getStatus() != null)
                .collect(Collectors.toMap(
                        CompanyProfileUpdateProposal::getId,
                        proposal -> proposal.getStatus().name(),
                        (left, right) -> left
                ));
    }

    private Map<Long, String> loadRelationshipProposalStatuses(List<CompanyMonitoringReview> reviews) {
        List<Long> proposalIds = reviews.stream()
                .map(CompanyMonitoringReview::getRelationshipChangeProposalId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (proposalIds.isEmpty()) {
            return Map.of();
        }

        return relationshipChangeProposalRepository.findAllById(proposalIds).stream()
                .filter(proposal -> proposal.getId() != null)
                .filter(proposal -> proposal.getStatus() != null)
                .collect(Collectors.toMap(
                        proposal -> proposal.getId(),
                        proposal -> proposal.getStatus().name(),
                        (left, right) -> left
                ));
    }

    private String resolveCompanyName(CompanyProfile companyProfile) {
        if (companyProfile != null && companyProfile.getIdentity() != null) {
            if (StringUtils.hasText(companyProfile.getIdentity().getTradeName())) {
                return companyProfile.getIdentity().getTradeName();
            }
            if (StringUtils.hasText(companyProfile.getIdentity().getLegalName())) {
                return companyProfile.getIdentity().getLegalName();
            }
        }
        if (companyProfile != null && StringUtils.hasText(companyProfile.getCompanyId())) {
            return companyProfile.getCompanyId();
        }
        return "Unknown Company";
    }

    String calculateDisplayStatus(CompanyMonitoringAssignment assignment) {
        if (assignment == null) {
            return "ON_SCHEDULE";
        }
        if (assignment.getStatus() == MonitoringStatus.PAUSED) {
            return "PAUSED";
        }
        if (assignment.getNextReviewAt() == null) {
            return "ON_SCHEDULE";
        }
        LocalDate today = LocalDate.now();
        LocalDate nextReviewDate = assignment.getNextReviewAt().toLocalDate();

        if (today.isAfter(nextReviewDate)) {
            return "OVERDUE";
        }
        if (today.isEqual(nextReviewDate)) {
            return "DUE";
        }
        return "ON_SCHEDULE";
    }

    public void enforceResponsibleManager(CompanyProfile profile, Account manager) {
        if (!manager.getRoles().contains(SystemRole.SYSTEM_ADMIN)) {
            if (profile.getResponsibleManagerId() == null || !manager.getId().equals(profile.getResponsibleManagerId())) {
                throw new org.springframework.security.access.AccessDeniedException("Only the responsible Manager or SYSTEM_ADMIN can manage this monitoring assignment");
            }
        }
    }
}

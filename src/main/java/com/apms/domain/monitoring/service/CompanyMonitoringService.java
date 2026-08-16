package com.apms.domain.monitoring.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.MonitoringFrequency;
import com.apms.common.enums.MonitoringReviewResult;
import com.apms.common.enums.MonitoringStatus;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.monitoring.dto.*;
import com.apms.domain.monitoring.model.CompanyMonitoringAssignment;
import com.apms.domain.monitoring.model.CompanyMonitoringReview;
import com.apms.domain.monitoring.repository.CompanyMonitoringAssignmentRepository;
import com.apms.domain.monitoring.repository.CompanyMonitoringReviewRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.CompanyProfileUpdateProposal;
import com.apms.domain.profile.repository.mongo.CompanyProfileUpdateProposalRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.common.enums.SystemRole;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CompanyMonitoringService {

    private final CompanyMonitoringAssignmentRepository assignmentRepository;
    private final CompanyMonitoringReviewRepository reviewRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final AccountRepository accountRepository;
    private final CompanyProfileUpdateProposalRepository proposalRepository;
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

        if (request.getResult() == MonitoringReviewResult.UPDATE_PROPOSED) {
            if (request.getUpdateProposalId() == null) {
                throw new IllegalArgumentException("Update proposal ID is required for UPDATE_PROPOSED result");
            }

            CompanyProfileUpdateProposal proposal = proposalRepository.findById(request.getUpdateProposalId())
                    .orElseThrow(() -> new IllegalArgumentException("Update proposal not found"));

            if (!proposal.getCompanyProfileId().equals(assignment.getCompanyProfileId())) {
                throw new IllegalArgumentException("Update proposal does not target the assigned company");
            }

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

        return CompanyMonitoringReviewResponse.builder()
                .id(review.getId())
                .monitoringAssignmentId(assignment.getId())
                .companyProfileId(review.getCompanyProfileId())
                .reviewedById(staff.getId())
                .reviewedByName(staff.getEmail()) // Assuming email acts as name for now
                .reviewedAt(review.getReviewedAt())
                .result(review.getResult())
                .updateProposalId(review.getUpdateProposalId())
                .note(review.getNote())
                .build();
    }

    @Transactional(readOnly = true)
    public CompanyMonitoringAssignmentResponse getAssignment(Long id) {
        CompanyMonitoringAssignment assignment = assignmentRepository.findById(id)
                .orElseThrow(() -> new com.apms.common.exception.ResourceNotFoundException("Assignment not found"));
        CompanyProfile companyProfile = companyProfileRepository.findById(assignment.getCompanyProfileId()).orElse(null);
        return mapToResponse(assignment, companyProfile);
    }

    @Transactional(readOnly = true)
    public CompanyMonitoringAssignmentResponse getAssignmentByCompany(String companyProfileId) {
        CompanyMonitoringAssignment assignment = assignmentRepository.findByCompanyProfileId(companyProfileId)
                .orElseThrow(() -> new com.apms.common.exception.ResourceNotFoundException("Assignment not found for company"));
        CompanyProfile companyProfile = companyProfileRepository.findById(assignment.getCompanyProfileId()).orElse(null);
        return mapToResponse(assignment, companyProfile);
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

    private LocalDateTime calculateNextReviewAt(LocalDateTime baseTime, MonitoringFrequency frequency) {
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
        
        String latestProposalStatus = null;
        String latestProposalId = null;
        if (companyProfile != null) {
            java.util.Optional<com.apms.domain.profile.CompanyProfileUpdateProposal> latestProposalOpt = 
                proposalRepository.findTopByCompanyProfileIdAndOriginOrderByCreatedAtDesc(companyProfile.getCompanyId(), com.apms.common.enums.ProposalOrigin.MONITORING);
            if (latestProposalOpt.isPresent()) {
                latestProposalStatus = latestProposalOpt.get().getStatus().name();
                latestProposalId = latestProposalOpt.get().getId();
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
                .latestProposalStatus(latestProposalStatus)
                .latestProposalId(latestProposalId)
                .lastReviewedAt(assignment.getLastReviewedAt())
                .nextReviewAt(assignment.getNextReviewAt())
                .createdAt(assignment.getCreatedAt())
                .updatedAt(assignment.getUpdatedAt())
                .build();
    }

    private String calculateDisplayStatus(CompanyMonitoringAssignment assignment) {
        if (assignment.getStatus() == MonitoringStatus.PAUSED) {
            return "PAUSED";
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(assignment.getNextReviewAt())) {
            return "OVERDUE";
        } else if (now.toLocalDate().isEqual(assignment.getNextReviewAt().toLocalDate())) {
            return "DUE";
        } else {
            return "UP_TO_DATE";
        }
    }

    private void enforceResponsibleManager(CompanyProfile profile, Account manager) {
        if (!manager.getRoles().contains(SystemRole.SYSTEM_ADMIN)) {
            if (profile.getResponsibleManagerId() == null || !manager.getId().equals(profile.getResponsibleManagerId())) {
                throw new org.springframework.security.access.AccessDeniedException("Only the responsible Manager or SYSTEM_ADMIN can manage this monitoring assignment");
            }
        }
    }
}

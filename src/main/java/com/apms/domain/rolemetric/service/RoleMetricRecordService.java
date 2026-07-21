package com.apms.domain.rolemetric.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.contract.entity.PartnerContractVersion;
import com.apms.domain.contract.entity.PartnerContractClauseVersion;
import com.apms.domain.contract.repository.sql.PartnerContractVersionRepository;
import com.apms.domain.contract.repository.sql.PartnerContractClauseVersionRepository;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.rolemetric.dto.*;
import com.apms.domain.rolemetric.entity.*;
import com.apms.domain.rolemetric.enums.*;
import com.apms.domain.rolemetric.repository.*;
import java.math.BigDecimal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleMetricRecordService {

    private final RoleMetricRecordRepository recordRepository;
    private final RoleMetricRecordVersionRepository versionRepository;
    private final RoleMetricEvidenceRepository evidenceRepository;
    private final RoleMetricEvidenceVersionRepository evidenceVersionRepository;
    private final ProjectRepository projectRepository;
    private final ProjectTaskRepository taskRepository;
    private final PartnerContractVersionRepository contractVersionRepository;
    private final PartnerContractClauseVersionRepository clauseVersionRepository;
    private final RawDocumentRepository rawDocumentRepository;
    private final AuditLogService auditLogService;

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public RoleMetricResponse createDraft(Long projectId, CreateRoleMetricRequest request) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found"));
        
        if (project.getTargetRelationshipType() == null || !project.getTargetRelationshipType().name().equals("PARTNER_WITH")) {
            throw new BusinessValidationException("Project relationship type must be PARTNER_WITH");
        }

        if (request.getTaskId() != null) {
            ProjectTask task = taskRepository.findById(request.getTaskId())
                    .orElseThrow(() -> new ResourceNotFoundException("Task not found"));
            if (!task.getProject().getId().equals(project.getId())) {
                throw new BusinessValidationException("Task does not belong to project");
            }
        }

        PartnerMetricDefinition def = PartnerMetricDefinition.fromKey(request.getMetricKey());
        
        String periodKey = generateAndValidatePeriodKey(def, request);
        
        // Exact duplicate protection via query (will also be protected by UNIQUE constraint)
        if (recordRepository.findByProjectIdAndCompanyIdAndRelationshipTypeAndMetricKeyAndPeriodKey(
                project.getId(), project.getTargetCompanyProfileId(), "PARTNER_WITH", request.getMetricKey(), periodKey).isPresent()) {
            throw new BusinessValidationException("Exact metric already exists for this period.");
        }

        if (def.getPeriodPolicy() == MetricPeriodType.PERIOD) {
            List<RoleMetricRecord> overlaps = recordRepository.findOverlappingPeriods(
                    project.getId(), project.getTargetCompanyProfileId(), "PARTNER_WITH", request.getMetricKey(),
                    request.getPeriodStart(), request.getPeriodEnd(), null);
            if (!overlaps.isEmpty()) {
                throw new BusinessValidationException("Metric overlaps with existing period metric.");
            }
        }
        
        validateTypedValues(def, request.getTargetNumericValue(), request.getActualNumericValue(), request.getTargetBooleanValue(), request.getActualBooleanValue());

        RoleMetricRecord record = new RoleMetricRecord();
        record.setProjectId(project.getId());
        record.setTaskId(request.getTaskId());
        record.setCompanyId(project.getTargetCompanyProfileId());
        record.setRelationshipType(project.getTargetRelationshipType().name());
        record.setMetricKey(def.getMetricKey());
        record.setValueType(def.getValueType());
        record.setPeriodType(def.getPeriodPolicy());
        record.setPeriodKey(periodKey);
        record.setMeasurementDate(request.getMeasurementDate());
        record.setPeriodStart(request.getPeriodStart());
        record.setPeriodEnd(request.getPeriodEnd());
        record.setTargetNumericValue(request.getTargetNumericValue());
        record.setActualNumericValue(request.getActualNumericValue());
        record.setTargetBooleanValue(request.getTargetBooleanValue());
        record.setActualBooleanValue(request.getActualBooleanValue());
        record.setUnitCode(request.getUnitCode());
        record.setCurrencyCode(request.getCurrencyCode());
        record.setStatus(RoleMetricStatus.DRAFT);
        record.setWorkingRevisionNumber(1);
        record.setCreatedByAccountId(getCurrentAccountId());
        record.setCreatedAt(java.time.LocalDateTime.now());
        record.setUpdatedAt(java.time.LocalDateTime.now());

        RoleMetricRecord saved = recordRepository.save(record);
        auditLogService.log(getCurrentAccountId(), AuditAction.ROLE_METRIC_CREATED, "RoleMetricRecord", String.valueOf(saved.getId()), "Draft created");
        return mapToResponse(saved);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public RoleMetricResponse updateDraft(Long projectId, Long metricId, UpdateRoleMetricRequest request) {
        RoleMetricRecord record = getForUpdate(projectId, metricId);
        
        PartnerMetricDefinition def = PartnerMetricDefinition.fromKey(record.getMetricKey());

        if (record.getCurrentApprovedVersionId() == null) {
            // Can update identity fields
            CreateRoleMetricRequest mockReq = new CreateRoleMetricRequest();
            mockReq.setMeasurementDate(request.getMeasurementDate());
            mockReq.setPeriodStart(request.getPeriodStart());
            mockReq.setPeriodEnd(request.getPeriodEnd());
            
            String newPeriodKey = generateAndValidatePeriodKey(def, mockReq);
            
            if (!newPeriodKey.equals(record.getPeriodKey())) {
                if (recordRepository.findByProjectIdAndCompanyIdAndRelationshipTypeAndMetricKeyAndPeriodKey(
                        record.getProjectId(), record.getCompanyId(), record.getRelationshipType(), record.getMetricKey(), newPeriodKey).isPresent()) {
                    throw new BusinessValidationException("Exact metric already exists for this period.");
                }
            }

            if (def.getPeriodPolicy() == MetricPeriodType.PERIOD) {
                List<RoleMetricRecord> overlaps = recordRepository.findOverlappingPeriods(
                        record.getProjectId(), record.getCompanyId(), record.getRelationshipType(), record.getMetricKey(),
                        request.getPeriodStart(), request.getPeriodEnd(), record.getId());
                if (!overlaps.isEmpty()) {
                    throw new BusinessValidationException("Metric overlaps with existing period metric.");
                }
            }
            
            record.setMeasurementDate(request.getMeasurementDate());
            record.setPeriodStart(request.getPeriodStart());
            record.setPeriodEnd(request.getPeriodEnd());
            record.setPeriodKey(newPeriodKey);
        } else {
            boolean dateChanged = request.getMeasurementDate() != null && !request.getMeasurementDate().equals(record.getMeasurementDate());
            boolean startChanged = request.getPeriodStart() != null && !request.getPeriodStart().equals(record.getPeriodStart());
            boolean endChanged = request.getPeriodEnd() != null && !request.getPeriodEnd().equals(record.getPeriodEnd());
            
            if (dateChanged || startChanged || endChanged) {
                throw new BusinessValidationException("Cannot modify identity fields (metricKey, period, measurementDate, unitCode) after initial approval.");
            }
        }
        
        validateTypedValues(def, request.getTargetNumericValue(), request.getActualNumericValue(), request.getTargetBooleanValue(), request.getActualBooleanValue());

        record.setTargetNumericValue(request.getTargetNumericValue());
        record.setActualNumericValue(request.getActualNumericValue());
        record.setTargetBooleanValue(request.getTargetBooleanValue());
        record.setActualBooleanValue(request.getActualBooleanValue());
        record.setUnitCode(request.getUnitCode());
        record.setCurrencyCode(request.getCurrencyCode());

        try {
            RoleMetricRecord saved = recordRepository.save(record);
            auditLogService.log(getCurrentAccountId(), AuditAction.ROLE_METRIC_UPDATED, "RoleMetricRecord", String.valueOf(saved.getId()), "Draft updated");
            return mapToResponse(saved);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessValidationException("Concurrent duplicate metric update prevented.");
        }
    }

    @Transactional
    public RoleMetricEvidenceResponse attachEvidence(Long projectId, Long metricId, RoleMetricEvidenceRequest request) {
        RoleMetricRecord record = getForUpdate(projectId, metricId);
        
        validateEvidenceSource(record, request);
        
        RoleMetricEvidence ev = new RoleMetricEvidence();
        ev.setRoleMetricRecordId(record.getId());
        ev.setCreatedByAccountId(getCurrentAccountId());
        ev.setUpdatedByAccountId(getCurrentAccountId());
        ev.setValueScope(request.getValueScope());
        ev.setSourceType(request.getSourceType());
        ev.setSourceContractVersionId(request.getSourceContractVersionId());
        ev.setSourceClauseVersionId(request.getSourceClauseVersionId());
        ev.setSourceRawDocumentId(request.getSourceRawDocumentId());
        ev.setDocumentSegmentId(request.getDocumentSegmentId());
        ev.setDocumentHash(request.getDocumentHash());
        ev.setSourceExcerpt(request.getSourceExcerpt());
        ev.setExternalReference(request.getExternalReference());
        ev.setEvidenceNote(request.getEvidenceNote());
        
        RoleMetricEvidence saved = evidenceRepository.save(ev);
        auditLogService.log(getCurrentAccountId(), AuditAction.ROLE_METRIC_EVIDENCE_ATTACHED, "RoleMetricRecord", String.valueOf(record.getId()), "Evidence attached");
        return mapToEvidenceResponse(saved);
    }
    
    @Transactional
    public void deleteEvidence(Long projectId, Long metricId, Long evidenceId) {
        RoleMetricRecord record = getForUpdate(projectId, metricId);
        RoleMetricEvidence ev = evidenceRepository.findById(evidenceId)
                .orElseThrow(() -> new ResourceNotFoundException("Evidence not found"));
                
        if (!ev.getRoleMetricRecordId().equals(record.getId())) {
            throw new BusinessValidationException("Evidence does not belong to this metric");
        }
        
        evidenceRepository.delete(ev);
        auditLogService.log(getCurrentAccountId(), AuditAction.ROLE_METRIC_EVIDENCE_DELETED, "RoleMetricRecord", String.valueOf(record.getId()), "Evidence deleted");
    }

    @Transactional
    public RoleMetricResponse submitForReview(Long projectId, Long metricId) {
        RoleMetricRecord record = getRecordAndVerifyProject(projectId, metricId);
                
        if (record.getStatus() == RoleMetricStatus.SUBMITTED || record.getStatus() == RoleMetricStatus.APPROVED) {
            return mapToResponse(record);
        }
        
        if (record.getStatus() != RoleMetricStatus.DRAFT && record.getStatus() != RoleMetricStatus.CHANGES_REQUESTED) {
            throw new BusinessValidationException("Only DRAFT or CHANGES_REQUESTED metrics can be submitted.");
        }
        
        validateCompleteness(record);

        record.setStatus(RoleMetricStatus.SUBMITTED);
        record.setSubmittedByAccountId(getCurrentAccountId());
        record.setSubmittedAt(LocalDateTime.now());
        
        RoleMetricRecord saved = recordRepository.save(record);
        auditLogService.log(getCurrentAccountId(), AuditAction.ROLE_METRIC_SUBMITTED, "RoleMetricRecord", String.valueOf(saved.getId()), "Submitted");
        return mapToResponse(saved);
    }

    @Transactional
    public RoleMetricResponse reviewMetric(Long projectId, Long metricId, ReviewRoleMetricRequest request) {
        RoleMetricRecord record = getRecordAndVerifyProject(projectId, metricId);
                
        if (record.getStatus() == RoleMetricStatus.APPROVED && request.getDecision() == RoleMetricReviewDecision.APPROVE) {
            return mapToResponse(record); // Idempotent
        }

        if (record.getStatus() != RoleMetricStatus.SUBMITTED) {
            throw new BusinessValidationException("Only SUBMITTED metrics can be reviewed.");
        }
        
        if (request.getDecision() != RoleMetricReviewDecision.APPROVE && (request.getComment() == null || request.getComment().isBlank())) {
            throw new BusinessValidationException("Comment required for rejection or changes requested.");
        }

        record.setReviewedByAccountId(getCurrentAccountId());
        record.setReviewedAt(LocalDateTime.now());
        record.setReviewComment(request.getComment());

        if (request.getDecision() == RoleMetricReviewDecision.APPROVE) {
            // Revalidate completeness and hashes
            validateCompleteness(record);
            
            record.setStatus(RoleMetricStatus.APPROVED);
            
            RoleMetricRecordVersion version = new RoleMetricRecordVersion();
            version.setRoleMetricRecordId(record.getId());
            version.setVersionNumber(record.getWorkingRevisionNumber());
            version.setProjectId(record.getProjectId());
            version.setTaskId(record.getTaskId());
            version.setCompanyId(record.getCompanyId());
            version.setRelationshipType(record.getRelationshipType());
            version.setMetricKey(record.getMetricKey());
            version.setValueType(record.getValueType());
            version.setPeriodType(record.getPeriodType());
            version.setPeriodKey(record.getPeriodKey());
            version.setMeasurementDate(record.getMeasurementDate());
            version.setPeriodStart(record.getPeriodStart());
            version.setPeriodEnd(record.getPeriodEnd());
            version.setTargetNumericValue(record.getTargetNumericValue());
            version.setActualNumericValue(record.getActualNumericValue());
            version.setTargetBooleanValue(record.getTargetBooleanValue());
            version.setActualBooleanValue(record.getActualBooleanValue());
            version.setUnitCode(record.getUnitCode());
            version.setCurrencyCode(record.getCurrencyCode());
            version.setWorkingRevisionNumber(record.getWorkingRevisionNumber());
            version.setStatus(RoleMetricStatus.APPROVED);
            version.setSubmittedByAccountId(record.getSubmittedByAccountId());
            version.setSubmittedAt(record.getSubmittedAt());
            version.setReviewedByAccountId(record.getReviewedByAccountId());
            version.setReviewedAt(record.getReviewedAt());
            version.setReviewComment(record.getReviewComment());
            version.setApprovedAt(LocalDateTime.now());
            version.setApprovedByAccountId(getCurrentAccountId());
            
            RoleMetricRecordVersion savedVersion = versionRepository.save(version);
            
            List<RoleMetricEvidence> evidences = evidenceRepository.findByRoleMetricRecordId(record.getId());
            for (RoleMetricEvidence ev : evidences) {
                RoleMetricEvidenceVersion evv = new RoleMetricEvidenceVersion();
                evv.setRoleMetricRecordVersionId(savedVersion.getId());
                evv.setSourceEvidenceId(ev.getId());
                evv.setValueScope(ev.getValueScope());
                evv.setSourceType(ev.getSourceType());
                evv.setSourceContractVersionId(ev.getSourceContractVersionId());
                evv.setSourceClauseVersionId(ev.getSourceClauseVersionId());
                evv.setSourceRawDocumentId(ev.getSourceRawDocumentId());
                evv.setDocumentSegmentId(ev.getDocumentSegmentId());
                evv.setDocumentHash(ev.getDocumentHash());
                evv.setSourceExcerpt(ev.getSourceExcerpt());
                evv.setExternalReference(ev.getExternalReference());
                evv.setEvidenceNote(ev.getEvidenceNote());
                evv.setSnapshotAt(LocalDateTime.now());
                evidenceVersionRepository.save(evv);
            }
            
            record.setCurrentApprovedVersionId(savedVersion.getId());
            record.setCurrentApprovedVersionNumber(savedVersion.getVersionNumber());
            
            auditLogService.log(getCurrentAccountId(), AuditAction.ROLE_METRIC_APPROVED, "RoleMetricRecord", String.valueOf(record.getId()), "Approved v" + savedVersion.getVersionNumber());
            
        } else if (request.getDecision() == RoleMetricReviewDecision.REQUEST_CHANGES) {
            record.setStatus(RoleMetricStatus.CHANGES_REQUESTED);
            auditLogService.log(getCurrentAccountId(), AuditAction.ROLE_METRIC_CHANGES_REQUESTED, "RoleMetricRecord", String.valueOf(record.getId()), "Changes requested");
        } else {
            record.setStatus(RoleMetricStatus.REJECTED);
            auditLogService.log(getCurrentAccountId(), AuditAction.ROLE_METRIC_REJECTED, "RoleMetricRecord", String.valueOf(record.getId()), "Rejected");
        }
        
        return mapToResponse(recordRepository.save(record));
    }

    @Transactional
    public RoleMetricResponse reviseMetric(Long projectId, Long metricId) {
        RoleMetricRecord record = getRecordAndVerifyProject(projectId, metricId);
                
        if (record.getStatus() != RoleMetricStatus.APPROVED) {
            throw new BusinessValidationException("Only APPROVED metrics can be revised.");
        }
        
        record.setWorkingRevisionNumber(record.getWorkingRevisionNumber() + 1);
        record.setStatus(RoleMetricStatus.DRAFT);
        RoleMetricRecord saved = recordRepository.save(record);
        
        auditLogService.log(getCurrentAccountId(), AuditAction.ROLE_METRIC_REVISION_CREATED, "RoleMetricRecord", String.valueOf(record.getId()), "Revision created");
        
        return mapToResponse(saved);
    }

    @Transactional
    public RoleMetricResponse reopenMetric(Long projectId, Long metricId) {
        RoleMetricRecord record = getRecordAndVerifyProject(projectId, metricId);
                
        if (record.getStatus() != RoleMetricStatus.CHANGES_REQUESTED && record.getStatus() != RoleMetricStatus.REJECTED) {
            throw new BusinessValidationException("Only CHANGES_REQUESTED or REJECTED metrics can be reopened.");
        }
        
        if (record.getStatus() == RoleMetricStatus.REJECTED) {
            record.setWorkingRevisionNumber(record.getWorkingRevisionNumber() + 1);
        }
        
        record.setStatus(RoleMetricStatus.DRAFT);
        RoleMetricRecord saved = recordRepository.save(record);
        
        auditLogService.log(getCurrentAccountId(), AuditAction.ROLE_METRIC_REOPENED, "RoleMetricRecord", String.valueOf(record.getId()), "Reopened");
        
        return mapToResponse(saved);
    }

    private RoleMetricRecord getRecordAndVerifyProject(Long projectId, Long metricId) {
        RoleMetricRecord record = recordRepository.findById(metricId)
                .orElseThrow(() -> new ResourceNotFoundException("Metric not found"));
        if (!record.getProjectId().equals(projectId)) {
            throw new ResourceNotFoundException("Metric not found in this project");
        }
        return record;
    }

    private RoleMetricRecord getForUpdate(Long projectId, Long metricId) {
        RoleMetricRecord record = getRecordAndVerifyProject(projectId, metricId);
        if (record.getStatus() != RoleMetricStatus.DRAFT) {
            throw new BusinessValidationException("Metric must be in DRAFT state to modify.");
        }
        return record;
    }
    
    private void validateEvidenceSource(RoleMetricRecord record, RoleMetricEvidenceRequest request) {
        if (request.getSourceType() == RoleMetricEvidenceSourceType.CONTRACT_CLAUSE) {
            if (request.getSourceContractVersionId() == null || request.getSourceClauseVersionId() == null) {
                throw new BusinessValidationException("Contract and clause versions required for CONTRACT_CLAUSE");
            }
            PartnerContractVersion cv = contractVersionRepository.findById(request.getSourceContractVersionId())
                    .orElseThrow(() -> new BusinessValidationException("Contract version not found"));
            if (!cv.getSourceProjectId().equals(record.getProjectId()) || !cv.getPartnerCompanyId().equals(record.getCompanyId())) {
                throw new BusinessValidationException("Contract version does not belong to project/company");
            }
            PartnerContractClauseVersion ccv = clauseVersionRepository.findById(request.getSourceClauseVersionId())
                    .orElseThrow(() -> new BusinessValidationException("Clause version not found"));
            if (!ccv.getPartnerContractVersionId().equals(cv.getId())) {
                throw new BusinessValidationException("Clause version does not belong to contract version");
            }
        } else if (request.getSourceType() == RoleMetricEvidenceSourceType.RAW_DOCUMENT) {
            if (request.getSourceRawDocumentId() == null) {
                throw new BusinessValidationException("Document ID required for RAW_DOCUMENT");
            }
            RawDocument doc = rawDocumentRepository.findById(request.getSourceRawDocumentId())
                    .orElseThrow(() -> new BusinessValidationException("Document not found"));
            if (!doc.getProjectId().equals(record.getProjectId())) {
                throw new BusinessValidationException("Document does not belong to project");
            }
            if (record.getTaskId() != null && (doc.getTaskId() == null || !doc.getTaskId().equals(record.getTaskId()))) {
                throw new BusinessValidationException("Document does not belong to task");
            }
            String docHash = doc.getStorage() != null ? doc.getStorage().getChecksum() : null;
            if (request.getDocumentHash() == null || !request.getDocumentHash().equals(docHash)) {
                throw new BusinessValidationException("Document hash mismatch");
            }
        }
    }
    
    private void validateCompleteness(RoleMetricRecord record) {
        if (record.getTargetNumericValue() == null && record.getTargetBooleanValue() == null &&
            record.getActualNumericValue() == null && record.getActualBooleanValue() == null) {
            throw new BusinessValidationException("At least one target or actual value must be provided.");
        }
        
        List<RoleMetricEvidence> evidences = evidenceRepository.findByRoleMetricRecordId(record.getId());
        
        boolean hasTarget = record.getTargetNumericValue() != null || record.getTargetBooleanValue() != null;
        boolean hasActual = record.getActualNumericValue() != null || record.getActualBooleanValue() != null;
        
        if (hasTarget) {
            boolean hasTargetEv = evidences.stream().anyMatch(e -> e.getValueScope() == RoleMetricEvidenceValueScope.TARGET || e.getValueScope() == RoleMetricEvidenceValueScope.BOTH);
            if (!hasTargetEv) throw new BusinessValidationException("Target requires evidence.");
        }
        if (hasActual) {
            boolean hasActualEv = evidences.stream().anyMatch(e -> e.getValueScope() == RoleMetricEvidenceValueScope.ACTUAL || e.getValueScope() == RoleMetricEvidenceValueScope.BOTH);
            if (!hasActualEv) throw new BusinessValidationException("Actual requires evidence.");
        }
        
        // Revalidate hash
        for (RoleMetricEvidence ev : evidences) {
            if (ev.getSourceType() == RoleMetricEvidenceSourceType.RAW_DOCUMENT) {
                RawDocument doc = rawDocumentRepository.findById(ev.getSourceRawDocumentId()).orElse(null);
                String docHash = doc != null && doc.getStorage() != null ? doc.getStorage().getChecksum() : null;
                if (doc == null || !ev.getDocumentHash().equals(docHash)) {
                    throw new BusinessValidationException("Underlying document hash changed for evidence ID " + ev.getId());
                }
            }
        }
    }

    private void validateTypedValues(PartnerMetricDefinition def, BigDecimal tNum, BigDecimal aNum, Boolean tBool, Boolean aBool) {
        if (def.getValueType() == MetricValueType.BOOLEAN) {
            if (tNum != null || aNum != null) throw new BusinessValidationException("Numeric values not allowed for BOOLEAN metrics.");
        } else {
            if (tBool != null || aBool != null) throw new BusinessValidationException("Boolean values not allowed for numeric metrics.");
        }
    }

    private String generateAndValidatePeriodKey(PartnerMetricDefinition def, CreateRoleMetricRequest req) {
        if (def.getPeriodPolicy() == MetricPeriodType.POINT_IN_TIME) {
            if (req.getMeasurementDate() == null) throw new BusinessValidationException("measurementDate required");
            if (req.getPeriodStart() != null || req.getPeriodEnd() != null) throw new BusinessValidationException("period dates forbidden for POINT_IN_TIME");
            return "AT:" + req.getMeasurementDate().toString();
        } else {
            if (req.getPeriodStart() == null || req.getPeriodEnd() == null) throw new BusinessValidationException("period dates required for PERIOD");
            if (req.getMeasurementDate() != null) throw new BusinessValidationException("measurementDate forbidden for PERIOD");
            if (req.getPeriodStart().isAfter(req.getPeriodEnd())) throw new BusinessValidationException("periodStart must be <= periodEnd");
            return "PERIOD:" + req.getPeriodStart().toString() + "/" + req.getPeriodEnd().toString();
        }
    }

    private Long getCurrentAccountId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserDetailsImpl)) return null;
        return ((UserDetailsImpl) auth.getPrincipal()).getId();
    }

    public RoleMetricResponse mapToResponse(RoleMetricRecord r) {
        RoleMetricResponse resp = new RoleMetricResponse();
        resp.setId(r.getId());
        resp.setProjectId(r.getProjectId());
        resp.setTaskId(r.getTaskId());
        resp.setCompanyId(r.getCompanyId());
        resp.setRelationshipType(r.getRelationshipType());
        resp.setMetricKey(r.getMetricKey());
        resp.setValueType(r.getValueType());
        resp.setPeriodType(r.getPeriodType());
        resp.setPeriodKey(r.getPeriodKey());
        resp.setMeasurementDate(r.getMeasurementDate());
        resp.setPeriodStart(r.getPeriodStart());
        resp.setPeriodEnd(r.getPeriodEnd());
        resp.setTargetNumericValue(r.getTargetNumericValue());
        resp.setActualNumericValue(r.getActualNumericValue());
        resp.setTargetBooleanValue(r.getTargetBooleanValue());
        resp.setActualBooleanValue(r.getActualBooleanValue());
        resp.setUnitCode(r.getUnitCode());
        resp.setCurrencyCode(r.getCurrencyCode());
        resp.setStatus(r.getStatus());
        resp.setSubmittedByAccountId(r.getSubmittedByAccountId());
        resp.setSubmittedAt(r.getSubmittedAt());
        resp.setReviewedByAccountId(r.getReviewedByAccountId());
        resp.setReviewedAt(r.getReviewedAt());
        resp.setReviewComment(r.getReviewComment());
        resp.setCurrentApprovedVersionId(r.getCurrentApprovedVersionId());
        resp.setCurrentApprovedVersionNumber(r.getCurrentApprovedVersionNumber());
        resp.setWorkingRevisionNumber(r.getWorkingRevisionNumber());
        resp.setOptimisticVersion(r.getOptimisticVersion());
        resp.setCreatedByAccountId(r.getCreatedByAccountId());
        resp.setCreatedAt(r.getCreatedAt());
        resp.setUpdatedAt(r.getUpdatedAt());
        resp.setEvidences(evidenceRepository.findByRoleMetricRecordId(r.getId()).stream().map(this::mapToEvidenceResponse).collect(Collectors.toList()));
        return resp;
    }
    
    private RoleMetricEvidenceResponse mapToEvidenceResponse(RoleMetricEvidence ev) {
        RoleMetricEvidenceResponse resp = new RoleMetricEvidenceResponse();
        resp.setId(ev.getId());
        resp.setRoleMetricRecordId(ev.getRoleMetricRecordId());
        resp.setValueScope(ev.getValueScope());
        resp.setSourceType(ev.getSourceType());
        resp.setSourceContractVersionId(ev.getSourceContractVersionId());
        resp.setSourceClauseVersionId(ev.getSourceClauseVersionId());
        resp.setSourceRawDocumentId(ev.getSourceRawDocumentId());
        resp.setDocumentSegmentId(ev.getDocumentSegmentId());
        resp.setDocumentHash(ev.getDocumentHash());
        resp.setSourceExcerpt(ev.getSourceExcerpt());
        resp.setExternalReference(ev.getExternalReference());
        resp.setEvidenceNote(ev.getEvidenceNote());
        resp.setOptimisticVersion(ev.getOptimisticVersion());
        resp.setCreatedByAccountId(ev.getCreatedByAccountId());
        resp.setCreatedAt(ev.getCreatedAt());
        resp.setUpdatedByAccountId(ev.getUpdatedByAccountId());
        resp.setUpdatedAt(ev.getUpdatedAt());
        return resp;
    }

    public List<RoleMetricVersionResponse> listApprovedMetrics(Long projectId) {
        return versionRepository.findByProjectId(projectId).stream()
            .map(this::mapToVersionResponse)
            .collect(Collectors.toList());
    }

    public RoleMetricVersionResponse getCurrentApproved(Long projectId, Long metricId) {
        RoleMetricRecord record = getRecordAndVerifyProject(projectId, metricId);
        if (!record.getProjectId().equals(projectId)) {
            throw new BusinessValidationException("Metric does not belong to project");
        }
        if (record.getCurrentApprovedVersionId() == null) {
            throw new ResourceNotFoundException("No approved version exists");
        }
        RoleMetricRecordVersion version = versionRepository.findById(record.getCurrentApprovedVersionId())
                .orElseThrow(() -> new ResourceNotFoundException("Version not found"));
        return mapToVersionResponse(version);
    }

    public RoleMetricVersionResponse getVersionByNumber(Long projectId, Long metricId, Integer versionNumber) {
        RoleMetricRecord record = getRecordAndVerifyProject(projectId, metricId);
        if (!record.getProjectId().equals(projectId)) {
            throw new BusinessValidationException("Metric does not belong to project");
        }
        RoleMetricRecordVersion version = versionRepository.findByRoleMetricRecordIdAndVersionNumber(metricId, versionNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Version not found"));
        return mapToVersionResponse(version);
    }

    public RoleMetricResponse getWorkingDetail(Long projectId, Long metricId) {
        RoleMetricRecord record = getRecordAndVerifyProject(projectId, metricId);
        return mapToResponse(record);
    }

    public List<RoleMetricVersionResponse> getVersions(Long projectId, Long metricId) {
        RoleMetricRecord record = getRecordAndVerifyProject(projectId, metricId);
        return versionRepository.findByRoleMetricRecordIdOrderByVersionNumberDesc(metricId)
                .stream().map(this::mapToVersionResponse).collect(Collectors.toList());
    }

    private RoleMetricVersionResponse mapToVersionResponse(RoleMetricRecordVersion v) {
        RoleMetricVersionResponse r = new RoleMetricVersionResponse();
        r.setId(v.getId());
        r.setVersionNumber(v.getVersionNumber());
        r.setCompanyId(v.getCompanyId());
        r.setRelationshipType(v.getRelationshipType());
        r.setMetricKey(v.getMetricKey());
        r.setValueType(v.getValueType());
        r.setPeriodType(v.getPeriodType());
        r.setPeriodKey(v.getPeriodKey());
        r.setMeasurementDate(v.getMeasurementDate());
        r.setPeriodStart(v.getPeriodStart());
        r.setPeriodEnd(v.getPeriodEnd());
        r.setTargetNumericValue(v.getTargetNumericValue());
        r.setActualNumericValue(v.getActualNumericValue());
        r.setTargetBooleanValue(v.getTargetBooleanValue());
        r.setActualBooleanValue(v.getActualBooleanValue());
        r.setUnitCode(v.getUnitCode());
        r.setCurrencyCode(v.getCurrencyCode());
        r.setApprovedByAccountId(v.getApprovedByAccountId());
        r.setApprovedAt(v.getApprovedAt());
        r.setEvidences(evidenceVersionRepository.findByRoleMetricRecordVersionId(v.getId()).stream().map(ev -> {
            RoleMetricEvidenceVersionResponse er = new RoleMetricEvidenceVersionResponse();
            er.setId(ev.getId());
            er.setDocumentHash(ev.getDocumentHash());
            return er;
        }).collect(Collectors.toList()));
        return r;
    }
}

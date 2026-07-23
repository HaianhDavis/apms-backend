package com.apms.domain.score.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.contract.entity.PartnerContractClauseVersion;
import com.apms.domain.contract.entity.PartnerContractVersion;
import com.apms.domain.contract.repository.sql.PartnerContractClauseVersionRepository;
import com.apms.domain.contract.repository.sql.PartnerContractVersionRepository;
import com.apms.domain.profile.CompanyProfileVersion;
import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import com.apms.domain.rolemetric.entity.RoleMetricEvidenceVersion;
import com.apms.domain.rolemetric.entity.RoleMetricRecordVersion;
import com.apms.domain.rolemetric.repository.RoleMetricEvidenceVersionRepository;
import com.apms.domain.rolemetric.repository.RoleMetricRecordVersionRepository;
import com.apms.domain.score.draft.ApprovedSourceReference;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.SourceSelectionRequest;
import com.apms.domain.score.enums.ApprovedSourceType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SourcePinningValidator {

    private final RoleMetricRecordVersionRepository metricVersionRepository;
    private final RoleMetricEvidenceVersionRepository evidenceVersionRepository;
    private final PartnerContractVersionRepository contractVersionRepository;
    private final PartnerContractClauseVersionRepository clauseVersionRepository;
    private final CompanyProfileVersionRepository companyProfileVersionRepository;

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Transactional(readOnly = true)
    public List<ApprovedSourceReference> validateAndBuildReferences(List<SourceSelectionRequest> requests, RoleEvaluationDraft draft) {
        List<ApprovedSourceReference> verifiedReferences = new ArrayList<>();

        for (SourceSelectionRequest request : requests) {
            ApprovedSourceReference ref = buildReference(request, draft);
            ref.validate();
            verifiedReferences.add(ref);
        }

        return verifiedReferences;
    }

    private ApprovedSourceReference buildReference(SourceSelectionRequest req, RoleEvaluationDraft draft) {
        if (req.getSourceType() == ApprovedSourceType.RAW_DOCUMENT_SEGMENT ||
            req.getSourceType() == ApprovedSourceType.EXTERNAL ||
            req.getSourceType() == ApprovedSourceType.MANUAL_NOTE) {
            throw new BusinessValidationException("Source type " + req.getSourceType() + " is unsupported in Phase 2C.5B pinning.");
        }

        ApprovedSourceReference.ApprovedSourceReferenceBuilder builder = ApprovedSourceReference.builder()
                .sourceType(req.getSourceType())
                .sqlSourceId(req.getSqlSourceId())
                .mongoSourceId(req.getMongoSourceId())
                .criterionKey(req.getCriterionKey())
                .projectId(draft.getProjectId())
                .companyProfileId(draft.getTargetCompanyId())
                .pinnedAt(LocalDateTime.now())
                .sharedAcrossCriteria(determineShared(req.getCriterionKey(), req.getSourceType()));

        String canonicalJson = null;

        if (req.getSourceType() == ApprovedSourceType.ROLE_METRIC_VERSION) {
            RoleMetricRecordVersion version = metricVersionRepository.findById(req.getSqlSourceId())
                    .orElseThrow(() -> new BusinessValidationException("Metric version not found: " + req.getSqlSourceId()));

            if (!"APPROVED".equals(version.getStatus().name())) {
                throw new BusinessValidationException("Metric version is not APPROVED");
            }
            if (!version.getProjectId().equals(draft.getProjectId())) {
                throw new BusinessValidationException("Metric version project mismatch");
            }
            if (!version.getCompanyId().equals(draft.getTargetCompanyId())) {
                throw new BusinessValidationException("Metric version company mismatch");
            }
            builder.sourceVersionNumber(version.getVersionNumber());
            canonicalJson = toCanonicalJson(version);
            builder.measurementDate(version.getMeasurementDate());
            builder.periodStart(version.getPeriodStart());
            builder.periodEnd(version.getPeriodEnd());

        } else if (req.getSourceType() == ApprovedSourceType.ROLE_METRIC_EVIDENCE_VERSION) {
            RoleMetricEvidenceVersion version = evidenceVersionRepository.findById(req.getSqlSourceId())
                    .orElseThrow(() -> new BusinessValidationException("Evidence version not found: " + req.getSqlSourceId()));

            RoleMetricRecordVersion parent = metricVersionRepository.findById(version.getRoleMetricRecordVersionId())
                    .orElseThrow(() -> new BusinessValidationException("Parent metric version not found"));
            if (!"APPROVED".equals(parent.getStatus().name())) {
                throw new BusinessValidationException("Parent metric version is not APPROVED");
            }
            if (!parent.getProjectId().equals(draft.getProjectId())) {
                throw new BusinessValidationException("Parent metric version project mismatch");
            }
            if (!parent.getCompanyId().equals(draft.getTargetCompanyId())) {
                throw new BusinessValidationException("Parent metric version company mismatch");
            }
            // Evidence version is tied to the parent's version
            builder.sourceVersionNumber(parent.getVersionNumber());
            canonicalJson = toCanonicalJson(version);

        } else if (req.getSourceType() == ApprovedSourceType.PARTNER_CONTRACT_VERSION) {
            PartnerContractVersion version = contractVersionRepository.findById(req.getSqlSourceId())
                    .orElseThrow(() -> new BusinessValidationException("Contract version not found: " + req.getSqlSourceId()));
            if (!"APPROVED".equals(version.getReviewStatus().name())) {
                throw new BusinessValidationException("Contract version is not APPROVED");
            }
            if (!version.getSourceProjectId().equals(draft.getProjectId())) {
                throw new BusinessValidationException("Contract version project mismatch");
            }
            if (!version.getPartnerCompanyId().equals(draft.getTargetCompanyId())) {
                throw new BusinessValidationException("Contract version company mismatch");
            }
            builder.sourceVersionNumber(version.getVersion());
            canonicalJson = toCanonicalJson(version);
            builder.periodStart(version.getEffectiveDate());
            builder.periodEnd(version.getExpiryDate());

        } else if (req.getSourceType() == ApprovedSourceType.PARTNER_CONTRACT_CLAUSE_VERSION) {
            PartnerContractClauseVersion version = clauseVersionRepository.findById(req.getSqlSourceId())
                    .orElseThrow(() -> new BusinessValidationException("Contract clause version not found: " + req.getSqlSourceId()));
            PartnerContractVersion parent = contractVersionRepository.findById(version.getPartnerContractVersionId())
                    .orElseThrow(() -> new BusinessValidationException("Parent contract version not found"));
            if (!"APPROVED".equals(parent.getReviewStatus().name())) {
                throw new BusinessValidationException("Parent contract version is not APPROVED");
            }
            if (!parent.getSourceProjectId().equals(draft.getProjectId())) {
                throw new BusinessValidationException("Parent contract version project mismatch");
            }
            if (!parent.getPartnerCompanyId().equals(draft.getTargetCompanyId())) {
                throw new BusinessValidationException("Parent contract version company mismatch");
            }
            builder.sourceVersionNumber(parent.getVersion());
            canonicalJson = toCanonicalJson(version);

        } else if (req.getSourceType() == ApprovedSourceType.COMPANY_PROFILE_VERSION) {
            CompanyProfileVersion version = companyProfileVersionRepository.findById(req.getMongoSourceId())
                    .orElseThrow(() -> new BusinessValidationException("Profile version not found: " + req.getMongoSourceId()));
            
            if (!version.getCompanyId().equals(draft.getTargetCompanyId())) {
                throw new BusinessValidationException("Profile version company mismatch");
            }
            builder.sourceVersionNumber(version.getVersion());
            canonicalJson = toCanonicalJson(version);
        }

        builder.sourceHash(computeSha256(canonicalJson));
        
        // Deterministic referenceId generation
        String idInput = String.format("%s:%s:%s:%s:%s",
                req.getSourceType().name(),
                req.getSqlSourceId() != null ? req.getSqlSourceId().toString() : req.getMongoSourceId(),
                builder.build().getSourceVersionNumber(),
                req.getCriterionKey(),
                builder.build().isSharedAcrossCriteria()
        );
        String uuidName = UUID.nameUUIDFromBytes(idInput.getBytes(StandardCharsets.UTF_8)).toString();
        builder.referenceId(uuidName);

        return builder.build();
    }

    private boolean determineShared(String criterionKey, ApprovedSourceType type) {
        if (type == ApprovedSourceType.COMPANY_PROFILE_VERSION) return true;
        return false;
    }

    private String toCanonicalJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize object to JSON for hashing", e);
        }
    }

    private String computeSha256(String input) {
        if (input == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not supported", e);
        }
    }
}

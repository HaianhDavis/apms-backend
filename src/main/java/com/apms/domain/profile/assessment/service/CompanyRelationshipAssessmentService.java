package com.apms.domain.profile.assessment.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.SystemRole;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.contract.entity.PartnerContract;
import com.apms.domain.contract.enums.ContractReviewStatus;
import com.apms.domain.contract.repository.sql.PartnerContractRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.assessment.CompanyRelationshipAssessment;
import com.apms.domain.profile.assessment.RelationshipAssessmentRank;
import com.apms.domain.profile.assessment.RelationshipAssessmentStatus;
import com.apms.domain.profile.assessment.RelationshipAssessmentType;
import com.apms.domain.profile.assessment.dto.*;
import com.apms.domain.profile.assessment.policy.RelationshipCommercialScoringPolicy;
import com.apms.domain.profile.assessment.policy.RelationshipCommercialScoringPolicy.CommercialEvidenceResult;
import com.apms.domain.profile.assessment.policy.RelationshipScoreCalculator;
import com.apms.domain.profile.assessment.policy.RelationshipScoreCalculator.CalculationResult;
import com.apms.domain.profile.assessment.repository.CompanyRelationshipAssessmentRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.notification.service.NotificationService;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.profile.service.ProfileService;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.security.UserDetailsImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyRelationshipAssessmentService {

    public static final Set<String> ELIGIBLE_RELATIONSHIP_TYPES = RelationshipClosenessAccessEvaluator.ELIGIBLE_RELATIONSHIP_TYPES;

    public static final Set<RelationshipAssessmentStatus> ACTIVE_STATUSES = Set.of(
            RelationshipAssessmentStatus.DRAFT,
            RelationshipAssessmentStatus.SUBMITTED,
            RelationshipAssessmentStatus.CHANGES_REQUESTED
    );

    private final CompanyRelationshipAssessmentRepository assessmentRepository;
    private final PartnerContractRepository partnerContractRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final OwnerOrganizationService ownerOrganizationService;
    private final ProjectRepository projectRepository;
    private final AuditLogService auditLogService;
    private final RelationshipCommercialScoringPolicy scoringPolicy;
    private final RelationshipScoreCalculator scoreCalculator;
    private final ProfileService profileService;
    private final RelationshipClosenessAccessEvaluator accessEvaluator;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ----------------------------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> getOverview(String targetCompanyProfileId, UserDetailsImpl currentUser) {
        validateTargetProfile(targetCompanyProfileId);
        validateAccess(targetCompanyProfileId, currentUser, false);

        String ownerId = ownerOrganizationService.getOwnerCompanyProfileId();
        List<String> targetIds = resolveTargetProfileIds(targetCompanyProfileId);

        Optional<CompanyRelationshipAssessment> activeOpt = assessmentRepository
                .findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusInOrderByVersionNumberDesc(
                        ownerId, targetCompanyProfileId, ACTIVE_STATUSES);
        if (activeOpt.isEmpty() && targetIds.size() > 1) {
            activeOpt = assessmentRepository
                    .findFirstByOwnerCompanyProfileIdAndCompanyProfileIdInAndStatusInOrderByVersionNumberDesc(
                            ownerId, targetIds, ACTIVE_STATUSES);
        }

        Optional<CompanyRelationshipAssessment> finalizedOpt = assessmentRepository
                .findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
                        ownerId, targetCompanyProfileId, RelationshipAssessmentStatus.FINALIZED);
        if (finalizedOpt.isEmpty() && targetIds.size() > 1) {
            finalizedOpt = assessmentRepository
                    .findFirstByOwnerCompanyProfileIdAndCompanyProfileIdInAndStatusOrderByVersionNumberDesc(
                            ownerId, targetIds, RelationshipAssessmentStatus.FINALIZED);
        }

        final Optional<CompanyRelationshipAssessment> effectiveFinalized = finalizedOpt;

        // Fetch live commercial evidence for preview
        CommercialEvidenceResponse liveCommercial = getLiveCommercialEvidence(targetCompanyProfileId);

        Map<String, Object> result = new HashMap<>();
        result.put("activeAssessment", activeOpt.map(a -> toResponse(a, currentUser, effectiveFinalized.orElse(null))).orElse(null));
        result.put("officialFinalizedAssessment", effectiveFinalized.map(a -> toResponse(a, currentUser, null)).orElse(null));
        result.put("liveCommercialEvidence", liveCommercial);
        result.put("hasActiveAssessment", activeOpt.isPresent());
        result.put("canCreateAssessment", canCreateNewAssessment(targetCompanyProfileId, currentUser, activeOpt.isPresent(), effectiveFinalized.isPresent()));

        return result;
    }

    @Transactional(readOnly = true)
    public List<RelationshipAssessmentResponse> getHistory(String targetCompanyProfileId, UserDetailsImpl currentUser) {
        validateTargetProfile(targetCompanyProfileId);
        validateAccess(targetCompanyProfileId, currentUser, false);

        String ownerId = ownerOrganizationService.getOwnerCompanyProfileId();
        List<String> targetIds = resolveTargetProfileIds(targetCompanyProfileId);
        List<CompanyRelationshipAssessment> all = assessmentRepository
                .findAllByOwnerCompanyProfileIdAndCompanyProfileIdOrderByVersionNumberDesc(ownerId, targetCompanyProfileId);
        if (all.isEmpty() && targetIds.size() > 1) {
            all = assessmentRepository
                    .findAllByOwnerCompanyProfileIdAndCompanyProfileIdInOrderByVersionNumberDesc(ownerId, targetIds);
        }

        return all.stream()
                .filter(a -> a.getStatus() == RelationshipAssessmentStatus.FINALIZED)
                .map(a -> toResponse(a, currentUser, null))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CompanyRecentAssessmentSummaryDto> getRecentAssessmentsSummary(UserDetailsImpl currentUser) {
        String ownerId = ownerOrganizationService.getOwnerCompanyProfileId();

        // 1. Fetch at most 2 latest finalized assessments per company (Zero N+1 DB query)
        List<CompanyRelationshipAssessment> assessments;
        try {
            assessments = assessmentRepository.findTop2FinalizedPerCompany(ownerId);
        } catch (Exception e) {
            log.warn("Failed native query findTop2FinalizedPerCompany, falling back to in-memory group: {}", e.getMessage());
            assessments = assessmentRepository.findAllByOwnerCompanyProfileIdAndStatusOrderByVersionNumberDesc(
                    ownerId, RelationshipAssessmentStatus.FINALIZED);
        }

        if (assessments == null || assessments.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. Group by companyProfileId preserving version order
        Map<String, List<CompanyRelationshipAssessment>> grouped = new LinkedHashMap<>();
        for (CompanyRelationshipAssessment a : assessments) {
            grouped.computeIfAbsent(a.getCompanyProfileId(), k -> new ArrayList<>()).add(a);
        }

        // 3. Batch load company profiles (Zero N+1 DB queries)
        Set<String> profileIds = grouped.keySet();
        List<CompanyProfile> loadedProfiles = companyProfileRepository.findAllById(profileIds);
        Map<String, CompanyProfile> profileMap = new HashMap<>();
        for (CompanyProfile p : loadedProfiles) {
            if (p.getId() != null) profileMap.put(p.getId(), p);
            if (p.getCompanyId() != null) profileMap.put(p.getCompanyId(), p);
        }

        Set<String> missing = profileIds.stream().filter(id -> !profileMap.containsKey(id)).collect(Collectors.toSet());
        if (!missing.isEmpty()) {
            List<CompanyProfile> byCompanyId = companyProfileRepository.findByCompanyIdIn(missing);
            for (CompanyProfile p : byCompanyId) {
                if (p.getId() != null) profileMap.put(p.getId(), p);
                if (p.getCompanyId() != null) profileMap.put(p.getCompanyId(), p);
            }
        }

        ZoneId vnZone = ZoneId.of("Asia/Ho_Chi_Minh");
        DateTimeFormatter isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

        List<CompanyRecentAssessmentSummaryDto> results = new ArrayList<>();

        for (Map.Entry<String, List<CompanyRelationshipAssessment>> entry : grouped.entrySet()) {
            String companyProfileId = entry.getKey();
            List<CompanyRelationshipAssessment> list = entry.getValue();
            if (list.isEmpty()) continue;

            list.sort(Comparator.comparing(CompanyRelationshipAssessment::getVersionNumber).reversed());

            CompanyRelationshipAssessment latestEntity = list.get(0);
            CompanyRelationshipAssessment previousEntity = list.size() > 1 ? list.get(1) : null;

            CompanyProfile profile = profileMap.get(companyProfileId);
            String companyId = profile != null && StringUtils.hasText(profile.getCompanyId())
                    ? profile.getCompanyId()
                    : companyProfileId;

            String companyName = profile != null
                    ? CompanyProfile.getCanonicalDisplayName(profile, companyId)
                    : companyProfileId;

            String relationshipType = accessEvaluator.resolveRelationshipType(companyId);

            RecentAssessmentItemDto latestDto = mapToRecentItemDto(latestEntity, vnZone, isoFormatter);
            RecentAssessmentItemDto prevDto = previousEntity != null ? mapToRecentItemDto(previousEntity, vnZone, isoFormatter) : null;

            results.add(CompanyRecentAssessmentSummaryDto.builder()
                    .companyProfileId(companyProfileId)
                    .companyId(companyId)
                    .companyName(companyName)
                    .relationshipType(relationshipType)
                    .latestAssessment(latestDto)
                    .previousAssessment(prevDto)
                    .build());
        }

        return results;
    }

    private RecentAssessmentItemDto mapToRecentItemDto(
            CompanyRelationshipAssessment entity,
            ZoneId zoneId,
            DateTimeFormatter formatter) {
        if (entity == null) return null;

        Integer officialScore = getOfficialTotalScore(entity);
        String officialRank = getOfficialRank(entity);
        String officialRankDesc = null;
        if (officialRank != null) {
            try {
                officialRankDesc = RelationshipAssessmentRank.valueOf(officialRank).getDescription();
            } catch (Exception ignored) {}
        }

        String finalizedAtIso = null;
        LocalDateTime finTime = entity.getFinalizedAt() != null ? entity.getFinalizedAt() : entity.getUpdatedAt();
        if (finTime != null) {
            finalizedAtIso = finTime.atZone(zoneId).format(formatter);
        }

        String actorRole = entity.getAssessmentType() == RelationshipAssessmentType.OWNER_ADJUSTMENT
                ? "BUSINESS_OWNER"
                : "BUSINESS_DEVELOPMENT_MANAGER";

        OfficialCriterionScores crit = getOfficialCriterionScores(entity);
        Map<String, Integer> criteriaMap = new LinkedHashMap<>();
        criteriaMap.put("commercial", crit.commercial());
        criteriaMap.put("interaction", crit.cooperation());
        criteriaMap.put("strategic", crit.strategic());
        criteriaMap.put("network", crit.relationshipNetwork());
        criteriaMap.put("engagement", crit.engagement());
        criteriaMap.put("trust", crit.qualitative());

        return RecentAssessmentItemDto.builder()
                .id(entity.getId())
                .versionNumber(entity.getVersionNumber())
                .assessmentType(entity.getAssessmentType() != null ? entity.getAssessmentType().name() : null)
                .score(officialScore)
                .rank(officialRank)
                .rankDescription(officialRankDesc)
                .finalizedAt(finalizedAtIso)
                .actorRole(actorRole)
                .criteria(criteriaMap)
                .build();
    }

    @Transactional(readOnly = true)
    public CommercialEvidenceResponse getLiveCommercialEvidence(String targetCompanyProfileId) {
        validateRelationshipClosenessEligibility(targetCompanyProfileId);
        List<PartnerContract> approved = partnerContractRepository
                .findByPartnerCompanyIdAndReviewStatus(targetCompanyProfileId, ContractReviewStatus.APPROVED);

        CommercialEvidenceResult eval = scoringPolicy.evaluate(approved, LocalDate.now());
        return toCommercialEvidenceResponse(eval);
    }

    @Transactional(readOnly = true)
    public RelationshipAssessmentResponse getAssessmentById(Long assessmentId, UserDetailsImpl currentUser) {
        CompanyRelationshipAssessment assessment = getAssessmentEntity(assessmentId);
        validateTargetProfile(assessment.getCompanyProfileId());
        validateAccess(assessment.getCompanyProfileId(), currentUser, false);
        return toResponse(assessment, currentUser, null);
    }

    // ----------------------------------------------------------------------------------
    // Manager Actions
    // ----------------------------------------------------------------------------------

    @Transactional
    public RelationshipAssessmentResponse createDraft(
            String targetCompanyProfileId,
            CreateRelationshipAssessmentRequest request,
            UserDetailsImpl currentUser) {

        validateTargetProfile(targetCompanyProfileId);
        validateDraftCreateAccess(targetCompanyProfileId, currentUser);

        String ownerId = ownerOrganizationService.getOwnerCompanyProfileId();
        CompanyProfile target = resolveTargetProfile(targetCompanyProfileId);
        String canonicalCompanyProfileId = target.getId() != null ? target.getId() : targetCompanyProfileId;
        List<String> targetIds = resolveTargetProfileIds(targetCompanyProfileId);

        boolean activeExists = assessmentRepository.existsByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusIn(ownerId, targetCompanyProfileId, ACTIVE_STATUSES);
        if (!activeExists && targetIds.size() > 1) {
            activeExists = assessmentRepository.existsByOwnerCompanyProfileIdAndCompanyProfileIdInAndStatusIn(ownerId, targetIds, ACTIVE_STATUSES);
        }
        if (activeExists) {
            throw new BusinessValidationException("An active assessment already exists for this company profile.");
        }

        // Determine next version number across ALL statuses
        int nextVersion = resolveNextVersionNumber(ownerId, targetCompanyProfileId, targetIds, null);

        // Calculate live commercial snapshot for initial draft
        List<PartnerContract> approved = partnerContractRepository
                .findByPartnerCompanyIdAndReviewStatus(targetCompanyProfileId, ContractReviewStatus.APPROVED);
        CommercialEvidenceResult commercial = scoringPolicy.evaluate(approved, LocalDate.now(), RelationshipCommercialScoringPolicy.POLICY_VERSION_V5);

        Integer commercialAwarded = request != null ? request.getCommercialAwardedScore() : null;
        Integer resolvedQualitativeScore = resolveTrustScore(
                request != null ? request.getQualitativeScore() : null,
                request != null ? request.getTrustScore() : null);
        String resolvedQualitativeNote = resolveTrustNote(
                request != null ? request.getQualitativeEvidenceNote() : null,
                request != null ? request.getTrustEvidenceNote() : null);

        if (request != null) {
            scoreCalculator.validateDraftRangesV5(
                    commercialAwarded,
                    request.getCooperationScore(),
                    request.getStrategicScore(),
                    request.getRelationshipNetworkScore(),
                    request.getEngagementScore(),
                    resolvedQualitativeScore
            );
        }

        CompanyRelationshipAssessment assessment = CompanyRelationshipAssessment.builder()
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(canonicalCompanyProfileId)
                .versionNumber(nextVersion)
                .status(RelationshipAssessmentStatus.DRAFT)
                .assessmentType(RelationshipAssessmentType.MANAGER_ASSESSMENT)
                .scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5)
                .commercialSuggestedScore(null)
                .commercialAwardedScore(commercialAwarded)
                .commercialScore(null)
                .commercialAdjustmentReason(null)
                .commercialEvidenceNote(request != null ? request.getCommercialEvidenceNote() : null)
                .cooperationScore(request != null ? request.getCooperationScore() : null)
                .cooperationEvidenceNote(request != null ? request.getCooperationEvidenceNote() : null)
                .strategicScore(request != null ? request.getStrategicScore() : null)
                .strategicEvidenceNote(request != null ? request.getStrategicEvidenceNote() : null)
                .relationshipNetworkScore(request != null ? request.getRelationshipNetworkScore() : null)
                .relationshipNetworkNote(request != null ? request.getRelationshipNetworkNote() : null)
                .engagementScore(request != null ? request.getEngagementScore() : null)
                .engagementEvidenceNote(request != null ? request.getEngagementEvidenceNote() : null)
                .qualitativeScore(resolvedQualitativeScore)
                .qualitativeEvidenceNote(resolvedQualitativeNote)
                .managerNote(request != null ? request.getManagerNote() : null)
                .createdByAccountId(currentUser.getId())
                .build();

        applyCommercialSnapshot(assessment, commercial);

        try {
            assessment = assessmentRepository.saveAndFlush(assessment);
        } catch (DataIntegrityViolationException e) {
            handleAssessmentDataIntegrityViolation(e, "An active assessment was created concurrently by another user.");
        }

        auditLogService.log(currentUser.getId(), AuditAction.RELATIONSHIP_ASSESSMENT_CREATED,
                "CompanyRelationshipAssessment", String.valueOf(assessment.getId()),
                String.format("Created draft assessment v%d for company %s", assessment.getVersionNumber(), targetCompanyProfileId));

        return toResponse(assessment, currentUser, null);
    }

    @Transactional
    public RelationshipAssessmentResponse updateDraft(
            Long assessmentId,
            UpdateRelationshipAssessmentRequest request,
            UserDetailsImpl currentUser) {

        CompanyRelationshipAssessment assessment = getAssessmentEntity(assessmentId);
        validateRelationshipClosenessEligibility(assessment.getCompanyProfileId());
        validateDraftEditAccess(assessment.getCompanyProfileId(), currentUser, assessment.getStatus());

        if (assessment.getStatus() != RelationshipAssessmentStatus.DRAFT &&
            assessment.getStatus() != RelationshipAssessmentStatus.CHANGES_REQUESTED) {
            throw new BusinessValidationException("Only DRAFT or CHANGES_REQUESTED assessments can be updated.");
        }

        if (assessment.getAssessmentType() == RelationshipAssessmentType.OWNER_ADJUSTMENT) {
            throw new BusinessValidationException("Please use the dedicated owner adjustment update endpoint for Owner Adjustment assessments.");
        }
        if (hasRole(currentUser, SystemRole.BUSINESS_OWNER)) {
            throw new AccessDeniedException("Owner cannot edit a Manager draft assessment.");
        }

        boolean isV5 = RelationshipCommercialScoringPolicy.POLICY_VERSION_V5.equals(assessment.getScoringPolicyVersion());
        boolean isV4 = RelationshipCommercialScoringPolicy.POLICY_VERSION_V4.equals(assessment.getScoringPolicyVersion());
        boolean isV3 = RelationshipCommercialScoringPolicy.POLICY_VERSION_V3.equals(assessment.getScoringPolicyVersion());

        if (request != null) {
            Integer resolvedQualScore = resolveTrustScore(request.getQualitativeScore(), request.getTrustScore());
            String resolvedQualNote = resolveTrustNote(request.getQualitativeEvidenceNote(), request.getTrustEvidenceNote());

            if (isV5) {
                scoreCalculator.validateDraftRangesV5(
                        request.getCommercialAwardedScore(),
                        request.getCooperationScore(),
                        request.getStrategicScore(),
                        request.getRelationshipNetworkScore(),
                        request.getEngagementScore(),
                        resolvedQualScore
                );

                boolean isSnapshot = Boolean.TRUE.equals(request.getFullSnapshot()) || Boolean.TRUE.equals(request.getIsFullSnapshot());
                if (isSnapshot) {
                    assessment.setCommercialAwardedScore(request.getCommercialAwardedScore());
                    assessment.setCommercialScore(null);
                    assessment.setCommercialEvidenceNote(request.getCommercialEvidenceNote());
                    assessment.setCooperationScore(request.getCooperationScore());
                    assessment.setCooperationEvidenceNote(request.getCooperationEvidenceNote());
                    assessment.setStrategicScore(request.getStrategicScore());
                    assessment.setStrategicEvidenceNote(request.getStrategicEvidenceNote());
                    assessment.setRelationshipNetworkScore(request.getRelationshipNetworkScore());
                    assessment.setRelationshipNetworkNote(request.getRelationshipNetworkNote());
                    assessment.setEngagementScore(request.getEngagementScore());
                    assessment.setEngagementEvidenceNote(request.getEngagementEvidenceNote());
                    assessment.setQualitativeScore(resolvedQualScore);
                    assessment.setQualitativeEvidenceNote(resolvedQualNote);
                    if (request.getManagerNote() != null) assessment.setManagerNote(request.getManagerNote());
                } else {
                    if (request.getCommercialAwardedScore() != null) {
                        assessment.setCommercialAwardedScore(request.getCommercialAwardedScore());
                        assessment.setCommercialScore(null);
                    }
                    if (request.getCommercialEvidenceNote() != null) {
                        assessment.setCommercialEvidenceNote(request.getCommercialEvidenceNote());
                    }
                    if (request.getCooperationScore() != null) assessment.setCooperationScore(request.getCooperationScore());
                    if (request.getCooperationEvidenceNote() != null) assessment.setCooperationEvidenceNote(request.getCooperationEvidenceNote());
                    if (request.getStrategicScore() != null) assessment.setStrategicScore(request.getStrategicScore());
                    if (request.getStrategicEvidenceNote() != null) assessment.setStrategicEvidenceNote(request.getStrategicEvidenceNote());
                    if (request.getRelationshipNetworkScore() != null) assessment.setRelationshipNetworkScore(request.getRelationshipNetworkScore());
                    if (request.getRelationshipNetworkNote() != null) assessment.setRelationshipNetworkNote(request.getRelationshipNetworkNote());
                    if (request.getEngagementScore() != null) assessment.setEngagementScore(request.getEngagementScore());
                    if (request.getEngagementEvidenceNote() != null) assessment.setEngagementEvidenceNote(request.getEngagementEvidenceNote());
                    if (resolvedQualScore != null) assessment.setQualitativeScore(resolvedQualScore);
                    if (resolvedQualNote != null) assessment.setQualitativeEvidenceNote(resolvedQualNote);
                    if (request.getManagerNote() != null) assessment.setManagerNote(request.getManagerNote());
                }
            } else if (isV4) {
                scoreCalculator.validateDraftRangesV4(
                        request.getCommercialAwardedScore(),
                        request.getCooperationScore(),
                        request.getStrategicScore(),
                        request.getRelationshipNetworkScore(),
                        request.getEngagementScore(),
                        resolvedQualScore
                );

                if (request.getCommercialAwardedScore() != null) {
                    assessment.setCommercialAwardedScore(request.getCommercialAwardedScore());
                    assessment.setCommercialScore(request.getCommercialAwardedScore());
                }
                if (request.getCommercialEvidenceNote() != null) {
                    assessment.setCommercialEvidenceNote(request.getCommercialEvidenceNote());
                }
                if (request.getCooperationScore() != null) assessment.setCooperationScore(request.getCooperationScore());
                if (request.getCooperationEvidenceNote() != null) assessment.setCooperationEvidenceNote(request.getCooperationEvidenceNote());
                if (request.getStrategicScore() != null) assessment.setStrategicScore(request.getStrategicScore());
                if (request.getStrategicEvidenceNote() != null) assessment.setStrategicEvidenceNote(request.getStrategicEvidenceNote());
                if (request.getRelationshipNetworkScore() != null) assessment.setRelationshipNetworkScore(request.getRelationshipNetworkScore());
                if (request.getRelationshipNetworkNote() != null) assessment.setRelationshipNetworkNote(request.getRelationshipNetworkNote());
                if (request.getEngagementScore() != null) assessment.setEngagementScore(request.getEngagementScore());
                if (request.getEngagementEvidenceNote() != null) assessment.setEngagementEvidenceNote(request.getEngagementEvidenceNote());
                if (resolvedQualScore != null) assessment.setQualitativeScore(resolvedQualScore);
                if (resolvedQualNote != null) assessment.setQualitativeEvidenceNote(resolvedQualNote);
                if (request.getManagerNote() != null) assessment.setManagerNote(request.getManagerNote());
            } else if (isV3) {
                scoreCalculator.validateDraftRangesV3(
                        request.getCommercialAwardedScore(),
                        request.getEngagementScore(),
                        request.getRelationshipNetworkScore()
                );

                if (request.getCommercialAwardedScore() != null) {
                    assessment.setCommercialAwardedScore(request.getCommercialAwardedScore());
                    assessment.setCommercialScore(request.getCommercialAwardedScore());
                }
                if (request.getCommercialAdjustmentReason() != null) {
                    assessment.setCommercialAdjustmentReason(request.getCommercialAdjustmentReason());
                }
                if (request.getRelationshipNetworkScore() != null) assessment.setRelationshipNetworkScore(request.getRelationshipNetworkScore());
                if (request.getRelationshipNetworkNote() != null) assessment.setRelationshipNetworkNote(request.getRelationshipNetworkNote());
                if (request.getEngagementScore() != null) assessment.setEngagementScore(request.getEngagementScore());
                if (request.getEngagementEvidenceNote() != null) assessment.setEngagementEvidenceNote(request.getEngagementEvidenceNote());
                if (request.getManagerNote() != null) assessment.setManagerNote(request.getManagerNote());
            } else {
                scoreCalculator.validateDraftRangesV2(
                        request.getCommercialAwardedScore(),
                        request.getCooperationScore(),
                        request.getStrategicScore(),
                        request.getRelationshipNetworkScore(),
                        request.getEngagementScore(),
                        resolvedQualScore
                );

                if (request.getCommercialAwardedScore() != null) {
                    assessment.setCommercialAwardedScore(request.getCommercialAwardedScore());
                    assessment.setCommercialScore(request.getCommercialAwardedScore());
                }
                if (request.getCommercialAdjustmentReason() != null) {
                    assessment.setCommercialAdjustmentReason(request.getCommercialAdjustmentReason());
                }
                if (request.getCooperationScore() != null) assessment.setCooperationScore(request.getCooperationScore());
                if (request.getCooperationEvidenceNote() != null) assessment.setCooperationEvidenceNote(request.getCooperationEvidenceNote());
                if (request.getStrategicScore() != null) assessment.setStrategicScore(request.getStrategicScore());
                if (request.getStrategicEvidenceNote() != null) assessment.setStrategicEvidenceNote(request.getStrategicEvidenceNote());
                if (request.getRelationshipNetworkScore() != null) assessment.setRelationshipNetworkScore(request.getRelationshipNetworkScore());
                if (request.getRelationshipNetworkNote() != null) assessment.setRelationshipNetworkNote(request.getRelationshipNetworkNote());
                if (request.getEngagementScore() != null) assessment.setEngagementScore(request.getEngagementScore());
                if (request.getEngagementEvidenceNote() != null) assessment.setEngagementEvidenceNote(request.getEngagementEvidenceNote());
                if (resolvedQualScore != null) assessment.setQualitativeScore(resolvedQualScore);
                if (resolvedQualNote != null) assessment.setQualitativeEvidenceNote(resolvedQualNote);
                if (request.getManagerNote() != null) assessment.setManagerNote(request.getManagerNote());
            }
        }

        // If in DRAFT, refresh preview commercial snapshot
        if (assessment.getStatus() == RelationshipAssessmentStatus.DRAFT) {
            List<PartnerContract> approved = partnerContractRepository
                    .findByPartnerCompanyIdAndReviewStatus(assessment.getCompanyProfileId(), ContractReviewStatus.APPROVED);
            CommercialEvidenceResult commercial = scoringPolicy.evaluate(approved, LocalDate.now(), assessment.getScoringPolicyVersion());
            applyCommercialSnapshot(assessment, commercial);
        }

        // Calculate live preview score if all criteria are present
        if (isV5) {
            boolean allPresent = assessment.getCommercialAwardedScore() != null
                    && assessment.getCooperationScore() != null
                    && assessment.getStrategicScore() != null
                    && assessment.getRelationshipNetworkScore() != null
                    && assessment.getEngagementScore() != null
                    && assessment.getQualitativeScore() != null;

            if (allPresent) {
                CalculationResult calc = scoreCalculator.calculateV5(
                        assessment.getCommercialAwardedScore(),
                        assessment.getCooperationScore(),
                        assessment.getStrategicScore(),
                        assessment.getRelationshipNetworkScore(),
                        assessment.getEngagementScore(),
                        assessment.getQualitativeScore()
                );
                assessment.setManagerRawScorableScore(calc.getRawScorableScore());
                assessment.setManagerTotalScore(calc.getNormalizedTotalScore());
                assessment.setManagerRank(calc.getRank().name());
            } else {
                assessment.setManagerRawScorableScore(null);
                assessment.setManagerTotalScore(null);
                assessment.setManagerRank(null);
            }
        } else if (isV3) {
            boolean allPresent = assessment.getCommercialAwardedScore() != null
                    && assessment.getEngagementScore() != null
                    && assessment.getRelationshipNetworkScore() != null;

            if (allPresent) {
                int subtotal = assessment.getCommercialAwardedScore()
                        + assessment.getEngagementScore()
                        + assessment.getRelationshipNetworkScore();
                subtotal = Math.max(0, Math.min(100, subtotal));
                assessment.setManagerTotalScore(subtotal);
                assessment.setManagerRank(RelationshipAssessmentRank.fromScore(subtotal).name());
            } else {
                assessment.setManagerRank(null);
            }
        } else {
            boolean allPresent = assessment.getCommercialAwardedScore() != null
                    && assessment.getCooperationScore() != null
                    && assessment.getStrategicScore() != null
                    && assessment.getRelationshipNetworkScore() != null
                    && assessment.getEngagementScore() != null
                    && assessment.getQualitativeScore() != null;

            if (allPresent) {
                int subtotal = assessment.getCommercialAwardedScore()
                        + assessment.getCooperationScore()
                        + assessment.getStrategicScore()
                        + assessment.getRelationshipNetworkScore()
                        + assessment.getEngagementScore()
                        + assessment.getQualitativeScore();
                subtotal = Math.max(0, Math.min(100, subtotal));
                assessment.setManagerTotalScore(subtotal);
                assessment.setManagerRank(RelationshipAssessmentRank.fromScore(subtotal).name());
            } else {
                assessment.setManagerRank(null);
            }
        }

        assessment = assessmentRepository.save(assessment);

        auditLogService.log(currentUser.getId(), AuditAction.RELATIONSHIP_ASSESSMENT_UPDATED,
                "CompanyRelationshipAssessment", String.valueOf(assessment.getId()),
                String.format("Updated assessment v%d for company %s", assessment.getVersionNumber(), assessment.getCompanyProfileId()));

        return toResponse(assessment, currentUser, null);
    }

    @Transactional
    public RelationshipAssessmentResponse submitAssessment(Long assessmentId, UserDetailsImpl currentUser) {
        CompanyRelationshipAssessment assessment = getAssessmentEntity(assessmentId);
        validateRelationshipClosenessEligibility(assessment.getCompanyProfileId());
        validateAccess(assessment.getCompanyProfileId(), currentUser, true);

        // Correction 5: Submit to Owner = MANAGER ONLY
        if (!hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_MANAGER)) {
            throw new AccessDeniedException("Only Business Development Manager can submit assessments to Owner.");
        }

        if (assessment.getStatus() != RelationshipAssessmentStatus.DRAFT &&
            assessment.getStatus() != RelationshipAssessmentStatus.CHANGES_REQUESTED) {
            throw new BusinessValidationException("Only DRAFT or CHANGES_REQUESTED assessments can be submitted.");
        }

        boolean wasDraft = assessment.getStatus() == RelationshipAssessmentStatus.DRAFT;
        boolean isV5 = RelationshipCommercialScoringPolicy.POLICY_VERSION_V5.equals(assessment.getScoringPolicyVersion());
        boolean isV4 = RelationshipCommercialScoringPolicy.POLICY_VERSION_V4.equals(assessment.getScoringPolicyVersion());
        boolean isV3 = RelationshipCommercialScoringPolicy.POLICY_VERSION_V3.equals(assessment.getScoringPolicyVersion());

        // Freeze Commercial Snapshot at first submission (Correction 6)
        CommercialEvidenceResult commercial = null;
        if (wasDraft) {
            List<PartnerContract> approved = partnerContractRepository
                    .findByPartnerCompanyIdAndReviewStatus(assessment.getCompanyProfileId(), ContractReviewStatus.APPROVED);
            commercial = scoringPolicy.evaluate(approved, LocalDate.now(), assessment.getScoringPolicyVersion());
            applyCommercialSnapshot(assessment, commercial);
        }

        if (!isV5 && !isV4 && assessment.getCommercialAwardedScore() == null) {
            assessment.setCommercialAwardedScore(assessment.getCommercialScore());
        }

        if (isV5) {
            scoreCalculator.validateCompleteSubmissionV5(
                    assessment.getCommercialAwardedScore(),
                    assessment.getCooperationScore(),
                    assessment.getStrategicScore(),
                    assessment.getRelationshipNetworkScore(),
                    assessment.getEngagementScore(),
                    assessment.getQualitativeScore()
            );

            // Evidence notes required for ALL 6 criteria in V5
            if (!StringUtils.hasText(assessment.getCommercialEvidenceNote())) {
                throw new BusinessValidationException("Commercial Relationship evidence note is required before submitting.");
            }
            if (!StringUtils.hasText(assessment.getCooperationEvidenceNote())) {
                throw new BusinessValidationException("Interaction & Cooperation evidence note is required before submitting.");
            }
            if (!StringUtils.hasText(assessment.getStrategicEvidenceNote())) {
                throw new BusinessValidationException("Strategic Importance evidence note is required before submitting.");
            }
            if (!StringUtils.hasText(assessment.getRelationshipNetworkNote())) {
                throw new BusinessValidationException("Relationship Network evidence note is required before submitting.");
            }
            if (!StringUtils.hasText(assessment.getEngagementEvidenceNote())) {
                throw new BusinessValidationException("Business Engagement evidence note is required before submitting.");
            }
            if (!StringUtils.hasText(assessment.getQualitativeEvidenceNote())) {
                throw new BusinessValidationException("Trust & Reliability evidence note is required before submitting.");
            }
            if (!StringUtils.hasText(assessment.getManagerNote())) {
                throw new BusinessValidationException("Overall Assessment Note is required before submitting to Owner.");
            }

            CalculationResult calc = scoreCalculator.calculateV5(
                    assessment.getCommercialAwardedScore(),
                    assessment.getCooperationScore(),
                    assessment.getStrategicScore(),
                    assessment.getRelationshipNetworkScore(),
                    assessment.getEngagementScore(),
                    assessment.getQualitativeScore()
            );

            assessment.setCommercialScore(null);
            assessment.setScorableBase(calc.getScorableBase());
            assessment.setNormalizationApplied(calc.isNormalizationApplied());
            assessment.setManagerRawScorableScore(calc.getRawScorableScore());
            assessment.setManagerTotalScore(calc.getNormalizedTotalScore());
            assessment.setManagerRank(calc.getRank().name());
            assessment.setManagerAccountId(currentUser.getId());
            assessment.setManagerSubmittedAt(LocalDateTime.now());
            assessment.setStatus(RelationshipAssessmentStatus.SUBMITTED);

            assessment = assessmentRepository.save(assessment);

            auditLogService.log(currentUser.getId(), AuditAction.RELATIONSHIP_ASSESSMENT_SUBMITTED,
                    "CompanyRelationshipAssessment", String.valueOf(assessment.getId()),
                    String.format("Submitted assessment v%d (Manager Total: %d, Rank: %s) for company %s",
                            assessment.getVersionNumber(), calc.getNormalizedTotalScore(), calc.getRank(), assessment.getCompanyProfileId()));

            return toResponse(assessment, currentUser, null);
        } else if (isV4) {
            // V4 criteria validation (Commercial 0..35, Cooperation 0..25, Strategic 0..20, Network 0..10, Engagement 0..5, Qualitative 0..5)
            scoreCalculator.validateCompleteSubmissionV4(
                    assessment.getCommercialAwardedScore(),
                    assessment.getCooperationScore(),
                    assessment.getStrategicScore(),
                    assessment.getRelationshipNetworkScore(),
                    assessment.getEngagementScore(),
                    assessment.getQualitativeScore()
            );

            // Evidence notes required for ALL 6 criteria in V4
            if (!StringUtils.hasText(assessment.getCommercialEvidenceNote())) {
                throw new BusinessValidationException("Commercial Relationship evidence note is required before submitting.");
            }
            if (!StringUtils.hasText(assessment.getCooperationEvidenceNote())) {
                throw new BusinessValidationException("Interaction & Cooperation evidence note is required before submitting.");
            }
            if (!StringUtils.hasText(assessment.getStrategicEvidenceNote())) {
                throw new BusinessValidationException("Strategic Relationship evidence note is required before submitting.");
            }
            if (!StringUtils.hasText(assessment.getRelationshipNetworkNote())) {
                throw new BusinessValidationException("Relationship Network evidence note is required before submitting.");
            }
            if (!StringUtils.hasText(assessment.getEngagementEvidenceNote())) {
                throw new BusinessValidationException("Business Engagement evidence note is required before submitting.");
            }
            if (!StringUtils.hasText(assessment.getQualitativeEvidenceNote())) {
                throw new BusinessValidationException("Qualitative Assessment evidence note is required before submitting.");
            }
            if (!StringUtils.hasText(assessment.getManagerNote())) {
                throw new BusinessValidationException("Overall Assessment Note is required before submitting to Owner.");
            }

            CalculationResult calc = scoreCalculator.calculateV4(
                    assessment.getCommercialAwardedScore(),
                    assessment.getCooperationScore(),
                    assessment.getStrategicScore(),
                    assessment.getRelationshipNetworkScore(),
                    assessment.getEngagementScore(),
                    assessment.getQualitativeScore()
            );

            assessment.setManagerRawScorableScore(calc.getRawScorableScore());
            assessment.setManagerTotalScore(calc.getNormalizedTotalScore());
            assessment.setManagerRank(calc.getRank().name());
            assessment.setManagerAccountId(currentUser.getId());
            assessment.setManagerSubmittedAt(LocalDateTime.now());
            assessment.setStatus(RelationshipAssessmentStatus.SUBMITTED);

            assessment = assessmentRepository.save(assessment);

            auditLogService.log(currentUser.getId(), AuditAction.RELATIONSHIP_ASSESSMENT_SUBMITTED,
                    "CompanyRelationshipAssessment", String.valueOf(assessment.getId()),
                    String.format("Submitted assessment v%d (Manager Total: %d, Rank: %s) for company %s",
                            assessment.getVersionNumber(), calc.getNormalizedTotalScore(), calc.getRank(), assessment.getCompanyProfileId()));

            return toResponse(assessment, currentUser, null);
        } else if (isV3) {
            // V3 criteria validation (Commercial 0..50, Engagement 0..20, Network 0..30)
            scoreCalculator.validateCompleteSubmissionV3(
                    assessment.getCommercialAwardedScore(),
                    assessment.getEngagementScore(),
                    assessment.getRelationshipNetworkScore()
            );

            // Commercial adjustment reason mandatory if Awarded != Suggested OR suggestion status is PARTIAL / UNAVAILABLE
            String suggStatus = commercial != null ? commercial.getCommercialSuggestionStatus()
                    : computeCommercialSuggestionInfo(assessment).status();
            boolean isPartialOrUnavailable = !"COMPLETE".equalsIgnoreCase(suggStatus);
            boolean isAdjusted = assessment.getCommercialSuggestedScore() != null &&
                    !Objects.equals(assessment.getCommercialAwardedScore(), assessment.getCommercialSuggestedScore());

            if (isPartialOrUnavailable || isAdjusted) {
                if (!StringUtils.hasText(assessment.getCommercialAdjustmentReason())) {
                    throw new BusinessValidationException("Commercial Adjustment Reason is required when Commercial data is partial/unavailable or differs from suggested score.");
                }
            }

            // Evidence notes required for Engagement & Relationship Network in V3
            if (!StringUtils.hasText(assessment.getEngagementEvidenceNote())) {
                throw new BusinessValidationException("Business Engagement evidence note is required before submitting.");
            }
            if (!StringUtils.hasText(assessment.getRelationshipNetworkNote())) {
                throw new BusinessValidationException("Relationship Network evidence note is required before submitting.");
            }
            if (!StringUtils.hasText(assessment.getManagerNote())) {
                throw new BusinessValidationException("Overall Assessment Note is required before submitting to Owner.");
            }

            CalculationResult calc = scoreCalculator.calculateV3(
                    assessment.getCommercialAwardedScore(),
                    assessment.getEngagementScore(),
                    assessment.getRelationshipNetworkScore()
            );

            assessment.setManagerRawScorableScore(calc.getRawScorableScore());
            assessment.setManagerTotalScore(calc.getNormalizedTotalScore());
            assessment.setManagerRank(calc.getRank().name());
            assessment.setManagerAccountId(currentUser.getId());
            assessment.setManagerSubmittedAt(LocalDateTime.now());
            assessment.setStatus(RelationshipAssessmentStatus.SUBMITTED);

            assessment = assessmentRepository.save(assessment);

            auditLogService.log(currentUser.getId(), AuditAction.RELATIONSHIP_ASSESSMENT_SUBMITTED,
                    "CompanyRelationshipAssessment", String.valueOf(assessment.getId()),
                    String.format("Submitted assessment v%d (Manager Total: %d, Rank: %s) for company %s",
                            assessment.getVersionNumber(), calc.getNormalizedTotalScore(), calc.getRank(), assessment.getCompanyProfileId()));

            return toResponse(assessment, currentUser, null);
        } else {
            // Legacy V2 / V1 criteria validation
            scoreCalculator.validateCompleteSubmissionV2(
                    assessment.getCommercialAwardedScore(),
                    assessment.getCooperationScore(),
                    assessment.getStrategicScore(),
                    assessment.getRelationshipNetworkScore(),
                    assessment.getEngagementScore(),
                    assessment.getQualitativeScore()
            );

            // Correction 4: Commercial adjustment reason required if Awarded != Suggested
            if (assessment.getCommercialSuggestedScore() != null &&
                !Objects.equals(assessment.getCommercialAwardedScore(), assessment.getCommercialSuggestedScore())) {
                if (!StringUtils.hasText(assessment.getCommercialAdjustmentReason())) {
                    throw new BusinessValidationException("Commercial Adjustment Reason is required when Awarded score differs from System Suggested score.");
                }
            }

            // Correction 4: Qualitative justification note required
            if (!StringUtils.hasText(assessment.getQualitativeEvidenceNote()) && !StringUtils.hasText(assessment.getManagerNote())) {
                throw new BusinessValidationException("Qualitative justification note is required before submitting.");
            }

            if (!StringUtils.hasText(assessment.getManagerNote())) {
                throw new BusinessValidationException("Overall Assessment Note is required before submitting to Owner.");
            }

            // Calculate Manager score via V2
            CalculationResult calc = scoreCalculator.calculateV2(
                    assessment.getCommercialAwardedScore(),
                    assessment.getCooperationScore(),
                    assessment.getStrategicScore(),
                    assessment.getRelationshipNetworkScore(),
                    assessment.getEngagementScore(),
                    assessment.getQualitativeScore()
            );

            assessment.setManagerRawScorableScore(calc.getRawScorableScore());
            assessment.setManagerTotalScore(calc.getNormalizedTotalScore());
            assessment.setManagerRank(calc.getRank().name());
            assessment.setManagerAccountId(currentUser.getId());
            assessment.setManagerSubmittedAt(LocalDateTime.now());
            assessment.setStatus(RelationshipAssessmentStatus.SUBMITTED);

            assessment = assessmentRepository.save(assessment);

            auditLogService.log(currentUser.getId(), AuditAction.RELATIONSHIP_ASSESSMENT_SUBMITTED,
                    "CompanyRelationshipAssessment", String.valueOf(assessment.getId()),
                    String.format("Submitted assessment v%d (Manager Total: %d, Rank: %s) for company %s",
                            assessment.getVersionNumber(), calc.getNormalizedTotalScore(), calc.getRank(), assessment.getCompanyProfileId()));

            return toResponse(assessment, currentUser, null);
        }
    }

    @Transactional
    public RelationshipAssessmentResponse completeAssessment(
            Long assessmentId,
            UpdateRelationshipAssessmentRequest request,
            UserDetailsImpl currentUser) {

        CompanyRelationshipAssessment assessment = getAssessmentEntity(assessmentId);
        validateRelationshipClosenessEligibility(assessment.getCompanyProfileId());
        validateAccess(assessment.getCompanyProfileId(), currentUser, true);

        if (!hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_MANAGER)) {
            throw new AccessDeniedException("Only Business Development Manager can complete relationship assessments.");
        }

        // Correction 1: COMPLETE ONLY FROM DRAFT
        if (assessment.getStatus() != RelationshipAssessmentStatus.DRAFT) {
            throw new BusinessValidationException("Only DRAFT assessments can be completed directly by Manager.");
        }

        if (assessment.getAssessmentType() == RelationshipAssessmentType.OWNER_ADJUSTMENT) {
            throw new BusinessValidationException("Please use the dedicated owner adjustment completion endpoint.");
        }

        boolean isV5 = RelationshipCommercialScoringPolicy.POLICY_VERSION_V5.equals(assessment.getScoringPolicyVersion());
        boolean isV4 = RelationshipCommercialScoringPolicy.POLICY_VERSION_V4.equals(assessment.getScoringPolicyVersion());
        boolean isV3 = RelationshipCommercialScoringPolicy.POLICY_VERSION_V3.equals(assessment.getScoringPolicyVersion());

        // Correction 2: Apply final Manager assessment payload atomically if provided
        if (request != null) {
            Integer resolvedQualScore = resolveTrustScore(request.getQualitativeScore(), request.getTrustScore());
            String resolvedQualNote = resolveTrustNote(request.getQualitativeEvidenceNote(), request.getTrustEvidenceNote());

            if (isV5) {
                scoreCalculator.validateDraftRangesV5(
                        request.getCommercialAwardedScore(),
                        request.getCooperationScore(),
                        request.getStrategicScore(),
                        request.getRelationshipNetworkScore(),
                        request.getEngagementScore(),
                        resolvedQualScore
                );

                if (request.getCommercialAwardedScore() != null) {
                    assessment.setCommercialAwardedScore(request.getCommercialAwardedScore());
                    assessment.setCommercialScore(null);
                }
                if (request.getCommercialEvidenceNote() != null) {
                    assessment.setCommercialEvidenceNote(request.getCommercialEvidenceNote());
                }
                if (request.getCooperationScore() != null) assessment.setCooperationScore(request.getCooperationScore());
                if (request.getCooperationEvidenceNote() != null) assessment.setCooperationEvidenceNote(request.getCooperationEvidenceNote());
                if (request.getStrategicScore() != null) assessment.setStrategicScore(request.getStrategicScore());
                if (request.getStrategicEvidenceNote() != null) assessment.setStrategicEvidenceNote(request.getStrategicEvidenceNote());
                if (request.getRelationshipNetworkScore() != null) assessment.setRelationshipNetworkScore(request.getRelationshipNetworkScore());
                if (request.getRelationshipNetworkNote() != null) assessment.setRelationshipNetworkNote(request.getRelationshipNetworkNote());
                if (request.getEngagementScore() != null) assessment.setEngagementScore(request.getEngagementScore());
                if (request.getEngagementEvidenceNote() != null) assessment.setEngagementEvidenceNote(request.getEngagementEvidenceNote());
                if (resolvedQualScore != null) assessment.setQualitativeScore(resolvedQualScore);
                if (resolvedQualNote != null) assessment.setQualitativeEvidenceNote(resolvedQualNote);
                if (request.getManagerNote() != null) assessment.setManagerNote(request.getManagerNote());
            } else if (isV4) {
                scoreCalculator.validateDraftRangesV4(
                        request.getCommercialAwardedScore(),
                        request.getCooperationScore(),
                        request.getStrategicScore(),
                        request.getRelationshipNetworkScore(),
                        request.getEngagementScore(),
                        resolvedQualScore
                );
                if (request.getCommercialAwardedScore() != null) {
                    assessment.setCommercialAwardedScore(request.getCommercialAwardedScore());
                    assessment.setCommercialScore(request.getCommercialAwardedScore());
                }
                if (request.getCommercialEvidenceNote() != null) assessment.setCommercialEvidenceNote(request.getCommercialEvidenceNote());
                if (request.getCooperationScore() != null) assessment.setCooperationScore(request.getCooperationScore());
                if (request.getCooperationEvidenceNote() != null) assessment.setCooperationEvidenceNote(request.getCooperationEvidenceNote());
                if (request.getStrategicScore() != null) assessment.setStrategicScore(request.getStrategicScore());
                if (request.getStrategicEvidenceNote() != null) assessment.setStrategicEvidenceNote(request.getStrategicEvidenceNote());
                if (request.getRelationshipNetworkScore() != null) assessment.setRelationshipNetworkScore(request.getRelationshipNetworkScore());
                if (request.getRelationshipNetworkNote() != null) assessment.setRelationshipNetworkNote(request.getRelationshipNetworkNote());
                if (request.getEngagementScore() != null) assessment.setEngagementScore(request.getEngagementScore());
                if (request.getEngagementEvidenceNote() != null) assessment.setEngagementEvidenceNote(request.getEngagementEvidenceNote());
                if (resolvedQualScore != null) assessment.setQualitativeScore(resolvedQualScore);
                if (resolvedQualNote != null) assessment.setQualitativeEvidenceNote(resolvedQualNote);
                if (request.getManagerNote() != null) assessment.setManagerNote(request.getManagerNote());
            } else if (isV3) {
                scoreCalculator.validateDraftRangesV3(
                        request.getCommercialAwardedScore(),
                        request.getEngagementScore(),
                        request.getRelationshipNetworkScore()
                );
                if (request.getCommercialAwardedScore() != null) {
                    assessment.setCommercialAwardedScore(request.getCommercialAwardedScore());
                    assessment.setCommercialScore(request.getCommercialAwardedScore());
                }
                if (request.getCommercialAdjustmentReason() != null) assessment.setCommercialAdjustmentReason(request.getCommercialAdjustmentReason());
                if (request.getRelationshipNetworkScore() != null) assessment.setRelationshipNetworkScore(request.getRelationshipNetworkScore());
                if (request.getRelationshipNetworkNote() != null) assessment.setRelationshipNetworkNote(request.getRelationshipNetworkNote());
                if (request.getEngagementScore() != null) assessment.setEngagementScore(request.getEngagementScore());
                if (request.getEngagementEvidenceNote() != null) assessment.setEngagementEvidenceNote(request.getEngagementEvidenceNote());
                if (request.getManagerNote() != null) assessment.setManagerNote(request.getManagerNote());
            } else {
                scoreCalculator.validateDraftRangesV2(
                        request.getCommercialAwardedScore(),
                        request.getCooperationScore(),
                        request.getStrategicScore(),
                        request.getRelationshipNetworkScore(),
                        request.getEngagementScore(),
                        resolvedQualScore
                );
                if (request.getCommercialAwardedScore() != null) {
                    assessment.setCommercialAwardedScore(request.getCommercialAwardedScore());
                    assessment.setCommercialScore(request.getCommercialAwardedScore());
                }
                if (request.getCommercialAdjustmentReason() != null) assessment.setCommercialAdjustmentReason(request.getCommercialAdjustmentReason());
                if (request.getCooperationScore() != null) assessment.setCooperationScore(request.getCooperationScore());
                if (request.getCooperationEvidenceNote() != null) assessment.setCooperationEvidenceNote(request.getCooperationEvidenceNote());
                if (request.getStrategicScore() != null) assessment.setStrategicScore(request.getStrategicScore());
                if (request.getStrategicEvidenceNote() != null) assessment.setStrategicEvidenceNote(request.getStrategicEvidenceNote());
                if (request.getRelationshipNetworkScore() != null) assessment.setRelationshipNetworkScore(request.getRelationshipNetworkScore());
                if (request.getRelationshipNetworkNote() != null) assessment.setRelationshipNetworkNote(request.getRelationshipNetworkNote());
                if (request.getEngagementScore() != null) assessment.setEngagementScore(request.getEngagementScore());
                if (request.getEngagementEvidenceNote() != null) assessment.setEngagementEvidenceNote(request.getEngagementEvidenceNote());
                if (resolvedQualScore != null) assessment.setQualitativeScore(resolvedQualScore);
                if (resolvedQualNote != null) assessment.setQualitativeEvidenceNote(resolvedQualNote);
                if (request.getManagerNote() != null) assessment.setManagerNote(request.getManagerNote());
            }
        }

        // Correction 7: COMPLETE: re-read current approved Contract evidence once and freeze snapshot.
        List<PartnerContract> approved = partnerContractRepository
                .findByPartnerCompanyIdAndReviewStatus(assessment.getCompanyProfileId(), ContractReviewStatus.APPROVED);
        CommercialEvidenceResult commercial = scoringPolicy.evaluate(approved, LocalDate.now(), assessment.getScoringPolicyVersion());
        applyCommercialSnapshot(assessment, commercial);

        if (isV5) {
            scoreCalculator.validateCompleteSubmissionV5(
                    assessment.getCommercialAwardedScore(),
                    assessment.getCooperationScore(),
                    assessment.getStrategicScore(),
                    assessment.getRelationshipNetworkScore(),
                    assessment.getEngagementScore(),
                    assessment.getQualitativeScore()
            );

            CalculationResult calc = scoreCalculator.calculateV5(
                    assessment.getCommercialAwardedScore(),
                    assessment.getCooperationScore(),
                    assessment.getStrategicScore(),
                    assessment.getRelationshipNetworkScore(),
                    assessment.getEngagementScore(),
                    assessment.getQualitativeScore()
            );

            assessment.setCommercialScore(null);
            assessment.setScorableBase(calc.getScorableBase());
            assessment.setNormalizationApplied(calc.isNormalizationApplied());
            assessment.setManagerRawScorableScore(calc.getRawScorableScore());
            assessment.setManagerTotalScore(calc.getNormalizedTotalScore());
            assessment.setManagerRank(calc.getRank().name());

            // Correction 3 & 4:
            // managerAccountId = current Manager
            // finalizedAt = completion timestamp
            // managerSubmittedAt = null (no submit step)
            // ownerAccountId and owner score fields remain null
            assessment.setManagerAccountId(currentUser.getId());
            assessment.setManagerSubmittedAt(null);
            assessment.setFinalizedAt(LocalDateTime.now());
            assessment.setStatus(RelationshipAssessmentStatus.FINALIZED);

            assessment = assessmentRepository.save(assessment);

            auditLogService.log(currentUser.getId(), AuditAction.RELATIONSHIP_ASSESSMENT_FINALIZED,
                    "CompanyRelationshipAssessment", String.valueOf(assessment.getId()),
                    String.format("DIRECT_MANAGER_COMPLETION: Finalized assessment v%d (Total: %d, Rank: %s) for company %s",
                            assessment.getVersionNumber(), calc.getNormalizedTotalScore(), calc.getRank(), assessment.getCompanyProfileId()));

            notificationService.notifyRelationshipAssessmentCompleted(assessment, currentUser != null ? currentUser.getId() : null);

            return toResponse(assessment, currentUser, null);
        } else if (isV4) {
            scoreCalculator.validateCompleteSubmissionV4(
                    assessment.getCommercialAwardedScore(),
                    assessment.getCooperationScore(),
                    assessment.getStrategicScore(),
                    assessment.getRelationshipNetworkScore(),
                    assessment.getEngagementScore(),
                    assessment.getQualitativeScore()
            );
            if (!StringUtils.hasText(assessment.getCommercialEvidenceNote())) {
                throw new BusinessValidationException("Commercial Relationship evidence note is required before completing.");
            }
            if (!StringUtils.hasText(assessment.getCooperationEvidenceNote())) {
                throw new BusinessValidationException("Interaction & Cooperation evidence note is required before completing.");
            }
            if (!StringUtils.hasText(assessment.getStrategicEvidenceNote())) {
                throw new BusinessValidationException("Strategic Relationship evidence note is required before completing.");
            }
            if (!StringUtils.hasText(assessment.getRelationshipNetworkNote())) {
                throw new BusinessValidationException("Relationship Network evidence note is required before completing.");
            }
            if (!StringUtils.hasText(assessment.getEngagementEvidenceNote())) {
                throw new BusinessValidationException("Business Engagement evidence note is required before completing.");
            }
            if (!StringUtils.hasText(assessment.getQualitativeEvidenceNote())) {
                throw new BusinessValidationException("Qualitative Assessment evidence note is required before completing.");
            }
            if (!StringUtils.hasText(assessment.getManagerNote())) {
                throw new BusinessValidationException("Overall Assessment Note is required before completing assessment.");
            }

            CalculationResult calc = scoreCalculator.calculateV4(
                    assessment.getCommercialAwardedScore(),
                    assessment.getCooperationScore(),
                    assessment.getStrategicScore(),
                    assessment.getRelationshipNetworkScore(),
                    assessment.getEngagementScore(),
                    assessment.getQualitativeScore()
            );

            assessment.setManagerRawScorableScore(calc.getRawScorableScore());
            assessment.setManagerTotalScore(calc.getNormalizedTotalScore());
            assessment.setManagerRank(calc.getRank().name());
            assessment.setManagerAccountId(currentUser.getId());
            assessment.setManagerSubmittedAt(null);
            assessment.setFinalizedAt(LocalDateTime.now());
            assessment.setStatus(RelationshipAssessmentStatus.FINALIZED);

            assessment = assessmentRepository.save(assessment);

            auditLogService.log(currentUser.getId(), AuditAction.RELATIONSHIP_ASSESSMENT_FINALIZED,
                    "CompanyRelationshipAssessment", String.valueOf(assessment.getId()),
                    String.format("DIRECT_MANAGER_COMPLETION: Finalized assessment v%d (Total: %d, Rank: %s) for company %s",
                            assessment.getVersionNumber(), calc.getNormalizedTotalScore(), calc.getRank(), assessment.getCompanyProfileId()));

            notificationService.notifyRelationshipAssessmentCompleted(assessment, currentUser != null ? currentUser.getId() : null);

            return toResponse(assessment, currentUser, null);
        } else if (isV3) {
            scoreCalculator.validateCompleteSubmissionV3(
                    assessment.getCommercialAwardedScore(),
                    assessment.getEngagementScore(),
                    assessment.getRelationshipNetworkScore()
            );
            if (!StringUtils.hasText(assessment.getEngagementEvidenceNote())) {
                throw new BusinessValidationException("Business Engagement evidence note is required before completing.");
            }
            if (!StringUtils.hasText(assessment.getRelationshipNetworkNote())) {
                throw new BusinessValidationException("Relationship Network evidence note is required before completing.");
            }
            if (!StringUtils.hasText(assessment.getManagerNote())) {
                throw new BusinessValidationException("Overall Assessment Note is required before completing assessment.");
            }

            CalculationResult calc = scoreCalculator.calculateV3(
                    assessment.getCommercialAwardedScore(),
                    assessment.getEngagementScore(),
                    assessment.getRelationshipNetworkScore()
            );

            assessment.setManagerRawScorableScore(calc.getRawScorableScore());
            assessment.setManagerTotalScore(calc.getNormalizedTotalScore());
            assessment.setManagerRank(calc.getRank().name());
            assessment.setManagerAccountId(currentUser.getId());
            assessment.setManagerSubmittedAt(null);
            assessment.setFinalizedAt(LocalDateTime.now());
            assessment.setStatus(RelationshipAssessmentStatus.FINALIZED);

            assessment = assessmentRepository.save(assessment);

            auditLogService.log(currentUser.getId(), AuditAction.RELATIONSHIP_ASSESSMENT_FINALIZED,
                    "CompanyRelationshipAssessment", String.valueOf(assessment.getId()),
                    String.format("DIRECT_MANAGER_COMPLETION: Finalized assessment v%d (Total: %d, Rank: %s) for company %s",
                            assessment.getVersionNumber(), calc.getNormalizedTotalScore(), calc.getRank(), assessment.getCompanyProfileId()));

            notificationService.notifyRelationshipAssessmentCompleted(assessment, currentUser != null ? currentUser.getId() : null);

            return toResponse(assessment, currentUser, null);
        } else {
            scoreCalculator.validateCompleteSubmissionV2(
                    assessment.getCommercialAwardedScore(),
                    assessment.getCooperationScore(),
                    assessment.getStrategicScore(),
                    assessment.getRelationshipNetworkScore(),
                    assessment.getEngagementScore(),
                    assessment.getQualitativeScore()
            );
            if (!StringUtils.hasText(assessment.getManagerNote())) {
                throw new BusinessValidationException("Overall Assessment Note is required before completing assessment.");
            }

            CalculationResult calc = scoreCalculator.calculateV2(
                    assessment.getCommercialAwardedScore(),
                    assessment.getCooperationScore(),
                    assessment.getStrategicScore(),
                    assessment.getRelationshipNetworkScore(),
                    assessment.getEngagementScore(),
                    assessment.getQualitativeScore()
            );

            assessment.setManagerRawScorableScore(calc.getRawScorableScore());
            assessment.setManagerTotalScore(calc.getNormalizedTotalScore());
            assessment.setManagerRank(calc.getRank().name());
            assessment.setManagerAccountId(currentUser.getId());
            assessment.setManagerSubmittedAt(null);
            assessment.setFinalizedAt(LocalDateTime.now());
            assessment.setStatus(RelationshipAssessmentStatus.FINALIZED);

            assessment = assessmentRepository.save(assessment);

            auditLogService.log(currentUser.getId(), AuditAction.RELATIONSHIP_ASSESSMENT_FINALIZED,
                    "CompanyRelationshipAssessment", String.valueOf(assessment.getId()),
                    String.format("DIRECT_MANAGER_COMPLETION: Finalized assessment v%d (Total: %d, Rank: %s) for company %s",
                            assessment.getVersionNumber(), calc.getNormalizedTotalScore(), calc.getRank(), assessment.getCompanyProfileId()));

            notificationService.notifyRelationshipAssessmentCompleted(assessment, currentUser != null ? currentUser.getId() : null);

            return toResponse(assessment, currentUser, null);
        }
    }

    // ----------------------------------------------------------------------------------
    // Owner Actions
    // ----------------------------------------------------------------------------------

    @Transactional
    public RelationshipAssessmentResponse requestChanges(
            Long assessmentId,
            RequestChangesAssessmentRequest request,
            UserDetailsImpl currentUser) {

        validateOwnerAccess(currentUser);
        CompanyRelationshipAssessment assessment = getAssessmentEntity(assessmentId);
        validateRelationshipClosenessEligibility(assessment.getCompanyProfileId());

        if (assessment.getStatus() != RelationshipAssessmentStatus.SUBMITTED) {
            throw new BusinessValidationException("Can only request changes on a SUBMITTED assessment.");
        }

        if (request == null || !StringUtils.hasText(request.getReason())) {
            throw new BusinessValidationException("Reason for requesting changes is mandatory.");
        }

        assessment.setStatus(RelationshipAssessmentStatus.CHANGES_REQUESTED);
        assessment.setChangesRequestedReason(request.getReason().trim());
        assessment.setChangesRequestedByAccountId(currentUser.getId());
        assessment.setChangesRequestedAt(LocalDateTime.now());

        assessment = assessmentRepository.save(assessment);

        auditLogService.log(currentUser.getId(), AuditAction.RELATIONSHIP_ASSESSMENT_CHANGES_REQUESTED,
                "CompanyRelationshipAssessment", String.valueOf(assessment.getId()),
                String.format("Requested changes on assessment v%d: %s", assessment.getVersionNumber(), request.getReason().trim()));

        return toResponse(assessment, currentUser, null);
    }

    @Transactional
    public RelationshipAssessmentResponse finalizeAssessment(
            Long assessmentId,
            FinalizeRelationshipAssessmentRequest request,
            UserDetailsImpl currentUser) {

        validateOwnerAccess(currentUser);
        CompanyRelationshipAssessment assessment = getAssessmentEntity(assessmentId);
        validateRelationshipClosenessEligibility(assessment.getCompanyProfileId());

        if (assessment.getStatus() != RelationshipAssessmentStatus.SUBMITTED) {
            throw new BusinessValidationException("Only a SUBMITTED assessment can be finalized.");
        }

        boolean isV5 = RelationshipCommercialScoringPolicy.POLICY_VERSION_V5.equals(assessment.getScoringPolicyVersion());
        boolean isV4 = RelationshipCommercialScoringPolicy.POLICY_VERSION_V4.equals(assessment.getScoringPolicyVersion());
        boolean isV3 = RelationshipCommercialScoringPolicy.POLICY_VERSION_V3.equals(assessment.getScoringPolicyVersion());

        if (isV5) {
            Integer resolvedOwnerTrust = resolveOwnerTrustScore(
                    request != null ? request.getOwnerQualitativeScore() : null,
                    request != null ? request.getOwnerTrustScore() : null
            );

            int ownerComm = request != null && request.getOwnerCommercialScore() != null
                    ? request.getOwnerCommercialScore()
                    : (assessment.getCommercialAwardedScore() != null ? assessment.getCommercialAwardedScore() : 0);

            int ownerCoop = request != null && request.getOwnerCooperationScore() != null
                    ? request.getOwnerCooperationScore()
                    : (assessment.getCooperationScore() != null ? assessment.getCooperationScore() : 0);

            int ownerStrat = request != null && request.getOwnerStrategicScore() != null
                    ? request.getOwnerStrategicScore()
                    : (assessment.getStrategicScore() != null ? assessment.getStrategicScore() : 0);

            int ownerNet = request != null && request.getOwnerRelationshipNetworkScore() != null
                    ? request.getOwnerRelationshipNetworkScore()
                    : (assessment.getRelationshipNetworkScore() != null ? assessment.getRelationshipNetworkScore() : 0);

            int ownerEng = request != null && request.getOwnerEngagementScore() != null
                    ? request.getOwnerEngagementScore()
                    : (assessment.getEngagementScore() != null ? assessment.getEngagementScore() : 0);

            int ownerQual = resolvedOwnerTrust != null
                    ? resolvedOwnerTrust
                    : (assessment.getQualitativeScore() != null ? assessment.getQualitativeScore() : 0);

            scoreCalculator.validateDraftRangesV5(ownerComm, ownerCoop, ownerStrat, ownerNet, ownerEng, ownerQual);

            // Check if Owner modified any of Manager's submitted values
            boolean criteriaAdjusted =
                    !Objects.equals(ownerComm, assessment.getCommercialAwardedScore()) ||
                    !Objects.equals(ownerCoop, assessment.getCooperationScore()) ||
                    !Objects.equals(ownerStrat, assessment.getStrategicScore()) ||
                    !Objects.equals(ownerNet, assessment.getRelationshipNetworkScore()) ||
                    !Objects.equals(ownerEng, assessment.getEngagementScore()) ||
                    !Objects.equals(ownerQual, assessment.getQualitativeScore());

            if (criteriaAdjusted) {
                if (request == null || !StringUtils.hasText(request.getOwnerAdjustmentReason())) {
                    throw new BusinessValidationException("Owner Adjustment Reason is mandatory when modifying Manager criteria.");
                }
            }

            CalculationResult ownerCalc = scoreCalculator.calculateV5(
                    ownerComm,
                    ownerCoop,
                    ownerStrat,
                    ownerNet,
                    ownerEng,
                    ownerQual
            );

            // Persist complete Owner final snapshot (all 6 criteria)
            assessment.setOwnerCommercialScore(ownerComm);
            assessment.setOwnerCooperationScore(ownerCoop);
            assessment.setOwnerStrategicScore(ownerStrat);
            assessment.setOwnerRelationshipNetworkScore(ownerNet);
            assessment.setOwnerRelationshipNetworkNote(request != null && request.getOwnerRelationshipNetworkNote() != null
                    ? request.getOwnerRelationshipNetworkNote()
                    : assessment.getRelationshipNetworkNote());
            assessment.setOwnerEngagementScore(ownerEng);
            assessment.setOwnerQualitativeScore(ownerQual);
            assessment.setOwnerNote(request != null ? request.getOwnerNote() : null);
            assessment.setOwnerAdjustmentReason(request != null ? request.getOwnerAdjustmentReason() : null);

            assessment.setOwnerRawScorableScore(ownerCalc.getRawScorableScore());
            assessment.setOwnerFinalTotalScore(ownerCalc.getNormalizedTotalScore());
            assessment.setOwnerFinalRank(ownerCalc.getRank().name());
            assessment.setOwnerAccountId(currentUser.getId());
            assessment.setFinalizedAt(LocalDateTime.now());
            assessment.setStatus(RelationshipAssessmentStatus.FINALIZED);

            assessment = assessmentRepository.save(assessment);

            String auditDetail = String.format("Finalized assessment v%d (Final Score: %d, Rank: %s). Criteria adjusted: %s",
                    assessment.getVersionNumber(), ownerCalc.getNormalizedTotalScore(), ownerCalc.getRank(), criteriaAdjusted);
            auditLogService.log(currentUser.getId(), AuditAction.RELATIONSHIP_ASSESSMENT_FINALIZED,
                    "CompanyRelationshipAssessment", String.valueOf(assessment.getId()), auditDetail);

            return toResponse(assessment, currentUser, null);
        } else if (isV4) {
            int ownerComm = request != null && request.getOwnerCommercialScore() != null
                    ? request.getOwnerCommercialScore()
                    : (assessment.getCommercialAwardedScore() != null ? assessment.getCommercialAwardedScore() : 0);

            int ownerCoop = request != null && request.getOwnerCooperationScore() != null
                    ? request.getOwnerCooperationScore()
                    : (assessment.getCooperationScore() != null ? assessment.getCooperationScore() : 0);

            int ownerStrat = request != null && request.getOwnerStrategicScore() != null
                    ? request.getOwnerStrategicScore()
                    : (assessment.getStrategicScore() != null ? assessment.getStrategicScore() : 0);

            int ownerNet = request != null && request.getOwnerRelationshipNetworkScore() != null
                    ? request.getOwnerRelationshipNetworkScore()
                    : (assessment.getRelationshipNetworkScore() != null ? assessment.getRelationshipNetworkScore() : 0);

            int ownerEng = request != null && request.getOwnerEngagementScore() != null
                    ? request.getOwnerEngagementScore()
                    : (assessment.getEngagementScore() != null ? assessment.getEngagementScore() : 0);

            int ownerQual = request != null && request.getOwnerQualitativeScore() != null
                    ? request.getOwnerQualitativeScore()
                    : (assessment.getQualitativeScore() != null ? assessment.getQualitativeScore() : 0);

            // Check if Owner modified any of Manager's submitted values
            boolean criteriaAdjusted =
                    !Objects.equals(ownerComm, assessment.getCommercialAwardedScore()) ||
                    !Objects.equals(ownerCoop, assessment.getCooperationScore()) ||
                    !Objects.equals(ownerStrat, assessment.getStrategicScore()) ||
                    !Objects.equals(ownerNet, assessment.getRelationshipNetworkScore()) ||
                    !Objects.equals(ownerEng, assessment.getEngagementScore()) ||
                    !Objects.equals(ownerQual, assessment.getQualitativeScore());

            if (criteriaAdjusted) {
                if (request == null || !StringUtils.hasText(request.getOwnerAdjustmentReason())) {
                    throw new BusinessValidationException("Owner Adjustment Reason is mandatory when modifying Manager criteria.");
                }
            }

            CalculationResult ownerCalc = scoreCalculator.calculateV4(
                    ownerComm,
                    ownerCoop,
                    ownerStrat,
                    ownerNet,
                    ownerEng,
                    ownerQual
            );

            // Persist complete Owner final snapshot (all 6 criteria)
            assessment.setOwnerCommercialScore(ownerComm);
            assessment.setOwnerCooperationScore(ownerCoop);
            assessment.setOwnerStrategicScore(ownerStrat);
            assessment.setOwnerRelationshipNetworkScore(ownerNet);
            assessment.setOwnerRelationshipNetworkNote(request != null && request.getOwnerRelationshipNetworkNote() != null
                    ? request.getOwnerRelationshipNetworkNote()
                    : assessment.getRelationshipNetworkNote());
            assessment.setOwnerEngagementScore(ownerEng);
            assessment.setOwnerQualitativeScore(ownerQual);
            assessment.setOwnerNote(request != null ? request.getOwnerNote() : null);
            assessment.setOwnerAdjustmentReason(request != null ? request.getOwnerAdjustmentReason() : null);

            assessment.setOwnerRawScorableScore(ownerCalc.getRawScorableScore());
            assessment.setOwnerFinalTotalScore(ownerCalc.getNormalizedTotalScore());
            assessment.setOwnerFinalRank(ownerCalc.getRank().name());
            assessment.setOwnerAccountId(currentUser.getId());
            assessment.setFinalizedAt(LocalDateTime.now());
            assessment.setStatus(RelationshipAssessmentStatus.FINALIZED);

            assessment = assessmentRepository.save(assessment);

            String auditDetail = String.format("Finalized assessment v%d (Final Score: %d, Rank: %s). Criteria adjusted: %s",
                    assessment.getVersionNumber(), ownerCalc.getNormalizedTotalScore(), ownerCalc.getRank(), criteriaAdjusted);
            auditLogService.log(currentUser.getId(), AuditAction.RELATIONSHIP_ASSESSMENT_FINALIZED,
                    "CompanyRelationshipAssessment", String.valueOf(assessment.getId()), auditDetail);

            return toResponse(assessment, currentUser, null);
        } else if (isV3) {
            int ownerComm = request != null && request.getOwnerCommercialScore() != null
                    ? request.getOwnerCommercialScore()
                    : (assessment.getCommercialAwardedScore() != null ? assessment.getCommercialAwardedScore() : 0);

            int ownerEng = request != null && request.getOwnerEngagementScore() != null
                    ? request.getOwnerEngagementScore()
                    : (assessment.getEngagementScore() != null ? assessment.getEngagementScore() : 0);

            int ownerNet = request != null && request.getOwnerRelationshipNetworkScore() != null
                    ? request.getOwnerRelationshipNetworkScore()
                    : (assessment.getRelationshipNetworkScore() != null ? assessment.getRelationshipNetworkScore() : 0);

            // Check if Owner modified any of Manager's submitted values
            boolean criteriaAdjusted =
                    !Objects.equals(ownerComm, assessment.getCommercialAwardedScore()) ||
                    !Objects.equals(ownerEng, assessment.getEngagementScore()) ||
                    !Objects.equals(ownerNet, assessment.getRelationshipNetworkScore());

            if (criteriaAdjusted) {
                if (request == null || !StringUtils.hasText(request.getOwnerAdjustmentReason())) {
                    throw new BusinessValidationException("Owner Adjustment Reason is mandatory when modifying Manager criteria.");
                }
            }

            CalculationResult ownerCalc = scoreCalculator.calculateV3(
                    ownerComm,
                    ownerEng,
                    ownerNet
            );

            assessment.setOwnerCommercialScore(ownerComm);
            assessment.setOwnerEngagementScore(ownerEng);
            assessment.setOwnerRelationshipNetworkScore(ownerNet);
            assessment.setOwnerRelationshipNetworkNote(request != null && request.getOwnerRelationshipNetworkNote() != null
                    ? request.getOwnerRelationshipNetworkNote()
                    : assessment.getRelationshipNetworkNote());
            assessment.setOwnerNote(request != null ? request.getOwnerNote() : null);
            assessment.setOwnerAdjustmentReason(request != null ? request.getOwnerAdjustmentReason() : null);

            assessment.setOwnerRawScorableScore(ownerCalc.getRawScorableScore());
            assessment.setOwnerFinalTotalScore(ownerCalc.getNormalizedTotalScore());
            assessment.setOwnerFinalRank(ownerCalc.getRank().name());
            assessment.setOwnerAccountId(currentUser.getId());
            assessment.setFinalizedAt(LocalDateTime.now());
            assessment.setStatus(RelationshipAssessmentStatus.FINALIZED);

            assessment = assessmentRepository.save(assessment);

            String auditDetail = String.format("Finalized assessment v%d (Final Score: %d, Rank: %s). Criteria adjusted: %s",
                    assessment.getVersionNumber(), ownerCalc.getNormalizedTotalScore(), ownerCalc.getRank(), criteriaAdjusted);
            auditLogService.log(currentUser.getId(), AuditAction.RELATIONSHIP_ASSESSMENT_FINALIZED,
                    "CompanyRelationshipAssessment", String.valueOf(assessment.getId()), auditDetail);

            return toResponse(assessment, currentUser, null);
        } else {
            // Determine Owner criteria (default to Manager criteria if not specified by Owner)
            int ownerComm = request != null && request.getOwnerCommercialScore() != null
                    ? request.getOwnerCommercialScore()
                    : (assessment.getCommercialAwardedScore() != null ? assessment.getCommercialAwardedScore() : 0);

            int ownerCoop = request != null && request.getOwnerCooperationScore() != null
                    ? request.getOwnerCooperationScore()
                    : (assessment.getCooperationScore() != null ? assessment.getCooperationScore() : 0);

            int ownerStrat = request != null && request.getOwnerStrategicScore() != null
                    ? request.getOwnerStrategicScore()
                    : (assessment.getStrategicScore() != null ? assessment.getStrategicScore() : 0);

            int ownerNet = request != null && request.getOwnerRelationshipNetworkScore() != null
                    ? request.getOwnerRelationshipNetworkScore()
                    : (assessment.getRelationshipNetworkScore() != null ? assessment.getRelationshipNetworkScore() : 0);

            int ownerEng = request != null && request.getOwnerEngagementScore() != null
                    ? request.getOwnerEngagementScore()
                    : (assessment.getEngagementScore() != null ? assessment.getEngagementScore() : 0);

            int ownerQual = request != null && request.getOwnerQualitativeScore() != null
                    ? request.getOwnerQualitativeScore()
                    : (assessment.getQualitativeScore() != null ? assessment.getQualitativeScore() : 0);

            // Check if Owner modified any of Manager's submitted values (Correction 2)
            boolean criteriaAdjusted =
                    !Objects.equals(ownerComm, assessment.getCommercialAwardedScore()) ||
                    !Objects.equals(ownerCoop, assessment.getCooperationScore()) ||
                    !Objects.equals(ownerStrat, assessment.getStrategicScore()) ||
                    !Objects.equals(ownerNet, assessment.getRelationshipNetworkScore()) ||
                    !Objects.equals(ownerEng, assessment.getEngagementScore()) ||
                    !Objects.equals(ownerQual, assessment.getQualitativeScore());

            if (criteriaAdjusted) {
                if (request == null || !StringUtils.hasText(request.getOwnerAdjustmentReason())) {
                    throw new BusinessValidationException("Owner Adjustment Reason is mandatory when modifying Manager criteria.");
                }
            }

            // Calculate Owner total via V2 (no 85-pt normalization on manual total!)
            CalculationResult ownerCalc = scoreCalculator.calculateV2(
                    ownerComm,
                    ownerCoop,
                    ownerStrat,
                    ownerNet,
                    ownerEng,
                    ownerQual
            );

            // User Correction 2: Preserve Manager score and Owner final score separately
            assessment.setOwnerCommercialScore(ownerComm);
            assessment.setOwnerCooperationScore(ownerCoop);
            assessment.setOwnerStrategicScore(ownerStrat);
            assessment.setOwnerRelationshipNetworkScore(ownerNet);
            assessment.setOwnerRelationshipNetworkNote(request != null && request.getOwnerRelationshipNetworkNote() != null
                    ? request.getOwnerRelationshipNetworkNote()
                    : assessment.getRelationshipNetworkNote());
            assessment.setOwnerEngagementScore(ownerEng);
            assessment.setOwnerQualitativeScore(ownerQual);
            assessment.setOwnerNote(request != null ? request.getOwnerNote() : null);
            assessment.setOwnerAdjustmentReason(request != null ? request.getOwnerAdjustmentReason() : null);

            assessment.setOwnerRawScorableScore(ownerCalc.getRawScorableScore());
            assessment.setOwnerFinalTotalScore(ownerCalc.getNormalizedTotalScore());
            assessment.setOwnerFinalRank(ownerCalc.getRank().name());
            assessment.setOwnerAccountId(currentUser.getId());
            assessment.setFinalizedAt(LocalDateTime.now());
            assessment.setStatus(RelationshipAssessmentStatus.FINALIZED);

            assessment = assessmentRepository.save(assessment);

            String auditDetail = String.format("Finalized assessment v%d (Final Score: %d, Rank: %s). Criteria adjusted: %s",
                    assessment.getVersionNumber(), ownerCalc.getNormalizedTotalScore(), ownerCalc.getRank(), criteriaAdjusted);
            auditLogService.log(currentUser.getId(), AuditAction.RELATIONSHIP_ASSESSMENT_FINALIZED,
                    "CompanyRelationshipAssessment", String.valueOf(assessment.getId()), auditDetail);

            return toResponse(assessment, currentUser, null);
        }
    }

    // ----------------------------------------------------------------------------------
    // Reassessment / New Version
    // ----------------------------------------------------------------------------------

    @Transactional
    public RelationshipAssessmentResponse createNewVersion(String targetCompanyProfileId, UserDetailsImpl currentUser) {
        validateTargetProfile(targetCompanyProfileId);
        validateManagerAccess(targetCompanyProfileId, currentUser);

        String ownerId = ownerOrganizationService.getOwnerCompanyProfileId();
        CompanyProfile target = resolveTargetProfile(targetCompanyProfileId);
        String canonicalCompanyProfileId = target.getId() != null ? target.getId() : targetCompanyProfileId;
        List<String> targetIds = resolveTargetProfileIds(targetCompanyProfileId);

        boolean activeExists = assessmentRepository.existsByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusIn(ownerId, targetCompanyProfileId, ACTIVE_STATUSES);
        if (!activeExists && targetIds.size() > 1) {
            activeExists = assessmentRepository.existsByOwnerCompanyProfileIdAndCompanyProfileIdInAndStatusIn(ownerId, targetIds, ACTIVE_STATUSES);
        }
        if (activeExists) {
            throw new BusinessValidationException("Cannot create a new version while an active assessment exists.");
        }

        // Must have at least one finalized assessment
        Optional<CompanyRelationshipAssessment> latestFinalizedOpt = assessmentRepository
                .findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
                        ownerId, targetCompanyProfileId, RelationshipAssessmentStatus.FINALIZED);
        if (latestFinalizedOpt.isEmpty() && targetIds.size() > 1) {
            latestFinalizedOpt = assessmentRepository
                    .findFirstByOwnerCompanyProfileIdAndCompanyProfileIdInAndStatusOrderByVersionNumberDesc(
                            ownerId, targetIds, RelationshipAssessmentStatus.FINALIZED);
        }
        CompanyRelationshipAssessment latestFinalized = latestFinalizedOpt
                .orElseThrow(() -> new BusinessValidationException("A previous finalized assessment is required to start a new version."));

        int nextVersion = resolveNextVersionNumber(ownerId, targetCompanyProfileId, targetIds, latestFinalized.getVersionNumber());

        // Fresh commercial calculation from CURRENT approved contracts for V5
        List<PartnerContract> approved = partnerContractRepository
                .findByPartnerCompanyIdAndReviewStatus(targetCompanyProfileId, ContractReviewStatus.APPROVED);
        CommercialEvidenceResult commercial = scoringPolicy.evaluate(approved, LocalDate.now(), RelationshipCommercialScoringPolicy.POLICY_VERSION_V5);

        CompanyRelationshipAssessment newAssessment = CompanyRelationshipAssessment.builder()
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(canonicalCompanyProfileId)
                .versionNumber(nextVersion)
                .status(RelationshipAssessmentStatus.DRAFT)
                .assessmentType(RelationshipAssessmentType.MANAGER_ASSESSMENT)
                .scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5)
                .commercialSuggestedScore(null)
                .commercialAwardedScore(null)
                .commercialScore(null)
                .commercialAdjustmentReason(null)
                .commercialEvidenceNote(null)
                .cooperationScore(null)
                .cooperationEvidenceNote(null)
                .strategicScore(null)
                .strategicEvidenceNote(null)
                .relationshipNetworkScore(null)
                .relationshipNetworkNote(null)
                .engagementScore(null)
                .engagementEvidenceNote(null)
                .qualitativeScore(null)
                .qualitativeEvidenceNote(null)
                .managerNote(null)
                .createdByAccountId(currentUser.getId())
                .build();

        applyCommercialSnapshot(newAssessment, commercial);

        try {
            newAssessment = assessmentRepository.saveAndFlush(newAssessment);
        } catch (DataIntegrityViolationException e) {
            handleAssessmentDataIntegrityViolation(e, "An active assessment was created concurrently.");
        }

        auditLogService.log(currentUser.getId(), AuditAction.RELATIONSHIP_ASSESSMENT_CREATED,
                "CompanyRelationshipAssessment", String.valueOf(newAssessment.getId()),
                String.format("Started reassessment v%d for company %s", newAssessment.getVersionNumber(), targetCompanyProfileId));

        return toResponse(newAssessment, currentUser, latestFinalized);
    }

    // ----------------------------------------------------------------------------------
    // Owner Adjustment Workflow
    // ----------------------------------------------------------------------------------

    @Transactional
    public RelationshipAssessmentResponse createOwnerAdjustment(Long sourceAssessmentId, UserDetailsImpl currentUser) {
        validateOwnerAccess(currentUser);

        CompanyRelationshipAssessment source = getAssessmentEntity(sourceAssessmentId);
        validateRelationshipClosenessEligibility(source.getCompanyProfileId());
        validateAccess(source.getCompanyProfileId(), currentUser, true);

        if (source.getStatus() != RelationshipAssessmentStatus.FINALIZED) {
            throw new BusinessValidationException("Only FINALIZED assessments can be adjusted.");
        }

        String ownerId = ownerOrganizationService.getOwnerCompanyProfileId();
        List<String> targetIds = resolveTargetProfileIds(source.getCompanyProfileId());

        // Verify source is the latest official finalized assessment
        Optional<CompanyRelationshipAssessment> latestFinalizedOpt = assessmentRepository
                .findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
                        ownerId, source.getCompanyProfileId(), RelationshipAssessmentStatus.FINALIZED);
        if (latestFinalizedOpt.isEmpty() && targetIds.size() > 1) {
            latestFinalizedOpt = assessmentRepository
                    .findFirstByOwnerCompanyProfileIdAndCompanyProfileIdInAndStatusOrderByVersionNumberDesc(
                            ownerId, targetIds, RelationshipAssessmentStatus.FINALIZED);
        }
        CompanyRelationshipAssessment latestFinalized = latestFinalizedOpt
                .orElseThrow(() -> new BusinessValidationException("No official finalized assessment found for this company."));

        if (!latestFinalized.getId().equals(source.getId())) {
            throw new BusinessValidationException("Owner adjustment can only be created from the latest official finalized assessment.");
        }

        // Verify no active assessment exists
        boolean activeExists = assessmentRepository.existsByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusIn(ownerId, source.getCompanyProfileId(), ACTIVE_STATUSES);
        if (!activeExists && targetIds.size() > 1) {
            activeExists = assessmentRepository.existsByOwnerCompanyProfileIdAndCompanyProfileIdInAndStatusIn(ownerId, targetIds, ACTIVE_STATUSES);
        }
        if (activeExists) {
            throw new BusinessValidationException("An active assessment already exists for this company profile.");
        }

        int nextVersion = resolveNextVersionNumber(ownerId, source.getCompanyProfileId(), targetIds, latestFinalized.getVersionNumber());

        OfficialCriterionScores officialBaseline = getOfficialCriterionScores(source);

        CompanyRelationshipAssessment adjustment = CompanyRelationshipAssessment.builder()
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(source.getCompanyProfileId())
                .versionNumber(nextVersion)
                .status(RelationshipAssessmentStatus.DRAFT)
                .assessmentType(RelationshipAssessmentType.OWNER_ADJUSTMENT)
                .sourceAssessmentId(source.getId())
                .scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5)
                // Copy commercial evidence snapshot exactly from source
                .commercialSuggestedScore(source.getCommercialSuggestedScore())
                .commercialScore(source.getCommercialScore())
                .contractValueScore(source.getContractValueScore())
                .contractCountScore(source.getContractCountScore())
                .relationshipDurationScore(source.getRelationshipDurationScore())
                .contractRecencyScore(source.getContractRecencyScore())
                .approvedContractCount(source.getApprovedContractCount())
                .totalContractValueVnd(source.getTotalContractValueVnd())
                .contractCurrencies(source.getContractCurrencies())
                .currencyBreakdown(source.getCurrencyBreakdown())
                .contractValueStatus(source.getContractValueStatus())
                .firstCooperationDate(source.getFirstCooperationDate())
                .latestContractDate(source.getLatestContractDate())
                .upcomingContractCount(source.getUpcomingContractCount())
                .scorableBase(source.getScorableBase())
                .normalizationApplied(source.getNormalizationApplied())
                // Baseline snapshot scores populated with official values from source
                .commercialAwardedScore(officialBaseline.commercial())
                .commercialAdjustmentReason(null)
                .commercialEvidenceNote(source.getCommercialEvidenceNote())
                .cooperationScore(officialBaseline.cooperation())
                .cooperationEvidenceNote(source.getCooperationEvidenceNote())
                .strategicScore(officialBaseline.strategic())
                .strategicEvidenceNote(source.getStrategicEvidenceNote())
                .relationshipNetworkScore(officialBaseline.relationshipNetwork())
                .relationshipNetworkNote(officialBaseline.relationshipNetworkNote())
                .engagementScore(officialBaseline.engagement())
                .engagementEvidenceNote(source.getEngagementEvidenceNote())
                .qualitativeScore(officialBaseline.qualitative())
                .qualitativeEvidenceNote(source.getQualitativeEvidenceNote())
                .managerNote(null)
                .managerRawScorableScore(officialBaseline.rawScorableScore())
                .managerTotalScore(officialBaseline.totalScore())
                .managerRank(officialBaseline.rank())
                .managerAccountId(null) // Correction 2: DO NOT set managerAccountId on Owner Adjustment
                // Prefill Owner scores from official baseline
                .ownerCommercialScore(officialBaseline.commercial())
                .ownerCooperationScore(officialBaseline.cooperation())
                .ownerStrategicScore(officialBaseline.strategic())
                .ownerRelationshipNetworkScore(officialBaseline.relationshipNetwork())
                .ownerRelationshipNetworkNote(officialBaseline.relationshipNetworkNote())
                .ownerEngagementScore(officialBaseline.engagement())
                .ownerQualitativeScore(officialBaseline.qualitative())
                .ownerRawScorableScore(officialBaseline.rawScorableScore())
                .ownerFinalTotalScore(officialBaseline.totalScore())
                .ownerFinalRank(officialBaseline.rank())
                .ownerNote(null)
                .ownerAdjustmentReason(null)
                .createdByAccountId(currentUser.getId())
                .build();

        try {
            adjustment = assessmentRepository.saveAndFlush(adjustment);
        } catch (DataIntegrityViolationException e) {
            handleAssessmentDataIntegrityViolation(e, "An active assessment was created concurrently by another user.");
        }

        auditLogService.log(currentUser.getId(), AuditAction.RELATIONSHIP_ASSESSMENT_CREATED,
                "CompanyRelationshipAssessment", String.valueOf(adjustment.getId()),
                String.format("Created Owner Adjustment v%d based on v%d for company %s",
                        adjustment.getVersionNumber(), source.getVersionNumber(), source.getCompanyProfileId()));

        return toResponse(adjustment, currentUser, latestFinalized);
    }

    @Transactional
    public RelationshipAssessmentResponse updateOwnerAdjustment(
            Long assessmentId,
            OwnerAdjustmentUpdateRequest request,
            UserDetailsImpl currentUser) {

        validateOwnerAccess(currentUser);
        CompanyRelationshipAssessment assessment = getAssessmentEntity(assessmentId);
        validateRelationshipClosenessEligibility(assessment.getCompanyProfileId());
        validateAccess(assessment.getCompanyProfileId(), currentUser, true);

        if (assessment.getStatus() != RelationshipAssessmentStatus.DRAFT) {
            throw new BusinessValidationException("Only DRAFT owner adjustments can be updated.");
        }

        if (assessment.getAssessmentType() != RelationshipAssessmentType.OWNER_ADJUSTMENT) {
            throw new BusinessValidationException("This assessment is not an Owner Adjustment.");
        }

        if (request != null) {
            validateOwnerScoreRange("Commercial", request.getOwnerCommercialScore());
            validateOwnerScoreRange("Cooperation", request.getOwnerCooperationScore());
            validateOwnerScoreRange("Strategic", request.getOwnerStrategicScore());
            validateOwnerScoreRange("Relationship Network", request.getOwnerRelationshipNetworkScore());
            validateOwnerScoreRange("Engagement", request.getOwnerEngagementScore());
            validateOwnerScoreRange("Qualitative / Trust", request.getOwnerQualitativeScore());

            if (request.getOwnerCommercialScore() != null) assessment.setOwnerCommercialScore(request.getOwnerCommercialScore());
            if (request.getOwnerCooperationScore() != null) assessment.setOwnerCooperationScore(request.getOwnerCooperationScore());
            if (request.getOwnerStrategicScore() != null) assessment.setOwnerStrategicScore(request.getOwnerStrategicScore());
            if (request.getOwnerRelationshipNetworkScore() != null) assessment.setOwnerRelationshipNetworkScore(request.getOwnerRelationshipNetworkScore());
            if (request.getOwnerEngagementScore() != null) assessment.setOwnerEngagementScore(request.getOwnerEngagementScore());
            if (request.getOwnerQualitativeScore() != null) assessment.setOwnerQualitativeScore(request.getOwnerQualitativeScore());
            assessment.setOwnerRelationshipNetworkNote(request.getOwnerRelationshipNetworkNote());
            assessment.setOwnerAdjustmentReason(request.getOwnerAdjustmentReason());
            assessment.setOwnerNote(request.getOwnerNote());
        }

        // Live preview score calculation for owner if all 6 owner scores are present
        boolean allPresent = assessment.getOwnerCommercialScore() != null
                && assessment.getOwnerCooperationScore() != null
                && assessment.getOwnerStrategicScore() != null
                && assessment.getOwnerRelationshipNetworkScore() != null
                && assessment.getOwnerEngagementScore() != null
                && assessment.getOwnerQualitativeScore() != null;

        if (allPresent) {
            CalculationResult calc = scoreCalculator.calculateV5(
                    assessment.getOwnerCommercialScore(),
                    assessment.getOwnerCooperationScore(),
                    assessment.getOwnerStrategicScore(),
                    assessment.getOwnerRelationshipNetworkScore(),
                    assessment.getOwnerEngagementScore(),
                    assessment.getOwnerQualitativeScore()
            );
            assessment.setOwnerRawScorableScore(calc.getRawScorableScore());
            assessment.setOwnerFinalTotalScore(calc.getNormalizedTotalScore());
            assessment.setOwnerFinalRank(calc.getRank().name());
        } else {
            assessment.setOwnerRawScorableScore(null);
            assessment.setOwnerFinalTotalScore(null);
            assessment.setOwnerFinalRank(null);
        }

        assessment = assessmentRepository.save(assessment);

        auditLogService.log(currentUser.getId(), AuditAction.RELATIONSHIP_ASSESSMENT_UPDATED,
                "CompanyRelationshipAssessment", String.valueOf(assessment.getId()),
                String.format("Updated Owner Adjustment v%d for company %s", assessment.getVersionNumber(), assessment.getCompanyProfileId()));

        return toResponse(assessment, currentUser, null);
    }

    @Transactional
    public RelationshipAssessmentResponse completeOwnerAdjustment(
            Long assessmentId,
            OwnerAdjustmentUpdateRequest request,
            UserDetailsImpl currentUser) {

        validateOwnerAccess(currentUser);
        CompanyRelationshipAssessment assessment = getAssessmentEntity(assessmentId);
        validateRelationshipClosenessEligibility(assessment.getCompanyProfileId());
        validateAccess(assessment.getCompanyProfileId(), currentUser, true);

        if (assessment.getStatus() != RelationshipAssessmentStatus.DRAFT) {
            throw new BusinessValidationException("Only DRAFT owner adjustments can be completed.");
        }

        if (assessment.getAssessmentType() != RelationshipAssessmentType.OWNER_ADJUSTMENT) {
            throw new BusinessValidationException("This assessment is not an Owner Adjustment.");
        }

        if (request != null) {
            validateOwnerScoreRange("Commercial", request.getOwnerCommercialScore());
            validateOwnerScoreRange("Cooperation", request.getOwnerCooperationScore());
            validateOwnerScoreRange("Strategic", request.getOwnerStrategicScore());
            validateOwnerScoreRange("Relationship Network", request.getOwnerRelationshipNetworkScore());
            validateOwnerScoreRange("Engagement", request.getOwnerEngagementScore());
            validateOwnerScoreRange("Qualitative / Trust", request.getOwnerQualitativeScore());

            if (request.getOwnerCommercialScore() != null) assessment.setOwnerCommercialScore(request.getOwnerCommercialScore());
            if (request.getOwnerCooperationScore() != null) assessment.setOwnerCooperationScore(request.getOwnerCooperationScore());
            if (request.getOwnerStrategicScore() != null) assessment.setOwnerStrategicScore(request.getOwnerStrategicScore());
            if (request.getOwnerRelationshipNetworkScore() != null) assessment.setOwnerRelationshipNetworkScore(request.getOwnerRelationshipNetworkScore());
            if (request.getOwnerEngagementScore() != null) assessment.setOwnerEngagementScore(request.getOwnerEngagementScore());
            if (request.getOwnerQualitativeScore() != null) assessment.setOwnerQualitativeScore(request.getOwnerQualitativeScore());
            assessment.setOwnerRelationshipNetworkNote(request.getOwnerRelationshipNetworkNote());
            assessment.setOwnerAdjustmentReason(request.getOwnerAdjustmentReason());
            assessment.setOwnerNote(request.getOwnerNote());
        }

        // Verify source assessment exists and is still the latest official finalized assessment (Correction 4)
        if (assessment.getSourceAssessmentId() == null) {
            throw new BusinessValidationException("Bản điều chỉnh không có phiên bản nguồn hợp lệ.");
        }

        String ownerId = assessment.getOwnerCompanyProfileId();
        List<String> targetIds = resolveTargetProfileIds(assessment.getCompanyProfileId());
        Optional<CompanyRelationshipAssessment> latestFinalizedOpt = assessmentRepository
                .findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
                        ownerId, assessment.getCompanyProfileId(), RelationshipAssessmentStatus.FINALIZED);
        if (latestFinalizedOpt.isEmpty() && targetIds.size() > 1) {
            latestFinalizedOpt = assessmentRepository
                    .findFirstByOwnerCompanyProfileIdAndCompanyProfileIdInAndStatusOrderByVersionNumberDesc(
                            ownerId, targetIds, RelationshipAssessmentStatus.FINALIZED);
        }
        CompanyRelationshipAssessment currentLatest = latestFinalizedOpt
                .orElseThrow(() -> new BusinessValidationException("Không tìm thấy bản đánh giá chính thức cho doanh nghiệp này."));
        if (!currentLatest.getId().equals(assessment.getSourceAssessmentId())) {
            throw new BusinessValidationException("Phiên bản đánh giá hiện tại đã thay đổi. Vui lòng tạo lại bản điều chỉnh từ kết quả mới nhất.");
        }

        // Validate all 6 owner scores must be present (0..5)
        scoreCalculator.validateCompleteSubmissionV5(
                assessment.getOwnerCommercialScore(),
                assessment.getOwnerCooperationScore(),
                assessment.getOwnerStrategicScore(),
                assessment.getOwnerRelationshipNetworkScore(),
                assessment.getOwnerEngagementScore(),
                assessment.getOwnerQualitativeScore()
        );

        OfficialCriterionScores sourceOfficial = getOfficialCriterionScores(currentLatest);

        boolean commChanged = !Objects.equals(assessment.getOwnerCommercialScore(), sourceOfficial.commercial());
        boolean coopChanged = !Objects.equals(assessment.getOwnerCooperationScore(), sourceOfficial.cooperation());
        boolean stratChanged = !Objects.equals(assessment.getOwnerStrategicScore(), sourceOfficial.strategic());
        boolean netChanged = !Objects.equals(assessment.getOwnerRelationshipNetworkScore(), sourceOfficial.relationshipNetwork());
        boolean engChanged = !Objects.equals(assessment.getOwnerEngagementScore(), sourceOfficial.engagement());
        boolean qualChanged = !Objects.equals(assessment.getOwnerQualitativeScore(), sourceOfficial.qualitative());

        boolean criteriaAdjusted = commChanged || coopChanged || stratChanged || netChanged || engChanged || qualChanged;

        if (!criteriaAdjusted) {
            throw new BusinessValidationException("At least one criterion score must be changed from the current official assessment to complete an adjustment.");
        }

        CalculationResult ownerCalc = scoreCalculator.calculateV5(
                assessment.getOwnerCommercialScore(),
                assessment.getOwnerCooperationScore(),
                assessment.getOwnerStrategicScore(),
                assessment.getOwnerRelationshipNetworkScore(),
                assessment.getOwnerEngagementScore(),
                assessment.getOwnerQualitativeScore()
        );

        assessment.setOwnerRawScorableScore(ownerCalc.getRawScorableScore());
        assessment.setOwnerFinalTotalScore(ownerCalc.getNormalizedTotalScore());
        assessment.setOwnerFinalRank(ownerCalc.getRank().name());
        assessment.setOwnerAccountId(currentUser.getId());
        assessment.setFinalizedAt(LocalDateTime.now());
        assessment.setStatus(RelationshipAssessmentStatus.FINALIZED);

        assessment = assessmentRepository.save(assessment);

        auditLogService.log(currentUser.getId(), AuditAction.RELATIONSHIP_ASSESSMENT_FINALIZED,
                "CompanyRelationshipAssessment", String.valueOf(assessment.getId()),
                String.format("Finalized Owner Adjustment v%d (Final Score: %d, Rank: %s) for company %s",
                        assessment.getVersionNumber(), ownerCalc.getNormalizedTotalScore(), ownerCalc.getRank(), assessment.getCompanyProfileId()));

        Long sourceManagerId = resolveSourceManagerAccountId(assessment.getSourceAssessmentId());
        notificationService.notifyRelationshipAssessmentOwnerAdjusted(assessment, currentLatest, sourceManagerId, currentUser != null ? currentUser.getId() : null);

        return toResponse(assessment, currentUser, null);
    }

    @Transactional
    public RelationshipAssessmentResponse cancelOwnerAdjustment(Long assessmentId, UserDetailsImpl currentUser) {
        validateOwnerAccess(currentUser);
        CompanyRelationshipAssessment assessment = getAssessmentEntity(assessmentId);
        validateRelationshipClosenessEligibility(assessment.getCompanyProfileId());
        validateAccess(assessment.getCompanyProfileId(), currentUser, true);

        if (assessment.getStatus() != RelationshipAssessmentStatus.DRAFT) {
            throw new BusinessValidationException("Only DRAFT owner adjustments can be cancelled.");
        }

        if (assessment.getAssessmentType() != RelationshipAssessmentType.OWNER_ADJUSTMENT) {
            throw new BusinessValidationException("This assessment is not an Owner Adjustment.");
        }

        assessment.setStatus(RelationshipAssessmentStatus.CANCELLED);
        assessment = assessmentRepository.save(assessment);

        auditLogService.log(currentUser.getId(), AuditAction.RELATIONSHIP_ASSESSMENT_CANCELLED,
                "CompanyRelationshipAssessment", String.valueOf(assessment.getId()),
                String.format("Cancelled Owner Adjustment v%d for company %s", assessment.getVersionNumber(), assessment.getCompanyProfileId()));

        return toResponse(assessment, currentUser, null);
    }

    // ----------------------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------------------

    private void applyCommercialSnapshot(CompanyRelationshipAssessment assessment, CommercialEvidenceResult result) {
        boolean isV5 = RelationshipCommercialScoringPolicy.POLICY_VERSION_V5.equals(assessment.getScoringPolicyVersion());
        boolean isV4 = RelationshipCommercialScoringPolicy.POLICY_VERSION_V4.equals(assessment.getScoringPolicyVersion());
        if (isV5) {
            assessment.setCommercialSuggestedScore(null);
            assessment.setCommercialScore(null);
        } else if (isV4) {
            assessment.setCommercialSuggestedScore(null);
            assessment.setCommercialScore(assessment.getCommercialAwardedScore());
        } else {
            assessment.setCommercialSuggestedScore(result.getCommercialScore());
            if (assessment.getCommercialAwardedScore() == null) {
                assessment.setCommercialAwardedScore(result.getCommercialScore());
            }
            assessment.setCommercialScore(assessment.getCommercialAwardedScore());
        }
        assessment.setContractValueScore(result.getContractValueScore());
        assessment.setContractCountScore(result.getContractCountScore() != null ? result.getContractCountScore() : 0);
        assessment.setRelationshipDurationScore(result.getRelationshipDurationScore() != null ? result.getRelationshipDurationScore() : 0);
        assessment.setContractRecencyScore(result.getContractRecencyScore() != null ? result.getContractRecencyScore() : 0);
        assessment.setApprovedContractCount(result.getApprovedContractCount());
        assessment.setTotalContractValueVnd(result.getTotalContractValueVnd());
        assessment.setContractCurrencies(result.getContractCurrencies());
        assessment.setContractValueStatus(result.getContractValueStatus() != null ? result.getContractValueStatus() : "SCORABLE");
        assessment.setFirstCooperationDate(result.getFirstCooperationDate());
        assessment.setLatestContractDate(result.getLatestContractDate());
        assessment.setUpcomingContractCount(result.getUpcomingContractCount());
        assessment.setScorableBase(result.getScorableBase());
        assessment.setNormalizationApplied(result.isNormalizationApplied());

        try {
            assessment.setCurrencyBreakdown(objectMapper.writeValueAsString(result.getValueByCurrency()));
        } catch (JsonProcessingException e) {
            assessment.setCurrencyBreakdown("{}");
        }
    }

    private CommercialEvidenceResponse toCommercialEvidenceResponse(CommercialEvidenceResult result) {
        return CommercialEvidenceResponse.builder()
                .approvedContractCount(result.getApprovedContractCount())
                .upcomingContractCount(result.getUpcomingContractCount())
                .firstCooperationDate(result.getFirstCooperationDate())
                .latestContractDate(result.getLatestContractDate())
                .relationshipDurationDays(result.getRelationshipDurationDays())
                .relationshipDurationMonths(result.getRelationshipDurationMonths())
                .contractRecencyDays(result.getContractRecencyDays())
                .contractRecencyMonths(result.getContractRecencyMonths())
                .totalContractValueVnd(result.getTotalContractValueVnd())
                .valueByCurrency(result.getValueByCurrency())
                .contractCurrencies(result.getContractCurrencies())
                .contractValueStatus(result.getContractValueStatus())
                .contractValueScore(result.getContractValueScore())
                .contractCountScore(result.getContractCountScore())
                .relationshipDurationScore(result.getRelationshipDurationScore())
                .contractRecencyScore(result.getContractRecencyScore())
                .commercialScore(result.getCommercialScore())
                .scorableBase(result.getScorableBase())
                .normalizationApplied(result.isNormalizationApplied())
                .scoringPolicyVersion(result.getScoringPolicyVersion())
                .hasValidHistoricalDates(result.isHasValidHistoricalDates())
                .commercialSuggestionStatus(result.getCommercialSuggestionStatus())
                .commercialAvailablePoints(result.getCommercialAvailablePoints())
                .build();
    }

    private Integer resolveTrustScore(Integer qualScore, Integer trustScore) {
        if (qualScore != null && trustScore != null && !qualScore.equals(trustScore)) {
            throw new BusinessValidationException("Conflicting values provided for qualitativeScore and trustScore.");
        }
        return trustScore != null ? trustScore : qualScore;
    }

    private String resolveTrustNote(String qualNote, String trustNote) {
        if (StringUtils.hasText(qualNote) && StringUtils.hasText(trustNote) && !qualNote.trim().equals(trustNote.trim())) {
            throw new BusinessValidationException("Conflicting values provided for qualitativeEvidenceNote and trustEvidenceNote.");
        }
        return StringUtils.hasText(trustNote) ? trustNote : qualNote;
    }

    private Integer resolveOwnerTrustScore(Integer qualScore, Integer trustScore) {
        if (qualScore != null && trustScore != null && !qualScore.equals(trustScore)) {
            throw new BusinessValidationException("Conflicting values provided for ownerQualitativeScore and ownerTrustScore.");
        }
        return trustScore != null ? trustScore : qualScore;
    }

    private void handleAssessmentDataIntegrityViolation(DataIntegrityViolationException e, String message) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        Throwable root = e.getRootCause();
        String rootMsg = (root != null && root.getMessage() != null) ? root.getMessage().toLowerCase() : "";

        boolean isUniqueViolation = msg.contains("uq_active")
                || msg.contains("uq_company_relationship_assessment")
                || msg.contains("unique")
                || msg.contains("duplicate")
                || rootMsg.contains("uq_active")
                || rootMsg.contains("uq_company_relationship_assessment")
                || rootMsg.contains("unique")
                || rootMsg.contains("duplicate")
                || rootMsg.contains("2601")
                || rootMsg.contains("2627");

        if (isUniqueViolation) {
            throw new BusinessValidationException(message);
        }
        throw e;
    }

    private record CommercialSuggestionInfo(String status, Integer availablePoints) {}

    private CommercialSuggestionInfo computeCommercialSuggestionInfo(CompanyRelationshipAssessment entity) {
        boolean isV5 = RelationshipCommercialScoringPolicy.POLICY_VERSION_V5.equals(entity.getScoringPolicyVersion());
        if (isV5) {
            return new CommercialSuggestionInfo("REFERENCE_ONLY", null);
        }
        boolean isV4 = RelationshipCommercialScoringPolicy.POLICY_VERSION_V4.equals(entity.getScoringPolicyVersion());
        if (isV4) {
            return new CommercialSuggestionInfo("REFERENCE_ONLY", 35);
        }
        boolean isV3 = RelationshipCommercialScoringPolicy.POLICY_VERSION_V3.equals(entity.getScoringPolicyVersion());
        int maxPossible = isV3 ? 50 : 35;
        int countAvailable = isV3 ? 10 : 8;
        int totalApproved = entity.getApprovedContractCount() != null ? entity.getApprovedContractCount() : 0;

        if (totalApproved == 0) {
            return new CommercialSuggestionInfo("UNAVAILABLE", 0);
        }

        boolean hasValue = !"UNSCORABLE_NON_VND".equalsIgnoreCase(entity.getContractValueStatus());
        int valueAvailable = hasValue ? (isV3 ? 20 : 15) : 0;

        boolean hasDates = entity.getFirstCooperationDate() != null;
        int durationAvailable = hasDates ? (isV3 ? 10 : 6) : 0;
        int recencyAvailable = hasDates ? (isV3 ? 10 : 6) : 0;

        int availablePoints = countAvailable + valueAvailable + durationAvailable + recencyAvailable;
        String status = (availablePoints == maxPossible) ? "COMPLETE" : "PARTIAL";
        return new CommercialSuggestionInfo(status, availablePoints);
    }

    private RelationshipAssessmentResponse toResponse(
            CompanyRelationshipAssessment entity,
            UserDetailsImpl currentUser,
            CompanyRelationshipAssessment latestFinalized) {

        boolean isFinalized = entity.getStatus() == RelationshipAssessmentStatus.FINALIZED;
        boolean isOwner = hasRole(currentUser, SystemRole.BUSINESS_OWNER);
        boolean isManager = hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
        boolean isStaff = hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        boolean isDraft = entity.getStatus() == RelationshipAssessmentStatus.DRAFT;
        boolean isChangesRequested = entity.getStatus() == RelationshipAssessmentStatus.CHANGES_REQUESTED;
        boolean isSubmitted = entity.getStatus() == RelationshipAssessmentStatus.SUBMITTED;
        boolean isOwnerAdjustment = entity.getAssessmentType() == RelationshipAssessmentType.OWNER_ADJUSTMENT;

        boolean canEditDraft;
        if (isDraft) {
            if (isOwnerAdjustment) {
                canEditDraft = isOwner;
            } else {
                canEditDraft = isManager || isStaff;
            }
        } else if (isChangesRequested) {
            canEditDraft = isManager || isOwner;
        } else {
            canEditDraft = false;
        }

        boolean activeExists = assessmentRepository.existsByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusIn(
                entity.getOwnerCompanyProfileId(), entity.getCompanyProfileId(), ACTIVE_STATUSES);

        boolean canCancelAdjustment = isDraft && isOwnerAdjustment && isOwner;
        boolean canSubmit = (isDraft || isChangesRequested) && isManager && !isOwnerAdjustment;
        boolean canComplete = isDraft && (isOwnerAdjustment ? isOwner : isManager);
        boolean canRequestChanges = isSubmitted && isOwner;
        boolean canFinalize = isSubmitted && isOwner;
        boolean canCreateNewVersion = isFinalized && !activeExists && isManager && canCreateNewAssessment(entity.getCompanyProfileId(), currentUser, false, true);

        boolean isLatestOfficialFinalized = false;
        if (isFinalized) {
            if (latestFinalized != null) {
                isLatestOfficialFinalized = entity.getId().equals(latestFinalized.getId());
            } else {
                Optional<CompanyRelationshipAssessment> topFinalized = assessmentRepository
                        .findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
                                entity.getOwnerCompanyProfileId(), entity.getCompanyProfileId(), RelationshipAssessmentStatus.FINALIZED);
                isLatestOfficialFinalized = topFinalized.map(f -> f.getId().equals(entity.getId())).orElse(true);
            }
        }

        boolean canAdjust = isLatestOfficialFinalized && !activeExists && isOwner;

        Integer sourceVersionNumber = null;
        if (entity.getSourceAssessmentId() != null) {
            sourceVersionNumber = assessmentRepository.findById(entity.getSourceAssessmentId())
                    .map(CompanyRelationshipAssessment::getVersionNumber)
                    .orElse(null);
        }

        boolean isV5 = RelationshipCommercialScoringPolicy.POLICY_VERSION_V5.equals(entity.getScoringPolicyVersion());
        boolean isV3 = RelationshipCommercialScoringPolicy.POLICY_VERSION_V3.equals(entity.getScoringPolicyVersion());
        int totalCriteriaCount = isV3 ? 3 : 6;

        // Calculate completed criteria count and subtotal
        int completedCount = 0;
        int subtotal = 0;
        if (isOwnerAdjustment) {
            if (entity.getOwnerCommercialScore() != null) { completedCount++; subtotal += entity.getOwnerCommercialScore(); }
            if (entity.getOwnerEngagementScore() != null) { completedCount++; subtotal += entity.getOwnerEngagementScore(); }
            if (entity.getOwnerRelationshipNetworkScore() != null) { completedCount++; subtotal += entity.getOwnerRelationshipNetworkScore(); }
            if (entity.getOwnerCooperationScore() != null) { completedCount++; subtotal += entity.getOwnerCooperationScore(); }
            if (entity.getOwnerStrategicScore() != null) { completedCount++; subtotal += entity.getOwnerStrategicScore(); }
            if (entity.getOwnerQualitativeScore() != null) { completedCount++; subtotal += entity.getOwnerQualitativeScore(); }
        } else {
            if (entity.getCommercialAwardedScore() != null) { completedCount++; subtotal += entity.getCommercialAwardedScore(); }
            if (entity.getEngagementScore() != null) { completedCount++; subtotal += entity.getEngagementScore(); }
            if (entity.getRelationshipNetworkScore() != null) { completedCount++; subtotal += entity.getRelationshipNetworkScore(); }
            if (!isV3) {
                if (entity.getCooperationScore() != null) { completedCount++; subtotal += entity.getCooperationScore(); }
                if (entity.getStrategicScore() != null) { completedCount++; subtotal += entity.getStrategicScore(); }
                if (entity.getQualitativeScore() != null) { completedCount++; subtotal += entity.getQualitativeScore(); }
            }
        }
        boolean isComplete = (completedCount == totalCriteriaCount);
        Integer draftSubtotal = (completedCount > 0) ? (isV5 ? Math.min(30, Math.max(0, subtotal)) : Math.min(100, Math.max(0, subtotal))) : null;

        Double managerNormalizedScore = null;
        if (isV5) {
            if (entity.getManagerRawScorableScore() != null) {
                managerNormalizedScore = (entity.getManagerRawScorableScore() * 100.0) / 30.0;
            } else if (!isOwnerAdjustment && completedCount == 6) {
                managerNormalizedScore = (subtotal * 100.0) / 30.0;
            }
        }

        Double ownerNormalizedScore = null;
        if (isV5 && entity.getOwnerRawScorableScore() != null) {
            ownerNormalizedScore = (entity.getOwnerRawScorableScore() * 100.0) / 30.0;
        }

        CommercialSuggestionInfo commInfo = computeCommercialSuggestionInfo(entity);
        boolean hasDates = entity.getFirstCooperationDate() != null;
        Integer durationScore = hasDates ? entity.getRelationshipDurationScore() : null;
        Integer recencyScore = hasDates ? entity.getContractRecencyScore() : null;

        Integer officialScore = null;
        String officialRank = null;
        String officialRankDesc = null;

        if (isFinalized) {
            officialScore = getOfficialTotalScore(entity);
            officialRank = getOfficialRank(entity);
            if (officialRank != null) {
                try {
                    officialRankDesc = RelationshipAssessmentRank.valueOf(officialRank).getDescription();
                } catch (Exception ignored) {}
            }
        } else if (latestFinalized != null) {
            officialScore = getOfficialTotalScore(latestFinalized);
            officialRank = getOfficialRank(latestFinalized);
            if (officialRank != null) {
                try {
                    officialRankDesc = RelationshipAssessmentRank.valueOf(officialRank).getDescription();
                } catch (Exception ignored) {}
            }
        }

        String managerRankDesc = null;
        if (entity.getManagerRank() != null) {
            try {
                managerRankDesc = RelationshipAssessmentRank.valueOf(entity.getManagerRank()).getDescription();
            } catch (Exception ignored) {}
        }

        String ownerRankDesc = null;
        if (entity.getOwnerFinalRank() != null) {
            try {
                ownerRankDesc = RelationshipAssessmentRank.valueOf(entity.getOwnerFinalRank()).getDescription();
            } catch (Exception ignored) {}
        }

        return RelationshipAssessmentResponse.builder()
                .id(entity.getId())
                .companyProfileId(entity.getCompanyProfileId())
                .ownerCompanyProfileId(entity.getOwnerCompanyProfileId())
                .versionNumber(entity.getVersionNumber())
                .status(entity.getStatus())
                .scoringPolicyVersion(entity.getScoringPolicyVersion())
                .commercialScore(isV5 ? null : entity.getCommercialScore())
                .commercialSuggestedScore(isV5 ? null : entity.getCommercialSuggestedScore())
                .commercialAwardedScore(entity.getCommercialAwardedScore())
                .commercialAdjustmentReason(entity.getCommercialAdjustmentReason())
                .commercialEvidenceNote(entity.getCommercialEvidenceNote())
                .contractValueScore(entity.getContractValueScore())
                .contractCountScore(entity.getContractCountScore())
                .relationshipDurationScore(durationScore)
                .contractRecencyScore(recencyScore)
                .approvedContractCount(entity.getApprovedContractCount())
                .totalContractValueVnd(entity.getTotalContractValueVnd())
                .contractCurrencies(entity.getContractCurrencies())
                .currencyBreakdown(entity.getCurrencyBreakdown())
                .contractValueStatus(entity.getContractValueStatus())
                .firstCooperationDate(entity.getFirstCooperationDate())
                .latestContractDate(entity.getLatestContractDate())
                .upcomingContractCount(entity.getUpcomingContractCount())
                .commercialSuggestionStatus(commInfo.status())
                .commercialAvailablePoints(commInfo.availablePoints())
                .scorableBase(entity.getScorableBase())
                .normalizationApplied(entity.getNormalizationApplied())
                .cooperationScore(entity.getCooperationScore())
                .cooperationEvidenceNote(entity.getCooperationEvidenceNote())
                .strategicScore(entity.getStrategicScore())
                .strategicEvidenceNote(entity.getStrategicEvidenceNote())
                .relationshipNetworkScore(entity.getRelationshipNetworkScore())
                .relationshipNetworkNote(entity.getRelationshipNetworkNote())
                .engagementScore(entity.getEngagementScore())
                .engagementEvidenceNote(entity.getEngagementEvidenceNote())
                .qualitativeScore(entity.getQualitativeScore())
                .qualitativeEvidenceNote(entity.getQualitativeEvidenceNote())
                .trustScore(entity.getQualitativeScore())
                .trustEvidenceNote(entity.getQualitativeEvidenceNote())
                .managerNote(entity.getManagerNote())
                .managerRawScorableScore(entity.getManagerRawScorableScore())
                .managerTotalScore(entity.getManagerTotalScore())
                .managerNormalizedScore(managerNormalizedScore)
                .managerRank(entity.getManagerRank())
                .managerRankDescription(managerRankDesc)
                .managerAccountId(entity.getManagerAccountId())
                .managerSubmittedAt(entity.getManagerSubmittedAt())
                .changesRequestedReason(entity.getChangesRequestedReason())
                .changesRequestedByAccountId(entity.getChangesRequestedByAccountId())
                .changesRequestedAt(entity.getChangesRequestedAt())
                .ownerCommercialScore(entity.getOwnerCommercialScore())
                .ownerCooperationScore(entity.getOwnerCooperationScore())
                .ownerStrategicScore(entity.getOwnerStrategicScore())
                .ownerRelationshipNetworkScore(entity.getOwnerRelationshipNetworkScore())
                .ownerRelationshipNetworkNote(entity.getOwnerRelationshipNetworkNote())
                .ownerEngagementScore(entity.getOwnerEngagementScore())
                .ownerQualitativeScore(entity.getOwnerQualitativeScore())
                .ownerTrustScore(entity.getOwnerQualitativeScore())
                .ownerNote(entity.getOwnerNote())
                .ownerAdjustmentReason(entity.getOwnerAdjustmentReason())
                .ownerRawScorableScore(entity.getOwnerRawScorableScore())
                .ownerFinalTotalScore(entity.getOwnerFinalTotalScore())
                .ownerNormalizedScore(ownerNormalizedScore)
                .ownerFinalRank(entity.getOwnerFinalRank())
                .ownerFinalRankDescription(ownerRankDesc)
                .ownerAccountId(entity.getOwnerAccountId())
                .finalizedAt(entity.getFinalizedAt())
                .completedCriteriaCount(completedCount)
                .totalCriteriaCount(totalCriteriaCount)
                .draftSubtotalScore(draftSubtotal)
                .isComplete(isComplete)
                .officialScore(officialScore)
                .officialRank(officialRank)
                .officialRankDescription(officialRankDesc)
                .isOfficialFinalized(officialScore != null)
                .assessmentType(entity.getAssessmentType() != null ? entity.getAssessmentType() : RelationshipAssessmentType.MANAGER_ASSESSMENT)
                .sourceAssessmentId(entity.getSourceAssessmentId())
                .sourceVersionNumber(sourceVersionNumber)
                .isOwnerAdjustment(isOwnerAdjustment)
                .createdByAccountId(entity.getCreatedByAccountId())
                .canEditDraft(canEditDraft)
                .canSubmit(canSubmit)
                .canComplete(canComplete)
                .canRequestChanges(canRequestChanges)
                .canFinalize(canFinalize)
                .canCreateNewVersion(canCreateNewVersion)
                .canAdjust(canAdjust)
                .canCancelAdjustment(canCancelAdjustment)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private CompanyRelationshipAssessment getAssessmentEntity(Long assessmentId) {
        return assessmentRepository.findById(assessmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Relationship Assessment not found: " + assessmentId));
    }

    private CompanyProfile resolveTargetProfile(String targetCompanyProfileId) {
        if (!StringUtils.hasText(targetCompanyProfileId)) {
            throw new ResourceNotFoundException("Target company profile identifier is required.");
        }
        return companyProfileRepository.findById(targetCompanyProfileId.trim())
                .or(() -> companyProfileRepository.findByCompanyId(targetCompanyProfileId.trim()))
                .orElseThrow(() -> new ResourceNotFoundException("Target CompanyProfile not found: " + targetCompanyProfileId));
    }

    private List<String> resolveTargetProfileIds(String targetCompanyProfileId) {
        Set<String> ids = new LinkedHashSet<>();
        if (StringUtils.hasText(targetCompanyProfileId)) {
            ids.add(targetCompanyProfileId.trim());
            try {
                CompanyProfile profile = resolveTargetProfile(targetCompanyProfileId);
                if (StringUtils.hasText(profile.getId())) {
                    ids.add(profile.getId().trim());
                }
                if (StringUtils.hasText(profile.getCompanyId())) {
                    ids.add(profile.getCompanyId().trim());
                }
            } catch (ResourceNotFoundException ignored) {
                // Keep provided id
            }
        }
        return new ArrayList<>(ids);
    }

    public void validateRelationshipClosenessEligibility(String targetCompanyProfileId) {
        if (ownerOrganizationService.isOwnerCompany(targetCompanyProfileId)) {
            throw new BusinessValidationException("Cannot evaluate relationship closeness for the Owner Organization itself.");
        }

        CompanyProfile target = resolveTargetProfile(targetCompanyProfileId);

        if (Boolean.TRUE.equals(target.getIsHidden()) || Boolean.TRUE.equals(target.getIsDeleted())) {
            throw new BusinessValidationException("Target CompanyProfile is hidden or deleted.");
        }

        String companyId = StringUtils.hasText(target.getCompanyId()) ? target.getCompanyId() : target.getId();
        String relType = accessEvaluator.resolveRelationshipType(companyId);
        if (!accessEvaluator.isEligibleRelationshipType(relType)) {
            throw new BusinessValidationException("Relationship Closeness assessment is not available for this company relationship type.");
        }
    }

    private void validateTargetProfile(String targetCompanyProfileId) {
        validateRelationshipClosenessEligibility(targetCompanyProfileId);
    }

    private void validateAccess(String targetCompanyProfileId, UserDetailsImpl user, boolean isWrite) {
        accessEvaluator.validateAssessmentAccess(targetCompanyProfileId, user, isWrite);
    }

    public record OfficialCriterionScores(
            Integer commercial,
            Integer cooperation,
            Integer strategic,
            Integer relationshipNetwork,
            String relationshipNetworkNote,
            Integer engagement,
            Integer qualitative,
            Integer rawScorableScore,
            Integer totalScore,
            String rank
    ) {}

    public OfficialCriterionScores getOfficialCriterionScores(CompanyRelationshipAssessment assessment) {
        if (assessment == null) {
            return new OfficialCriterionScores(null, null, null, null, null, null, null, null, null, null);
        }

        boolean isOwnerAdj = assessment.getAssessmentType() == RelationshipAssessmentType.OWNER_ADJUSTMENT;
        boolean hasOwnerFinal = assessment.getOwnerFinalTotalScore() != null;

        if (isOwnerAdj || hasOwnerFinal) {
            return new OfficialCriterionScores(
                    assessment.getOwnerCommercialScore() != null ? assessment.getOwnerCommercialScore() : assessment.getCommercialAwardedScore(),
                    assessment.getOwnerCooperationScore() != null ? assessment.getOwnerCooperationScore() : assessment.getCooperationScore(),
                    assessment.getOwnerStrategicScore() != null ? assessment.getOwnerStrategicScore() : assessment.getStrategicScore(),
                    assessment.getOwnerRelationshipNetworkScore() != null ? assessment.getOwnerRelationshipNetworkScore() : assessment.getRelationshipNetworkScore(),
                    StringUtils.hasText(assessment.getOwnerRelationshipNetworkNote()) ? assessment.getOwnerRelationshipNetworkNote() : assessment.getRelationshipNetworkNote(),
                    assessment.getOwnerEngagementScore() != null ? assessment.getOwnerEngagementScore() : assessment.getEngagementScore(),
                    assessment.getOwnerQualitativeScore() != null ? assessment.getOwnerQualitativeScore() : assessment.getQualitativeScore(),
                    assessment.getOwnerRawScorableScore() != null ? assessment.getOwnerRawScorableScore() : assessment.getManagerRawScorableScore(),
                    assessment.getOwnerFinalTotalScore() != null ? assessment.getOwnerFinalTotalScore() : assessment.getManagerTotalScore(),
                    assessment.getOwnerFinalRank() != null ? assessment.getOwnerFinalRank() : assessment.getManagerRank()
            );
        } else {
            return new OfficialCriterionScores(
                    assessment.getCommercialAwardedScore() != null ? assessment.getCommercialAwardedScore() : assessment.getCommercialScore(),
                    assessment.getCooperationScore(),
                    assessment.getStrategicScore(),
                    assessment.getRelationshipNetworkScore(),
                    assessment.getRelationshipNetworkNote(),
                    assessment.getEngagementScore(),
                    assessment.getQualitativeScore(),
                    assessment.getManagerRawScorableScore(),
                    assessment.getManagerTotalScore(),
                    assessment.getManagerRank()
            );
        }
    }

    public Integer getOfficialTotalScore(CompanyRelationshipAssessment assessment) {
        if (assessment == null) return null;
        if (assessment.getStatus() != RelationshipAssessmentStatus.FINALIZED) return null;
        boolean isV3 = RelationshipCommercialScoringPolicy.POLICY_VERSION_V3.equals(assessment.getScoringPolicyVersion());
        if (isV3 && assessment.getOwnerFinalTotalScore() == null) {
            return null;
        }
        if (assessment.getAssessmentType() == RelationshipAssessmentType.OWNER_ADJUSTMENT || assessment.getOwnerFinalTotalScore() != null) {
            return assessment.getOwnerFinalTotalScore();
        }
        return assessment.getManagerTotalScore();
    }

    public String getOfficialRank(CompanyRelationshipAssessment assessment) {
        if (assessment == null) return null;
        if (assessment.getStatus() != RelationshipAssessmentStatus.FINALIZED) return null;
        boolean isV3 = RelationshipCommercialScoringPolicy.POLICY_VERSION_V3.equals(assessment.getScoringPolicyVersion());
        if (isV3 && assessment.getOwnerFinalRank() == null) {
            return null;
        }
        if (assessment.getAssessmentType() == RelationshipAssessmentType.OWNER_ADJUSTMENT || assessment.getOwnerFinalRank() != null) {
            return assessment.getOwnerFinalRank();
        }
        return assessment.getManagerRank();
    }

    public Long resolveSourceManagerAccountId(Long sourceAssessmentId) {
        Long currentSourceId = sourceAssessmentId;
        Set<Long> visited = new HashSet<>();

        while (currentSourceId != null && visited.add(currentSourceId)) {
            Optional<CompanyRelationshipAssessment> sourceOpt = assessmentRepository.findById(currentSourceId);
            if (sourceOpt.isEmpty()) {
                break;
            }
            CompanyRelationshipAssessment source = sourceOpt.get();
            if (source.getAssessmentType() == RelationshipAssessmentType.MANAGER_ASSESSMENT) {
                if (source.getManagerAccountId() != null) {
                    return source.getManagerAccountId();
                }
                if (source.getCreatedByAccountId() != null) {
                    return source.getCreatedByAccountId();
                }
            }
            if (source.getManagerAccountId() != null) {
                return source.getManagerAccountId();
            }
            currentSourceId = source.getSourceAssessmentId();
        }
        return null;
    }

    private void validateDraftCreateAccess(String targetCompanyProfileId, UserDetailsImpl user) {
        validateAccess(targetCompanyProfileId, user, true);
        // Correction 1: Initial assessment creation MUST be BD Manager only
        if (!hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_MANAGER)) {
            throw new AccessDeniedException("Only Business Development Manager can create an initial relationship assessment.");
        }
    }

    private void validateDraftEditAccess(String targetCompanyProfileId, UserDetailsImpl user, RelationshipAssessmentStatus status) {
        validateAccess(targetCompanyProfileId, user, true);
        if (hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_STAFF)) {
            if (status != RelationshipAssessmentStatus.DRAFT) {
                throw new AccessDeniedException("Staff can only edit assessments in DRAFT status.");
            }
        }
    }

    private void validateManagerAccess(String targetCompanyProfileId, UserDetailsImpl user) {
        validateAccess(targetCompanyProfileId, user, true);
        if (!hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_MANAGER)) {
            throw new AccessDeniedException("Only Business Development Manager can create a reassessment version.");
        }
    }

    private void validateManagerOrOwnerAccess(String targetCompanyProfileId, UserDetailsImpl user) {
        validateAccess(targetCompanyProfileId, user, true);
        if (!hasRole(user, SystemRole.BUSINESS_OWNER) && !hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_MANAGER)) {
            throw new AccessDeniedException("Only Manager or Owner can author relationship assessments.");
        }
    }

    private void validateOwnerAccess(UserDetailsImpl user) {
        if (!hasRole(user, SystemRole.BUSINESS_OWNER)) {
            throw new AccessDeniedException("Only Business Owner can perform this action.");
        }
    }

    private boolean canCreateNewAssessment(String targetCompanyProfileId, UserDetailsImpl user, boolean hasActive, boolean hasFinalized) {
        if (hasActive) return false;
        if (hasRole(user, SystemRole.BUSINESS_DEVELOPMENT_MANAGER)) {
            CompanyProfile target = companyProfileRepository.findById(targetCompanyProfileId)
                    .or(() -> companyProfileRepository.findByCompanyId(targetCompanyProfileId))
                    .orElse(null);
            if (target == null) return false;
            boolean isResponsible = target.getResponsibleManagerId() != null
                    && target.getResponsibleManagerId().equals(user.getId());
            return isResponsible || accessEvaluator.isInProjectScope(target, user.getId(), List.of(ProjectStatus.DRAFT, ProjectStatus.ACTIVE));
        }
        if (hasRole(user, SystemRole.BUSINESS_OWNER)) {
            // Owner can create adjustment ONLY when at least one finalized assessment exists
            return hasFinalized;
        }
        return false;
    }

    private boolean canCreateNewAssessment(String targetCompanyProfileId, UserDetailsImpl user, boolean hasActive) {
        String ownerId = ownerOrganizationService.getOwnerCompanyProfileId();
        List<String> targetIds = resolveTargetProfileIds(targetCompanyProfileId);
        boolean hasFinalized = assessmentRepository.existsByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusIn(
                ownerId, targetCompanyProfileId, List.of(RelationshipAssessmentStatus.FINALIZED));
        if (!hasFinalized && targetIds.size() > 1) {
            hasFinalized = assessmentRepository.existsByOwnerCompanyProfileIdAndCompanyProfileIdInAndStatusIn(
                ownerId, targetIds, List.of(RelationshipAssessmentStatus.FINALIZED));
        }
        return canCreateNewAssessment(targetCompanyProfileId, user, hasActive, hasFinalized);
    }

    private int resolveNextVersionNumber(String ownerId, String targetCompanyProfileId, List<String> targetIds, Integer fallbackVersion) {
        Optional<CompanyRelationshipAssessment> latestAny = assessmentRepository
                .findFirstByOwnerCompanyProfileIdAndCompanyProfileIdOrderByVersionNumberDesc(ownerId, targetCompanyProfileId);
        if (latestAny.isEmpty() && targetIds != null && targetIds.size() > 1) {
            latestAny = assessmentRepository
                    .findFirstByOwnerCompanyProfileIdAndCompanyProfileIdInOrderByVersionNumberDesc(ownerId, targetIds);
        }
        int maxVersion = latestAny.map(CompanyRelationshipAssessment::getVersionNumber).orElse(0);
        if (fallbackVersion != null && fallbackVersion > maxVersion) {
            maxVersion = fallbackVersion;
        }
        return maxVersion + 1;
    }

    private void validateOwnerScoreRange(String criterionName, Integer score) {
        if (score != null && (score < 0 || score > 5)) {
            throw new BusinessValidationException(criterionName + " score must be between 0 and 5.");
        }
    }

    private boolean hasRole(UserDetailsImpl user, SystemRole role) {
        String roleName = "ROLE_" + role.name();
        return user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(roleName));
    }
}

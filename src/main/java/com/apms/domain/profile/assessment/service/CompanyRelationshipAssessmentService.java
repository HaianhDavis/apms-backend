package com.apms.domain.profile.assessment.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.SystemRole;
import com.apms.common.exception.BusinessConflictException;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.contract.entity.PartnerContract;
import com.apms.domain.contract.enums.ContractReviewStatus;
import com.apms.domain.contract.repository.sql.PartnerContractRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.assessment.CompanyRelationshipAssessment;
import com.apms.domain.profile.assessment.RelationshipAssessmentDraft;
import com.apms.domain.profile.assessment.RelationshipAssessmentRank;
import com.apms.domain.profile.assessment.RelationshipAssessmentStatus;
import com.apms.domain.profile.assessment.RelationshipAssessmentType;
import com.apms.domain.profile.assessment.dto.*;
import com.apms.domain.profile.assessment.policy.RelationshipCommercialScoringPolicy;
import com.apms.domain.profile.assessment.policy.RelationshipCommercialScoringPolicy.CommercialEvidenceResult;
import com.apms.domain.profile.assessment.policy.RelationshipScoreCalculator;
import com.apms.domain.profile.assessment.policy.RelationshipScoreCalculator.CalculationResult;
import com.apms.domain.profile.assessment.repository.CompanyRelationshipAssessmentRepository;
import com.apms.domain.profile.assessment.repository.RelationshipAssessmentDraftRepository;
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
import org.springframework.transaction.annotation.Isolation;
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
    private final RelationshipAssessmentDraftRepository relationshipAssessmentDraftRepository;
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

        Optional<CompanyRelationshipAssessment> effectiveFinalized = getLatestFinalizedAssessment(ownerId, targetCompanyProfileId, targetIds);
        Optional<CompanyRelationshipAssessment> previousOfficial = effectiveFinalized.flatMap(this::getPreviousOfficialAssessment);

        // Fetch live commercial evidence for preview
        CommercialEvidenceResponse liveCommercial = getLiveCommercialEvidence(targetCompanyProfileId);

        RelationshipAssessmentDraftResponse myDraft = getMyDraft(targetCompanyProfileId, currentUser);

        Map<String, Object> result = new HashMap<>();
        result.put("activeAssessment", null);
        result.put("officialFinalizedAssessment", effectiveFinalized.map(a -> toResponse(a, currentUser, null)).orElse(null));
        result.put("previousOfficialAssessment", previousOfficial.map(p -> toResponse(p, currentUser, null)).orElse(null));

        if (effectiveFinalized.isPresent() && previousOfficial.isPresent()) {
            CompanyRelationshipAssessment curr = effectiveFinalized.get();
            CompanyRelationshipAssessment prev = previousOfficial.get();
            Integer currScore = getOfficialTotalScore(curr);
            Integer prevScore = getOfficialTotalScore(prev);
            String currRank = getOfficialRank(curr);
            String prevRank = getOfficialRank(prev);
            Integer diff = (currScore != null && prevScore != null) ? currScore - prevScore : null;

            Map<String, Object> trendMap = new HashMap<>();
            trendMap.put("prevVersion", prev.getVersionNumber());
            trendMap.put("prevFormattedVersion", prev.getFormattedVersion());
            trendMap.put("prevScore", prevScore);
            trendMap.put("prevRank", prevRank != null ? prevRank : "D");
            trendMap.put("currentVersion", curr.getVersionNumber());
            trendMap.put("currentFormattedVersion", curr.getFormattedVersion());
            trendMap.put("currentScore", currScore);
            trendMap.put("currentRank", currRank != null ? currRank : "D");
            trendMap.put("diff", diff);
            result.put("trendInfo", trendMap);
        } else {
            result.put("trendInfo", null);
        }

        result.put("liveCommercialEvidence", liveCommercial);
        result.put("hasActiveAssessment", false);
        result.put("canCreateAssessment", canCreateNewAssessment(targetCompanyProfileId, currentUser, false, effectiveFinalized.isPresent()));
        result.put("myDraft", myDraft);

        return result;
    }

    @Transactional(readOnly = true)
    public List<RelationshipAssessmentResponse> getHistory(String targetCompanyProfileId, UserDetailsImpl currentUser) {
        validateTargetProfile(targetCompanyProfileId);
        validateAccess(targetCompanyProfileId, currentUser, false);

        String ownerId = ownerOrganizationService.getOwnerCompanyProfileId();
        List<String> targetIds = resolveTargetProfileIds(targetCompanyProfileId);
        List<CompanyRelationshipAssessment> all = assessmentRepository
                .findAllByOwnerCompanyProfileIdAndCompanyProfileIdOrderByFinalizedAtDescMajorVersionDescMinorRevisionDesc(ownerId, targetCompanyProfileId);
        if (all.isEmpty() && targetIds.size() > 1) {
            all = assessmentRepository
                    .findAllByOwnerCompanyProfileIdAndCompanyProfileIdInOrderByFinalizedAtDescMajorVersionDescMinorRevisionDesc(ownerId, targetIds);
        }
        if (all.isEmpty()) {
            all = assessmentRepository
                    .findAllByOwnerCompanyProfileIdAndCompanyProfileIdOrderByMajorVersionDescMinorRevisionDesc(ownerId, targetCompanyProfileId);
            if (all.isEmpty() && targetIds.size() > 1) {
                all = assessmentRepository
                        .findAllByOwnerCompanyProfileIdAndCompanyProfileIdInOrderByMajorVersionDescMinorRevisionDesc(ownerId, targetIds);
            }
        }

        return all.stream()
                .filter(a -> a.getStatus() == RelationshipAssessmentStatus.FINALIZED)
                .sorted(Comparator.comparing(CompanyRelationshipAssessment::getFinalizedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(CompanyRelationshipAssessment::getMajorVersion, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(CompanyRelationshipAssessment::getMinorRevision, Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed())
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
            assessments = assessmentRepository.findAllByOwnerCompanyProfileIdAndStatusOrderByFinalizedAtDescMajorVersionDescMinorRevisionDesc(
                    ownerId, RelationshipAssessmentStatus.FINALIZED);
            if (assessments.isEmpty()) {
                assessments = assessmentRepository.findAllByOwnerCompanyProfileIdAndStatusOrderByVersionNumberDesc(
                        ownerId, RelationshipAssessmentStatus.FINALIZED);
            }
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

            list.sort(Comparator.comparing(CompanyRelationshipAssessment::getFinalizedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(CompanyRelationshipAssessment::getMajorVersion, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(CompanyRelationshipAssessment::getMinorRevision, Comparator.nullsLast(Comparator.naturalOrder()))
                    .reversed());

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
                .majorVersion(entity.getMajorVersion())
                .minorRevision(entity.getMinorRevision())
                .formattedVersion(entity.getFormattedVersion())
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
    // Latest Finalized Helper
    // ----------------------------------------------------------------------------------

    public Optional<CompanyRelationshipAssessment> getLatestFinalizedAssessment(String ownerId, String targetCompanyProfileId, List<String> targetIds) {
        Optional<CompanyRelationshipAssessment> finalizedOpt = assessmentRepository
                .findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByFinalizedAtDescMajorVersionDescMinorRevisionDesc(
                        ownerId, targetCompanyProfileId, RelationshipAssessmentStatus.FINALIZED);
        if (finalizedOpt.isEmpty()) {
            finalizedOpt = assessmentRepository
                    .findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByMajorVersionDescMinorRevisionDesc(
                            ownerId, targetCompanyProfileId, RelationshipAssessmentStatus.FINALIZED);
        }
        if (finalizedOpt.isEmpty()) {
            finalizedOpt = assessmentRepository
                    .findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
                            ownerId, targetCompanyProfileId, RelationshipAssessmentStatus.FINALIZED);
        }
        if (finalizedOpt.isEmpty() && targetIds != null && targetIds.size() > 1) {
            finalizedOpt = assessmentRepository
                    .findFirstByOwnerCompanyProfileIdAndCompanyProfileIdInAndStatusOrderByFinalizedAtDescMajorVersionDescMinorRevisionDesc(
                            ownerId, targetIds, RelationshipAssessmentStatus.FINALIZED);
            if (finalizedOpt.isEmpty()) {
                finalizedOpt = assessmentRepository
                        .findFirstByOwnerCompanyProfileIdAndCompanyProfileIdInAndStatusOrderByMajorVersionDescMinorRevisionDesc(
                                ownerId, targetIds, RelationshipAssessmentStatus.FINALIZED);
            }
            if (finalizedOpt.isEmpty()) {
                finalizedOpt = assessmentRepository
                        .findFirstByOwnerCompanyProfileIdAndCompanyProfileIdInAndStatusOrderByVersionNumberDesc(
                                ownerId, targetIds, RelationshipAssessmentStatus.FINALIZED);
            }
        }
        return finalizedOpt;
    }

    public Optional<CompanyRelationshipAssessment> getPreviousOfficialAssessment(CompanyRelationshipAssessment currentAssessment) {
        if (currentAssessment == null || currentAssessment.getId() == null) {
            return Optional.empty();
        }

        String ownerId = currentAssessment.getOwnerCompanyProfileId();
        String companyProfileId = currentAssessment.getCompanyProfileId();
        List<String> targetIds = resolveTargetProfileIds(companyProfileId);

        List<CompanyRelationshipAssessment> finalizedList = assessmentRepository
                .findAllByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByFinalizedAtDescMajorVersionDescMinorRevisionDesc(
                        ownerId, companyProfileId, RelationshipAssessmentStatus.FINALIZED);
        if (finalizedList.isEmpty() && targetIds.size() > 1) {
            finalizedList = assessmentRepository
                    .findAllByOwnerCompanyProfileIdAndCompanyProfileIdInAndStatusOrderByFinalizedAtDescMajorVersionDescMinorRevisionDesc(
                            ownerId, targetIds, RelationshipAssessmentStatus.FINALIZED);
        }
        if (finalizedList.isEmpty()) {
            finalizedList = assessmentRepository
                    .findAllByOwnerCompanyProfileIdAndCompanyProfileIdOrderByMajorVersionDescMinorRevisionDesc(ownerId, companyProfileId);
            if (finalizedList.isEmpty() && targetIds.size() > 1) {
                finalizedList = assessmentRepository
                        .findAllByOwnerCompanyProfileIdAndCompanyProfileIdInOrderByMajorVersionDescMinorRevisionDesc(ownerId, targetIds);
            }
        }

        List<CompanyRelationshipAssessment> sorted = finalizedList.stream()
                .filter(a -> a.getStatus() == RelationshipAssessmentStatus.FINALIZED)
                .sorted(Comparator.comparing(CompanyRelationshipAssessment::getFinalizedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(CompanyRelationshipAssessment::getMajorVersion, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(CompanyRelationshipAssessment::getMinorRevision, Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed())
                .toList();

        int currentIndex = -1;
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getId().equals(currentAssessment.getId())) {
                currentIndex = i;
                break;
            }
        }

        if (currentIndex >= 0) {
            if (currentIndex + 1 < sorted.size()) {
                return Optional.of(sorted.get(currentIndex + 1));
            }
            return Optional.empty();
        }

        // If current assessment is not yet in the persisted list (e.g. before finalize or test mock)
        LocalDateTime currentFinalizedAt = currentAssessment.getFinalizedAt();
        return sorted.stream()
                .filter(a -> !a.getId().equals(currentAssessment.getId()))
                .filter(a -> {
                    if (currentFinalizedAt == null) return true;
                    if (a.getFinalizedAt() == null) return false;
                    if (a.getFinalizedAt().isBefore(currentFinalizedAt)) return true;
                    if (a.getFinalizedAt().isEqual(currentFinalizedAt)) {
                        int majorCmp = Integer.compare(
                                a.getMajorVersion() != null ? a.getMajorVersion() : 0,
                                currentAssessment.getMajorVersion() != null ? currentAssessment.getMajorVersion() : 0);
                        if (majorCmp < 0) return true;
                        if (majorCmp == 0) {
                            return Integer.compare(
                                    a.getMinorRevision() != null ? a.getMinorRevision() : 0,
                                    currentAssessment.getMinorRevision() != null ? currentAssessment.getMinorRevision() : 0) < 0;
                        }
                    }
                    return false;
                })
                .findFirst();
    }

    // ----------------------------------------------------------------------------------
    // Private Draft Management (Actor-Scoped)
    // ----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public RelationshipAssessmentDraftResponse getMyDraft(String targetCompanyProfileId, UserDetailsImpl currentUser) {
        validateTargetProfile(targetCompanyProfileId);
        validateAccess(targetCompanyProfileId, currentUser, false);

        boolean isOwner = hasRole(currentUser, SystemRole.BUSINESS_OWNER);
        boolean isManager = hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_MANAGER)
                || hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_STAFF);

        if (!isOwner && !isManager) {
            return null;
        }

        RelationshipAssessmentType draftType = isOwner
                ? RelationshipAssessmentType.OWNER_ADJUSTMENT
                : RelationshipAssessmentType.MANAGER_ASSESSMENT;

        String ownerId = ownerOrganizationService.getOwnerCompanyProfileId();
        List<String> targetIds = resolveTargetProfileIds(targetCompanyProfileId);

        Optional<RelationshipAssessmentDraft> draftOpt = relationshipAssessmentDraftRepository
                .findByOwnerCompanyProfileIdAndCompanyProfileIdAndActorAccountIdAndDraftType(
                        ownerId, targetCompanyProfileId, currentUser.getId(), draftType);
        if (draftOpt.isEmpty() && targetIds.size() > 1) {
            for (String tid : targetIds) {
                draftOpt = relationshipAssessmentDraftRepository
                        .findByOwnerCompanyProfileIdAndCompanyProfileIdAndActorAccountIdAndDraftType(
                                ownerId, tid, currentUser.getId(), draftType);
                if (draftOpt.isPresent()) break;
            }
        }

        if (draftOpt.isEmpty()) {
            return null;
        }

        RelationshipAssessmentDraft draft = draftOpt.get();
        Optional<CompanyRelationshipAssessment> latestOfficialOpt = getLatestFinalizedAssessment(ownerId, targetCompanyProfileId, targetIds);

        boolean isStale = false;
        boolean isBaseUpdated = false;
        CompanyRelationshipAssessment latestOfficial = latestOfficialOpt.orElse(null);

        if (draftType == RelationshipAssessmentType.MANAGER_ASSESSMENT) {
            if (latestOfficial != null) {
                int latestMajor = latestOfficial.getMajorVersion() != null ? latestOfficial.getMajorVersion() : 1;
                int draftBaseMajor = draft.getBaseMajorVersion() != null ? draft.getBaseMajorVersion() : 0;
                if (draftBaseMajor < latestMajor) {
                    // Newer major reassessment exists! Draft is stale
                    isStale = true;
                    isBaseUpdated = true;
                } else if (draftBaseMajor == latestMajor) {
                    // Same major, but check if an owner minor revision occurred (e.g. V1.1 vs V1)
                    int latestMinor = latestOfficial.getMinorRevision() != null ? latestOfficial.getMinorRevision() : 0;
                    int draftBaseMinor = draft.getBaseMinorRevision() != null ? draft.getBaseMinorRevision() : 0;
                    if (draftBaseMinor != latestMinor) {
                        isBaseUpdated = true; // Informational warning only!
                        isStale = false;
                    }
                }
            }
        } else {
            // OWNER_ADJUSTMENT strictly requires baseOfficialAssessmentId == latestOfficial.getId()
            if (latestOfficial == null) {
                isStale = true;
                isBaseUpdated = true;
            } else {
                if (draft.getBaseOfficialAssessmentId() == null || !draft.getBaseOfficialAssessmentId().equals(latestOfficial.getId())) {
                    isStale = true;
                    isBaseUpdated = true;
                }
            }
        }

        int changedCount = 0;
        if (draftType == RelationshipAssessmentType.OWNER_ADJUSTMENT && latestOfficial != null) {
            OfficialCriterionScores base = getOfficialCriterionScores(latestOfficial);
            if (draft.getOwnerCommercialScore() != null && !draft.getOwnerCommercialScore().equals(base.commercial())) changedCount++;
            if (draft.getOwnerCooperationScore() != null && !draft.getOwnerCooperationScore().equals(base.cooperation())) changedCount++;
            if (draft.getOwnerStrategicScore() != null && !draft.getOwnerStrategicScore().equals(base.strategic())) changedCount++;
            if (draft.getOwnerRelationshipNetworkScore() != null && !draft.getOwnerRelationshipNetworkScore().equals(base.relationshipNetwork())) changedCount++;
            if (draft.getOwnerEngagementScore() != null && !draft.getOwnerEngagementScore().equals(base.engagement())) changedCount++;
            if (draft.getOwnerQualitativeScore() != null && !draft.getOwnerQualitativeScore().equals(base.qualitative())) changedCount++;
        }

        int completedCriteriaCount = 0;
        if (draftType == RelationshipAssessmentType.MANAGER_ASSESSMENT) {
            if (draft.getCommercialScore() != null) completedCriteriaCount++;
            if (draft.getCooperationScore() != null) completedCriteriaCount++;
            if (draft.getStrategicScore() != null) completedCriteriaCount++;
            if (draft.getRelationshipNetworkScore() != null) completedCriteriaCount++;
            if (draft.getEngagementScore() != null) completedCriteriaCount++;
            if (draft.getQualitativeScore() != null) completedCriteriaCount++;
        } else {
            completedCriteriaCount = changedCount;
        }

        return RelationshipAssessmentDraftResponse.builder()
                .draftId(draft.getId())
                .companyProfileId(draft.getCompanyProfileId())
                .actorAccountId(draft.getActorAccountId())
                .actorRole(draft.getActorRole())
                .draftType(draft.getDraftType())
                .baseOfficialAssessmentId(draft.getBaseOfficialAssessmentId())
                .baseMajorVersion(draft.getBaseMajorVersion())
                .baseMinorRevision(draft.getBaseMinorRevision())
                .baseFormattedVersion(draft.getBaseFormattedVersion())
                .latestOfficialAssessmentId(latestOfficial != null ? latestOfficial.getId() : null)
                .latestOfficialMajorVersion(latestOfficial != null ? latestOfficial.getMajorVersion() : null)
                .latestOfficialMinorRevision(latestOfficial != null ? latestOfficial.getMinorRevision() : null)
                .latestOfficialFormattedVersion(latestOfficial != null ? latestOfficial.getFormattedVersion() : null)
                .isStale(isStale)
                .isBaseUpdated(isBaseUpdated)
                .completedCriteriaCount(completedCriteriaCount)
                .changedCriterionCount(changedCount)
                .commercialScore(draft.getCommercialScore())
                .cooperationScore(draft.getCooperationScore())
                .strategicScore(draft.getStrategicScore())
                .relationshipNetworkScore(draft.getRelationshipNetworkScore())
                .engagementScore(draft.getEngagementScore())
                .qualitativeScore(draft.getQualitativeScore())
                .ownerCommercialScore(draft.getOwnerCommercialScore())
                .ownerCooperationScore(draft.getOwnerCooperationScore())
                .ownerStrategicScore(draft.getOwnerStrategicScore())
                .ownerRelationshipNetworkScore(draft.getOwnerRelationshipNetworkScore())
                .ownerEngagementScore(draft.getOwnerEngagementScore())
                .ownerQualitativeScore(draft.getOwnerQualitativeScore())
                .commercialEvidenceNote(draft.getCommercialEvidenceNote())
                .cooperationEvidenceNote(draft.getCooperationEvidenceNote())
                .strategicEvidenceNote(draft.getStrategicEvidenceNote())
                .relationshipNetworkNote(draft.getRelationshipNetworkNote())
                .engagementEvidenceNote(draft.getEngagementEvidenceNote())
                .qualitativeEvidenceNote(draft.getQualitativeEvidenceNote())
                .managerNote(draft.getManagerNote())
                .ownerCommercialNote(draft.getOwnerCommercialNote())
                .ownerCooperationNote(draft.getOwnerCooperationNote())
                .ownerStrategicNote(draft.getOwnerStrategicNote())
                .ownerRelationshipNetworkNote(draft.getOwnerRelationshipNetworkNote())
                .ownerEngagementNote(draft.getOwnerEngagementNote())
                .ownerQualitativeNote(draft.getOwnerQualitativeNote())
                .ownerNote(draft.getOwnerNote())
                .ownerAdjustmentReason(draft.getOwnerAdjustmentReason())
                .createdAt(draft.getCreatedAt())
                .updatedAt(draft.getUpdatedAt())
                .build();
    }

    @Transactional
    public RelationshipAssessmentDraftResponse saveDraft(
            String targetCompanyProfileId,
            SaveRelationshipAssessmentDraftRequest request,
            UserDetailsImpl currentUser) {
        validateTargetProfile(targetCompanyProfileId);
        validateAccess(targetCompanyProfileId, currentUser, true);

        boolean isOwner = hasRole(currentUser, SystemRole.BUSINESS_OWNER);
        boolean isManager = hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_MANAGER)
                || hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_STAFF);

        if (!isOwner && !isManager) {
            throw new AccessDeniedException("Only BD Manager or Business Owner can save relationship assessment drafts.");
        }

        RelationshipAssessmentType draftType = isOwner
                ? RelationshipAssessmentType.OWNER_ADJUSTMENT
                : RelationshipAssessmentType.MANAGER_ASSESSMENT;

        String ownerId = ownerOrganizationService.getOwnerCompanyProfileId();
        CompanyProfile canonicalProfile = resolveTargetProfile(targetCompanyProfileId);
        String canonicalCompanyProfileId = canonicalProfile.getId();
        List<String> targetIds = resolveTargetProfileIds(targetCompanyProfileId);

        Optional<CompanyRelationshipAssessment> latestOfficialOpt = getLatestFinalizedAssessment(ownerId, canonicalCompanyProfileId, targetIds);

        if (isOwner && latestOfficialOpt.isEmpty()) {
            throw new BusinessValidationException("Không thể tạo bản nháp điều chỉnh khi chưa có bản đánh giá chính thức nào.");
        }

        if (!isOwner) {
            boolean hasScore = request != null && (
                    request.getCommercialScore() != null ||
                    request.getCooperationScore() != null ||
                    request.getStrategicScore() != null ||
                    request.getRelationshipNetworkScore() != null ||
                    request.getEngagementScore() != null ||
                    request.getQualitativeScore() != null
            );
            boolean hasNote = request != null && (
                    StringUtils.hasText(request.getCommercialEvidenceNote()) ||
                    StringUtils.hasText(request.getCooperationEvidenceNote()) ||
                    StringUtils.hasText(request.getStrategicEvidenceNote()) ||
                    StringUtils.hasText(request.getRelationshipNetworkNote()) ||
                    StringUtils.hasText(request.getEngagementEvidenceNote()) ||
                    StringUtils.hasText(request.getQualitativeEvidenceNote()) ||
                    StringUtils.hasText(request.getManagerNote())
            );
            if (!hasScore && !hasNote) {
                throw new BusinessValidationException("Không thể lưu bản nháp trống. Vui lòng chọn ít nhất 1 điểm tiêu chí hoặc nhập 1 ghi chú.");
            }
        } else {
            boolean hasScore = request != null && (
                    request.getOwnerCommercialScore() != null ||
                    request.getOwnerCooperationScore() != null ||
                    request.getOwnerStrategicScore() != null ||
                    request.getOwnerRelationshipNetworkScore() != null ||
                    request.getOwnerEngagementScore() != null ||
                    request.getOwnerQualitativeScore() != null
            );
            boolean hasNote = request != null && (
                    StringUtils.hasText(request.getOwnerCommercialNote()) ||
                    StringUtils.hasText(request.getOwnerCooperationNote()) ||
                    StringUtils.hasText(request.getOwnerStrategicNote()) ||
                    StringUtils.hasText(request.getOwnerRelationshipNetworkNote()) ||
                    StringUtils.hasText(request.getOwnerEngagementNote()) ||
                    StringUtils.hasText(request.getOwnerQualitativeNote()) ||
                    StringUtils.hasText(request.getOwnerNote()) ||
                    StringUtils.hasText(request.getOwnerAdjustmentReason())
            );
            if (!hasScore && !hasNote) {
                throw new BusinessValidationException("Không thể lưu bản nháp trống. Vui lòng chọn ít nhất 1 điểm điều chỉnh hoặc nhập 1 ghi chú.");
            }
        }

        RelationshipAssessmentDraft draft = relationshipAssessmentDraftRepository
                .findByOwnerCompanyProfileIdAndCompanyProfileIdAndActorAccountIdAndDraftType(
                        ownerId, canonicalCompanyProfileId, currentUser.getId(), draftType)
                .orElse(null);

        if (draft == null) {
            draft = RelationshipAssessmentDraft.builder()
                    .ownerCompanyProfileId(ownerId)
                    .companyProfileId(canonicalCompanyProfileId)
                    .actorAccountId(currentUser.getId())
                    .actorRole(isOwner ? "BUSINESS_OWNER" : (hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_MANAGER) ? "BUSINESS_DEVELOPMENT_MANAGER" : "BUSINESS_DEVELOPMENT_STAFF"))
                    .draftType(draftType)
                    .build();
        }

        if (isOwner) {
            CompanyRelationshipAssessment latestOfficial = latestOfficialOpt.get();
            draft.setBaseOfficialAssessmentId(request != null && request.getBaseOfficialAssessmentId() != null
                    ? request.getBaseOfficialAssessmentId()
                    : latestOfficial.getId());
            draft.setBaseMajorVersion(request != null && request.getBaseMajorVersion() != null
                    ? request.getBaseMajorVersion()
                    : latestOfficial.getMajorVersion());
            draft.setBaseMinorRevision(request != null && request.getBaseMinorRevision() != null
                    ? request.getBaseMinorRevision()
                    : latestOfficial.getMinorRevision());

            if (request != null) {
                draft.setOwnerCommercialScore(request.getOwnerCommercialScore());
                draft.setOwnerCooperationScore(request.getOwnerCooperationScore());
                draft.setOwnerStrategicScore(request.getOwnerStrategicScore());
                draft.setOwnerRelationshipNetworkScore(request.getOwnerRelationshipNetworkScore());
                draft.setOwnerEngagementScore(request.getOwnerEngagementScore());
                draft.setOwnerQualitativeScore(request.getOwnerQualitativeScore());

                draft.setOwnerCommercialNote(request.getOwnerCommercialNote());
                draft.setOwnerCooperationNote(request.getOwnerCooperationNote());
                draft.setOwnerStrategicNote(request.getOwnerStrategicNote());
                draft.setOwnerRelationshipNetworkNote(request.getOwnerRelationshipNetworkNote());
                draft.setOwnerEngagementNote(request.getOwnerEngagementNote());
                draft.setOwnerQualitativeNote(request.getOwnerQualitativeNote());
                draft.setOwnerNote(request.getOwnerNote());
                draft.setOwnerAdjustmentReason(request.getOwnerAdjustmentReason());
            }
        } else {
            // Manager
            draft.setBaseOfficialAssessmentId(request != null && request.getBaseOfficialAssessmentId() != null
                    ? request.getBaseOfficialAssessmentId()
                    : latestOfficialOpt.map(CompanyRelationshipAssessment::getId).orElse(null));
            draft.setBaseMajorVersion(request != null && request.getBaseMajorVersion() != null
                    ? request.getBaseMajorVersion()
                    : latestOfficialOpt.map(CompanyRelationshipAssessment::getMajorVersion).orElse(0));
            draft.setBaseMinorRevision(request != null && request.getBaseMinorRevision() != null
                    ? request.getBaseMinorRevision()
                    : latestOfficialOpt.map(CompanyRelationshipAssessment::getMinorRevision).orElse(0));

            if (request != null) {
                draft.setCommercialScore(request.getCommercialScore());
                draft.setCooperationScore(request.getCooperationScore());
                draft.setStrategicScore(request.getStrategicScore());
                draft.setRelationshipNetworkScore(request.getRelationshipNetworkScore());
                draft.setEngagementScore(request.getEngagementScore());
                draft.setQualitativeScore(request.getQualitativeScore());

                draft.setCommercialEvidenceNote(request.getCommercialEvidenceNote());
                draft.setCooperationEvidenceNote(request.getCooperationEvidenceNote());
                draft.setStrategicEvidenceNote(request.getStrategicEvidenceNote());
                draft.setRelationshipNetworkNote(request.getRelationshipNetworkNote());
                draft.setEngagementEvidenceNote(request.getEngagementEvidenceNote());
                draft.setQualitativeEvidenceNote(request.getQualitativeEvidenceNote());
                draft.setManagerNote(request.getManagerNote());
            }
        }

        relationshipAssessmentDraftRepository.saveAndFlush(draft);
        return getMyDraft(targetCompanyProfileId, currentUser);
    }

    @Transactional
    public void deleteMyDraft(String targetCompanyProfileId, UserDetailsImpl currentUser) {
        validateTargetProfile(targetCompanyProfileId);
        validateAccess(targetCompanyProfileId, currentUser, false);

        boolean isOwner = hasRole(currentUser, SystemRole.BUSINESS_OWNER);
        RelationshipAssessmentType draftType = isOwner
                ? RelationshipAssessmentType.OWNER_ADJUSTMENT
                : RelationshipAssessmentType.MANAGER_ASSESSMENT;

        String ownerId = ownerOrganizationService.getOwnerCompanyProfileId();
        CompanyProfile canonicalProfile = resolveTargetProfile(targetCompanyProfileId);
        String canonicalCompanyProfileId = canonicalProfile.getId();
        List<String> targetIds = resolveTargetProfileIds(targetCompanyProfileId);

        relationshipAssessmentDraftRepository
                .deleteByOwnerCompanyProfileIdAndCompanyProfileIdAndActorAccountIdAndDraftType(
                        ownerId, canonicalCompanyProfileId, currentUser.getId(), draftType);
        for (String tid : targetIds) {
            relationshipAssessmentDraftRepository
                    .deleteByOwnerCompanyProfileIdAndCompanyProfileIdAndActorAccountIdAndDraftType(
                            ownerId, tid, currentUser.getId(), draftType);
        }
    }

    @Transactional
    public RelationshipAssessmentDraftResponse rebaseOwnerDraft(String targetCompanyProfileId, UserDetailsImpl currentUser) {
        validateOwnerAccess(currentUser);
        validateTargetProfile(targetCompanyProfileId);
        validateAccess(targetCompanyProfileId, currentUser, true);

        String ownerId = ownerOrganizationService.getOwnerCompanyProfileId();
        CompanyProfile canonicalProfile = resolveTargetProfile(targetCompanyProfileId);
        String canonicalCompanyProfileId = canonicalProfile.getId();
        List<String> targetIds = resolveTargetProfileIds(targetCompanyProfileId);

        RelationshipAssessmentDraft draft = relationshipAssessmentDraftRepository
                .findByOwnerCompanyProfileIdAndCompanyProfileIdAndActorAccountIdAndDraftType(
                        ownerId, canonicalCompanyProfileId, currentUser.getId(), RelationshipAssessmentType.OWNER_ADJUSTMENT)
                .orElse(null);

        if (draft == null && targetIds.size() > 1) {
            for (String tid : targetIds) {
                draft = relationshipAssessmentDraftRepository
                        .findByOwnerCompanyProfileIdAndCompanyProfileIdAndActorAccountIdAndDraftType(
                                ownerId, tid, currentUser.getId(), RelationshipAssessmentType.OWNER_ADJUSTMENT)
                        .orElse(null);
                if (draft != null) break;
            }
        }

        if (draft == null) {
            throw new ResourceNotFoundException("Không tìm thấy bản nháp điều chỉnh để cập nhật.");
        }

        CompanyRelationshipAssessment latestOfficial = getLatestFinalizedAssessment(ownerId, canonicalCompanyProfileId, targetIds)
                .orElseThrow(() -> new BusinessValidationException("Không tìm thấy bản đánh giá chính thức mới để cập nhật."));

        OfficialCriterionScores latestScores = getOfficialCriterionScores(latestOfficial);

        // Rebase lineage
        draft.setBaseOfficialAssessmentId(latestOfficial.getId());
        draft.setBaseMajorVersion(latestOfficial.getMajorVersion());
        draft.setBaseMinorRevision(latestOfficial.getMinorRevision());

        // Mandatory Correction 3: Normalization
        // Preserve explicit adjustments, but if adjustedScore == newBaselineScore, normalize it to null (no-op).
        if (draft.getOwnerCommercialScore() != null && draft.getOwnerCommercialScore().equals(latestScores.commercial())) {
            draft.setOwnerCommercialScore(null);
        }
        if (draft.getOwnerCooperationScore() != null && draft.getOwnerCooperationScore().equals(latestScores.cooperation())) {
            draft.setOwnerCooperationScore(null);
        }
        if (draft.getOwnerStrategicScore() != null && draft.getOwnerStrategicScore().equals(latestScores.strategic())) {
            draft.setOwnerStrategicScore(null);
        }
        if (draft.getOwnerRelationshipNetworkScore() != null && draft.getOwnerRelationshipNetworkScore().equals(latestScores.relationshipNetwork())) {
            draft.setOwnerRelationshipNetworkScore(null);
        }
        if (draft.getOwnerEngagementScore() != null && draft.getOwnerEngagementScore().equals(latestScores.engagement())) {
            draft.setOwnerEngagementScore(null);
        }
        if (draft.getOwnerQualitativeScore() != null && draft.getOwnerQualitativeScore().equals(latestScores.qualitative())) {
            draft.setOwnerQualitativeScore(null);
        }

        relationshipAssessmentDraftRepository.saveAndFlush(draft);
        return getMyDraft(targetCompanyProfileId, currentUser);
    }

    // ----------------------------------------------------------------------------------
    // Manager Actions
    // ----------------------------------------------------------------------------------

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public RelationshipAssessmentResponse completeDirectAssessment(
            String targetCompanyProfileId,
            CompleteRelationshipAssessmentRequest request,
            UserDetailsImpl currentUser) {

        validateTargetProfile(targetCompanyProfileId);
        validateRelationshipClosenessEligibility(targetCompanyProfileId);
        validateAccess(targetCompanyProfileId, currentUser, true);

        if (!hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_MANAGER)) {
            throw new AccessDeniedException("Only Business Development Manager can complete relationship assessments.");
        }

        String ownerId = ownerOrganizationService.getOwnerCompanyProfileId();
        CompanyProfile target = resolveTargetProfile(targetCompanyProfileId);
        String canonicalCompanyProfileId = target.getId() != null ? target.getId() : targetCompanyProfileId;
        List<String> targetIds = resolveTargetProfileIds(targetCompanyProfileId);

        // Optimistic concurrency / major lineage check (Mandatory Correction 1):
        Optional<CompanyRelationshipAssessment> latestFinalizedOpt = getLatestFinalizedAssessment(ownerId, canonicalCompanyProfileId, targetIds);

        if (latestFinalizedOpt.isPresent()) {
            CompanyRelationshipAssessment latestFinalized = latestFinalizedOpt.get();
            int latestMajor = (latestFinalized.getMajorVersion() != null && latestFinalized.getMajorVersion() > 0)
                    ? latestFinalized.getMajorVersion()
                    : (latestFinalized.getVersionNumber() != null ? latestFinalized.getVersionNumber() : 1);
            Integer baseMajor = request != null ? request.getBaseMajorVersion() : null;
            if (baseMajor == null && request != null && request.getSourceAssessmentId() != null) {
                CompanyRelationshipAssessment src = assessmentRepository.findById(request.getSourceAssessmentId()).orElse(null);
                if (src != null) {
                    baseMajor = (src.getMajorVersion() != null && src.getMajorVersion() > 0) ? src.getMajorVersion() : src.getVersionNumber();
                } else if (!request.getSourceAssessmentId().equals(latestFinalized.getId())) {
                    throw new BusinessConflictException("Bản đánh giá chính thức đã được cập nhật trong khi bạn đang thực hiện đánh giá. Vui lòng tải lại dữ liệu trước khi tiếp tục.");
                }
            }
            if (baseMajor != null && baseMajor < latestMajor) {
                throw new BusinessConflictException("Bản đánh giá đã có phiên bản chính mới hơn (V" + latestMajor + "). Vui lòng tải lại dữ liệu trước khi tiếp tục.");
            }
        }

        if (request == null) {
            throw new BusinessValidationException("Dữ liệu đánh giá không được để trống.");
        }

        Integer resolvedQualScore = resolveTrustScore(request.getQualitativeScore(), request.getTrustScore());
        String resolvedQualNote = resolveTrustNote(request.getQualitativeEvidenceNote(), request.getTrustEvidenceNote());

        scoreCalculator.validateCompleteSubmissionV5(
                request.getCommercialAwardedScore(),
                request.getCooperationScore(),
                request.getStrategicScore(),
                request.getRelationshipNetworkScore(),
                request.getEngagementScore(),
                resolvedQualScore
        );

        CalculationResult calc = scoreCalculator.calculateV5(
                request.getCommercialAwardedScore(),
                request.getCooperationScore(),
                request.getStrategicScore(),
                request.getRelationshipNetworkScore(),
                request.getEngagementScore(),
                resolvedQualScore
        );

        List<PartnerContract> approved = partnerContractRepository
                .findByPartnerCompanyIdAndReviewStatus(targetCompanyProfileId, ContractReviewStatus.APPROVED);
        CommercialEvidenceResult commercial = scoringPolicy.evaluate(approved, LocalDate.now(), RelationshipCommercialScoringPolicy.POLICY_VERSION_V5);

        // Manager creates next MAJOR version
        int maxMajor = assessmentRepository.findMaxFinalizedMajorVersion(ownerId, targetIds);
        if (maxMajor == 0) {
            maxMajor = assessmentRepository.findMaxFinalizedVersionNumber(ownerId, targetIds);
        }
        int nextMajor = maxMajor + 1;
        int nextMinor = 0;

        CompanyRelationshipAssessment assessment = CompanyRelationshipAssessment.builder()
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(canonicalCompanyProfileId)
                .versionNumber(nextMajor)
                .majorVersion(nextMajor)
                .minorRevision(nextMinor)
                .status(RelationshipAssessmentStatus.FINALIZED)
                .assessmentType(RelationshipAssessmentType.MANAGER_ASSESSMENT)
                .sourceAssessmentId(request.getSourceAssessmentId())
                .scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5)
                .commercialSuggestedScore(null)
                .commercialAwardedScore(request.getCommercialAwardedScore())
                .commercialScore(null)
                .commercialAdjustmentReason(request.getCommercialAdjustmentReason())
                .commercialEvidenceNote(request.getCommercialEvidenceNote())
                .cooperationScore(request.getCooperationScore())
                .cooperationEvidenceNote(request.getCooperationEvidenceNote())
                .strategicScore(request.getStrategicScore())
                .strategicEvidenceNote(request.getStrategicEvidenceNote())
                .relationshipNetworkScore(request.getRelationshipNetworkScore())
                .relationshipNetworkNote(request.getRelationshipNetworkNote())
                .engagementScore(request.getEngagementScore())
                .engagementEvidenceNote(request.getEngagementEvidenceNote())
                .qualitativeScore(resolvedQualScore)
                .qualitativeEvidenceNote(resolvedQualNote)
                .managerNote(request.getManagerNote())
                .scorableBase(calc.getScorableBase())
                .normalizationApplied(calc.isNormalizationApplied())
                .managerRawScorableScore(calc.getRawScorableScore())
                .managerTotalScore(calc.getNormalizedTotalScore())
                .managerRank(calc.getRank().name())
                .managerAccountId(currentUser.getId())
                .managerSubmittedAt(null)
                .finalizedAt(LocalDateTime.now())
                .createdByAccountId(currentUser.getId())
                .build();

        applyCommercialSnapshot(assessment, commercial);

        try {
            assessment = assessmentRepository.saveAndFlush(assessment);
        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent assessment version allocation for company {}: {}", canonicalCompanyProfileId, e.getMessage());
            throw new BusinessConflictException("Phiên bản đánh giá đã được tạo bởi một phiên làm việc khác. Vui lòng tải lại dữ liệu trước khi tiếp tục.");
        }

        // Atomically delete Manager's draft
        relationshipAssessmentDraftRepository.deleteByOwnerCompanyProfileIdAndCompanyProfileIdAndActorAccountIdAndDraftType(
                ownerId, canonicalCompanyProfileId, currentUser.getId(), RelationshipAssessmentType.MANAGER_ASSESSMENT);
        for (String tid : targetIds) {
            relationshipAssessmentDraftRepository.deleteByOwnerCompanyProfileIdAndCompanyProfileIdAndActorAccountIdAndDraftType(
                    ownerId, tid, currentUser.getId(), RelationshipAssessmentType.MANAGER_ASSESSMENT);
        }

        auditLogService.log(currentUser.getId(), AuditAction.RELATIONSHIP_ASSESSMENT_FINALIZED,
                "CompanyRelationshipAssessment", String.valueOf(assessment.getId()),
                String.format("Finalized Manager assessment %s (Total: %d, Rank: %s) for company %s",
                        assessment.getFormattedVersion(), calc.getNormalizedTotalScore(), calc.getRank(), canonicalCompanyProfileId));

        notificationService.notifyRelationshipAssessmentCompleted(assessment, currentUser != null ? currentUser.getId() : null);

        return toResponse(assessment, currentUser, null);
    }

    // ----------------------------------------------------------------------------------
    // Legacy Draft Lifecycle Endpoints (Disabled - Atomic Completion Required)
    // ----------------------------------------------------------------------------------

    @Transactional
    public RelationshipAssessmentResponse createDraft(
            String targetCompanyProfileId,
            CreateRelationshipAssessmentRequest request,
            UserDetailsImpl currentUser) {
        throw new BusinessValidationException("Draft workflow is no longer supported for Relationship Closeness assessments. Assessments are completed and finalized atomically.");
    }

    @Transactional
    public RelationshipAssessmentResponse updateDraft(
            Long assessmentId,
            UpdateRelationshipAssessmentRequest request,
            UserDetailsImpl currentUser) {
        throw new BusinessValidationException("Draft workflow is no longer supported for Relationship Closeness assessments. Assessments are completed and finalized atomically.");
    }

    @Transactional
    public RelationshipAssessmentResponse submitAssessment(Long assessmentId, UserDetailsImpl currentUser) {
        throw new BusinessValidationException("Draft workflow is no longer supported for Relationship Closeness assessments. Assessments are completed and finalized atomically.");
    }

    @Transactional
    public RelationshipAssessmentResponse completeAssessment(
            Long assessmentId,
            UpdateRelationshipAssessmentRequest request,
            UserDetailsImpl currentUser) {
        throw new BusinessValidationException("Draft workflow is no longer supported for Relationship Closeness assessments. Assessments are completed and finalized atomically.");
    }

    // ----------------------------------------------------------------------------------
    // Owner Actions (Atomic Completion)
    // ----------------------------------------------------------------------------------

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public RelationshipAssessmentResponse completeDirectOwnerAdjustment(
            String targetCompanyProfileId,
            CompleteOwnerAdjustmentRequest request,
            UserDetailsImpl currentUser) {

        validateOwnerAccess(currentUser);
        validateTargetProfile(targetCompanyProfileId);
        validateRelationshipClosenessEligibility(targetCompanyProfileId);
        validateAccess(targetCompanyProfileId, currentUser, true);

        String ownerId = ownerOrganizationService.getOwnerCompanyProfileId();
        List<String> targetIds = resolveTargetProfileIds(targetCompanyProfileId);

        Optional<CompanyRelationshipAssessment> latestFinalizedOpt = getLatestFinalizedAssessment(ownerId, targetCompanyProfileId, targetIds);
        CompanyRelationshipAssessment source = latestFinalizedOpt
                .orElseThrow(() -> new BusinessValidationException("Không thể điều chỉnh khi chưa có bản đánh giá chính thức nào."));

        if (request == null || request.getSourceAssessmentId() == null || !request.getSourceAssessmentId().equals(source.getId())) {
            throw new BusinessConflictException("Bản đánh giá chính thức đã được cập nhật trong khi bạn đang thực hiện đánh giá. Vui lòng tải lại dữ liệu trước khi tiếp tục.");
        }

        OfficialCriterionScores sourceOfficial = getOfficialCriterionScores(source);

        int ownerComm = request.getOwnerCommercialScore() != null ? request.getOwnerCommercialScore() : sourceOfficial.commercial();
        int ownerCoop = request.getOwnerCooperationScore() != null ? request.getOwnerCooperationScore() : sourceOfficial.cooperation();
        int ownerStrat = request.getOwnerStrategicScore() != null ? request.getOwnerStrategicScore() : sourceOfficial.strategic();
        int ownerNet = request.getOwnerRelationshipNetworkScore() != null ? request.getOwnerRelationshipNetworkScore() : sourceOfficial.relationshipNetwork();
        int ownerEng = request.getOwnerEngagementScore() != null ? request.getOwnerEngagementScore() : sourceOfficial.engagement();
        int ownerQual = request.getOwnerQualitativeScore() != null ? request.getOwnerQualitativeScore() : sourceOfficial.qualitative();

        scoreCalculator.validateCompleteSubmissionV5(
                ownerComm,
                ownerCoop,
                ownerStrat,
                ownerNet,
                ownerEng,
                ownerQual
        );

        boolean commChanged = !Objects.equals(ownerComm, sourceOfficial.commercial());
        boolean coopChanged = !Objects.equals(ownerCoop, sourceOfficial.cooperation());
        boolean stratChanged = !Objects.equals(ownerStrat, sourceOfficial.strategic());
        boolean netChanged = !Objects.equals(ownerNet, sourceOfficial.relationshipNetwork());
        boolean engChanged = !Objects.equals(ownerEng, sourceOfficial.engagement());
        boolean qualChanged = !Objects.equals(ownerQual, sourceOfficial.qualitative());

        boolean criteriaAdjusted = commChanged || coopChanged || stratChanged || netChanged || engChanged || qualChanged;
        if (!criteriaAdjusted) {
            throw new BusinessValidationException("Cần có ít nhất một tiêu chí thay đổi so với bản đánh giá chính thức để hoàn tất điều chỉnh.");
        }

        CalculationResult ownerCalc = scoreCalculator.calculateV5(
                ownerComm,
                ownerCoop,
                ownerStrat,
                ownerNet,
                ownerEng,
                ownerQual
        );

        int currentMajor = (source.getMajorVersion() != null && source.getMajorVersion() > 0)
                ? source.getMajorVersion()
                : (source.getVersionNumber() != null && source.getVersionNumber() > 0 ? source.getVersionNumber() : 1);
        int maxMinor = assessmentRepository.findMaxFinalizedMinorRevision(ownerId, targetIds, currentMajor);
        int nextMinor = maxMinor + 1;
        int legacyVersionNumber = currentMajor * 100 + nextMinor;

        CompanyRelationshipAssessment adjustment = CompanyRelationshipAssessment.builder()
                .ownerCompanyProfileId(ownerId)
                .companyProfileId(source.getCompanyProfileId())
                .versionNumber(legacyVersionNumber)
                .majorVersion(currentMajor)
                .minorRevision(nextMinor)
                .status(RelationshipAssessmentStatus.FINALIZED)
                .assessmentType(RelationshipAssessmentType.OWNER_ADJUSTMENT)
                .sourceAssessmentId(source.getId())
                .scoringPolicyVersion(RelationshipCommercialScoringPolicy.POLICY_VERSION_V5)
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
                .commercialAwardedScore(sourceOfficial.commercial())
                .commercialAdjustmentReason(null)
                .commercialEvidenceNote(source.getCommercialEvidenceNote())
                .cooperationScore(sourceOfficial.cooperation())
                .cooperationEvidenceNote(source.getCooperationEvidenceNote())
                .strategicScore(sourceOfficial.strategic())
                .strategicEvidenceNote(source.getStrategicEvidenceNote())
                .relationshipNetworkScore(sourceOfficial.relationshipNetwork())
                .relationshipNetworkNote(sourceOfficial.relationshipNetworkNote())
                .engagementScore(sourceOfficial.engagement())
                .engagementEvidenceNote(source.getEngagementEvidenceNote())
                .qualitativeScore(sourceOfficial.qualitative())
                .qualitativeEvidenceNote(source.getQualitativeEvidenceNote())
                .managerNote(null)
                .managerRawScorableScore(sourceOfficial.rawScorableScore())
                .managerTotalScore(sourceOfficial.totalScore())
                .managerRank(sourceOfficial.rank())
                .managerAccountId(null)
                .ownerCommercialScore(ownerComm)
                .ownerCommercialNote(request != null ? request.getOwnerCommercialNote() : null)
                .ownerCooperationScore(ownerCoop)
                .ownerCooperationNote(request != null ? request.getOwnerCooperationNote() : null)
                .ownerStrategicScore(ownerStrat)
                .ownerStrategicNote(request != null ? request.getOwnerStrategicNote() : null)
                .ownerRelationshipNetworkScore(ownerNet)
                .ownerRelationshipNetworkNote(request != null && request.getOwnerRelationshipNetworkNote() != null
                        ? request.getOwnerRelationshipNetworkNote()
                        : sourceOfficial.relationshipNetworkNote())
                .ownerEngagementScore(ownerEng)
                .ownerEngagementNote(request != null ? request.getOwnerEngagementNote() : null)
                .ownerQualitativeScore(ownerQual)
                .ownerQualitativeNote(request != null ? request.getOwnerQualitativeNote() : null)
                .ownerNote(request != null ? request.getOwnerNote() : null)
                .ownerAdjustmentReason(request != null ? request.getOwnerAdjustmentReason() : null)
                .ownerRawScorableScore(ownerCalc.getRawScorableScore())
                .ownerFinalTotalScore(ownerCalc.getNormalizedTotalScore())
                .ownerFinalRank(ownerCalc.getRank().name())
                .ownerAccountId(currentUser.getId())
                .finalizedAt(LocalDateTime.now())
                .createdByAccountId(currentUser.getId())
                .build();

        try {
            adjustment = assessmentRepository.saveAndFlush(adjustment);
        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent adjustment version allocation for company {}: {}", source.getCompanyProfileId(), e.getMessage());
            throw new BusinessConflictException("Phiên bản đánh giá đã được tạo bởi một phiên làm việc khác. Vui lòng tải lại dữ liệu trước khi tiếp tục.");
        }

        // Atomically delete Owner's draft
        relationshipAssessmentDraftRepository.deleteByOwnerCompanyProfileIdAndCompanyProfileIdAndActorAccountIdAndDraftType(
                ownerId, source.getCompanyProfileId(), currentUser.getId(), RelationshipAssessmentType.OWNER_ADJUSTMENT);
        for (String tid : targetIds) {
            relationshipAssessmentDraftRepository.deleteByOwnerCompanyProfileIdAndCompanyProfileIdAndActorAccountIdAndDraftType(
                    ownerId, tid, currentUser.getId(), RelationshipAssessmentType.OWNER_ADJUSTMENT);
        }

        auditLogService.log(currentUser.getId(), AuditAction.RELATIONSHIP_ASSESSMENT_FINALIZED,
                "CompanyRelationshipAssessment", String.valueOf(adjustment.getId()),
                String.format("Finalized Owner Adjustment %s (Final Score: %d, Rank: %s) for company %s",
                        adjustment.getFormattedVersion(), ownerCalc.getNormalizedTotalScore(), ownerCalc.getRank(), adjustment.getCompanyProfileId()));

        Long sourceManagerId = resolveSourceManagerAccountId(source.getId());
        notificationService.notifyRelationshipAssessmentOwnerAdjusted(adjustment, source, sourceManagerId, currentUser != null ? currentUser.getId() : null);

        return toResponse(adjustment, currentUser, null);
    }

    // ----------------------------------------------------------------------------------
    // Legacy Owner / Version Endpoints (Disabled - Atomic Completion Required)
    // ----------------------------------------------------------------------------------

    @Transactional
    public RelationshipAssessmentResponse requestChanges(
            Long assessmentId,
            RequestChangesAssessmentRequest request,
            UserDetailsImpl currentUser) {
        throw new BusinessValidationException("Draft workflow is no longer supported for Relationship Closeness assessments. Assessments are completed and finalized atomically.");
    }

    @Transactional
    public RelationshipAssessmentResponse finalizeAssessment(
            Long assessmentId,
            FinalizeRelationshipAssessmentRequest request,
            UserDetailsImpl currentUser) {
        throw new BusinessValidationException("Draft workflow is no longer supported for Relationship Closeness assessments. Assessments are completed and finalized atomically.");
    }

    @Transactional
    public RelationshipAssessmentResponse createNewVersion(String targetCompanyProfileId, UserDetailsImpl currentUser) {
        throw new BusinessValidationException("Draft workflow is no longer supported for Relationship Closeness assessments. Assessments are completed and finalized atomically.");
    }

    @Transactional
    public RelationshipAssessmentResponse createOwnerAdjustment(Long sourceAssessmentId, UserDetailsImpl currentUser) {
        throw new BusinessValidationException("Draft workflow is no longer supported for Relationship Closeness assessments. Assessments are completed and finalized atomically.");
    }

    @Transactional
    public RelationshipAssessmentResponse updateOwnerAdjustment(
            Long assessmentId,
            OwnerAdjustmentUpdateRequest request,
            UserDetailsImpl currentUser) {
        throw new BusinessValidationException("Draft workflow is no longer supported for Relationship Closeness assessments. Assessments are completed and finalized atomically.");
    }

    @Transactional
    public RelationshipAssessmentResponse completeOwnerAdjustment(
            Long assessmentId,
            OwnerAdjustmentUpdateRequest request,
            UserDetailsImpl currentUser) {
        throw new BusinessValidationException("Draft workflow is no longer supported for Relationship Closeness assessments. Assessments are completed and finalized atomically.");
    }

    @Transactional
    public RelationshipAssessmentResponse cancelOwnerAdjustment(Long assessmentId, UserDetailsImpl currentUser) {
        throw new BusinessValidationException("Draft workflow is no longer supported for Relationship Closeness assessments. Assessments are completed and finalized atomically.");
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
                Optional<CompanyRelationshipAssessment> topFinalized = getLatestFinalizedAssessment(
                        entity.getOwnerCompanyProfileId(), entity.getCompanyProfileId(), null);
                isLatestOfficialFinalized = topFinalized.map(f -> f.getId().equals(entity.getId())).orElse(true);
            }
        }

        boolean canAdjust = isLatestOfficialFinalized && !activeExists && isOwner;

        Integer sourceVersionNumber = null;
        String sourceFormattedVersion = null;
        if (entity.getSourceAssessmentId() != null) {
            var sourceOpt = assessmentRepository.findById(entity.getSourceAssessmentId());
            sourceVersionNumber = sourceOpt.map(CompanyRelationshipAssessment::getVersionNumber).orElse(null);
            sourceFormattedVersion = sourceOpt.map(CompanyRelationshipAssessment::getFormattedVersion).orElse(null);
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
                .majorVersion(entity.getMajorVersion())
                .minorRevision(entity.getMinorRevision())
                .formattedVersion(entity.getFormattedVersion())
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
                .ownerCommercialNote(entity.getOwnerCommercialNote())
                .ownerCooperationScore(entity.getOwnerCooperationScore())
                .ownerCooperationNote(entity.getOwnerCooperationNote())
                .ownerStrategicScore(entity.getOwnerStrategicScore())
                .ownerStrategicNote(entity.getOwnerStrategicNote())
                .ownerRelationshipNetworkScore(entity.getOwnerRelationshipNetworkScore())
                .ownerRelationshipNetworkNote(entity.getOwnerRelationshipNetworkNote())
                .ownerEngagementScore(entity.getOwnerEngagementScore())
                .ownerEngagementNote(entity.getOwnerEngagementNote())
                .ownerQualitativeScore(entity.getOwnerQualitativeScore())
                .ownerQualitativeNote(entity.getOwnerQualitativeNote())
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
                .sourceFormattedVersion(sourceFormattedVersion)
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

    private int resolveNextOfficialVersionNumber(String ownerId, List<String> targetIds) {
        int maxFinalized = assessmentRepository.findMaxFinalizedVersionNumber(ownerId, targetIds);
        return maxFinalized + 1;
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
            return isResponsible;
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

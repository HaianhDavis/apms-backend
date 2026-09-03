package com.apms.domain.contract.service;

import com.apms.domain.contract.dto.ai.*;
import com.apms.domain.contract.enums.ContractFieldInputMethod;
import com.apms.domain.contract.enums.ContractFieldQualityStatus;
import com.apms.domain.contract.enums.ContractFieldVerificationStatus;
import com.apms.domain.contract.enums.ContractStatus;
import com.apms.domain.contract.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

@Component
@Slf4j
public class ContractExtractionNormalizer {

    @Value("${app.contract.extraction.review-confidence-threshold:0.70}")
    private double confidenceThreshold;

    public CommonContractData normalizeCommonData(AiCommonContractCandidate candidate, int totalPages, String docText) {
        if (candidate == null) {
            return CommonContractData.builder().build();
        }

        List<ContractParty> parties = new ArrayList<>();
        if (candidate.getParties() != null) {
            for (AiContractPartyCandidate p : candidate.getParties()) {
                if (p != null && StringUtils.hasText(p.getLegalName())) {
                    parties.add(ContractParty.builder()
                            .id(UUID.randomUUID().toString())
                            .legalName(p.getLegalName().trim())
                            .taxCode(cleanText(p.getTaxCode()))
                            .address(cleanText(p.getAddress()))
                            .representative(cleanText(p.getRepresentative()))
                            .role(cleanText(p.getRole()))
                            .sourcePage(validatePage(p.getSourcePage(), totalPages))
                            .evidence(cleanText(p.getEvidence()))
                            .confidence(p.getConfidence())
                            .qualityStatus(evaluateQuality(p.getConfidence(), p.getSourcePage(), totalPages, p.getEvidence(), docText))
                            .verificationStatus(ContractFieldVerificationStatus.UNVERIFIED)
                            .inputMethod(ContractFieldInputMethod.AI_EXTRACTED)
                            .build());
                }
            }
        }

        LocalDate signing = parseDate(candidate.getSigningDate());
        LocalDate effective = parseDate(candidate.getEffectiveDate());
        LocalDate expiry = parseDate(candidate.getExpiryDate());

        ContractFieldQualityStatus expiryQuality = evaluateQuality(
                candidate.getExpiryDate() != null ? candidate.getExpiryDate().getConfidence() : 0.0,
                candidate.getExpiryDate() != null ? candidate.getExpiryDate().getSourcePage() : null,
                totalPages,
                candidate.getExpiryDate() != null ? candidate.getExpiryDate().getEvidence() : null,
                docText
        );
        if (effective != null && expiry != null && expiry.isBefore(effective)) {
            expiryQuality = ContractFieldQualityStatus.NEEDS_REVIEW;
        }

        return CommonContractData.builder()
                .contractTitle(normalizeStringField(candidate.getContractTitle(), totalPages, docText))
                .contractNumber(normalizeStringField(candidate.getContractNumber(), totalPages, docText))
                .signingDate(buildDateField(signing, candidate.getSigningDate(), totalPages, docText))
                .effectiveDate(buildDateField(effective, candidate.getEffectiveDate(), totalPages, docText))
                .expiryDate(ExtractedContractField.<LocalDate>builder()
                        .value(expiry)
                        .sourcePage(candidate.getExpiryDate() != null ? validatePage(candidate.getExpiryDate().getSourcePage(), totalPages) : null)
                        .evidence(candidate.getExpiryDate() != null ? cleanText(candidate.getExpiryDate().getEvidence()) : null)
                        .confidence(candidate.getExpiryDate() != null ? candidate.getExpiryDate().getConfidence() : null)
                        .qualityStatus(expiryQuality)
                        .verificationStatus(ContractFieldVerificationStatus.UNVERIFIED)
                        .inputMethod(ContractFieldInputMethod.AI_EXTRACTED)
                        .build())
                .term(normalizeStringField(candidate.getTerm(), totalPages, docText))
                .parties(parties)
                .purpose(normalizeStringField(candidate.getPurpose(), totalPages, docText))
                .contractValue(normalizeContractValue(candidate.getContractValue(), totalPages, docText))
                .governingLaw(normalizeStringField(candidate.getGoverningLaw(), totalPages, docText))
                .build();
    }

    public CooperationAgreementData normalizeCooperationData(AiCooperationAgreementCandidate candidate, int totalPages, String docText) {
        if (candidate == null) return CooperationAgreementData.builder().build();

        List<ExtractedContractField<String>> activities = new ArrayList<>();
        if (candidate.getCooperationActivities() != null) {
            for (AiContractFieldCandidate f : candidate.getCooperationActivities()) {
                if (f != null && StringUtils.hasText(f.getValue())) {
                    activities.add(normalizeStringField(f, totalPages, docText));
                }
            }
        }

        List<PartyResponsibility> responsibilities = new ArrayList<>();
        if (candidate.getResponsibilities() != null) {
            for (AiPartyResponsibilityCandidate r : candidate.getResponsibilities()) {
                if (r != null && (StringUtils.hasText(r.getParty()) || StringUtils.hasText(r.getResponsibility()))) {
                    responsibilities.add(PartyResponsibility.builder()
                            .id(UUID.randomUUID().toString())
                            .party(cleanText(r.getParty()))
                            .responsibility(cleanText(r.getResponsibility()))
                            .sourcePage(validatePage(r.getSourcePage(), totalPages))
                            .evidence(cleanText(r.getEvidence()))
                            .confidence(r.getConfidence())
                            .qualityStatus(evaluateQuality(r.getConfidence(), r.getSourcePage(), totalPages, r.getEvidence(), docText))
                            .verificationStatus(ContractFieldVerificationStatus.UNVERIFIED)
                            .inputMethod(ContractFieldInputMethod.AI_EXTRACTED)
                            .build());
                }
            }
        }

        List<ResourceCommitment> commitments = new ArrayList<>();
        if (candidate.getResourceCommitments() != null) {
            for (AiResourceCommitmentCandidate rc : candidate.getResourceCommitments()) {
                if (rc != null && (StringUtils.hasText(rc.getParty()) || StringUtils.hasText(rc.getDescription()))) {
                    commitments.add(ResourceCommitment.builder()
                            .id(UUID.randomUUID().toString())
                            .party(cleanText(rc.getParty()))
                            .resourceType(cleanText(rc.getResourceType()))
                            .description(cleanText(rc.getDescription()))
                            .sourcePage(validatePage(rc.getSourcePage(), totalPages))
                            .evidence(cleanText(rc.getEvidence()))
                            .confidence(rc.getConfidence())
                            .qualityStatus(evaluateQuality(rc.getConfidence(), rc.getSourcePage(), totalPages, rc.getEvidence(), docText))
                            .verificationStatus(ContractFieldVerificationStatus.UNVERIFIED)
                            .inputMethod(ContractFieldInputMethod.AI_EXTRACTED)
                            .build());
                }
            }
        }

        List<ExtractedContractField<String>> termConds = new ArrayList<>();
        if (candidate.getTerminationConditions() != null) {
            for (AiContractFieldCandidate f : candidate.getTerminationConditions()) {
                if (f != null && StringUtils.hasText(f.getValue())) {
                    termConds.add(normalizeStringField(f, totalPages, docText));
                }
            }
        }

        return CooperationAgreementData.builder()
                .cooperationScope(normalizeStringField(candidate.getCooperationScope(), totalPages, docText))
                .cooperationActivities(activities)
                .responsibilities(responsibilities)
                .resourceCommitments(commitments)
                .informationSharing(normalizeStringField(candidate.getInformationSharing(), totalPages, docText))
                .coordinationMechanism(normalizeStringField(candidate.getCoordinationMechanism(), totalPages, docText))
                .terminationConditions(termConds)
                .build();
    }

    public PartnershipAgreementData normalizePartnershipData(AiPartnershipAgreementCandidate candidate, int totalPages, String docText) {
        if (candidate == null) return PartnershipAgreementData.builder().build();

        List<PartnerRole> roles = new ArrayList<>();
        if (candidate.getPartnerRoles() != null) {
            for (AiPartnerRoleCandidate r : candidate.getPartnerRoles()) {
                if (r != null && (StringUtils.hasText(r.getParty()) || StringUtils.hasText(r.getRole()))) {
                    roles.add(PartnerRole.builder()
                            .id(UUID.randomUUID().toString())
                            .party(cleanText(r.getParty()))
                            .role(cleanText(r.getRole()))
                            .sourcePage(validatePage(r.getSourcePage(), totalPages))
                            .evidence(cleanText(r.getEvidence()))
                            .confidence(r.getConfidence())
                            .qualityStatus(evaluateQuality(r.getConfidence(), r.getSourcePage(), totalPages, r.getEvidence(), docText))
                            .verificationStatus(ContractFieldVerificationStatus.UNVERIFIED)
                            .inputMethod(ContractFieldInputMethod.AI_EXTRACTED)
                            .build());
                }
            }
        }

        List<MutualCommitment> commitments = new ArrayList<>();
        if (candidate.getMutualCommitments() != null) {
            for (AiMutualCommitmentCandidate mc : candidate.getMutualCommitments()) {
                if (mc != null && (StringUtils.hasText(mc.getParty()) || StringUtils.hasText(mc.getCommitment()))) {
                    commitments.add(MutualCommitment.builder()
                            .id(UUID.randomUUID().toString())
                            .party(cleanText(mc.getParty()))
                            .commitment(cleanText(mc.getCommitment()))
                            .sourcePage(validatePage(mc.getSourcePage(), totalPages))
                            .evidence(cleanText(mc.getEvidence()))
                            .confidence(mc.getConfidence())
                            .qualityStatus(evaluateQuality(mc.getConfidence(), mc.getSourcePage(), totalPages, mc.getEvidence(), docText))
                            .verificationStatus(ContractFieldVerificationStatus.UNVERIFIED)
                            .inputMethod(ContractFieldInputMethod.AI_EXTRACTED)
                            .build());
                }
            }
        }

        List<PerformanceRequirement> perfs = new ArrayList<>();
        if (candidate.getPerformanceRequirements() != null) {
            for (AiPerformanceRequirementCandidate pr : candidate.getPerformanceRequirements()) {
                if (pr != null && (StringUtils.hasText(pr.getRequirement()) || StringUtils.hasText(pr.getTarget()))) {
                    perfs.add(PerformanceRequirement.builder()
                            .id(UUID.randomUUID().toString())
                            .requirement(cleanText(pr.getRequirement()))
                            .target(cleanText(pr.getTarget()))
                            .sourcePage(validatePage(pr.getSourcePage(), totalPages))
                            .evidence(cleanText(pr.getEvidence()))
                            .confidence(pr.getConfidence())
                            .qualityStatus(evaluateQuality(pr.getConfidence(), pr.getSourcePage(), totalPages, pr.getEvidence(), docText))
                            .verificationStatus(ContractFieldVerificationStatus.UNVERIFIED)
                            .inputMethod(ContractFieldInputMethod.AI_EXTRACTED)
                            .build());
                }
            }
        }

        ExtractedContractField<ExclusivityClause> exclusivity = null;
        if (candidate.getExclusivity() != null) {
            Boolean isExcl = candidate.getExclusivity().getIsExclusive();
            String scope = cleanText(candidate.getExclusivity().getScope());
            String evidence = cleanText(candidate.getExclusivity().getEvidence());
            if (isExcl != null || StringUtils.hasText(scope) || StringUtils.hasText(evidence)) {
                ExclusivityClause clause = ExclusivityClause.builder()
                        .isExclusive(isExcl)
                        .scope(scope)
                        .build();
                exclusivity = ExtractedContractField.<ExclusivityClause>builder()
                        .value(clause)
                        .sourcePage(validatePage(candidate.getExclusivity().getSourcePage(), totalPages))
                        .evidence(evidence)
                        .confidence(candidate.getExclusivity().getConfidence())
                        .qualityStatus(evaluateQuality(candidate.getExclusivity().getConfidence(), candidate.getExclusivity().getSourcePage(), totalPages, evidence, docText))
                        .verificationStatus(ContractFieldVerificationStatus.UNVERIFIED)
                        .inputMethod(ContractFieldInputMethod.AI_EXTRACTED)
                        .build();
            }
        }

        List<ExtractedContractField<String>> termConds = new ArrayList<>();
        if (candidate.getTerminationConditions() != null) {
            for (AiContractFieldCandidate f : candidate.getTerminationConditions()) {
                if (f != null && StringUtils.hasText(f.getValue())) {
                    termConds.add(normalizeStringField(f, totalPages, docText));
                }
            }
        }

        return PartnershipAgreementData.builder()
                .partnershipScope(normalizeStringField(candidate.getPartnershipScope(), totalPages, docText))
                .partnerRoles(roles)
                .mutualCommitments(commitments)
                .benefitSharing(normalizeStringField(candidate.getBenefitSharing(), totalPages, docText))
                .salesOrMarketRights(normalizeStringField(candidate.getSalesOrMarketRights(), totalPages, docText))
                .exclusivity(exclusivity)
                .performanceRequirements(perfs)
                .relationshipGovernance(normalizeStringField(candidate.getRelationshipGovernance(), totalPages, docText))
                .terminationConditions(termConds)
                .build();
    }

    public JointVentureAgreementData normalizeJointVentureData(AiJointVentureAgreementCandidate candidate, int totalPages, String docText) {
        if (candidate == null) return JointVentureAgreementData.builder().build();

        List<CapitalContribution> caps = new ArrayList<>();
        if (candidate.getCapitalContributions() != null) {
            for (AiCapitalContributionCandidate c : candidate.getCapitalContributions()) {
                if (c != null && (StringUtils.hasText(c.getParty()) || StringUtils.hasText(c.getAmount()))) {
                    BigDecimal amt = parseBigDecimal(c.getAmount());
                    caps.add(CapitalContribution.builder()
                            .id(UUID.randomUUID().toString())
                            .party(cleanText(c.getParty()))
                            .amount(amt)
                            .currency(StringUtils.hasText(c.getCurrency()) ? c.getCurrency().trim() : "VND")
                            .contributionType(cleanText(c.getContributionType()))
                            .sourcePage(validatePage(c.getSourcePage(), totalPages))
                            .evidence(cleanText(c.getEvidence()))
                            .confidence(c.getConfidence())
                            .qualityStatus(evaluateQuality(c.getConfidence(), c.getSourcePage(), totalPages, c.getEvidence(), docText))
                            .verificationStatus(ContractFieldVerificationStatus.UNVERIFIED)
                            .inputMethod(ContractFieldInputMethod.AI_EXTRACTED)
                            .build());
                }
            }
        }

        List<OwnershipPercentage> owns = new ArrayList<>();
        if (candidate.getOwnershipPercentages() != null) {
            for (AiOwnershipPercentageCandidate o : candidate.getOwnershipPercentages()) {
                if (o != null && (StringUtils.hasText(o.getParty()) || StringUtils.hasText(o.getPercentage()))) {
                    BigDecimal pct = parsePercentage(o.getPercentage());
                    owns.add(OwnershipPercentage.builder()
                            .id(UUID.randomUUID().toString())
                            .party(cleanText(o.getParty()))
                            .percentage(pct)
                            .sourcePage(validatePage(o.getSourcePage(), totalPages))
                            .evidence(cleanText(o.getEvidence()))
                            .confidence(o.getConfidence())
                            .qualityStatus(evaluateQuality(o.getConfidence(), o.getSourcePage(), totalPages, o.getEvidence(), docText))
                            .verificationStatus(ContractFieldVerificationStatus.UNVERIFIED)
                            .inputMethod(ContractFieldInputMethod.AI_EXTRACTED)
                            .build());
                }
            }
        }

        List<VotingRight> votes = new ArrayList<>();
        if (candidate.getVotingRights() != null) {
            for (AiVotingRightCandidate v : candidate.getVotingRights()) {
                if (v != null && (StringUtils.hasText(v.getParty()) || StringUtils.hasText(v.getVotingPercentage()) || StringUtils.hasText(v.getDescription()))) {
                    BigDecimal vPct = parsePercentage(v.getVotingPercentage());
                    votes.add(VotingRight.builder()
                            .id(UUID.randomUUID().toString())
                            .party(cleanText(v.getParty()))
                            .votingPercentage(vPct)
                            .description(cleanText(v.getDescription()))
                            .sourcePage(validatePage(v.getSourcePage(), totalPages))
                            .evidence(cleanText(v.getEvidence()))
                            .confidence(v.getConfidence())
                            .qualityStatus(evaluateQuality(v.getConfidence(), v.getSourcePage(), totalPages, v.getEvidence(), docText))
                            .verificationStatus(ContractFieldVerificationStatus.UNVERIFIED)
                            .inputMethod(ContractFieldInputMethod.AI_EXTRACTED)
                            .build());
                }
            }
        }

        List<DistributionShare> profits = new ArrayList<>();
        if (candidate.getProfitDistribution() != null) {
            for (AiDistributionShareCandidate p : candidate.getProfitDistribution()) {
                if (p != null && (StringUtils.hasText(p.getParty()) || StringUtils.hasText(p.getPercentage()))) {
                    profits.add(DistributionShare.builder()
                            .id(UUID.randomUUID().toString())
                            .party(cleanText(p.getParty()))
                            .percentage(parsePercentage(p.getPercentage()))
                            .description(cleanText(p.getDescription()))
                            .sourcePage(validatePage(p.getSourcePage(), totalPages))
                            .evidence(cleanText(p.getEvidence()))
                            .confidence(p.getConfidence())
                            .qualityStatus(evaluateQuality(p.getConfidence(), p.getSourcePage(), totalPages, p.getEvidence(), docText))
                            .verificationStatus(ContractFieldVerificationStatus.UNVERIFIED)
                            .inputMethod(ContractFieldInputMethod.AI_EXTRACTED)
                            .build());
                }
            }
        }

        List<DistributionShare> losses = new ArrayList<>();
        if (candidate.getLossSharing() != null) {
            for (AiDistributionShareCandidate l : candidate.getLossSharing()) {
                if (l != null && (StringUtils.hasText(l.getParty()) || StringUtils.hasText(l.getPercentage()))) {
                    losses.add(DistributionShare.builder()
                            .id(UUID.randomUUID().toString())
                            .party(cleanText(l.getParty()))
                            .percentage(parsePercentage(l.getPercentage()))
                            .description(cleanText(l.getDescription()))
                            .sourcePage(validatePage(l.getSourcePage(), totalPages))
                            .evidence(cleanText(l.getEvidence()))
                            .confidence(l.getConfidence())
                            .qualityStatus(evaluateQuality(l.getConfidence(), l.getSourcePage(), totalPages, l.getEvidence(), docText))
                            .verificationStatus(ContractFieldVerificationStatus.UNVERIFIED)
                            .inputMethod(ContractFieldInputMethod.AI_EXTRACTED)
                            .build());
                }
            }
        }

        List<ManagementAppointment> appts = new ArrayList<>();
        if (candidate.getManagementAppointments() != null) {
            for (AiManagementAppointmentCandidate ma : candidate.getManagementAppointments()) {
                if (ma != null && (StringUtils.hasText(ma.getPosition()) || StringUtils.hasText(ma.getAppointedBy()))) {
                    appts.add(ManagementAppointment.builder()
                            .id(UUID.randomUUID().toString())
                            .position(cleanText(ma.getPosition()))
                            .appointedBy(cleanText(ma.getAppointedBy()))
                            .sourcePage(validatePage(ma.getSourcePage(), totalPages))
                            .evidence(cleanText(ma.getEvidence()))
                            .confidence(ma.getConfidence())
                            .qualityStatus(evaluateQuality(ma.getConfidence(), ma.getSourcePage(), totalPages, ma.getEvidence(), docText))
                            .verificationStatus(ContractFieldVerificationStatus.UNVERIFIED)
                            .inputMethod(ContractFieldInputMethod.AI_EXTRACTED)
                            .build());
                }
            }
        }

        List<ExtractedContractField<String>> decisionRules = new ArrayList<>();
        if (candidate.getDecisionMakingRules() != null) {
            for (AiContractFieldCandidate f : candidate.getDecisionMakingRules()) {
                if (f != null && StringUtils.hasText(f.getValue())) {
                    decisionRules.add(normalizeStringField(f, totalPages, docText));
                }
            }
        }

        List<ExtractedContractField<String>> exits = new ArrayList<>();
        if (candidate.getExitConditions() != null) {
            for (AiContractFieldCandidate f : candidate.getExitConditions()) {
                if (f != null && StringUtils.hasText(f.getValue())) {
                    exits.add(normalizeStringField(f, totalPages, docText));
                }
            }
        }

        List<ExtractedContractField<String>> transfers = new ArrayList<>();
        if (candidate.getTransferRestrictions() != null) {
            for (AiContractFieldCandidate f : candidate.getTransferRestrictions()) {
                if (f != null && StringUtils.hasText(f.getValue())) {
                    transfers.add(normalizeStringField(f, totalPages, docText));
                }
            }
        }

        return JointVentureAgreementData.builder()
                .jointVentureName(normalizeStringField(candidate.getJointVentureName(), totalPages, docText))
                .jointVenturePurpose(normalizeStringField(candidate.getJointVenturePurpose(), totalPages, docText))
                .capitalContributions(caps)
                .ownershipPercentages(owns)
                .governanceStructure(normalizeStringField(candidate.getGovernanceStructure(), totalPages, docText))
                .votingRights(votes)
                .decisionMakingRules(decisionRules)
                .profitDistribution(profits)
                .lossSharing(losses)
                .managementAppointments(appts)
                .exitConditions(exits)
                .transferRestrictions(transfers)
                .build();
    }

    public BusinessCooperationContractData normalizeBccData(AiBusinessCooperationContractCandidate candidate, int totalPages, String docText) {
        if (candidate == null) return BusinessCooperationContractData.builder().build();

        List<BccContribution> contribs = new ArrayList<>();
        if (candidate.getContributions() != null) {
            for (AiBccContributionCandidate c : candidate.getContributions()) {
                if (c != null && (StringUtils.hasText(c.getParty()) || StringUtils.hasText(c.getAmount()))) {
                    contribs.add(BccContribution.builder()
                            .id(UUID.randomUUID().toString())
                            .party(cleanText(c.getParty()))
                            .amount(parseBigDecimal(c.getAmount()))
                            .currency(StringUtils.hasText(c.getCurrency()) ? c.getCurrency().trim() : "VND")
                            .contributionType(cleanText(c.getContributionType()))
                            .description(cleanText(c.getDescription()))
                            .sourcePage(validatePage(c.getSourcePage(), totalPages))
                            .evidence(cleanText(c.getEvidence()))
                            .confidence(c.getConfidence())
                            .qualityStatus(evaluateQuality(c.getConfidence(), c.getSourcePage(), totalPages, c.getEvidence(), docText))
                            .verificationStatus(ContractFieldVerificationStatus.UNVERIFIED)
                            .inputMethod(ContractFieldInputMethod.AI_EXTRACTED)
                            .build());
                }
            }
        }

        List<ContributionRatio> ratios = new ArrayList<>();
        if (candidate.getContributionRatios() != null) {
            for (AiContributionRatioCandidate r : candidate.getContributionRatios()) {
                if (r != null && (StringUtils.hasText(r.getParty()) || StringUtils.hasText(r.getRatioPercentage()))) {
                    ratios.add(ContributionRatio.builder()
                            .id(UUID.randomUUID().toString())
                            .party(cleanText(r.getParty()))
                            .ratioPercentage(parsePercentage(r.getRatioPercentage()))
                            .sourcePage(validatePage(r.getSourcePage(), totalPages))
                            .evidence(cleanText(r.getEvidence()))
                            .confidence(r.getConfidence())
                            .qualityStatus(evaluateQuality(r.getConfidence(), r.getSourcePage(), totalPages, r.getEvidence(), docText))
                            .verificationStatus(ContractFieldVerificationStatus.UNVERIFIED)
                            .inputMethod(ContractFieldInputMethod.AI_EXTRACTED)
                            .build());
                }
            }
        }

        List<SharingArrangement> revenues = normalizeSharingList(candidate.getRevenueSharing(), totalPages, docText);
        List<SharingArrangement> profits = normalizeSharingList(candidate.getProfitSharing(), totalPages, docText);
        List<SharingArrangement> costs = normalizeSharingList(candidate.getCostSharing(), totalPages, docText);
        List<SharingArrangement> losses = normalizeSharingList(candidate.getLossSharing(), totalPages, docText);

        List<PartyRightsAndObligations> roList = new ArrayList<>();
        if (candidate.getRightsAndObligations() != null) {
            for (AiPartyRightsAndObligationsCandidate ro : candidate.getRightsAndObligations()) {
                if (ro != null && StringUtils.hasText(ro.getParty())) {
                    roList.add(PartyRightsAndObligations.builder()
                            .id(UUID.randomUUID().toString())
                            .party(cleanText(ro.getParty()))
                            .rights(ro.getRights() != null ? ro.getRights() : new ArrayList<>())
                            .obligations(ro.getObligations() != null ? ro.getObligations() : new ArrayList<>())
                            .sourcePage(validatePage(ro.getSourcePage(), totalPages))
                            .evidence(cleanText(ro.getEvidence()))
                            .confidence(ro.getConfidence())
                            .qualityStatus(evaluateQuality(ro.getConfidence(), ro.getSourcePage(), totalPages, ro.getEvidence(), docText))
                            .verificationStatus(ContractFieldVerificationStatus.UNVERIFIED)
                            .inputMethod(ContractFieldInputMethod.AI_EXTRACTED)
                            .build());
                }
            }
        }

        return BusinessCooperationContractData.builder()
                .businessScope(normalizeStringField(candidate.getBusinessScope(), totalPages, docText))
                .contributions(contribs)
                .contributionRatios(ratios)
                .revenueSharing(revenues)
                .profitSharing(profits)
                .costSharing(costs)
                .lossSharing(losses)
                .rightsAndObligations(roList)
                .managementMechanism(normalizeStringField(candidate.getManagementMechanism(), totalPages, docText))
                .financialManagement(normalizeStringField(candidate.getFinancialManagement(), totalPages, docText))
                .assetOwnership(normalizeStringField(candidate.getAssetOwnership(), totalPages, docText))
                .terminationSettlement(normalizeStringField(candidate.getTerminationSettlement(), totalPages, docText))
                .build();
    }

    private List<SharingArrangement> normalizeSharingList(List<AiSharingArrangementCandidate> candidates, int totalPages, String docText) {
        List<SharingArrangement> list = new ArrayList<>();
        if (candidates != null) {
            for (AiSharingArrangementCandidate c : candidates) {
                if (c != null && (StringUtils.hasText(c.getParty()) || StringUtils.hasText(c.getPercentage()))) {
                    list.add(SharingArrangement.builder()
                            .id(UUID.randomUUID().toString())
                            .party(cleanText(c.getParty()))
                            .percentage(parsePercentage(c.getPercentage()))
                            .description(cleanText(c.getDescription()))
                            .sourcePage(validatePage(c.getSourcePage(), totalPages))
                            .evidence(cleanText(c.getEvidence()))
                            .confidence(c.getConfidence())
                            .qualityStatus(evaluateQuality(c.getConfidence(), c.getSourcePage(), totalPages, c.getEvidence(), docText))
                            .verificationStatus(ContractFieldVerificationStatus.UNVERIFIED)
                            .inputMethod(ContractFieldInputMethod.AI_EXTRACTED)
                            .build());
                }
            }
        }
        return list;
    }

    public ContractStatus deriveContractStatus(LocalDate effectiveDate, LocalDate expiryDate, boolean hasExplicitTerminationFact) {
        if (hasExplicitTerminationFact) {
            return ContractStatus.TERMINATED;
        }
        LocalDate now = LocalDate.now();
        if (expiryDate != null && expiryDate.isBefore(now)) {
            return ContractStatus.EXPIRED;
        }
        if (effectiveDate != null && effectiveDate.isAfter(now)) {
            return ContractStatus.NOT_EFFECTIVE;
        }
        if (effectiveDate != null && (expiryDate == null || !expiryDate.isBefore(now))) {
            return ContractStatus.ACTIVE;
        }
        return ContractStatus.UNKNOWN;
    }

    public ExtractedContractField<String> normalizeStringField(AiContractFieldCandidate candidate, int totalPages, String docText) {
        if (candidate == null || !StringUtils.hasText(candidate.getValue())) {
            return null;
        }
        return ExtractedContractField.<String>builder()
                .value(candidate.getValue().trim())
                .sourcePage(validatePage(candidate.getSourcePage(), totalPages))
                .evidence(cleanText(candidate.getEvidence()))
                .confidence(candidate.getConfidence())
                .qualityStatus(evaluateQuality(candidate.getConfidence(), candidate.getSourcePage(), totalPages, candidate.getEvidence(), docText))
                .verificationStatus(ContractFieldVerificationStatus.UNVERIFIED)
                .inputMethod(ContractFieldInputMethod.AI_EXTRACTED)
                .build();
    }

    public ExtractedContractField<ContractValue> normalizeContractValue(AiContractValueCandidate candidate, int totalPages, String docText) {
        if (candidate == null || (!StringUtils.hasText(candidate.getRawAmount()) && !StringUtils.hasText(candidate.getNormalizedAmount()))) {
            return null;
        }
        BigDecimal amount = parseBigDecimal(StringUtils.hasText(candidate.getNormalizedAmount()) ? candidate.getNormalizedAmount() : candidate.getRawAmount());
        String currency = StringUtils.hasText(candidate.getCurrency()) ? candidate.getCurrency().trim() : "VND";

        ContractValue cv = ContractValue.builder()
                .amount(amount)
                .currency(currency)
                .rawAmountText(cleanText(candidate.getRawAmount()))
                .build();

        return ExtractedContractField.<ContractValue>builder()
                .value(cv)
                .sourcePage(validatePage(candidate.getSourcePage(), totalPages))
                .evidence(cleanText(candidate.getEvidence()))
                .confidence(candidate.getConfidence())
                .qualityStatus(evaluateQuality(candidate.getConfidence(), candidate.getSourcePage(), totalPages, candidate.getEvidence(), docText))
                .verificationStatus(ContractFieldVerificationStatus.UNVERIFIED)
                .inputMethod(ContractFieldInputMethod.AI_EXTRACTED)
                .build();
    }

    private ExtractedContractField<LocalDate> buildDateField(LocalDate date, AiContractFieldCandidate candidate, int totalPages, String docText) {
        if (candidate == null && date == null) return null;
        return ExtractedContractField.<LocalDate>builder()
                .value(date)
                .sourcePage(candidate != null ? validatePage(candidate.getSourcePage(), totalPages) : null)
                .evidence(candidate != null ? cleanText(candidate.getEvidence()) : null)
                .confidence(candidate != null ? candidate.getConfidence() : null)
                .qualityStatus(evaluateQuality(candidate != null ? candidate.getConfidence() : null, candidate != null ? candidate.getSourcePage() : null, totalPages, candidate != null ? candidate.getEvidence() : null, docText))
                .verificationStatus(ContractFieldVerificationStatus.UNVERIFIED)
                .inputMethod(ContractFieldInputMethod.AI_EXTRACTED)
                .build();
    }

    public ContractFieldQualityStatus evaluateQuality(Double confidence, Integer page, int totalPages, String evidence, String docText) {
        if (page == null || page < 1 || page > totalPages) {
            return ContractFieldQualityStatus.NEEDS_REVIEW;
        }
        if (!StringUtils.hasText(evidence)) {
            return ContractFieldQualityStatus.NEEDS_REVIEW;
        }
        if (confidence != null && confidence < confidenceThreshold) {
            return ContractFieldQualityStatus.NEEDS_REVIEW;
        }
        if (StringUtils.hasText(docText)) {
            String normEvidence = evidence.replaceAll("\\s+", " ").trim().toLowerCase();
            String normDoc = docText.replaceAll("\\s+", " ").trim().toLowerCase();
            if (!normDoc.contains(normEvidence) && normEvidence.length() > 5) {
                // If evidence is not contained in document text
                return ContractFieldQualityStatus.NEEDS_REVIEW;
            }
        }
        return ContractFieldQualityStatus.VALID;
    }

    public Integer validatePage(Integer page, int totalPages) {
        if (page == null) return 1;
        if (page < 1) return 1;
        if (page > totalPages) return totalPages;
        return page;
    }

    public BigDecimal parseBigDecimal(String str) {
        if (!StringUtils.hasText(str)) return null;
        try {
            String s = str.trim();
            // Count dots and commas
            long dotCount = s.chars().filter(ch -> ch == '.').count();
            long commaCount = s.chars().filter(ch -> ch == ',').count();

            if (dotCount > 1 && commaCount == 0) {
                // e.g. 47.575.826.926.383 (Vietnamese/European integer)
                s = s.replace(".", "");
            } else if (commaCount > 1 && dotCount == 0) {
                // e.g. 47,575,826,926,383 (US integer)
                s = s.replace(",", "");
            } else if (dotCount >= 1 && commaCount == 1) {
                // e.g. 47.575.826,50
                s = s.replace(".", "").replace(",", ".");
            } else if (commaCount >= 1 && dotCount == 1) {
                // e.g. 47,575,826.50
                s = s.replace(",", "");
            } else if (commaCount == 1 && dotCount == 0) {
                // e.g. 65,5 -> 65.5
                s = s.replace(",", ".");
            }

            String cleaned = s.replaceAll("[^0-9.-]", "").trim();
            if (cleaned.isBlank()) return null;
            return new BigDecimal(cleaned);
        } catch (Exception e) {
            log.warn("Failed to parse BigDecimal from string: {}", str);
            return null;
        }
    }

    public BigDecimal parsePercentage(String str) {
        if (!StringUtils.hasText(str)) return null;
        try {
            BigDecimal bd = parseBigDecimal(str);
            if (bd == null) return null;
            if (bd.compareTo(BigDecimal.ZERO) < 0) return BigDecimal.ZERO;
            if (bd.compareTo(new BigDecimal("100")) > 0) return new BigDecimal("100");
            return bd;
        } catch (Exception e) {
            log.warn("Failed to parse percentage from string: {}", str);
            return null;
        }
    }

    public LocalDate parseDate(AiContractFieldCandidate candidate) {
        if (candidate == null || !StringUtils.hasText(candidate.getValue())) return null;
        String val = candidate.getValue().trim();
        try {
            return LocalDate.parse(val);
        } catch (DateTimeParseException e) {
            log.debug("Could not parse date '{}' as ISO date", val);
            return null;
        }
    }

    private String cleanText(String str) {
        if (!StringUtils.hasText(str)) return null;
        return str.trim();
    }
}

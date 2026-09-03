package com.apms.domain.contract.service;

import com.apms.domain.contract.dto.ai.*;
import com.apms.domain.contract.enums.ContractFieldQualityStatus;
import com.apms.domain.contract.enums.ContractStatus;
import com.apms.domain.contract.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContractExtractionNormalizerTest {

    private ContractExtractionNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new ContractExtractionNormalizer();
        ReflectionTestUtils.setField(normalizer, "confidenceThreshold", 0.70);
    }

    @Test
    @DisplayName("Lossless numeric parsing for large VND amounts")
    void parseBigDecimal_Lossless() {
        BigDecimal val = normalizer.parseBigDecimal("47.575.826.926.383");
        assertThat(val).isNotNull();
        assertThat(val.toPlainString()).isEqualTo("47575826926383");

        BigDecimal pct = normalizer.parsePercentage("65.50");
        assertThat(pct).isNotNull();
        assertThat(pct).isEqualByComparingTo("65.50");
    }

    @Test
    @DisplayName("Contract status derived deterministically")
    void deriveContractStatus_Deterministic() {
        LocalDate now = LocalDate.now();

        // 1. Explicit termination fact
        assertThat(normalizer.deriveContractStatus(now.minusMonths(6), now.plusMonths(6), true))
                .isEqualTo(ContractStatus.TERMINATED);

        // 2. Active contract (effective in past, expires in future)
        assertThat(normalizer.deriveContractStatus(now.minusMonths(6), now.plusMonths(6), false))
                .isEqualTo(ContractStatus.ACTIVE);

        // 3. Expired contract (expired in past)
        assertThat(normalizer.deriveContractStatus(now.minusYears(2), now.minusMonths(1), false))
                .isEqualTo(ContractStatus.EXPIRED);

        // 4. Not effective (effective in future)
        assertThat(normalizer.deriveContractStatus(now.plusMonths(2), now.plusYears(1), false))
                .isEqualTo(ContractStatus.NOT_EFFECTIVE);
    }

    @Test
    @DisplayName("Page validation and evidence verification")
    void evaluateQuality_ChecksPageBoundaryAndEvidence() {
        int totalPages = 10;
        String docText = "=== PAGE 1 ===\nThis Agreement is signed between Party A and Party B in Hanoi.";

        // Page out of bounds -> NEEDS_REVIEW
        assertThat(normalizer.evaluateQuality(0.95, 15, totalPages, "signed between Party A", docText))
                .isEqualTo(ContractFieldQualityStatus.NEEDS_REVIEW);

        // Page < 1 -> NEEDS_REVIEW
        assertThat(normalizer.evaluateQuality(0.95, 0, totalPages, "signed between Party A", docText))
                .isEqualTo(ContractFieldQualityStatus.NEEDS_REVIEW);

        // Confidence below threshold -> NEEDS_REVIEW
        assertThat(normalizer.evaluateQuality(0.50, 1, totalPages, "signed between Party A", docText))
                .isEqualTo(ContractFieldQualityStatus.NEEDS_REVIEW);

        // Valid page & evidence present -> VALID
        assertThat(normalizer.evaluateQuality(0.95, 1, totalPages, "signed between Party A", docText))
                .isEqualTo(ContractFieldQualityStatus.VALID);
    }

    @Test
    @DisplayName("BCC Structured Candidates mapping separates rights and obligations")
    void normalizeBccData_MapsRightsAndObligationsSeparately() {
        AiPartyRightsAndObligationsCandidate roCandidate = AiPartyRightsAndObligationsCandidate.builder()
                .party("Corporation X")
                .rights(List.of("Right to receive 30% gross revenue", "Right to audit accounts"))
                .obligations(List.of("Hand over clean site", "Obtain land use permits"))
                .sourcePage(2)
                .evidence("Corporation X shall have the right to receive...")
                .confidence(0.95)
                .build();

        AiBusinessCooperationContractCandidate bccCandidate = AiBusinessCooperationContractCandidate.builder()
                .businessScope(AiContractFieldCandidate.builder().value("Real Estate Development").sourcePage(1).evidence("Scope").confidence(0.95).build())
                .rightsAndObligations(List.of(roCandidate))
                .build();

        BusinessCooperationContractData data = normalizer.normalizeBccData(bccCandidate, 5, "=== PAGE 2 ===\nCorporation X shall have the right to receive...");

        assertThat(data).isNotNull();
        assertThat(data.getRightsAndObligations()).hasSize(1);
        PartyRightsAndObligations ro = data.getRightsAndObligations().get(0);
        assertThat(ro.getParty()).isEqualTo("Corporation X");
        assertThat(ro.getRights()).containsExactly("Right to receive 30% gross revenue", "Right to audit accounts");
        assertThat(ro.getObligations()).containsExactly("Hand over clean site", "Obtain land use permits");
    }

    @Test
    @DisplayName("JVA Structured Candidates mapping preserves voting rights and distribution shares")
    void normalizeJointVentureData_MapsVotingAndShares() {
        AiVotingRightCandidate vote = AiVotingRightCandidate.builder()
                .party("Investor A")
                .votingPercentage("51.0")
                .description("Majority voting power on Board of Directors")
                .sourcePage(3)
                .evidence("Investor A holds 51% voting rights")
                .confidence(0.95)
                .build();

        AiDistributionShareCandidate profit = AiDistributionShareCandidate.builder()
                .party("Investor A")
                .percentage("51.0")
                .description("51% of net profit after tax")
                .sourcePage(4)
                .evidence("51% of net profit")
                .confidence(0.95)
                .build();

        AiJointVentureAgreementCandidate jvaCandidate = AiJointVentureAgreementCandidate.builder()
                .jointVentureName(AiContractFieldCandidate.builder().value("ABC JV Co., Ltd").sourcePage(1).evidence("JV Name").confidence(0.95).build())
                .votingRights(List.of(vote))
                .profitDistribution(List.of(profit))
                .build();

        JointVentureAgreementData data = normalizer.normalizeJointVentureData(jvaCandidate, 5, "=== PAGE 3 ===\nInvestor A holds 51% voting rights");

        assertThat(data).isNotNull();
        assertThat(data.getVotingRights()).hasSize(1);
        assertThat(data.getVotingRights().get(0).getVotingPercentage()).isEqualByComparingTo("51.0");
        assertThat(data.getProfitDistribution()).hasSize(1);
        assertThat(data.getProfitDistribution().get(0).getPercentage()).isEqualByComparingTo("51.0");
    }
}

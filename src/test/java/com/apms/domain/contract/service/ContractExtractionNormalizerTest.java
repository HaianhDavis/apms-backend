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

    @Test
    @DisplayName("Governing Law: Preserves full composite components without data loss")
    void normalizeGoverningLawValue_PreservesCompositeComponents() {
        String input = "Pháp luật Việt Nam | Trọng tài tại Trung tâm Trọng tài Quốc tế Việt Nam (VIAC) | Ưu tiên thương lượng trong vòng 30 ngày";
        String normalized = normalizer.normalizeGoverningLawValue(input);

        assertThat(normalized).isEqualTo(input);
    }

    @Test
    @DisplayName("Governing Law: Trims whitespace around pipe separators")
    void normalizeGoverningLawValue_CleansWhitespace() {
        String input = "  Pháp luật Việt Nam   |   Trọng tài tại VIAC   |   Thương lượng 30 ngày  ";
        String normalized = normalizer.normalizeGoverningLawValue(input);

        assertThat(normalized).isEqualTo("Pháp luật Việt Nam | Trọng tài tại VIAC | Thương lượng 30 ngày");
    }

    @Test
    @DisplayName("Governing Law: Single values preserved without fabricating missing components")
    void normalizeGoverningLawValue_PreservesSingleValues() {
        // Law only
        assertThat(normalizer.normalizeGoverningLawValue("Pháp luật Việt Nam"))
                .isEqualTo("Pháp luật Việt Nam");

        // Court only (does not fabricate Vietnamese law)
        assertThat(normalizer.normalizeGoverningLawValue("Tòa án có thẩm quyền"))
                .isEqualTo("Tòa án có thẩm quyền");

        // Arbitration only (does not fabricate negotiation)
        assertThat(normalizer.normalizeGoverningLawValue("Trọng tài tại VIAC"))
                .isEqualTo("Trọng tài tại VIAC");
    }

    @Test
    @DisplayName("Governing Law: Full CommonData normalization retains composite value and evidence")
    void normalizeCommonData_RetainsGoverningLawComposite() {
        String compositeVal = "Pháp luật Việt Nam | Trọng tài tại Trung tâm Trọng tài Quốc tế Việt Nam (VIAC) | Ưu tiên thương lượng trong vòng 30 ngày";
        String evidence = "Hợp đồng được điều chỉnh theo pháp luật Việt Nam. Tranh chấp được giải quyết tại VIAC.";
        String docText = "=== PAGE 23 ===\n" + evidence;

        AiCommonContractCandidate candidate = AiCommonContractCandidate.builder()
                .contractTitle(AiContractFieldCandidate.builder().value("Hợp đồng nạo vét duy tu").sourcePage(1).evidence("Hợp đồng nạo vét").confidence(0.95).build())
                .governingLaw(AiContractFieldCandidate.builder()
                        .value(compositeVal)
                        .sourcePage(23)
                        .evidence(evidence)
                        .confidence(0.95)
                        .build())
                .build();

        CommonContractData common = normalizer.normalizeCommonData(candidate, 24, docText);

        assertThat(common).isNotNull();
        assertThat(common.getGoverningLaw()).isNotNull();
        assertThat(common.getGoverningLaw().getValue()).isEqualTo(compositeVal);
        assertThat(common.getGoverningLaw().getSourcePage()).isEqualTo(23);
        assertThat(common.getGoverningLaw().getQualityStatus()).isEqualTo(ContractFieldQualityStatus.VALID);
    }

    @Test
    @DisplayName("Quality Evaluation: Supports multi-segment evidence joined by pipe or ellipsis")
    void evaluateQuality_SupportsMultiSegmentEvidence() {
        String docText = "=== PAGE 1 ===\nĐiều 18: Hợp đồng được điều chỉnh bởi pháp luật Việt Nam.\nMột số điều khoản khác...\n=== PAGE 2 ===\nĐiều 20: Tranh chấp được giải quyết tại VIAC.";
        String multiEvidence = "Điều 18: Hợp đồng được điều chỉnh bởi pháp luật Việt Nam. | Điều 20: Tranh chấp được giải quyết tại VIAC.";

        ContractFieldQualityStatus status = normalizer.evaluateQuality(0.95, 1, 2, multiEvidence, docText);
        assertThat(status).isEqualTo(ContractFieldQualityStatus.VALID);
    }

    @Test
    @DisplayName("Date Parsing: Supports various Vietnamese and international date formats")
    void parseDateString_SupportsMultipleFormats() {
        // Standard ISO
        assertThat(normalizer.parseDateString("2019-09-09")).isEqualTo(LocalDate.of(2019, 9, 9));
        // ISO Timestamp
        assertThat(normalizer.parseDateString("2019-09-09T14:30:00")).isEqualTo(LocalDate.of(2019, 9, 9));
        // Vietnamese DD/MM/YYYY
        assertThat(normalizer.parseDateString("09/09/2019")).isEqualTo(LocalDate.of(2019, 9, 9));
        assertThat(normalizer.parseDateString("5/6/2023")).isEqualTo(LocalDate.of(2023, 6, 5));
        // DD-MM-YYYY & DD.MM.YYYY
        assertThat(normalizer.parseDateString("09-09-2019")).isEqualTo(LocalDate.of(2019, 9, 9));
        assertThat(normalizer.parseDateString("09.09.2019")).isEqualTo(LocalDate.of(2019, 9, 9));
        // Vietnamese natural words: ngày DD tháng MM năm YYYY
        assertThat(normalizer.parseDateString("ngày 09 tháng 09 năm 2019")).isEqualTo(LocalDate.of(2019, 9, 9));
        assertThat(normalizer.parseDateString("Ngày 5 tháng 12 năm 2024")).isEqualTo(LocalDate.of(2024, 12, 5));
        // Prefixed with 'ngày'
        assertThat(normalizer.parseDateString("ngày 15/10/2023")).isEqualTo(LocalDate.of(2023, 10, 15));
        // Null / empty / invalid
        assertThat(normalizer.parseDateString(null)).isNull();
        assertThat(normalizer.parseDateString("")).isNull();
        assertThat(normalizer.parseDateString("không xác định")).isNull();
    }

    @Test
    @DisplayName("Date Parsing: Candidate wrapping delegates correctly to parseDateString")
    void parseDate_CandidateWrapping() {
        AiContractFieldCandidate candidate = AiContractFieldCandidate.builder()
                .value("ngày 09 tháng 09 năm 2019")
                .sourcePage(1)
                .build();
        assertThat(normalizer.parseDate(candidate)).isEqualTo(LocalDate.of(2019, 9, 9));
    }

    @Test
    @DisplayName("Date Normalization: Null or blank expiryDate returns null without generating phantom NEEDS_REVIEW")
    void normalizeCommonData_NullOrBlankExpiryDate_ReturnsNull() {
        // Case 1: Candidate has no expiryDate at all
        AiCommonContractCandidate candidateWithoutExpiry = AiCommonContractCandidate.builder()
                .contractTitle(AiContractFieldCandidate.builder().value("Hợp đồng hợp tác").sourcePage(1).evidence("Hợp đồng").confidence(0.95).build())
                .signingDate(AiContractFieldCandidate.builder().value("2023-01-01").sourcePage(1).evidence("2023-01-01").confidence(0.95).build())
                .build();

        CommonContractData common1 = normalizer.normalizeCommonData(candidateWithoutExpiry, 5, "=== PAGE 1 ===\nHợp đồng 2023-01-01");
        assertThat(common1.getExpiryDate()).isNull();
        assertThat(common1.getSigningDate()).isNotNull();
        assertThat(common1.getEffectiveDate()).isNull();

        // Case 2: Candidate has expiryDate with empty/unparseable string
        AiCommonContractCandidate candidateWithBlankExpiry = AiCommonContractCandidate.builder()
                .contractTitle(AiContractFieldCandidate.builder().value("Hợp đồng hợp tác").sourcePage(1).evidence("Hợp đồng").confidence(0.95).build())
                .expiryDate(AiContractFieldCandidate.builder().value("").sourcePage(1).evidence("").confidence(0.0).build())
                .build();

        CommonContractData common2 = normalizer.normalizeCommonData(candidateWithBlankExpiry, 5, "=== PAGE 1 ===\nHợp đồng");
        assertThat(common2.getExpiryDate()).isNull();
    }

    @Test
    @DisplayName("Date Normalization: Expiry before effective date flags NEEDS_REVIEW")
    void normalizeCommonData_ExpiryBeforeEffective_FlagsNeedsReview() {
        AiCommonContractCandidate candidate = AiCommonContractCandidate.builder()
                .contractTitle(AiContractFieldCandidate.builder().value("Hợp đồng").sourcePage(1).evidence("Hợp đồng").confidence(0.95).build())
                .effectiveDate(AiContractFieldCandidate.builder().value("2024-01-01").sourcePage(1).evidence("2024-01-01").confidence(0.95).build())
                .expiryDate(AiContractFieldCandidate.builder().value("2023-01-01").sourcePage(1).evidence("2023-01-01").confidence(0.95).build())
                .build();

        CommonContractData common = normalizer.normalizeCommonData(candidate, 5, "=== PAGE 1 ===\nHợp đồng 2024-01-01 2023-01-01");
        assertThat(common.getExpiryDate()).isNotNull();
        assertThat(common.getExpiryDate().getQualityStatus()).isEqualTo(ContractFieldQualityStatus.NEEDS_REVIEW);
    }
}

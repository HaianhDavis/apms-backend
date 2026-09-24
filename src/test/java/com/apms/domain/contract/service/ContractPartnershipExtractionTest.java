package com.apms.domain.contract.service;

import com.apms.domain.contract.dto.ai.*;
import com.apms.domain.contract.enums.ContractFieldQualityStatus;
import com.apms.domain.contract.enums.ContractFieldVerificationStatus;
import com.apms.domain.contract.model.PartnershipAgreementData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContractPartnershipExtractionTest {

    private ContractExtractionNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new ContractExtractionNormalizer();
        ReflectionTestUtils.setField(normalizer, "confidenceThreshold", 0.70);
    }

    @Test
    @DisplayName("A. Complete 9-field Partnership Agreement candidates are mapped without loss")
    void normalizePartnershipData_Full9Fields_MapsAllSuccessfully() {
        int totalPages = 5;
        String docText = "=== PAGE 1 ===\nMOU FIP VUNG TAU 2025 2035\n=== PAGE 2 ===\nPham vi hop tac chien luoc phat trien du an nang luong gio.\n=== PAGE 3 ===\nFIP Partners cam ket cung cap tai chinh va bao lanh ky thuat.\n=== PAGE 4 ===\nDoc quyen phan phoi tai thi truong Dong Nam Bo.\nChi tieu toi thieu 500 MW.";

        AiPartnershipAgreementCandidate candidate = AiPartnershipAgreementCandidate.builder()
                .partnershipScope(AiContractFieldCandidate.builder()
                        .value("Phat trien du an nang luong tai Vung Tau")
                        .sourcePage(2)
                        .evidence("Pham vi hop tac chien luoc")
                        .confidence(0.95)
                        .build())
                .partnerRoles(List.of(
                        AiPartnerRoleCandidate.builder()
                                .party("Tong Cong Ty Nang Luong A")
                                .role("Van hanh va dieu phoi toan bo du an")
                                .sourcePage(2)
                                .evidence("Van hanh va dieu phoi")
                                .confidence(0.92)
                                .build()
                ))
                .mutualCommitments(List.of(
                        AiMutualCommitmentCandidate.builder()
                                .party("FIP Partners")
                                .commitment("Cung cap tai chinh va bao lanh ky thuat")
                                .sourcePage(3)
                                .evidence("FIP Partners cam ket")
                                .confidence(0.90)
                                .build()
                ))
                .benefitSharing(AiContractFieldCandidate.builder()
                        .value("Chia se loi nhuan theo ty le 60:40 sau thue")
                        .sourcePage(3)
                        .evidence("Chia se loi nhuan")
                        .confidence(0.88)
                        .build())
                .salesOrMarketRights(AiContractFieldCandidate.builder()
                        .value("Quyen phan phoi doc quyen tai khu vuc Dong Nam Bo")
                        .sourcePage(4)
                        .evidence("Doc quyen phan phoi")
                        .confidence(0.91)
                        .build())
                .exclusivity(AiExclusivityCandidate.builder()
                        .isExclusive(true)
                        .scope("Toan bo thi truong Dong Nam Bo")
                        .sourcePage(4)
                        .evidence("Doc quyen phan phoi tai thi truong")
                        .confidence(0.95)
                        .build())
                .performanceRequirements(List.of(
                        AiPerformanceRequirementCandidate.builder()
                                .requirement("Cong suat phat dien toi thieu")
                                .target("500 MW")
                                .sourcePage(4)
                                .evidence("Chi tieu toi thieu 500 MW")
                                .confidence(0.93)
                                .build()
                ))
                .relationshipGovernance(AiContractFieldCandidate.builder()
                        .value("Uy ban dieu hanh chung hop dinh ky hang quy")
                        .sourcePage(3)
                        .evidence("hop dinh ky hang quy")
                        .confidence(0.89)
                        .build())
                .terminationConditions(List.of(
                        AiContractFieldCandidate.builder()
                                .value("Cham dut neu mot ben vi pham nghiem trong sau 30 ngay thong bao")
                                .sourcePage(5)
                                .evidence("Cham dut neu mot ben vi pham")
                                .confidence(0.90)
                                .build()
                ))
                .build();

        PartnershipAgreementData data = normalizer.normalizePartnershipData(candidate, totalPages, docText);

        assertThat(data).isNotNull();
        // 1. Scope
        assertThat(data.getPartnershipScope()).isNotNull();
        assertThat(data.getPartnershipScope().getValue()).isEqualTo("Phat trien du an nang luong tai Vung Tau");
        assertThat(data.getPartnershipScope().getSourcePage()).isEqualTo(2);
        assertThat(data.getPartnershipScope().getQualityStatus()).isEqualTo(ContractFieldQualityStatus.VALID);

        // 2. Partner Roles
        assertThat(data.getPartnerRoles()).hasSize(1);
        assertThat(data.getPartnerRoles().get(0).getParty()).isEqualTo("Tong Cong Ty Nang Luong A");
        assertThat(data.getPartnerRoles().get(0).getRole()).isEqualTo("Van hanh va dieu phoi toan bo du an");

        // 3. Mutual Commitments
        assertThat(data.getMutualCommitments()).hasSize(1);
        assertThat(data.getMutualCommitments().get(0).getParty()).isEqualTo("FIP Partners");
        assertThat(data.getMutualCommitments().get(0).getCommitment()).isEqualTo("Cung cap tai chinh va bao lanh ky thuat");

        // 4. Benefit Sharing
        assertThat(data.getBenefitSharing()).isNotNull();
        assertThat(data.getBenefitSharing().getValue()).isEqualTo("Chia se loi nhuan theo ty le 60:40 sau thue");

        // 5. Sales / Market Rights
        assertThat(data.getSalesOrMarketRights()).isNotNull();
        assertThat(data.getSalesOrMarketRights().getValue()).isEqualTo("Quyen phan phoi doc quyen tai khu vuc Dong Nam Bo");

        // 6. Exclusivity
        assertThat(data.getExclusivity()).isNotNull();
        assertThat(data.getExclusivity().getValue()).isNotNull();
        assertThat(data.getExclusivity().getValue().getIsExclusive()).isTrue();
        assertThat(data.getExclusivity().getValue().getScope()).isEqualTo("Toan bo thi truong Dong Nam Bo");

        // 7. Performance Requirements
        assertThat(data.getPerformanceRequirements()).hasSize(1);
        assertThat(data.getPerformanceRequirements().get(0).getRequirement()).isEqualTo("Cong suat phat dien toi thieu");
        assertThat(data.getPerformanceRequirements().get(0).getTarget()).isEqualTo("500 MW");

        // 8. Relationship Governance
        assertThat(data.getRelationshipGovernance()).isNotNull();
        assertThat(data.getRelationshipGovernance().getValue()).isEqualTo("Uy ban dieu hanh chung hop dinh ky hang quy");

        // 9. Termination Conditions
        assertThat(data.getTerminationConditions()).hasSize(1);
        assertThat(data.getTerminationConditions().get(0).getValue()).contains("Cham dut neu mot ben vi pham");
    }

    @Test
    @DisplayName("B. Missing scalar fields normalize to null without error")
    void normalizePartnershipData_MissingScalars_ReturnsNullSafely() {
        AiPartnershipAgreementCandidate candidate = AiPartnershipAgreementCandidate.builder()
                .partnershipScope(null)
                .benefitSharing(null)
                .salesOrMarketRights(null)
                .relationshipGovernance(null)
                .partnerRoles(Collections.emptyList())
                .mutualCommitments(Collections.emptyList())
                .performanceRequirements(Collections.emptyList())
                .terminationConditions(Collections.emptyList())
                .build();

        PartnershipAgreementData data = normalizer.normalizePartnershipData(candidate, 5, "");

        assertThat(data).isNotNull();
        assertThat(data.getPartnershipScope()).isNull();
        assertThat(data.getBenefitSharing()).isNull();
        assertThat(data.getSalesOrMarketRights()).isNull();
        assertThat(data.getRelationshipGovernance()).isNull();
        assertThat(data.getExclusivity()).isNull();
    }

    @Test
    @DisplayName("C. Empty array fields normalize to empty lists")
    void normalizePartnershipData_EmptyArrays_ReturnsEmptyLists() {
        AiPartnershipAgreementCandidate candidate = AiPartnershipAgreementCandidate.builder()
                .partnerRoles(null)
                .mutualCommitments(Collections.emptyList())
                .performanceRequirements(null)
                .terminationConditions(Collections.emptyList())
                .build();

        PartnershipAgreementData data = normalizer.normalizePartnershipData(candidate, 5, "");

        assertThat(data).isNotNull();
        assertThat(data.getPartnerRoles()).isEmpty();
        assertThat(data.getMutualCommitments()).isEmpty();
        assertThat(data.getPerformanceRequirements()).isEmpty();
        assertThat(data.getTerminationConditions()).isEmpty();
    }

    @Test
    @DisplayName("D. Unspecified exclusivity ({ isExclusive: null, scope: null }) normalizes to null")
    void normalizePartnershipData_ExclusivityUnspecified_ReturnsNullField() {
        AiPartnershipAgreementCandidate candidate = AiPartnershipAgreementCandidate.builder()
                .exclusivity(AiExclusivityCandidate.builder()
                        .isExclusive(null)
                        .scope(null)
                        .evidence(null)
                        .build())
                .build();

        PartnershipAgreementData data = normalizer.normalizePartnershipData(candidate, 5, "");

        assertThat(data).isNotNull();
        assertThat(data.getExclusivity()).isNull();
    }

    @Test
    @DisplayName("E. Explicit non-exclusivity ({ isExclusive: false }) is preserved as false")
    void normalizePartnershipData_ExclusivityExplicitFalse_Preserved() {
        String docText = "=== PAGE 1 ===\nHop tac nay la khong doc quyen giua hai ben.";
        AiPartnershipAgreementCandidate candidate = AiPartnershipAgreementCandidate.builder()
                .exclusivity(AiExclusivityCandidate.builder()
                        .isExclusive(false)
                        .scope("Khong doc quyen")
                        .sourcePage(1)
                        .evidence("Hop tac nay la khong doc quyen")
                        .confidence(0.95)
                        .build())
                .build();

        PartnershipAgreementData data = normalizer.normalizePartnershipData(candidate, 5, docText);

        assertThat(data).isNotNull();
        assertThat(data.getExclusivity()).isNotNull();
        assertThat(data.getExclusivity().getValue()).isNotNull();
        assertThat(data.getExclusivity().getValue().getIsExclusive()).isFalse();
        assertThat(data.getExclusivity().getValue().getScope()).isEqualTo("Khong doc quyen");
    }

    @Test
    @DisplayName("F. Explicit exclusivity ({ isExclusive: true }) is preserved as true")
    void normalizePartnershipData_ExclusivityExplicitTrue_Preserved() {
        String docText = "=== PAGE 1 ===\nBen B duoc trao quyen doc quyen toan dien.";
        AiPartnershipAgreementCandidate candidate = AiPartnershipAgreementCandidate.builder()
                .exclusivity(AiExclusivityCandidate.builder()
                        .isExclusive(true)
                        .scope("Doc quyen toan dien")
                        .sourcePage(1)
                        .evidence("quyen doc quyen toan dien")
                        .confidence(0.95)
                        .build())
                .build();

        PartnershipAgreementData data = normalizer.normalizePartnershipData(candidate, 5, docText);

        assertThat(data).isNotNull();
        assertThat(data.getExclusivity()).isNotNull();
        assertThat(data.getExclusivity().getValue()).isNotNull();
        assertThat(data.getExclusivity().getValue().getIsExclusive()).isTrue();
    }

    @Test
    @DisplayName("G. Substantive partner roles are captured distinctly from generic party role")
    void normalizePartnershipData_PartnerRoles_PreservesSubstantiveRole() {
        AiPartnershipAgreementCandidate candidate = AiPartnershipAgreementCandidate.builder()
                .partnerRoles(List.of(
                        AiPartnerRoleCandidate.builder()
                                .party("Cong Ty ABC")
                                .role("Chiu trach nhiem thiet ke ky thuat va giam sat cong trinh")
                                .sourcePage(1)
                                .evidence("ABC chiu trach nhiem")
                                .confidence(0.95)
                                .build()
                ))
                .build();

        PartnershipAgreementData data = normalizer.normalizePartnershipData(candidate, 5, "=== PAGE 1 ===\nABC chiu trach nhiem");

        assertThat(data).isNotNull();
        assertThat(data.getPartnerRoles()).hasSize(1);
        assertThat(data.getPartnerRoles().get(0).getRole()).isEqualTo("Chiu trach nhiem thiet ke ky thuat va giam sat cong trinh");
    }

    @Test
    @DisplayName("H. Collective parties like 'FIP Partners' are preserved faithfully")
    void normalizePartnershipData_CollectiveParties_Preserved() {
        AiPartnershipAgreementCandidate candidate = AiPartnershipAgreementCandidate.builder()
                .mutualCommitments(List.of(
                        AiMutualCommitmentCandidate.builder()
                                .party("FIP Partners")
                                .commitment("Dong thuan thuc hien cac quy chuan moi truong")
                                .sourcePage(2)
                                .evidence("FIP Partners dong thuan")
                                .confidence(0.95)
                                .build()
                ))
                .build();

        PartnershipAgreementData data = normalizer.normalizePartnershipData(candidate, 5, "=== PAGE 2 ===\nFIP Partners dong thuan");

        assertThat(data).isNotNull();
        assertThat(data.getMutualCommitments()).hasSize(1);
        assertThat(data.getMutualCommitments().get(0).getParty()).isEqualTo("FIP Partners");
    }

    @Test
    @DisplayName("I. Signing date is preserved and never falls back to document creation date")
    void normalizeCommonData_SigningDate_Preserved() {
        AiCommonContractCandidate candidateWithDate = AiCommonContractCandidate.builder()
                .signingDate(AiContractFieldCandidate.builder().value("2025-06-15").sourcePage(1).evidence("Ngay 15/06/2025").confidence(0.95).build())
                .build();

        var commonData = normalizer.normalizeCommonData(candidateWithDate, 5, "=== PAGE 1 ===\nNgay 15/06/2025");
        assertThat(commonData.getSigningDate()).isNotNull();
        assertThat(commonData.getSigningDate().getValue()).isEqualTo(LocalDate.of(2025, 6, 15));

        AiCommonContractCandidate candidateWithoutDate = AiCommonContractCandidate.builder()
                .signingDate(null)
                .build();

        var commonDataWithoutDate = normalizer.normalizeCommonData(candidateWithoutDate, 5, "");
        assertThat(commonDataWithoutDate.getSigningDate()).isNull();
    }
}

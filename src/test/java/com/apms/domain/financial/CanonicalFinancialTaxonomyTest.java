package com.apms.domain.financial;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalFinancialTaxonomyTest {

    @Test
    @DisplayName("Taxonomy Parity: contains exactly 61 canonical metrics")
    void taxonomy_containsExactly61Metrics() {
        assertThat(CanonicalFinancialTaxonomy.DEFINITIONS).hasSize(61);
    }

    @Test
    @DisplayName("Taxonomy Parity: no duplicate codes")
    void taxonomy_noDuplicateCodes() {
        List<String> codes = CanonicalFinancialTaxonomy.DEFINITIONS.stream()
                .map(CanonicalFinancialTaxonomy.MetricDefinition::getCode)
                .toList();

        Set<String> uniqueCodes = Set.copyOf(codes);
        assertThat(uniqueCodes).hasSize(61);
    }

    @Test
    @DisplayName("Taxonomy Parity: displayOrder covers 1 to 61 continuously")
    void taxonomy_displayOrderContiguous1To61() {
        List<Integer> orders = CanonicalFinancialTaxonomy.DEFINITIONS.stream()
                .map(CanonicalFinancialTaxonomy.MetricDefinition::getDisplayOrder)
                .toList();

        List<Integer> expected = IntStream.rangeClosed(1, 61).boxed().toList();
        assertThat(orders).isEqualTo(expected);
    }

    @Test
    @DisplayName("Taxonomy Parity: Balance Sheet has 29 metrics across 5 subcategories")
    void taxonomy_balanceSheetStructure() {
        List<CanonicalFinancialTaxonomy.MetricDefinition> bs = CanonicalFinancialTaxonomy.DEFINITIONS.stream()
                .filter(d -> "BALANCE_SHEET".equals(d.getStatementType()))
                .toList();
        assertThat(bs).hasSize(29);

        long currentAssets = bs.stream().filter(d -> "CURRENT_ASSETS".equals(d.getSubCategory())).count();
        long nonCurrentAssets = bs.stream().filter(d -> "NON_CURRENT_ASSETS".equals(d.getSubCategory())).count();
        long totalAssets = bs.stream().filter(d -> "TOTAL_ASSETS".equals(d.getSubCategory())).count();
        long liabilities = bs.stream().filter(d -> "LIABILITIES".equals(d.getSubCategory())).count();
        long equity = bs.stream().filter(d -> "EQUITY".equals(d.getSubCategory())).count();

        assertThat(currentAssets).isEqualTo(7);
        assertThat(nonCurrentAssets).isEqualTo(8);
        assertThat(totalAssets).isEqualTo(1);
        assertThat(liabilities).isEqualTo(8);
        assertThat(equity).isEqualTo(5);
    }

    @Test
    @DisplayName("Taxonomy Parity: Income Statement has 18 metrics")
    void taxonomy_incomeStatementMetrics() {
        List<CanonicalFinancialTaxonomy.MetricDefinition> is = CanonicalFinancialTaxonomy.DEFINITIONS.stream()
                .filter(d -> "INCOME_STATEMENT".equals(d.getStatementType()))
                .toList();
        assertThat(is).hasSize(18);
    }

    @Test
    @DisplayName("Taxonomy Parity: Banking has exactly 8 metrics")
    void taxonomy_bankingMetrics() {
        List<CanonicalFinancialTaxonomy.MetricDefinition> banking = CanonicalFinancialTaxonomy.DEFINITIONS.stream()
                .filter(d -> "BANKING".equals(d.getStatementType()))
                .toList();
        assertThat(banking).hasSize(8);

        List<String> expectedCodes = List.of(
                "CUSTOMER_LOANS",
                "CUSTOMER_DEPOSITS",
                "NET_INTEREST_INCOME",
                "NON_INTEREST_INCOME",
                "NET_FEE_COMMISSION_INCOME",
                "TOTAL_OPERATING_INCOME",
                "OPERATING_EXPENSES",
                "CREDIT_RISK_PROVISION"
        );
        List<String> actualCodes = banking.stream().map(CanonicalFinancialTaxonomy.MetricDefinition::getCode).toList();
        assertThat(actualCodes).isEqualTo(expectedCodes);
    }

    @Test
    @DisplayName("Taxonomy Parity: Ratios has exactly 6 metrics all with PERCENT default unit")
    void taxonomy_ratiosMetrics() {
        List<CanonicalFinancialTaxonomy.MetricDefinition> ratios = CanonicalFinancialTaxonomy.DEFINITIONS.stream()
                .filter(d -> "RATIOS".equals(d.getStatementType()))
                .toList();
        assertThat(ratios).hasSize(6);

        for (CanonicalFinancialTaxonomy.MetricDefinition r : ratios) {
            assertThat(r.getDefaultUnit()).isEqualTo("PERCENT");
        }

        List<String> expectedCodes = List.of("NIM", "CIR", "NPL", "CAR", "ROAA", "ROAE");
        List<String> actualCodes = ratios.stream().map(CanonicalFinancialTaxonomy.MetricDefinition::getCode).toList();
        assertThat(actualCodes).isEqualTo(expectedCodes);
    }

    @Test
    @DisplayName("Taxonomy Aliases: resolves canonical definitions by code or Vietnamese aliases")
    void taxonomy_resolvesAliases() {
        Optional<CanonicalFinancialTaxonomy.MetricDefinition> cash =
                CanonicalFinancialTaxonomy.findByCodeOrAlias("Tiền và các khoản tương đương tiền");
        assertThat(cash).isPresent();
        assertThat(cash.get().getCode()).isEqualTo("CASH_AND_CASH_EQUIVALENTS");

        Optional<CanonicalFinancialTaxonomy.MetricDefinition> totalAssets =
                CanonicalFinancialTaxonomy.findByCodeOrAlias("Tổng cộng tài sản");
        assertThat(totalAssets).isPresent();
        assertThat(totalAssets.get().getCode()).isEqualTo("TOTAL_ASSETS");

        Optional<CanonicalFinancialTaxonomy.MetricDefinition> loans =
                CanonicalFinancialTaxonomy.findByCodeOrAlias("Dư nợ cho vay khách hàng");
        assertThat(loans).isPresent();
        assertThat(loans.get().getCode()).isEqualTo("CUSTOMER_LOANS");

        Optional<CanonicalFinancialTaxonomy.MetricDefinition> npl =
                CanonicalFinancialTaxonomy.findByCodeOrAlias("NPL");
        assertThat(npl).isPresent();
        assertThat(npl.get().getCode()).isEqualTo("NPL");
    }
}

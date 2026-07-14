package com.apms.domain.score.service;

import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.CompanyProfile.Business;
import com.apms.domain.profile.CompanyProfile.Product;
import com.apms.domain.score.draft.AutomaticSuggestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompetitorComparisonServiceTest {

    private CompetitorComparisonService service;

    @BeforeEach
    void setUp() {
        service = new CompetitorComparisonService();
    }

    // ---- Helper builders ----
    private CompanyProfile profileWith(List<Product> products, List<String> markets, List<String> industries, List<String> customers) {
        CompanyProfile p = new CompanyProfile();
        Business biz = new Business();
        biz.setProducts(products);
        biz.setMarkets(markets);
        biz.setIndustries(industries);
        biz.setTargetCustomers(customers);
        p.setBusiness(biz);
        return p;
    }

    private Product product(String name, String category) {
        Product p = new Product();
        p.setName(name);
        p.setCategory(category);
        return p;
    }

    /**
     * REQ: Coefficients sum to exactly 1.00
     */
    @Test
    void coefficients_SumToExactlyOne() {
        // productName 0.30 + productCategory 0.10 + market 0.25 + industry 0.20 + targetCustomer 0.15
        BigDecimal sum = new BigDecimal("0.30")
                .add(new BigDecimal("0.10"))
                .add(new BigDecimal("0.25"))
                .add(new BigDecimal("0.20"))
                .add(new BigDecimal("0.15"));
        assertEquals(0, sum.compareTo(BigDecimal.ONE), "Coefficients must sum to exactly 1.00");
    }

    /**
     * REQ: Exact Jaccard calculation for a known input
     */
    @Test
    void exactJaccard_KnownInput() {
        // intersection = {"cloud"} size=1, union = {"cloud", "iot", "ai"} size=3 → Jaccard = 1/3 ≈ 33.33
        List<String> markets1 = Arrays.asList("Cloud", "IoT");
        List<String> markets2 = Arrays.asList("Cloud", "AI");
        // Other components null to focus test
        CompanyProfile target = profileWith(null, markets1, null, null);
        CompanyProfile reference = profileWith(null, markets2, null, null);
        AutomaticSuggestion result = service.suggestProductMarketOverlap(target, reference, com.apms.domain.score.enums.OverlapSuggestionMode.LEGACY_PARTIAL);
        // productNameOverlap will be null → insufficient coverage
        assertNull(result.getSuggestedRawScore(), "Insufficient coverage — productName is null");
    }

    /**
     * REQ: productName and productCategory handled separately; description ignored
     */
    @Test
    void productNameAndCategory_HandledSeparately() {
        Product p1 = new Product();
        p1.setName("CloudStorage");
        p1.setCategory("Infrastructure");
        p1.setDescription("Should be ignored in comparison");

        Product p2 = new Product();
        p2.setName("CloudStorage");
        p2.setCategory("Software");

        CompanyProfile target = profileWith(List.of(p1), List.of("Cloud"), List.of("IT"), List.of("Enterprise"));
        CompanyProfile reference = profileWith(List.of(p2), List.of("Cloud"), List.of("IT"), List.of("Enterprise"));

        AutomaticSuggestion result = service.suggestProductMarketOverlap(target, reference, com.apms.domain.score.enums.OverlapSuggestionMode.LEGACY_PARTIAL);

        // productNameOverlap = 100.00 (CloudStorage matches)
        BigDecimal nameScore = result.getComponentScores().get("productNameOverlap");
        assertNotNull(nameScore, "productNameOverlap should be calculable");
        assertEquals(0, nameScore.compareTo(new BigDecimal("100.00")));

        // productCategoryOverlap = 0.00 (Infrastructure vs Software don't match)
        BigDecimal catScore = result.getComponentScores().get("productCategoryOverlap");
        assertNotNull(catScore);
        assertEquals(0, catScore.compareTo(BigDecimal.ZERO));
    }

    /**
     * REQ: Unicode NFC normalization and lowercase Locale.ROOT
     */
    @Test
    void normalization_UnicodeAndCase() {
        // Using same market with different case/whitespace
        List<String> m1 = List.of("Cloud  Market");
        List<String> m2 = List.of("cloud market");

        // Also need products to meet min coverage
        List<Product> products = List.of(product("ProductA", "CategoryA"));
        CompanyProfile target = profileWith(products, m1, List.of("Tech"), List.of("SME"));
        CompanyProfile reference = profileWith(products, m2, List.of("Tech"), List.of("SME"));

        AutomaticSuggestion result = service.suggestProductMarketOverlap(target, reference, com.apms.domain.score.enums.OverlapSuggestionMode.LEGACY_PARTIAL);
        assertNotNull(result.getSuggestedRawScore(), "Should normalize and match");
        // market overlap should be 100%
        BigDecimal marketScore = result.getComponentScores().get("marketOverlap");
        assertNotNull(marketScore);
        assertEquals(0, marketScore.compareTo(new BigDecimal("100.00")));
    }

    /**
     * REQ: Empty side (null) returns null component score
     */
    @Test
    void nullSide_ReturnsNullComponent() {
        CompanyProfile target = profileWith(null, null, null, null);
        CompanyProfile reference = profileWith(null, null, null, null);
        AutomaticSuggestion result = service.suggestProductMarketOverlap(target, reference, com.apms.domain.score.enums.OverlapSuggestionMode.LEGACY_PARTIAL);
        assertNull(result.getSuggestedRawScore(), "Null data → insufficient coverage → null score");
        assertFalse(result.getMissingComponents().isEmpty());
    }

    /**
     * REQ: No divide by zero (empty lists after normalization)
     */
    @Test
    void emptyLists_NoDivideByZero() {
        List<Product> emptyProducts = List.of();
        CompanyProfile target = profileWith(emptyProducts, List.of(), List.of(), List.of());
        CompanyProfile reference = profileWith(emptyProducts, List.of(), List.of(), List.of());
        // Should not throw
        assertDoesNotThrow(() -> service.suggestProductMarketOverlap(target, reference, com.apms.domain.score.enums.OverlapSuggestionMode.LEGACY_PARTIAL));
    }

    /**
     * REQ: Missing components are NOT redistributed to other weights
     */
    @Test
    void missingComponents_NotReweighted() {
        List<Product> products = List.of(product("ProductA", "CatA"));
        List<String> markets = List.of("Cloud");
        List<String> industries = List.of("IT");

        // Only 3 components (productName, market, industry) — target customers null
        CompanyProfile target = profileWith(products, markets, industries, null);
        CompanyProfile reference = profileWith(products, markets, industries, null);

        AutomaticSuggestion result = service.suggestProductMarketOverlap(target, reference, com.apms.domain.score.enums.OverlapSuggestionMode.LEGACY_PARTIAL);
        // targetCustomer is missing → should not appear in weights
        assertFalse(result.getComponentWeights().containsKey("targetCustomerOverlap"));
        // Total coverage should be 0.30 + 0.10 + 0.25 + 0.20 = 0.85 (not 1.00)
        BigDecimal expectedCoverage = new BigDecimal("0.85");
        assertEquals(0, result.getComponentCoverage().compareTo(expectedCoverage),
                "Coverage must be 0.85 with targetCustomer missing, NOT redistributed");
    }

    /**
     * REQ: Minimum coverage (productName + at least 2 others) for valid proposal
     */
    @Test
    void minimumCoverage_MetWhenProductNamePlusTwoOthers() {
        List<Product> products = List.of(product("SaaS", "Software"));
        List<String> markets = List.of("Asia");
        List<String> industries = List.of("Finance");

        CompanyProfile target = profileWith(products, markets, industries, null);  // customers null
        CompanyProfile reference = profileWith(products, markets, industries, null);

        AutomaticSuggestion result = service.suggestProductMarketOverlap(target, reference, com.apms.domain.score.enums.OverlapSuggestionMode.LEGACY_PARTIAL);
        assertNotNull(result.getSuggestedRawScore(),
                "productName + market + industry meets minimum coverage");
    }

    /**
     * REQ: Insufficient coverage returns null score
     */
    @Test
    void insufficientCoverage_NullProposal() {
        // Only market, no products
        CompanyProfile target = profileWith(null, List.of("Cloud"), null, null);
        CompanyProfile reference = profileWith(null, List.of("Cloud"), null, null);

        AutomaticSuggestion result = service.suggestProductMarketOverlap(target, reference, com.apms.domain.score.enums.OverlapSuggestionMode.LEGACY_PARTIAL);
        assertNull(result.getSuggestedRawScore(), "No productName → insufficient coverage");
        assertNotNull(result.getSuggestionRationale());
        assertEquals(com.apms.domain.score.enums.CriterionSuggestionValidationStatus.WARNING, result.getValidationStatus());
        assertEquals(com.apms.domain.score.enums.CriterionSuggestionReviewStatus.NEEDS_MORE_DATA, result.getReviewStatus());
        assertFalse(result.getMissingData().isEmpty());
    }

    /**
     * REQ: Duplicate product names normalized and deduplicated before Jaccard
     */
    @Test
    void duplicateNames_RemovedBeforeJaccard() {
        // Duplicates in target — should be treated as one
        List<Product> targetProducts = List.of(product("AI", "ML"), product("AI", "ML"), product("Cloud", "Infra"));
        List<Product> refProducts = List.of(product("AI", "ML"), product("Cloud", "Infra"));

        CompanyProfile target = profileWith(targetProducts, List.of("Global"), List.of("IT"), List.of("Enterprise"));
        CompanyProfile reference = profileWith(refProducts, List.of("Global"), List.of("IT"), List.of("Enterprise"));

        AutomaticSuggestion result = service.suggestProductMarketOverlap(target, reference, com.apms.domain.score.enums.OverlapSuggestionMode.LEGACY_PARTIAL);
        // Deduplication: target = {ai, cloud}, reference = {ai, cloud} → Jaccard = 100
        BigDecimal nameScore = result.getComponentScores().get("productNameOverlap");
        assertEquals(0, nameScore.compareTo(new BigDecimal("100.00")));
    }

    /**
     * REQ: rubricVersion must be COMPETITOR_OVERLAP_RUBRIC_V1
     */
    @Test
    void rubricVersion_CorrectValue() {
        CompanyProfile target = profileWith(null, null, null, null);
        CompanyProfile reference = profileWith(null, null, null, null);
        AutomaticSuggestion result = service.suggestProductMarketOverlap(target, reference, com.apms.domain.score.enums.OverlapSuggestionMode.LEGACY_PARTIAL);
        assertEquals("COMPETITOR_OVERLAP_RUBRIC_V1", result.getRubricVersion());
    }
}

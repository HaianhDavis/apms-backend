package com.apms.domain.score.service;

import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.CompanyProfile.Business;
import com.apms.domain.profile.CompanyProfile.Product;
import com.apms.domain.score.draft.AutomaticSuggestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompetitorComparisonLegacyBehaviorTest {

    private CompetitorComparisonService service;

    @BeforeEach
    void setUp() {
        service = new CompetitorComparisonService();
    }

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

    @Test
    void completeDataDeterministicResult_RemainsBackwardCompatible() {
        List<Product> p1 = List.of(product("P1", "C1"));
        CompanyProfile target = profileWith(p1, List.of("M1"), List.of("I1"), List.of("TC1"));
        CompanyProfile ref = profileWith(p1, List.of("M1"), List.of("I1"), List.of("TC1"));

        AutomaticSuggestion result = service.suggestProductMarketOverlap(target, ref, com.apms.domain.score.enums.OverlapSuggestionMode.LEGACY_PARTIAL);
        assertEquals(new BigDecimal("100.00"), result.getSuggestedRawScore());
        assertEquals("COMPETITOR_OVERLAP_RUBRIC_V1", result.getRubricVersion());
        assertEquals(com.apms.domain.score.enums.CriterionSuggestionMethod.DETERMINISTIC, result.getMethod());
    }

    @Test
    void missingComponents_AreNotTreatedAsZero_AndNotTreatedAs50_AndNoSilentRenormalization() {
        List<Product> p1 = List.of(product("P1", "C1"));
        // missing industries and customers
        CompanyProfile target = profileWith(p1, List.of("M1"), null, null);
        CompanyProfile ref = profileWith(p1, List.of("M1"), null, null);

        AutomaticSuggestion result = service.suggestProductMarketOverlap(target, ref, com.apms.domain.score.enums.OverlapSuggestionMode.LEGACY_PARTIAL);

        // Product name: 0.30, category: 0.10, market: 0.25 -> total coverage 0.65
        // Score: 100 * 0.30 + 100 * 0.10 + 100 * 0.25 = 65
        assertEquals(new BigDecimal("65.00"), result.getSuggestedRawScore());
        assertEquals(new BigDecimal("0.65"), result.getComponentCoverage());
        assertFalse(result.getComponentScores().containsKey("industryOverlap"));
        assertFalse(result.getComponentScores().containsKey("targetCustomerOverlap"));
    }

    @Test
    void unifiedCanonicalRoute_ReturnsNeedsMoreData_WhenRequiredDimensionsAreIncomplete() {
        // Missing product name
        CompanyProfile target = profileWith(null, List.of("M1"), List.of("I1"), List.of("TC1"));
        CompanyProfile ref = profileWith(null, List.of("M1"), List.of("I1"), List.of("TC1"));

        AutomaticSuggestion result = service.suggestProductMarketOverlap(target, ref, com.apms.domain.score.enums.OverlapSuggestionMode.CANONICAL_STRICT);
        assertNull(result.getSuggestedRawScore());
        assertEquals(com.apms.domain.score.enums.CriterionSuggestionReviewStatus.NEEDS_MORE_DATA, result.getReviewStatus());
        assertEquals(com.apms.domain.score.enums.CriterionSuggestionValidationStatus.WARNING, result.getValidationStatus());
    }
}

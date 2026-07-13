package com.apms.domain.score.service;

import com.apms.domain.score.draft.AutomaticSuggestion;
import com.apms.domain.score.enums.CriterionSuggestionReviewStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.bson.Document;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataMongoTest
public class MongoMappingIntegrationTest {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Test
    void testLegacyDeserialization() {
        Document doc = new Document();
        doc.put("criterionKey", "productMarketOverlapScore");
        doc.put("suggestedRawScore", 75);
        doc.put("suggestionRationale", "Legacy rationale");
        doc.put("componentCoverage", 0.85);
        doc.put("missingComponents", List.of("Some detail"));
        doc.put("calculationWarnings", List.of("Warning 1"));
        doc.put("accepted", true);
        doc.put("acceptedByAccountId", 100L);

        AutomaticSuggestion suggestion = mongoTemplate.getConverter().read(AutomaticSuggestion.class, doc);

        assertThat(suggestion.getCriterionKey()).isEqualTo("productMarketOverlapScore");
        assertThat(suggestion.getSuggestedRawScore()).isEqualTo(new BigDecimal("75"));
        assertThat(suggestion.getEffectiveExplanation()).isEqualTo("Legacy rationale");
        assertThat(suggestion.getEffectiveReviewStatus()).isEqualTo(CriterionSuggestionReviewStatus.ACCEPTED);

        assertThat(suggestion.getEvidenceCoverage()).isNull();
        assertThat(suggestion.getValidationWarnings()).isEmpty();
        assertThat(suggestion.getMissingData()).isEmpty();
        assertThat(suggestion.getReviewStatus()).isNull();
    }
}

package com.apms.domain.ai.service.provider;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.contract.dto.PartnerContractExtractionOutput;
import com.apms.domain.document.RawDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
public class GeminiPartnerContractExtractionProviderTest {

    private GeminiPartnerContractExtractionProvider provider;

    private ObjectMapper strictMapper;

    @BeforeEach
    void setup() {
        strictMapper = new ObjectMapper();
        strictMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        provider = new GeminiPartnerContractExtractionProvider(strictMapper, new GeminiApiKeyManager("key", ""), "gemini-3.6-flash");

    }

    @Test
    void shouldRejectUnknownTopLevelField() {
        String json = "{ \"metadataFields\": [], \"clauseCandidates\": [], \"unknownField\": \"value\" }";
        assertThatThrownBy(() -> parseStrict(json))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("AI Extraction Provider returned malformed or unexpected data");
    }

    @Test
    void shouldRejectUnknownMetadataField() {
        String json = "{ \"metadataFields\": [{ \"fieldName\": \"key\", \"value\": \"val\", \"evidenceReferences\": [], \"sourceExcerpt\": \"abc\", \"unknownMeta\": 1 }], \"clauseCandidates\": [] }";
        assertThatThrownBy(() -> parseStrict(json))
                .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void shouldRejectUnknownClauseItemField() {
        String json = "{ \"metadataFields\": [], \"clauseCandidates\": [{ \"clauseCandidateId\": \"id\", \"clauseTitle\": \"t\", \"clauseType\": \"SLA\", \"normalizedTerms\": {}, \"evidenceReferences\": [], \"sourceExcerpt\": \"exc\", \"unknownClause\": 2 }] }";
        assertThatThrownBy(() -> parseStrict(json))
                .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void shouldRejectUnknownContractClauseTermsField() {
        String json = "{ \"metadataFields\": [], \"clauseCandidates\": [{ \"clauseCandidateId\": \"id\", \"clauseTitle\": \"t\", \"clauseType\": \"SLA\", \"normalizedTerms\": { \"targetMetricKey\": \"k\", \"unknownTerm\": \"v\" }, \"evidenceReferences\": [], \"sourceExcerpt\": \"exc\" }] }";
        assertThatThrownBy(() -> parseStrict(json))
                .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void shouldRejectInternalScoringFields() {
        String json1 = "{ \"overallScore\": 10, \"metadataFields\": [], \"clauseCandidates\": [] }";
        assertThatThrownBy(() -> parseStrict(json1)).isInstanceOf(BusinessValidationException.class);

        String json2 = "{ \"criterionScore\": 10, \"metadataFields\": [], \"clauseCandidates\": [] }";
        assertThatThrownBy(() -> parseStrict(json2)).isInstanceOf(BusinessValidationException.class);

        String json3 = "{ \"normalizedScore\": 10, \"metadataFields\": [], \"clauseCandidates\": [] }";
        assertThatThrownBy(() -> parseStrict(json3)).isInstanceOf(BusinessValidationException.class);

        String json4 = "{ \"weightedScore\": 10, \"metadataFields\": [], \"clauseCandidates\": [] }";
        assertThatThrownBy(() -> parseStrict(json4)).isInstanceOf(BusinessValidationException.class);

        String json5 = "{ \"AHPWeight\": 10, \"metadataFields\": [], \"clauseCandidates\": [] }";
        assertThatThrownBy(() -> parseStrict(json5)).isInstanceOf(BusinessValidationException.class);

        String json6 = "{ \"relationshipType\": \"PARTNER\", \"metadataFields\": [], \"clauseCandidates\": [] }";
        assertThatThrownBy(() -> parseStrict(json6)).isInstanceOf(BusinessValidationException.class);

        String json7 = "{ \"approvalStatus\": \"APPROVED\", \"metadataFields\": [], \"clauseCandidates\": [] }";
        assertThatThrownBy(() -> parseStrict(json7)).isInstanceOf(BusinessValidationException.class);

        String json8 = "{ \"lifecycleStatus\": \"ACTIVE\", \"metadataFields\": [], \"clauseCandidates\": [] }";
        assertThatThrownBy(() -> parseStrict(json8)).isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void shouldRejectMalformedJson() {
        String json = "{ malformed json }";
        assertThatThrownBy(() -> parseStrict(json))
                .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void shouldAcceptFullyValidResponse() {
        String json = "{\n" +
                "  \"metadataFields\": [\n" +
                "    {\n" +
                "      \"fieldName\": \"contractTitle\",\n" +
                "      \"value\": \"Service Agreement\",\n" +
                "      \"evidenceReferences\": [\"seg-1\"],\n" +
                "      \"evidenceText\": \"This Service Agreement...\"\n" +
                "    }\n" +
                "  ],\n" +
                "  \"clauseCandidates\": [\n" +
                "    {\n" +
                "      \"clauseCandidateId\": \"c1\",\n" +
                "      \"clauseTitle\": \"Uptime SLA\",\n" +
                "      \"clauseType\": \"SLA\",\n" +
                "      \"normalizedTerms\": {\n" +
                "         \"liabilityCap\": \"1000\"\n" +
                "      },\n" +
                "      \"evidenceReferences\": [\"seg-1\"],\n" +
                "      \"sourceExcerpt\": \"Provider guarantees 99.9% uptime...\"\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        PartnerContractExtractionOutput result = parseStrict(json);

        assertThat(result.getMetadataFields()).hasSize(1);
        assertThat(result.getMetadataFields().get(0).getFieldName()).isEqualTo("contractTitle");

        assertThat(result.getClauseCandidates()).hasSize(1);
        assertThat(result.getClauseCandidates().get(0).getClauseTitle()).isEqualTo("Uptime SLA");
        assertThat(result.getClauseCandidates().get(0).getNormalizedTerms().getLiabilityCap()).isEqualTo("1000");
    }

    private PartnerContractExtractionOutput parseStrict(String json) {
        try {
            return strictMapper.readValue(json, PartnerContractExtractionOutput.class);
        } catch (Exception e) {
            throw new BusinessValidationException("AI Extraction Provider returned malformed or unexpected data");
        }
    }
}

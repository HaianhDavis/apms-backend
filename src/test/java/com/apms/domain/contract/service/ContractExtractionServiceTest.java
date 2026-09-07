package com.apms.domain.contract.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.contract.dto.ai.AiContractExtractionCandidate;
import com.apms.domain.contract.enums.ContractType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractExtractionServiceTest {

    private ContractExtractionService extractionService;

    @BeforeEach
    void setUp() {
        extractionService = new ContractExtractionService(new ObjectMapper(), RestClient.builder());
    }

    @Test
    @DisplayName("Prompt assembly: should include Cooperation subtype schema when confirmedType is COOPERATION_AGREEMENT")
    void shouldIncludeSubtypeSchemaWhenConfirmedTypeIsProvided() {
        String prompt = extractionService.buildStructuredContractPrompt(ContractType.COOPERATION_AGREEMENT);

        assertThat(prompt).isNotNull();
        // Common schema must always be present
        assertThat(prompt).contains("commonData");
        assertThat(prompt).contains("contractNumber");
        assertThat(prompt).contains("governingLaw");

        // Subtype schema for COOPERATION must be injected
        assertThat(prompt).contains("cooperationAgreementData");
        assertThat(prompt).contains("cooperationScope");
        assertThat(prompt).contains("cooperationActivities");

        // Subtype placeholder must be replaced
        assertThat(prompt).doesNotContain("{{SUBTYPE_SCHEMA}}");
    }

    @Test
    @DisplayName("Prompt assembly: should include correct subtype schema for PARTNERSHIP_AGREEMENT and not others")
    void shouldIncludeCorrectSubtypeSchemaForPartnership() {
        String prompt = extractionService.buildStructuredContractPrompt(ContractType.PARTNERSHIP_AGREEMENT);

        assertThat(prompt).isNotNull();
        // Must contain PARTNERSHIP specific fields
        assertThat(prompt).contains("partnershipAgreementData");
        assertThat(prompt).contains("partnerRoles");
        assertThat(prompt).contains("mutualCommitments");
        assertThat(prompt).contains("exclusivity");

        // Must NOT contain fields from other subtypes
        assertThat(prompt).doesNotContain("cooperationAgreementData");
        assertThat(prompt).doesNotContain("jointVentureAgreementData");
        assertThat(prompt).doesNotContain("businessCooperationContractData");

        // Subtype placeholder must be replaced
        assertThat(prompt).doesNotContain("{{SUBTYPE_SCHEMA}}");
    }

    @Test
    @DisplayName("Prompt assembly: should not include subtype schema when confirmedType is UNKNOWN")
    void shouldNotIncludeSubtypeSchemaWhenConfirmedTypeIsUnknown() {
        String prompt = extractionService.buildStructuredContractPrompt(ContractType.UNKNOWN);

        assertThat(prompt).isNotNull();
        assertThat(prompt).contains("commonData");
        assertThat(prompt).contains("governingLaw");

        assertThat(prompt).doesNotContain("cooperationAgreementData");
        assertThat(prompt).doesNotContain("partnershipAgreementData");
        assertThat(prompt).doesNotContain("jointVentureAgreementData");
        assertThat(prompt).doesNotContain("businessCooperationContractData");
        assertThat(prompt).doesNotContain("{{SUBTYPE_SCHEMA}}");
    }

    @Test
    @DisplayName("Prompt assembly: should not fail when confirmedType is null")
    void shouldNotFailWhenConfirmedTypeIsNull() {
        String prompt = extractionService.buildStructuredContractPrompt(null);

        assertThat(prompt).isNotNull();
        assertThat(prompt).contains("commonData");
        assertThat(prompt).contains("governingLaw");
        assertThat(prompt).doesNotContain("{{SUBTYPE_SCHEMA}}");
    }

    @Test
    @DisplayName("Prompt assembly: must contain comprehensive governing law and dispute resolution instructions")
    void shouldContainComprehensiveGoverningLawDisputeResolutionInstructions() {
        String prompt = extractionService.buildStructuredContractPrompt(ContractType.COOPERATION_AGREEMENT);

        assertThat(prompt).contains("Governing Law & Dispute Resolution");
        assertThat(prompt).contains("Applicable / governing law");
        assertThat(prompt).contains("Negotiation requirement");
        assertThat(prompt).contains("Arbitration");
        assertThat(prompt).contains("VIAC");
        assertThat(prompt).contains("NEVER infer, assume, or fabricate missing legal terms");
        assertThat(prompt).contains("[Applicable Law] | [Dispute Resolution Method / Forum] | [Pre-dispute Negotiation / Mediation Procedure]");
    }

    @Test
    @DisplayName("parseResponse: should throw AI_RECITATION_FILTER when finishReason is RECITATION")
    void shouldThrowRecitationFilterExceptionWhenGeminiReturnsRecitation() {
        String recitationJson = """
            {
              "candidates": [
                {
                  "content": {},
                  "finishReason": "RECITATION",
                  "index": 0,
                  "finishMessage": "The generated content was filtered because it may contain material that resembles existing copyrighted works."
                }
              ]
            }
            """;

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                extractionService.parseResponse(recitationJson, AiContractExtractionCandidate.class))
                .isInstanceOf(com.apms.common.exception.BusinessValidationException.class)
                .satisfies(ex -> {
                    com.apms.common.exception.BusinessValidationException bve = (com.apms.common.exception.BusinessValidationException) ex;
                    assertThat(bve.getErrorCode()).isEqualTo("AI_RECITATION_FILTER");
                });
    }

    @Test
    @DisplayName("parseResponse: should throw AI_SAFETY_FILTER when finishReason is SAFETY")
    void shouldThrowSafetyFilterExceptionWhenGeminiReturnsSafety() {
        String safetyJson = """
            {
              "candidates": [
                {
                  "content": {},
                  "finishReason": "SAFETY",
                  "index": 0,
                  "finishMessage": "The response was blocked due to safety settings."
                }
              ]
            }
            """;

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                extractionService.parseResponse(safetyJson, AiContractExtractionCandidate.class))
                .isInstanceOf(com.apms.common.exception.BusinessValidationException.class)
                .satisfies(ex -> {
                    com.apms.common.exception.BusinessValidationException bve = (com.apms.common.exception.BusinessValidationException) ex;
                    assertThat(bve.getErrorCode()).isEqualTo("AI_SAFETY_FILTER");
                });
    }

    @Test
    @DisplayName("parseResponse: should throw AI_TOKEN_LIMIT_EXCEEDED when finishReason is MAX_TOKENS")
    void shouldThrowMaxTokensExceptionWhenGeminiTruncated() {
        String maxTokensJson = """
            {
              "candidates": [
                {
                  "finishReason": "MAX_TOKENS",
                  "index": 0
                }
              ]
            }
            """;

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                extractionService.parseResponse(maxTokensJson, AiContractExtractionCandidate.class))
                .isInstanceOf(com.apms.common.exception.BusinessValidationException.class)
                .satisfies(ex -> {
                    com.apms.common.exception.BusinessValidationException bve = (com.apms.common.exception.BusinessValidationException) ex;
                    assertThat(bve.getErrorCode()).isEqualTo("AI_TOKEN_LIMIT_EXCEEDED");
                });
    }

    @Test
    @DisplayName("parseResponse: should parse successful response with Markdown backticks correctly")
    void shouldParseSuccessfulResponseWithMarkdown() {
        String successJson = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "```json\\n{\\n  \\"commonData\\": {\\n    \\"contractTitle\\": { \\"value\\": \\"HỢP ĐỒNG HỢP TÁC\\", \\"sourcePage\\": 1, \\"evidence\\": \\"HỢP ĐỒNG\\", \\"confidence\\": 0.95 }\\n  }\\n}\\n```"
                      }
                    ]
                  },
                  "finishReason": "STOP",
                  "index": 0
                }
              ]
            }
            """;

        AiContractExtractionCandidate result =
                extractionService.parseResponse(successJson, AiContractExtractionCandidate.class);

        assertThat(result).isNotNull();
        assertThat(result.getCommonData()).isNotNull();
        assertThat(result.getCommonData().getContractTitle().getValue()).isEqualTo("HỢP ĐỒNG HỢP TÁC");
    }
}

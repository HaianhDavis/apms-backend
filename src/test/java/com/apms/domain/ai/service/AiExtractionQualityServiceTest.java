package com.apms.domain.ai.service;

import com.apms.domain.ai.dto.ExtractionFieldResult;
import com.apms.domain.ai.dto.ExtractionQualityMetrics;
import com.apms.domain.ai.dto.ExtractionQualityStatus;
import com.apms.domain.ai.dto.ExtractionValidationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiExtractionQualityServiceTest {

    private AiExtractionQualityService service;

    @BeforeEach
    void setUp() {
        service = new AiExtractionQualityService();
    }

    // ─────────────────────────────────────────────
    // VALIDATION TESTS
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Field Validation Rules")
    class FieldValidation {

        @Test
        @DisplayName("Value with evidence should PASS")
        void valueWithEvidence_shouldPass() {
            Map<String, ExtractionFieldResult> fields = new HashMap<>();
            fields.put("tradeName", ExtractionFieldResult.builder()
                    .fieldName("tradeName")
                    .value("Test Company LLC")
                    .evidenceText("Document header states Test Company LLC")
                    .confidence(0.95)
                    .build());

            service.validateExtraction(fields);

            assertEquals(ExtractionValidationStatus.PASS, fields.get("tradeName").getValidationStatus());
        }

        @Test
        @DisplayName("Non-critical field without evidence should get WARNING")
        void nonCriticalFieldWithoutEvidence_shouldWarn() {
            Map<String, ExtractionFieldResult> fields = new HashMap<>();
            fields.put("businessModel", ExtractionFieldResult.builder()
                    .fieldName("businessModel")
                    .value("B2B SaaS")
                    .build());

            service.validateExtraction(fields);

            assertEquals(ExtractionValidationStatus.WARNING, fields.get("businessModel").getValidationStatus());
            assertNotNull(fields.get("businessModel").getValidationMessages());
        }

        @Test
        @DisplayName("Critical field (tradeName) without evidence should FAIL")
        void criticalFieldWithoutEvidence_shouldFail() {
            Map<String, ExtractionFieldResult> fields = new HashMap<>();
            fields.put("tradeName", ExtractionFieldResult.builder()
                    .fieldName("tradeName")
                    .value("Suspicious Corp")
                    .build());

            service.validateExtraction(fields);

            assertEquals(ExtractionValidationStatus.FAIL, fields.get("tradeName").getValidationStatus());
            assertTrue(fields.get("tradeName").getValidationMessages().contains("evidence"));
        }

        @Test
        @DisplayName("Invalid email format should FAIL")
        void invalidEmail_shouldFail() {
            Map<String, ExtractionFieldResult> fields = new HashMap<>();
            fields.put("email", ExtractionFieldResult.builder()
                    .fieldName("email")
                    .value("not-an-email")
                    .evidenceText("Found in contact section")
                    .build());

            service.validateExtraction(fields);

            assertEquals(ExtractionValidationStatus.FAIL, fields.get("email").getValidationStatus());
            assertTrue(fields.get("email").getValidationMessages().contains("email"));
        }

        @Test
        @DisplayName("Valid email format should PASS")
        void validEmail_shouldPass() {
            Map<String, ExtractionFieldResult> fields = new HashMap<>();
            fields.put("email", ExtractionFieldResult.builder()
                    .fieldName("email")
                    .value("contact@company.com")
                    .evidenceText("Email: contact@company.com")
                    .build());

            service.validateExtraction(fields);

            assertEquals(ExtractionValidationStatus.PASS, fields.get("email").getValidationStatus());
        }

        @Test
        @DisplayName("Invalid website URL should FAIL")
        void invalidWebsite_shouldFail() {
            Map<String, ExtractionFieldResult> fields = new HashMap<>();
            fields.put("website", ExtractionFieldResult.builder()
                    .fieldName("website")
                    .value("just some text not a url")
                    .evidenceText("Found in header")
                    .build());

            service.validateExtraction(fields);

            assertEquals(ExtractionValidationStatus.FAIL, fields.get("website").getValidationStatus());
        }

        @Test
        @DisplayName("Short tax code should FAIL")
        void shortTaxCode_shouldFail() {
            Map<String, ExtractionFieldResult> fields = new HashMap<>();
            fields.put("taxCode", ExtractionFieldResult.builder()
                    .fieldName("taxCode")
                    .value("12")
                    .evidenceText("Page 1 shows tax code")
                    .build());

            service.validateExtraction(fields);

            assertEquals(ExtractionValidationStatus.FAIL, fields.get("taxCode").getValidationStatus());
            assertTrue(fields.get("taxCode").getValidationMessages().contains("short"));
        }

        @Test
        @DisplayName("Low confidence should produce WARNING")
        void lowConfidence_shouldWarn() {
            Map<String, ExtractionFieldResult> fields = new HashMap<>();
            fields.put("businessModel", ExtractionFieldResult.builder()
                    .fieldName("businessModel")
                    .value("B2C E-commerce")
                    .evidenceText("Seems to be an e-commerce company")
                    .confidence(0.3)
                    .build());

            service.validateExtraction(fields);

            assertEquals(ExtractionValidationStatus.WARNING, fields.get("businessModel").getValidationStatus());
            assertTrue(fields.get("businessModel").getValidationMessages().contains("confidence"));
        }

        @Test
        @DisplayName("Null or empty field should PASS (nothing to validate)")
        void nullField_shouldPass() {
            Map<String, ExtractionFieldResult> fields = new HashMap<>();
            fields.put("tradeName", ExtractionFieldResult.builder()
                    .fieldName("tradeName")
                    .value(null)
                    .build());

            service.validateExtraction(fields);

            assertEquals(ExtractionValidationStatus.PASS, fields.get("tradeName").getValidationStatus());
        }

        @Test
        @DisplayName("Empty list value should PASS")
        void emptyListValue_shouldPass() {
            Map<String, ExtractionFieldResult> fields = new HashMap<>();
            fields.put("industries", ExtractionFieldResult.builder()
                    .fieldName("industries")
                    .value("[]")
                    .build());

            service.validateExtraction(fields);

            assertEquals(ExtractionValidationStatus.PASS, fields.get("industries").getValidationStatus());
        }
    }

    // ─────────────────────────────────────────────
    // METRICS TESTS
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Quality Metrics Computation")
    class MetricsComputation {

        @Test
        @DisplayName("Metrics correctly count fields and evidence")
        void computeMetrics_countsCorrectly() {
            Map<String, ExtractionFieldResult> fields = new HashMap<>();
            fields.put("website", ExtractionFieldResult.builder()
                    .fieldName("website").value("https://example.com").evidenceText("Stated on page 1")
                    .confidence(0.9).validationStatus(ExtractionValidationStatus.PASS).build());
            fields.put("businessModel", ExtractionFieldResult.builder()
                    .fieldName("businessModel").value("B2B")
                    .validationStatus(ExtractionValidationStatus.WARNING).build());
            fields.put("tradeName", ExtractionFieldResult.builder()
                    .fieldName("tradeName").value(null)
                    .validationStatus(ExtractionValidationStatus.PASS).build());

            ExtractionQualityMetrics metrics = service.computeMetrics(fields);

            assertEquals(13, metrics.getTotalFields());
            assertEquals(2, metrics.getFieldsWithValue()); // website + businessModel
            assertEquals(1, metrics.getFieldsWithEvidence()); // only website
            assertEquals(2, metrics.getPassedFields());
            assertEquals(1, metrics.getWarningFields());
            assertEquals(0, metrics.getFailedFields());
            assertEquals(1, metrics.getHallucinationRiskCount()); // businessModel has value but no evidence
            assertEquals(0.5, metrics.getEvidenceCoverageRate()); // 1/2
        }

        @Test
        @DisplayName("Completeness rate considers required fields")
        void computeMetrics_completenessRate() {
            Map<String, ExtractionFieldResult> fields = new HashMap<>();
            fields.put("tradeName", ExtractionFieldResult.builder()
                    .fieldName("tradeName").value("Found Name")
                    .validationStatus(ExtractionValidationStatus.PASS).build());
            fields.put("industries", ExtractionFieldResult.builder()
                    .fieldName("industries").value(List.of("Tech"))
                    .validationStatus(ExtractionValidationStatus.PASS).build());
            // "description" is missing -> completenessRate = 2/3

            ExtractionQualityMetrics metrics = service.computeMetrics(fields);

            assertEquals(2.0 / 3.0, metrics.getCompletenessRate(), 0.01);
        }

        @Test
        @DisplayName("Average confidence computed correctly")
        void computeMetrics_averageConfidence() {
            Map<String, ExtractionFieldResult> fields = new HashMap<>();
            fields.put("website", ExtractionFieldResult.builder()
                    .fieldName("website").value("https://corp-a.com").confidence(0.8)
                    .validationStatus(ExtractionValidationStatus.PASS).build());
            fields.put("tradeName", ExtractionFieldResult.builder()
                    .fieldName("tradeName").value("Corp A Trade").confidence(0.6)
                    .validationStatus(ExtractionValidationStatus.PASS).build());

            ExtractionQualityMetrics metrics = service.computeMetrics(fields);

            assertEquals(0.7, metrics.getAverageConfidence(), 0.01);
        }

        @Test
        @DisplayName("Empty fieldResults returns zero metrics")
        void computeMetrics_emptyFields() {
            ExtractionQualityMetrics metrics = service.computeMetrics(new HashMap<>());

            assertEquals(0, metrics.getTotalFields());
            assertNull(metrics.getAverageConfidence());
        }
    }

    // ─────────────────────────────────────────────
    // OVERALL STATUS DETERMINATION
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("Overall Quality Status")
    class OverallStatus {

        @Test
        @DisplayName("Metrics with failures should return NEEDS_REVIEW")
        void failedFields_needsReview() {
            ExtractionQualityMetrics metrics = ExtractionQualityMetrics.builder()
                    .failedFields(2).warningFields(1).passedFields(5).build();

            assertEquals(ExtractionQualityStatus.NEEDS_REVIEW, service.determineOverallStatus(metrics));
        }

        @Test
        @DisplayName("Metrics with only warnings should return VALIDATED")
        void warningsOnly_validated() {
            ExtractionQualityMetrics metrics = ExtractionQualityMetrics.builder()
                    .failedFields(0).warningFields(3).passedFields(5).build();

            assertEquals(ExtractionQualityStatus.VALIDATED, service.determineOverallStatus(metrics));
        }

        @Test
        @DisplayName("All passed should return VALIDATED")
        void allPassed_validated() {
            ExtractionQualityMetrics metrics = ExtractionQualityMetrics.builder()
                    .failedFields(0).warningFields(0).passedFields(8).build();

            assertEquals(ExtractionQualityStatus.VALIDATED, service.determineOverallStatus(metrics));
        }
    }
}

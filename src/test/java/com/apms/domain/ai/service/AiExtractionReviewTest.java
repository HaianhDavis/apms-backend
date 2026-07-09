package com.apms.domain.ai.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.ai.AiExtractionCache;
import com.apms.domain.ai.dto.*;
import com.apms.domain.ai.repository.mongo.AiExtractionCacheRepository;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.document.repository.sql.ImportJobRepository;
import com.apms.domain.ai.service.provider.GeminiExtractionProvider;
import com.apms.domain.ai.service.provider.MockExtractionProvider;
import com.apms.domain.ai.service.provider.OpenAiExtractionProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiExtractionReviewTest {

    @Mock private ImportJobRepository importJobRepository;
    @Mock private RawDocumentRepository rawDocumentRepository;
    @Mock private AiExtractionCacheRepository extractionCacheRepository;
    @Mock private MockExtractionProvider mockProvider;
    @Mock private GeminiExtractionProvider geminiProvider;
    @Mock private OpenAiExtractionProvider openAiProvider;
    @Mock private AiExtractionQualityService qualityService;

    private AiExtractionService extractionService;

    @BeforeEach
    void setUp() {
        extractionService = new AiExtractionService(
                importJobRepository, rawDocumentRepository, extractionCacheRepository,
                mockProvider, geminiProvider, openAiProvider, qualityService, new ObjectMapper());
    }

    private AiExtractionCache buildCache(String id, ExtractionQualityStatus qualityStatus) {
        Map<String, ExtractionFieldResult> fieldResults = new HashMap<>();
        fieldResults.put("legalName", ExtractionFieldResult.builder()
                .fieldName("legalName").value("Test Corp")
                .evidenceText("Page 1 header")
                .validationStatus(ExtractionValidationStatus.PASS)
                .reviewStatus(ExtractionReviewStatus.PENDING)
                .build());
        fieldResults.put("taxCode", ExtractionFieldResult.builder()
                .fieldName("taxCode").value("1234567890")
                .evidenceText("Tax registration document")
                .validationStatus(ExtractionValidationStatus.PASS)
                .reviewStatus(ExtractionReviewStatus.PENDING)
                .build());

        return AiExtractionCache.builder()
                .id(id)
                .importJobId(1L)
                .qualityStatus(qualityStatus)
                .fieldResults(fieldResults)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ─────────────────────────────────────────────
    // REVIEW FIELD TESTS
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("reviewField")
    class ReviewField {

        @Test
        @DisplayName("EDITED review status requires a reviewedValue")
        void editedReview_requiresReviewedValue() {
            AiExtractionCache cache = buildCache("ext-1", ExtractionQualityStatus.VALIDATED);
            when(extractionCacheRepository.findById("ext-1")).thenReturn(Optional.of(cache));

            ExtractionReviewRequest request = new ExtractionReviewRequest();
            request.setReviewStatus(ExtractionReviewStatus.EDITED);
            request.setReviewedValue(null); // Missing!

            assertThrows(BusinessValidationException.class, () ->
                    extractionService.reviewField("ext-1", "legalName", request, 100L));
        }

        @Test
        @DisplayName("EDITED review with value succeeds and stores reviewedValue")
        void editedReview_withValue_succeeds() {
            AiExtractionCache cache = buildCache("ext-2", ExtractionQualityStatus.VALIDATED);
            when(extractionCacheRepository.findById("ext-2")).thenReturn(Optional.of(cache));
            when(extractionCacheRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            ExtractionReviewRequest request = new ExtractionReviewRequest();
            request.setReviewStatus(ExtractionReviewStatus.EDITED);
            request.setReviewedValue("Corrected Company Name");
            request.setComment("Fixed typo");

            AiExtractionCache result = extractionService.reviewField("ext-2", "legalName", request, 100L);

            ExtractionFieldResult reviewed = result.getFieldResults().get("legalName");
            assertEquals(ExtractionReviewStatus.EDITED, reviewed.getReviewStatus());
            assertEquals("Corrected Company Name", reviewed.getReviewedValue());
            assertEquals("Fixed typo", reviewed.getReviewComment());
            assertEquals(100L, reviewed.getReviewedByUserId());
            assertNotNull(reviewed.getReviewedAt());
        }

        @Test
        @DisplayName("ACCEPTED review stores the status without requiring a reviewedValue")
        void acceptedReview_succeeds() {
            AiExtractionCache cache = buildCache("ext-3", ExtractionQualityStatus.VALIDATED);
            when(extractionCacheRepository.findById("ext-3")).thenReturn(Optional.of(cache));
            when(extractionCacheRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            ExtractionReviewRequest request = new ExtractionReviewRequest();
            request.setReviewStatus(ExtractionReviewStatus.ACCEPTED);

            AiExtractionCache result = extractionService.reviewField("ext-3", "legalName", request, 200L);

            assertEquals(ExtractionReviewStatus.ACCEPTED, result.getFieldResults().get("legalName").getReviewStatus());
        }

        @Test
        @DisplayName("Review for non-existing field creates a new entry")
        void reviewNewField_createsEntry() {
            AiExtractionCache cache = buildCache("ext-4", ExtractionQualityStatus.VALIDATED);
            when(extractionCacheRepository.findById("ext-4")).thenReturn(Optional.of(cache));
            when(extractionCacheRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            ExtractionReviewRequest request = new ExtractionReviewRequest();
            request.setReviewStatus(ExtractionReviewStatus.ACCEPTED);

            AiExtractionCache result = extractionService.reviewField("ext-4", "newField", request, 300L);

            assertTrue(result.getFieldResults().containsKey("newField"));
            assertEquals(ExtractionReviewStatus.ACCEPTED, result.getFieldResults().get("newField").getReviewStatus());
        }
    }

    // ─────────────────────────────────────────────
    // COMPLETE REVIEW TESTS
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("completeReview")
    class CompleteReview {

        @Test
        @DisplayName("completeReview sets qualityStatus to REVIEWED when all critical fields are reviewed")
        void completeReview_setsReviewed() {
            AiExtractionCache cache = buildCache("ext-5", ExtractionQualityStatus.VALIDATED);
            // Mark critical fields as ACCEPTED
            cache.getFieldResults().get("legalName").setReviewStatus(ExtractionReviewStatus.ACCEPTED);
            cache.getFieldResults().get("taxCode").setReviewStatus(ExtractionReviewStatus.ACCEPTED);

            when(extractionCacheRepository.findById("ext-5")).thenReturn(Optional.of(cache));
            when(extractionCacheRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            AiExtractionCache result = extractionService.completeReview("ext-5", 100L);

            assertEquals(ExtractionQualityStatus.REVIEWED, result.getQualityStatus());
            assertEquals(100L, result.getReviewedByUserId());
            assertNotNull(result.getReviewedAt());
        }

        @Test
        @DisplayName("completeReview fails when a critical field has NEEDS_REVIEW status")
        void completeReview_failsOnNeedsReview() {
            AiExtractionCache cache = buildCache("ext-6", ExtractionQualityStatus.VALIDATED);
            cache.getFieldResults().get("legalName").setReviewStatus(ExtractionReviewStatus.NEEDS_REVIEW);
            cache.getFieldResults().get("taxCode").setReviewStatus(ExtractionReviewStatus.ACCEPTED);

            when(extractionCacheRepository.findById("ext-6")).thenReturn(Optional.of(cache));

            assertThrows(BusinessValidationException.class, () ->
                    extractionService.completeReview("ext-6", 100L));
        }

        @Test
        @DisplayName("completeReview fails when a critical field is PENDING and validation is FAIL")
        void completeReview_failsOnPendingWithFail() {
            AiExtractionCache cache = buildCache("ext-7", ExtractionQualityStatus.NEEDS_REVIEW);
            cache.getFieldResults().get("legalName").setReviewStatus(ExtractionReviewStatus.PENDING);
            cache.getFieldResults().get("legalName").setValidationStatus(ExtractionValidationStatus.FAIL);
            cache.getFieldResults().get("taxCode").setReviewStatus(ExtractionReviewStatus.ACCEPTED);

            when(extractionCacheRepository.findById("ext-7")).thenReturn(Optional.of(cache));

            BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                    extractionService.completeReview("ext-7", 100L));
            assertTrue(ex.getMessage().contains("legalName"));
        }

        @Test
        @DisplayName("completeReview succeeds when critical fields are PENDING but validation is PASS")
        void completeReview_succeedsWhenPendingButPassed() {
            AiExtractionCache cache = buildCache("ext-8", ExtractionQualityStatus.VALIDATED);
            // Validation PASS but review still PENDING is allowed
            cache.getFieldResults().get("legalName").setReviewStatus(ExtractionReviewStatus.PENDING);
            cache.getFieldResults().get("legalName").setValidationStatus(ExtractionValidationStatus.PASS);
            cache.getFieldResults().get("taxCode").setReviewStatus(ExtractionReviewStatus.PENDING);
            cache.getFieldResults().get("taxCode").setValidationStatus(ExtractionValidationStatus.PASS);

            when(extractionCacheRepository.findById("ext-8")).thenReturn(Optional.of(cache));
            when(extractionCacheRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            AiExtractionCache result = extractionService.completeReview("ext-8", 100L);

            assertEquals(ExtractionQualityStatus.REVIEWED, result.getQualityStatus());
        }
    }
}

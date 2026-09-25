//package com.apms.domain.ai.service;
//
//import com.apms.common.exception.BusinessValidationException;
//import com.apms.common.exception.ResourceNotFoundException;
//import com.apms.domain.ai.AiExtractionCache;
//import com.apms.domain.ai.dto.*;
//import com.apms.domain.ai.repository.mongo.AiExtractionCacheRepository;
//import com.apms.domain.audit.service.AuditLogService;
//import com.apms.domain.candidate.CompanyCandidate;
//import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
//import com.apms.domain.document.repository.sql.ImportJobRepository;
//import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
//import com.apms.domain.profile.repository.mongo.CompanyProfileUpdateProposalRepository;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Nested;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//
//import java.time.LocalDateTime;
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//import java.util.Optional;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.Mockito.*;
//
//@ExtendWith(MockitoExtension.class)
//class ExtractionMergeServiceGuardTest {
//
//    @Mock private AiExtractionCacheRepository extractionCacheRepository;
//    @Mock private ImportJobRepository importJobRepository;
//    @Mock private CompanyCandidateRepository candidateRepository;
//    @Mock private CompanyProfileRepository companyProfileRepository;
//    @Mock private CompanyProfileUpdateProposalRepository proposalRepository;
//    @Mock private com.apms.domain.project.repository.sql.ProjectRepository projectRepository;
//    @Mock private AuditLogService auditLogService;
//
//    private ExtractionMergeService mergeService;
//
//    @BeforeEach
//    void setUp() {
//        mergeService = new ExtractionMergeService(
//                extractionCacheRepository, importJobRepository,
//                candidateRepository, companyProfileRepository,
//                proposalRepository, projectRepository, auditLogService);
//    }
//
//    private AiExtractionCache buildExtraction(String id, ExtractionQualityStatus status) {
//        Map<String, ExtractionFieldResult> fieldResults = new HashMap<>();
//        fieldResults.put("legalName", ExtractionFieldResult.builder()
//                .fieldName("legalName").value("Test Corp").evidenceText("Header")
//                .validationStatus(ExtractionValidationStatus.PASS)
//                .managerReviewStatus(ExtractionReviewStatus.ACCEPTED).build());
//        fieldResults.put("industries", ExtractionFieldResult.builder()
//                .fieldName("industries").value(List.of("Tech"))
//                .validationStatus(ExtractionValidationStatus.PASS)
//                .managerReviewStatus(ExtractionReviewStatus.ACCEPTED).build());
//
//        ExtractedCompanyData data = ExtractedCompanyData.builder()
//                .legalName("Test Corp")
//                .industries(List.of("Tech"))
//                .build();
//
//        return AiExtractionCache.builder()
//                .id(id)
//                .importJobId(1L)
//                .rawDocumentId("doc-1")
//                .qualityStatus(status)
//                .fieldResults(fieldResults)
//                .extractedData(data)
//                .createdAt(LocalDateTime.now())
//                .build();
//    }
//
//    // ─────────────────────────────────────────────
//    // CANDIDATE DRAFT GUARD
//    // ─────────────────────────────────────────────
//
//    @Nested
//    @DisplayName("Candidate Draft Generation Guard")
//    class CandidateDraftGuard {
//
//        @Test
//        @DisplayName("Unreviewed extraction blocks candidate draft generation")
//        void unreviewedExtraction_blocksDraft() {
//            AiExtractionCache unreviewedCache = buildExtraction("ext-unreviewed", ExtractionQualityStatus.VALIDATED);
//            when(extractionCacheRepository.findById("ext-unreviewed")).thenReturn(Optional.of(unreviewedCache));
//
//            BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
//                    mergeService.mergeExtractionsIntoCandidate(1L, 1L, List.of("ext-unreviewed"), "test", 100L));
//
//            assertTrue(ex.getMessage().contains("REVIEWED"));
//            assertTrue(ex.getMessage().contains("ext-unreviewed"));
//            verify(candidateRepository, never()).save(any());
//        }
//
//        @Test
//        @DisplayName("NEEDS_REVIEW extraction blocks candidate draft generation")
//        void needsReviewExtraction_blocksDraft() {
//            AiExtractionCache cache = buildExtraction("ext-needs", ExtractionQualityStatus.NEEDS_REVIEW);
//            when(extractionCacheRepository.findById("ext-needs")).thenReturn(Optional.of(cache));
//
//            assertThrows(BusinessValidationException.class, () ->
//                    mergeService.mergeExtractionsIntoCandidate(1L, 1L, List.of("ext-needs"), "test", 100L));
//        }
//
//        @Test
//        @DisplayName("PENDING_VALIDATION extraction blocks candidate draft generation")
//        void pendingValidationExtraction_blocksDraft() {
//            AiExtractionCache cache = buildExtraction("ext-pending", ExtractionQualityStatus.PENDING_VALIDATION);
//            when(extractionCacheRepository.findById("ext-pending")).thenReturn(Optional.of(cache));
//
//            assertThrows(BusinessValidationException.class, () ->
//                    mergeService.mergeExtractionsIntoCandidate(1L, 1L, List.of("ext-pending"), "test", 100L));
//        }
//
//        @Test
//        @DisplayName("REVIEWED extraction allows candidate draft generation")
//        void reviewedExtraction_allowsDraft() {
//            AiExtractionCache reviewedCache = buildExtraction("ext-reviewed", ExtractionQualityStatus.REVIEWED);
//            when(extractionCacheRepository.findById("ext-reviewed")).thenReturn(Optional.of(reviewedCache));
//            when(candidateRepository.save(any())).thenAnswer(i -> {
//                CompanyCandidate c = i.getArgument(0);
//                // Simulate ID assignment
//                return c;
//            });
//
//            // Should not throw
//            assertDoesNotThrow(() ->
//                    mergeService.mergeExtractionsIntoCandidate(1L, 1L, List.of("ext-reviewed"), "test", 100L));
//
//            verify(candidateRepository, times(1)).save(any());
//        }
//
//        @Test
//        @DisplayName("Null qualityStatus (legacy extraction) is allowed through the guard")
//        void nullQualityStatus_allowsDraft() {
//            AiExtractionCache legacyCache = buildExtraction("ext-legacy", null);
//            legacyCache.setQualityStatus(null); // Simulate legacy data
//            when(extractionCacheRepository.findById("ext-legacy")).thenReturn(Optional.of(legacyCache));
//            when(candidateRepository.save(any())).thenAnswer(i -> i.getArgument(0));
//
//            assertDoesNotThrow(() ->
//                    mergeService.mergeExtractionsIntoCandidate(1L, 1L, List.of("ext-legacy"), "test", 100L));
//        }
//
//        @Test
//        @DisplayName("Mixed reviewed and unreviewed extractions blocks draft generation")
//        void mixedExtractions_blocks() {
//            AiExtractionCache reviewed = buildExtraction("ext-ok", ExtractionQualityStatus.REVIEWED);
//            AiExtractionCache unreviewed = buildExtraction("ext-bad", ExtractionQualityStatus.VALIDATED);
//            when(extractionCacheRepository.findById("ext-ok")).thenReturn(Optional.of(reviewed));
//            when(extractionCacheRepository.findById("ext-bad")).thenReturn(Optional.of(unreviewed));
//
//            assertThrows(BusinessValidationException.class, () ->
//                    mergeService.mergeExtractionsIntoCandidate(1L, 1L, List.of("ext-ok", "ext-bad"), "test", 100L));
//        }
//    }
//
//    // ─────────────────────────────────────────────
//    // REVIEWED VALUE USAGE IN MERGE
//    // ─────────────────────────────────────────────
//
//    @Nested
//    @DisplayName("Reviewed Value Usage in Merge")
//    class ReviewedValueUsage {
//
//        @Test
//        @DisplayName("EDITED field uses reviewedValue instead of original value")
//        void editedField_usesReviewedValue() {
//            AiExtractionCache cache = buildExtraction("ext-edited", ExtractionQualityStatus.REVIEWED);
//            cache.getFieldResults().get("legalName").setManagerReviewStatus(ExtractionReviewStatus.EDITED);
//            cache.getFieldResults().get("legalName").setStaffReviewedValue("Corrected Name");
//
//            when(extractionCacheRepository.findById("ext-edited")).thenReturn(Optional.of(cache));
//            when(candidateRepository.save(any())).thenAnswer(i -> i.getArgument(0));
//
//            MergeCandidateResponse response = mergeService.mergeExtractionsIntoCandidate(
//                    1L, 1L, List.of("ext-edited"), "test", 100L);
//
//            // The identity section should contain "Corrected Name" not "Test Corp"
//            assertNotNull(response.getIdentity());
//            assertEquals("Corrected Name", response.getIdentity().get("legalName"));
//        }
//
//        @Test
//        @DisplayName("REJECTED field is excluded from merge (null)")
//        void rejectedField_excluded() {
//            AiExtractionCache cache = buildExtraction("ext-rejected", ExtractionQualityStatus.REVIEWED);
//            cache.getFieldResults().get("legalName").setManagerReviewStatus(ExtractionReviewStatus.REJECTED);
//
//            when(extractionCacheRepository.findById("ext-rejected")).thenReturn(Optional.of(cache));
//            when(candidateRepository.save(any())).thenAnswer(i -> i.getArgument(0));
//
//            MergeCandidateResponse response = mergeService.mergeExtractionsIntoCandidate(
//                    1L, 1L, List.of("ext-rejected"), "test", 100L);
//
//            // legalName should be null since it was rejected
//            Object legalName = response.getIdentity().get("legalName");
//            assertNull(legalName);
//        }
//    }
//}

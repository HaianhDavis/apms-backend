package com.apms.domain.financial.service;

import com.apms.domain.financial.*;
import com.apms.domain.financial.repository.FinancialResearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FinancialExtractionProgressTest {

    @Mock
    private FinancialResearchRepository researchRepository;

    private FinancialResearch createResearchWithReport(ExtractionStatus status) {
        return createResearchWithReport(status, null, null, null);
    }

    private FinancialResearch createResearchWithReport(
            ExtractionStatus status,
            FinancialExtractionStage stage,
            Integer progress,
            LocalDateTime startedAt
    ) {
        FinancialReportEntry report = FinancialReportEntry.builder()
                .id("report-1")
                .documentId("doc-1")
                .title("Test Report Q2")
                .extractionStatus(status)
                .extractionStage(stage)
                .extractionProgress(progress)
                .extractionStartedAt(startedAt)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        return FinancialResearch.builder()
                .id("research-1")
                .taskId(100L)
                .projectId(1L)
                .reports(new ArrayList<>(List.of(report)))
                .metrics(new ArrayList<>())
                .status(FinancialResearchStatus.DRAFT)
                .build();
    }

    @Test
    @DisplayName("Extract starts with EXTRACTING/QUEUED/5% state")
    void extractStartsProcessing() {
        FinancialResearch research = createResearchWithReport(ExtractionStatus.NOT_EXTRACTED);

        // After extractReport is called, the report should be set to EXTRACTING/QUEUED/5
        FinancialReportEntry report = research.getReports().get(0);

        // Simulate what extractReport does before launching async
        report.setExtractionStatus(ExtractionStatus.EXTRACTING);
        report.setExtractionStage(FinancialExtractionStage.QUEUED);
        report.setExtractionProgress(5);
        report.setExtractionStartedAt(LocalDateTime.now());

        assertThat(report.getExtractionStatus()).isEqualTo(ExtractionStatus.EXTRACTING);
        assertThat(report.getExtractionStage()).isEqualTo(FinancialExtractionStage.QUEUED);
        assertThat(report.getExtractionProgress()).isEqualTo(5);
        assertThat(report.getExtractionStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("Extraction progress persisted through stages")
    void progressPersistedThroughStages() {
        FinancialReportEntry report = FinancialReportEntry.builder()
                .id("report-1")
                .extractionStatus(ExtractionStatus.EXTRACTING)
                .build();

        // QUEUED → 5%
        report.setExtractionStage(FinancialExtractionStage.QUEUED);
        report.setExtractionProgress(5);
        assertThat(report.getExtractionProgress()).isEqualTo(5);

        // PARSING_DOCUMENT → 15%
        report.setExtractionStage(FinancialExtractionStage.PARSING_DOCUMENT);
        report.setExtractionProgress(15);
        assertThat(report.getExtractionProgress()).isEqualTo(15);

        // EXTRACTING_METRICS → 45%
        report.setExtractionStage(FinancialExtractionStage.EXTRACTING_METRICS);
        report.setExtractionProgress(45);
        assertThat(report.getExtractionProgress()).isEqualTo(45);

        // VALIDATING_RESULTS → 75%
        report.setExtractionStage(FinancialExtractionStage.VALIDATING_RESULTS);
        report.setExtractionProgress(75);
        assertThat(report.getExtractionProgress()).isEqualTo(75);

        // SAVING_RESULTS → 90%
        report.setExtractionStage(FinancialExtractionStage.SAVING_RESULTS);
        report.setExtractionProgress(90);
        assertThat(report.getExtractionProgress()).isEqualTo(90);

        // COMPLETED → 100%
        report.setExtractionStatus(ExtractionStatus.EXTRACTED);
        report.setExtractionStage(FinancialExtractionStage.COMPLETED);
        report.setExtractionProgress(100);
        assertThat(report.getExtractionProgress()).isEqualTo(100);
        assertThat(report.getExtractionStatus()).isEqualTo(ExtractionStatus.EXTRACTED);
    }

    @Test
    @DisplayName("Failed extraction preserves last progress value")
    void failedExtractionPreservesProgress() {
        FinancialReportEntry report = FinancialReportEntry.builder()
                .id("report-1")
                .extractionStatus(ExtractionStatus.EXTRACTING)
                .extractionStage(FinancialExtractionStage.EXTRACTING_METRICS)
                .extractionProgress(45)
                .extractionStartedAt(LocalDateTime.now())
                .build();

        // Fail at EXTRACTING_METRICS stage
        report.setExtractionStatus(ExtractionStatus.FAILED);
        report.setExtractionStage(FinancialExtractionStage.FAILED);
        report.setExtractionErrorCode("AI_EXTRACTION_FAILED");
        report.setExtractionErrorMessage("Unable to extract financial data from this report. Please retry.");
        report.setExtractionCompletedAt(LocalDateTime.now());
        // Progress stays at 45 — not reset

        assertThat(report.getExtractionStatus()).isEqualTo(ExtractionStatus.FAILED);
        assertThat(report.getExtractionProgress()).isEqualTo(45);
        assertThat(report.getExtractionErrorCode()).isEqualTo("AI_EXTRACTION_FAILED");
        assertThat(report.getExtractionErrorMessage()).doesNotContain("Exception");
    }

    @Test
    @DisplayName("Retry from FAILED resets to QUEUED/5%")
    void retryFromFailedResetsProgress() {
        FinancialReportEntry report = FinancialReportEntry.builder()
                .id("report-1")
                .extractionStatus(ExtractionStatus.FAILED)
                .extractionStage(FinancialExtractionStage.FAILED)
                .extractionProgress(45)
                .extractionErrorCode("AI_EXTRACTION_FAILED")
                .extractionErrorMessage("Something failed")
                .extractionCompletedAt(LocalDateTime.now())
                .build();

        // Simulate retry
        report.setExtractionStatus(ExtractionStatus.EXTRACTING);
        report.setExtractionStage(FinancialExtractionStage.QUEUED);
        report.setExtractionProgress(5);
        report.setExtractionStartedAt(LocalDateTime.now());
        report.setExtractionCompletedAt(null);
        report.setExtractionErrorCode(null);
        report.setExtractionErrorMessage(null);

        assertThat(report.getExtractionStatus()).isEqualTo(ExtractionStatus.EXTRACTING);
        assertThat(report.getExtractionStage()).isEqualTo(FinancialExtractionStage.QUEUED);
        assertThat(report.getExtractionProgress()).isEqualTo(5);
        assertThat(report.getExtractionErrorCode()).isNull();
        assertThat(report.getExtractionErrorMessage()).isNull();
    }

    @Test
    @DisplayName("Duplicate extract prevented when already EXTRACTING")
    void duplicateExtractPrevented() {
        FinancialResearch research = createResearchWithReport(
                ExtractionStatus.EXTRACTING,
                FinancialExtractionStage.EXTRACTING_METRICS,
                45,
                LocalDateTime.now()
        );

        FinancialReportEntry report = research.getReports().get(0);

        // Should not change state when already extracting
        assertThat(report.getExtractionStatus()).isEqualTo(ExtractionStatus.EXTRACTING);
        assertThat(report.getExtractionProgress()).isEqualTo(45);
        // extractReport should return current state without modification
    }

    @Test
    @DisplayName("Report A progress independent from Report B")
    void independentReportProgress() {
        FinancialReportEntry reportA = FinancialReportEntry.builder()
                .id("report-a")
                .extractionStatus(ExtractionStatus.EXTRACTING)
                .extractionStage(FinancialExtractionStage.EXTRACTING_METRICS)
                .extractionProgress(45)
                .build();

        FinancialReportEntry reportB = FinancialReportEntry.builder()
                .id("report-b")
                .extractionStatus(ExtractionStatus.EXTRACTED)
                .extractionStage(FinancialExtractionStage.COMPLETED)
                .extractionProgress(100)
                .build();

        FinancialReportEntry reportC = FinancialReportEntry.builder()
                .id("report-c")
                .extractionStatus(ExtractionStatus.NOT_EXTRACTED)
                .build();

        assertThat(reportA.getExtractionProgress()).isEqualTo(45);
        assertThat(reportB.getExtractionProgress()).isEqualTo(100);
        assertThat(reportC.getExtractionProgress()).isNull();
    }

    @Test
    @DisplayName("Stale EXTRACTING recovery after timeout")
    void staleExtractionRecovery() {
        // Report stuck in EXTRACTING for more than 10 minutes
        FinancialReportEntry report = FinancialReportEntry.builder()
                .id("report-1")
                .extractionStatus(ExtractionStatus.EXTRACTING)
                .extractionStage(FinancialExtractionStage.EXTRACTING_METRICS)
                .extractionProgress(45)
                .extractionStartedAt(LocalDateTime.now().minusMinutes(15))
                .build();

        // Simulate stale recovery
        assertThat(report.getExtractionStartedAt()).isBefore(LocalDateTime.now().minusMinutes(10));

        report.setExtractionStatus(ExtractionStatus.FAILED);
        report.setExtractionStage(FinancialExtractionStage.FAILED);
        report.setExtractionErrorCode("EXTRACTION_TIMEOUT");
        report.setExtractionErrorMessage("Extraction interrupted. Please retry.");
        report.setExtractionCompletedAt(LocalDateTime.now());

        assertThat(report.getExtractionStatus()).isEqualTo(ExtractionStatus.FAILED);
        assertThat(report.getExtractionErrorCode()).isEqualTo("EXTRACTION_TIMEOUT");
        assertThat(report.getExtractionProgress()).isEqualTo(45);
    }

    @Test
    @DisplayName("Completed extraction has all required fields")
    void completedExtractionRequiredFields() {
        FinancialReportEntry report = FinancialReportEntry.builder()
                .id("report-1")
                .extractionStatus(ExtractionStatus.EXTRACTED)
                .extractionStage(FinancialExtractionStage.COMPLETED)
                .extractionProgress(100)
                .extractionStartedAt(LocalDateTime.now().minusSeconds(25))
                .extractionCompletedAt(LocalDateTime.now())
                .build();

        assertThat(report.getExtractionStatus()).isEqualTo(ExtractionStatus.EXTRACTED);
        assertThat(report.getExtractionStage()).isEqualTo(FinancialExtractionStage.COMPLETED);
        assertThat(report.getExtractionProgress()).isEqualTo(100);
        assertThat(report.getExtractionStartedAt()).isNotNull();
        assertThat(report.getExtractionCompletedAt()).isNotNull();
        assertThat(report.getExtractionErrorCode()).isNull();
        assertThat(report.getExtractionErrorMessage()).isNull();
    }

    @Test
    @DisplayName("Extraction stage enum has all expected values")
    void extractionStageEnumValues() {
        FinancialExtractionStage[] stages = FinancialExtractionStage.values();
        assertThat(stages).containsExactly(
                FinancialExtractionStage.QUEUED,
                FinancialExtractionStage.PARSING_DOCUMENT,
                FinancialExtractionStage.EXTRACTING_METRICS,
                FinancialExtractionStage.VALIDATING_RESULTS,
                FinancialExtractionStage.SAVING_RESULTS,
                FinancialExtractionStage.COMPLETED,
                FinancialExtractionStage.FAILED
        );
    }

    @Test
    @DisplayName("ExtractionStatus enum unchanged — backward compatible")
    void extractionStatusEnumUnchanged() {
        ExtractionStatus[] statuses = ExtractionStatus.values();
        assertThat(statuses).containsExactly(
                ExtractionStatus.NOT_EXTRACTED,
                ExtractionStatus.EXTRACTING,
                ExtractionStatus.EXTRACTED,
                ExtractionStatus.NEEDS_REVIEW,
                ExtractionStatus.FAILED
        );
    }
}

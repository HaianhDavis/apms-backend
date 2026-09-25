package com.apms.domain.financial.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.dto.ImportJobResponse;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.document.service.DocumentService;
import com.apms.domain.financial.*;
import com.apms.domain.financial.dto.FinancialResearchResponse;
import com.apms.domain.financial.dto.UpdateFinancialReportRequest;
import com.apms.domain.financial.repository.FinancialResearchRepository;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FinancialReportEditAndReplaceTest {

    @Mock
    private FinancialResearchRepository researchRepository;
    @Mock
    private RawDocumentRepository documentRepository;
    @Mock
    private DocumentService documentService;
    @Mock
    private ProjectTaskRepository projectTaskRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private DocumentCompanyMatcher companyMatcher;
    @Mock
    private ProjectTaskSubmissionRepository submissionRepository;
    @Mock
    private FinancialExtractionService extractionService;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private FinancialResearchService researchService;

    private final Long projectId = 1L;
    private final Long taskId = 10L;
    private final Long userId = 42L;

    private FinancialResearch research;
    private FinancialReportEntry reportA;
    private FinancialReportEntry reportB;
    private FinancialMetric metricA;
    private FinancialMetric metricB;

    @BeforeEach
    void setUp() {
        reportA = FinancialReportEntry.builder()
                .id("report-aaa")
                .documentId("doc-old-111")
                .fileName("BIDV_Report_2024.pdf")
                .title("Báo cáo tài chính Q1 2025")
                .publicationDate(LocalDate.of(2025, 4, 15))
                .reportingPeriod(ReportingPeriod.builder().year(2025).period("Q1").periodType(ReportingPeriodType.QUARTER).build())
                .reportType(ReportType.FINANCIAL_STATEMENT)
                .statementScope(StatementScope.CONSOLIDATED)
                .extractionStatus(ExtractionStatus.EXTRACTED)
                .extractionStage(FinancialExtractionStage.COMPLETED)
                .extractionProgress(100)
                .documentContext(DocumentContext.builder().documentName("BIDV_Report_2024.pdf").companyName("BIDV").build())
                .createdAt(LocalDateTime.now().minusDays(2))
                .updatedAt(LocalDateTime.now().minusDays(2))
                .build();

        reportB = FinancialReportEntry.builder()
                .id("report-bbb")
                .documentId("doc-old-222")
                .fileName("Other_Report_2024.pdf")
                .title("Báo cáo tài chính Q2 2025")
                .reportingPeriod(ReportingPeriod.builder().year(2025).period("Q2").periodType(ReportingPeriodType.QUARTER).build())
                .reportType(ReportType.FINANCIAL_STATEMENT)
                .statementScope(StatementScope.CONSOLIDATED)
                .extractionStatus(ExtractionStatus.EXTRACTED)
                .extractionStage(FinancialExtractionStage.COMPLETED)
                .extractionProgress(100)
                .createdAt(LocalDateTime.now().minusDays(1))
                .updatedAt(LocalDateTime.now().minusDays(1))
                .build();

        metricA = FinancialMetric.builder()
                .id("metric-1")
                .label("Doanh thu thuần")
                .rawValue("1000000")
                .period(ReportingPeriod.builder().year(2025).period("Q1").periodType(ReportingPeriodType.QUARTER).build())
                .source(MetricSource.builder().reportEntryId("report-aaa").documentId("doc-old-111").build())
                .build();

        metricB = FinancialMetric.builder()
                .id("metric-2")
                .label("Lợi nhuận sau thuế")
                .rawValue("200000")
                .period(ReportingPeriod.builder().year(2025).period("Q2").periodType(ReportingPeriodType.QUARTER).build())
                .source(MetricSource.builder().reportEntryId("report-bbb").documentId("doc-old-222").build())
                .build();

        research = FinancialResearch.builder()
                .id("research-1")
                .projectId(projectId)
                .taskId(taskId)
                .status(FinancialResearchStatus.DRAFT)
                .reports(new ArrayList<>(List.of(reportA, reportB)))
                .metrics(new ArrayList<>(List.of(metricA, metricB)))
                .build();
    }

    @Test
    @DisplayName("Test 1: Metadata-only update changes title and preserves extraction and metrics")
    void testMetadataOnlyUpdate_PreservesExtractionAndMetrics() {
        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(researchRepository.save(any(FinancialResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateFinancialReportRequest request = new UpdateFinancialReportRequest();
        request.setTitle("Báo cáo tài chính Q1 2025 (Đã chỉnh sửa)");

        FinancialResearchResponse response = researchService.updateReport(projectId, taskId, "report-aaa", request);

        FinancialReportEntry updated = response.getReports().stream().filter(r -> r.getId().equals("report-aaa")).findFirst().orElseThrow();
        assertThat(updated.getTitle()).isEqualTo("Báo cáo tài chính Q1 2025 (Đã chỉnh sửa)");
        assertThat(updated.getDocumentId()).isEqualTo("doc-old-111");
        assertThat(updated.getExtractionStatus()).isEqualTo(ExtractionStatus.EXTRACTED);
        assertThat(updated.getDocumentContext()).isNotNull();

        // Metrics for report-aaa must remain intact
        assertThat(response.getMetrics()).hasSize(2);
        assertThat(response.getMetrics().get(0).getId()).isEqualTo("metric-1");
        assertThat(response.getMetrics().get(0).getRawValue()).isEqualTo("1000000");
    }

    @Test
    @DisplayName("Test 2: Reporting period metadata update synchronizes metric periods but preserves values")
    void testReportingPeriodMetadataUpdate_SyncsMetricPeriods() {
        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(researchRepository.save(any(FinancialResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateFinancialReportRequest request = new UpdateFinancialReportRequest();
        request.setTitle("Báo cáo tài chính Q3 2025");
        request.setReportingPeriod(ReportingPeriod.builder().year(2025).period("Q3").periodType(ReportingPeriodType.QUARTER).build());

        FinancialResearchResponse response = researchService.updateReport(projectId, taskId, "report-aaa", request);

        FinancialReportEntry updated = response.getReports().stream().filter(r -> r.getId().equals("report-aaa")).findFirst().orElseThrow();
        assertThat(updated.getReportingPeriod().getPeriod()).isEqualTo("Q3");

        // Associated metric period must be synchronized to Q3
        FinancialMetric updatedMetricA = research.getMetrics().stream().filter(m -> m.getId().equals("metric-1")).findFirst().orElseThrow();
        assertThat(updatedMetricA.getPeriod().getPeriod()).isEqualTo("Q3");
        assertThat(updatedMetricA.getRawValue()).isEqualTo("1000000");

        // Metric B must remain Q2
        FinancialMetric intactMetricB = research.getMetrics().stream().filter(m -> m.getId().equals("metric-2")).findFirst().orElseThrow();
        assertThat(intactMetricB.getPeriod().getPeriod()).isEqualTo("Q2");
    }

    @Test
    @DisplayName("Test 3: Replace source PDF keeps reportId, resets extraction, and removes associated metrics")
    void testReplaceSourcePdf_KeepsReportIdAndResetsExtraction() {
        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(researchRepository.save(any(FinancialResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile newFile = new MockMultipartFile(
                "file", "BIDV_Report_2025_Revised.pdf", "application/pdf", "dummy pdf content".getBytes()
        );

        ImportJobResponse importJob = ImportJobResponse.builder()
                .id(999L)
                .rawDocumentId("doc-new-333")
                .fileName("BIDV_Report_2025_Revised.pdf")
                .build();
        when(documentService.uploadDocument(eq(projectId), eq(taskId), any(), any())).thenReturn(importJob);

        FinancialResearchResponse response = researchService.replaceReportFile(projectId, taskId, "report-aaa", newFile, userId);

        FinancialReportEntry updated = response.getReports().stream().filter(r -> r.getId().equals("report-aaa")).findFirst().orElseThrow();
        // Preserves report identity
        assertThat(updated.getId()).isEqualTo("report-aaa");
        // Updates documentId and fileName
        assertThat(updated.getDocumentId()).isEqualTo("doc-new-333");
        assertThat(updated.getFileName()).isEqualTo("BIDV_Report_2025_Revised.pdf");
        // Resets extraction status
        assertThat(updated.getExtractionStatus()).isEqualTo(ExtractionStatus.NOT_EXTRACTED);
        assertThat(updated.getExtractionStage()).isNull();
        assertThat(updated.getExtractionProgress()).isEqualTo(0);
        assertThat(updated.getDocumentContext()).isNull();

        // Metrics belonging to report-aaa must be removed, but metric-2 (report-bbb) remains
        assertThat(research.getMetrics()).hasSize(1);
        assertThat(research.getMetrics().get(0).getId()).isEqualTo("metric-2");

        // Old document soft-deleted
        verify(documentService, times(1)).deleteDocument(projectId, taskId, "doc-old-111", userId);
    }

    @Test
    @DisplayName("Test 4: Other reports unaffected when one report's document is replaced")
    void testOtherReportsUnaffected() {
        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(researchRepository.save(any(FinancialResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile newFile = new MockMultipartFile(
                "file", "BIDV_New.pdf", "application/pdf", "pdf bytes".getBytes()
        );
        when(documentService.uploadDocument(any(), any(), any(), any())).thenReturn(
                ImportJobResponse.builder().rawDocumentId("doc-new").fileName("BIDV_New.pdf").build()
        );

        FinancialResearchResponse response = researchService.replaceReportFile(projectId, taskId, "report-aaa", newFile, userId);

        FinancialReportEntry repB = response.getReports().stream().filter(r -> r.getId().equals("report-bbb")).findFirst().orElseThrow();
        assertThat(repB.getDocumentId()).isEqualTo("doc-old-222");
        assertThat(repB.getExtractionStatus()).isEqualTo(ExtractionStatus.EXTRACTED);
        assertThat(repB.getExtractionProgress()).isEqualTo(100);

        // Metric for report-bbb preserved
        assertThat(research.getMetrics().stream().anyMatch(m -> m.getId().equals("metric-2"))).isTrue();
    }

    @Test
    @DisplayName("Test 5: Approved report rejects metadata edit and file replacement")
    void testApprovedReport_RejectsEditAndReplacement() {
        reportA.setReviewStatus(FinancialReportReviewStatus.APPROVED);
        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));

        // Metadata edit rejected
        UpdateFinancialReportRequest request = new UpdateFinancialReportRequest();
        request.setTitle("New Title");
        assertThrows(BusinessValidationException.class, () ->
                researchService.updateReport(projectId, taskId, "report-aaa", request)
        );

        // File replacement rejected
        MockMultipartFile newFile = new MockMultipartFile("file", "New.pdf", "application/pdf", "bytes".getBytes());
        assertThrows(BusinessValidationException.class, () ->
                researchService.replaceReportFile(projectId, taskId, "report-aaa", newFile, userId)
        );
    }

    @Test
    @DisplayName("Test 6: Extraction running rejects metadata edit and file replacement")
    void testExtractionRunning_RejectsEditAndReplacement() {
        reportA.setExtractionStatus(ExtractionStatus.EXTRACTING);
        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));

        UpdateFinancialReportRequest request = new UpdateFinancialReportRequest();
        request.setTitle("New Title");
        assertThrows(BusinessValidationException.class, () ->
                researchService.updateReport(projectId, taskId, "report-aaa", request)
        );

        MockMultipartFile newFile = new MockMultipartFile("file", "New.pdf", "application/pdf", "bytes".getBytes());
        assertThrows(BusinessValidationException.class, () ->
                researchService.replaceReportFile(projectId, taskId, "report-aaa", newFile, userId)
        );
    }

    @Test
    @DisplayName("Test 7: Non-PDF or empty file is rejected")
    void testInvalidFile_Rejection() {
        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));

        // Empty file
        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);
        assertThrows(BusinessValidationException.class, () ->
                researchService.replaceReportFile(projectId, taskId, "report-aaa", emptyFile, userId)
        );

        // Non-PDF file
        MockMultipartFile docxFile = new MockMultipartFile("file", "report.docx", "application/vnd.openxmlformats", "docx".getBytes());
        assertThrows(BusinessValidationException.class, () ->
                researchService.replaceReportFile(projectId, taskId, "report-aaa", docxFile, userId)
        );
    }

    @Test
    @DisplayName("Test 8: Reset CHANGES_REQUESTED review status on replacement")
    void testResetChangesRequestedOnReplacement() {
        reportA.setReviewStatus(FinancialReportReviewStatus.CHANGES_REQUESTED);
        reportA.setReviewComment("Please upload updated report with audited numbers");
        reportA.setReviewedBy(99L);
        reportA.setReviewedByName("Manager");
        reportA.setReviewedAt(LocalDateTime.now().minusHours(1));

        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        when(researchRepository.save(any(FinancialResearch.class))).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile newFile = new MockMultipartFile("file", "Audited.pdf", "application/pdf", "pdf bytes".getBytes());
        when(documentService.uploadDocument(any(), any(), any(), any())).thenReturn(
                ImportJobResponse.builder().rawDocumentId("doc-audited").fileName("Audited.pdf").build()
        );

        FinancialResearchResponse response = researchService.replaceReportFile(projectId, taskId, "report-aaa", newFile, userId);

        FinancialReportEntry updated = response.getReports().stream().filter(r -> r.getId().equals("report-aaa")).findFirst().orElseThrow();
        assertThat(updated.getReviewStatus()).isNull();
        assertThat(updated.getReviewComment()).isNull();
        assertThat(updated.getReviewedBy()).isNull();
        assertThat(updated.getReviewedByName()).isNull();
        assertThat(updated.getReviewedAt()).isNull();
    }
}

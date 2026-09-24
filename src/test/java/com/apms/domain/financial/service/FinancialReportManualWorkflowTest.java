package com.apms.domain.financial.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.TaskStatus;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.dto.ImportJobResponse;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.document.service.DocumentService;
import com.apms.domain.financial.*;
import com.apms.domain.financial.dto.*;
import com.apms.domain.financial.repository.FinancialResearchRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.user.UserProfile;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.domain.user.repository.sql.UserProfileRepository;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FinancialReportManualWorkflowTest {

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
    private ProjectTaskSubmissionRepository submissionRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private UserProfileRepository userProfileRepository;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private FinancialExtractionService extractionService;

    @InjectMocks
    private FinancialResearchService researchService;

    private final Long projectId = 1L;
    private final Long taskId = 10L;
    private final Long staffId = 42L;
    private final Long managerId = 99L;

    private FinancialResearch research;
    private Project project;
    private ProjectTask task;
    private ProjectTaskSubmission submission;

    @BeforeEach
    void setUp() {
        project = Project.builder()
                .id(projectId)
                .projectName("Test Project")
                .targetCompanyProfileId("CP-123")
                .build();

        task = ProjectTask.builder()
                .id(taskId)
                .project(project)
                .status(TaskStatus.IN_PROGRESS)
                .targetCompanyProfileId("CP-123")
                .build();

        submission = ProjectTaskSubmission.builder()
                .id(500L)
                .projectTask(task)
                .project(project)
                .status(SubmissionStatus.IN_REVIEW)
                .build();
        submission.setTargetItemIdList(new ArrayList<>());

        research = FinancialResearch.builder()
                .id("res-100")
                .projectId(projectId)
                .taskId(taskId)
                .status(FinancialResearchStatus.DRAFT)
                .targetResearchPeriod(ReportingPeriod.builder().year(2025).period("Q1").periodType(ReportingPeriodType.QUARTER).build())
                .reports(new ArrayList<>())
                .metrics(new ArrayList<>())
                .submittedReportIds(new ArrayList<>())
                .createdAt(LocalDateTime.now().minusDays(1))
                .updatedAt(LocalDateTime.now().minusDays(1))
                .build();

        UserDetailsImpl userDetails = new UserDetailsImpl(
                staffId,
                "staff@apms.com",
                "hash",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_STAFF")),
                true
        );

        Authentication auth = mock(Authentication.class);
        lenient().when(auth.getPrincipal()).thenReturn(userDetails);
        SecurityContext secContext = mock(SecurityContext.class);
        lenient().when(secContext.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(secContext);

        lenient().when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(research));
        lenient().when(researchRepository.save(any(FinancialResearch.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(projectTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        lenient().when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));
        lenient().when(submissionRepository.findByProjectTask_Id(taskId)).thenReturn(Collections.singletonList(submission));
    }

    @Test
    @DisplayName("Test 1: AI report creation requires source document (PDF)")
    void createReport_aiExtraction_requiresPdf() {
        CreateFinancialReportRequest request = CreateFinancialReportRequest.builder()
                .title("AI Report Q1")
                .dataEntryMethod(FinancialDataEntryMethod.AI_EXTRACTION)
                .documentId(null)
                .build();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                researchService.addReport(projectId, taskId, request)
        );
        assertThat(ex.getMessage()).contains("AI Extraction requires a source document");
    }

    @Test
    @DisplayName("Test 2: Manual report creation without PDF succeeds and sets NOT_EXTRACTED")
    void createReport_manual_withoutPdf_succeeds() {
        CreateFinancialReportRequest request = CreateFinancialReportRequest.builder()
                .title("Manual Report Q1")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .documentId(null)
                .reportingPeriod(ReportingPeriod.builder().period("Q1").build())
                .build();

        FinancialResearchResponse response = researchService.addReport(projectId, taskId, request);
        assertThat(response.getReports()).hasSize(1);
        FinancialReportEntry rep = response.getReports().get(0);
        assertThat(rep.getDataEntryMethod()).isEqualTo(FinancialDataEntryMethod.MANUAL);
        assertThat(rep.getDocumentId()).isNull();
        assertThat(rep.getExtractionStatus()).isEqualTo(ExtractionStatus.NOT_EXTRACTED);
        assertThat(rep.getReportingPeriod().getYear()).isEqualTo(2025);
    }

    @Test
    @DisplayName("Test 3: Manual report creation with reference PDF succeeds")
    void createReport_manual_withPdf_succeeds() {
        CreateFinancialReportRequest request = CreateFinancialReportRequest.builder()
                .title("Manual Report Q2 with PDF")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .documentId("doc-reference-999")
                .reportingPeriod(ReportingPeriod.builder().period("Q2").build())
                .build();

        FinancialResearchResponse response = researchService.addReport(projectId, taskId, request);
        assertThat(response.getReports()).hasSize(1);
        FinancialReportEntry rep = response.getReports().get(0);
        assertThat(rep.getDataEntryMethod()).isEqualTo(FinancialDataEntryMethod.MANUAL);
        assertThat(rep.getDocumentId()).isEqualTo("doc-reference-999");
        assertThat(rep.getExtractionStatus()).isEqualTo(ExtractionStatus.NOT_EXTRACTED);
    }

    @Test
    @DisplayName("Test 4: Derives year from task targetResearchPeriod; rejects if null")
    void createReport_derivesYearFromTaskPeriod() {
        // Without targetResearchPeriod and request without year -> exception
        research.setTargetResearchPeriod(null);

        CreateFinancialReportRequest request = CreateFinancialReportRequest.builder()
                .title("Manual Report No Year")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .build();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                researchService.addReport(projectId, taskId, request)
        );
        assertThat(ex.getMessage()).contains("Target research period year is not configured");
    }

    @Test
    @DisplayName("Test 4b: Derives year from task due date when targetResearchPeriod is null")
    void createReport_derivesYearFromTaskDueDate() {
        research.setTargetResearchPeriod(null);
        task.setDueDate(LocalDateTime.of(2025, 12, 31, 23, 59, 59));
        lenient().when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));

        CreateFinancialReportRequest request = CreateFinancialReportRequest.builder()
                .title("Manual Report Auto Year")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .build();

        FinancialResearchResponse response = researchService.addReport(projectId, taskId, request);
        assertThat(response.getReports()).hasSize(1);
        assertThat(response.getReports().get(0).getReportingPeriod().getYear()).isEqualTo(2025);
        assertThat(response.getTargetResearchPeriod()).isNotNull();
        assertThat(response.getTargetResearchPeriod().getYear()).isEqualTo(2025);
    }

    @Test
    @DisplayName("Test 4c: Derives year from task title when dates are null")
    void createReport_derivesYearFromTaskTitle() {
        research.setTargetResearchPeriod(null);
        task.setDueDate(null);
        task.setTitle("Nghiên cứu báo cáo tài chính năm 2024");
        lenient().when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));

        CreateFinancialReportRequest request = CreateFinancialReportRequest.builder()
                .title("Manual Report Title Year")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .build();

        FinancialResearchResponse response = researchService.addReport(projectId, taskId, request);
        assertThat(response.getReports()).hasSize(1);
        assertThat(response.getReports().get(0).getReportingPeriod().getYear()).isEqualTo(2024);
    }

    @Test
    @DisplayName("Test 4d: getResearch auto-heals targetResearchPeriod from task context")
    void getResearch_autoHealsTargetResearchPeriod() {
        research.setTargetResearchPeriod(null);
        task.setDueDate(LocalDateTime.of(2025, 6, 30, 0, 0));
        lenient().when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.of(task));

        Optional<FinancialResearchResponse> responseOpt = researchService.getResearch(projectId, taskId);
        assertThat(responseOpt).isPresent();
        assertThat(responseOpt.get().getTargetResearchPeriod()).isNotNull();
        assertThat(responseOpt.get().getTargetResearchPeriod().getYear()).isEqualTo(2025);
    }

    @Test
    @DisplayName("Test 5: addManualMetric attaches manual metric to manual report")
    void addManualMetric_attachesToManualReport() {
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-1")
                .title("Manual Report")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .extractionStatus(ExtractionStatus.NOT_EXTRACTED)
                .reportingPeriod(ReportingPeriod.builder().year(2025).period("Q1").build())
                .build();
        research.getReports().add(manualReport);

        CreateFinancialMetricRequest metricRequest = CreateFinancialMetricRequest.builder()
                .reportEntryId("rep-manual-1")
                .label("Tổng tài sản")
                .rawValue("1500000000")
                .rawUnit("VND")
                .build();

        FinancialResearchResponse response = researchService.addManualMetric(projectId, taskId, metricRequest);
        assertThat(response.getMetrics()).hasSize(1);
        FinancialMetricResponse m = response.getMetrics().get(0);
        assertThat(m.getInputMethod()).isEqualTo(MetricInputMethod.MANUAL);
        assertThat(m.getSource().getReportEntryId()).isEqualTo("rep-manual-1");
        assertThat(m.getVerificationStatus()).isNull();
        // Ensure manual report extraction status is still NOT_EXTRACTED
        assertThat(research.getReports().get(0).getExtractionStatus()).isEqualTo(ExtractionStatus.NOT_EXTRACTED);
    }

    @Test
    @DisplayName("Test 6: addManualMetric inherits report period if not explicitly provided")
    void addManualMetric_inheritsReportPeriod() {
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-1")
                .title("Manual Report Q3")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .reportingPeriod(ReportingPeriod.builder().year(2025).period("Q3").periodType(ReportingPeriodType.QUARTER).build())
                .build();
        research.getReports().add(manualReport);

        CreateFinancialMetricRequest metricRequest = CreateFinancialMetricRequest.builder()
                .reportEntryId("rep-manual-1")
                .label("Doanh thu thuần")
                .rawValue("500000000")
                .rawUnit("VND")
                .build();

        FinancialResearchResponse response = researchService.addManualMetric(projectId, taskId, metricRequest);
        assertThat(response.getMetrics()).hasSize(1);
        FinancialMetricResponse m = response.getMetrics().get(0);
        assertThat(m.getPeriod().getPeriod()).isEqualTo("Q3");
        assertThat(m.getPeriod().getYear()).isEqualTo(2025);
    }

    @Test
    @DisplayName("Test 7: updateReport syncs metrics period for associated manual metrics")
    void updateReport_syncsMetricsPeriod() {
        ReportingPeriod oldPeriod = ReportingPeriod.builder().year(2025).period("Q1").periodType(ReportingPeriodType.QUARTER).build();
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-1")
                .title("Manual Report Q1")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .reportingPeriod(oldPeriod)
                .build();
        research.getReports().add(manualReport);

        FinancialMetric metric = FinancialMetric.builder()
                .id("met-1")
                .label("Doanh thu thuần")
                .rawValue("500")
                .rawUnit("VND")
                .period(oldPeriod)
                .source(MetricSource.builder().reportEntryId("rep-manual-1").build())
                .build();
        research.getMetrics().add(metric);

        ReportingPeriod newPeriod = ReportingPeriod.builder().year(2025).period("Q2").periodType(ReportingPeriodType.QUARTER).build();
        UpdateFinancialReportRequest updateReq = new UpdateFinancialReportRequest();
        updateReq.setTitle("Manual Report Q2");
        updateReq.setReportingPeriod(newPeriod);

        FinancialResearchResponse response = researchService.updateReport(projectId, taskId, "rep-manual-1", updateReq);
        assertThat(response.getReports().get(0).getTitle()).isEqualTo("Manual Report Q2");
        assertThat(response.getReports().get(0).getReportingPeriod().getPeriod()).isEqualTo("Q2");
        assertThat(response.getMetrics().get(0).getPeriod().getPeriod()).isEqualTo("Q2");
    }

    @Test
    @DisplayName("Test 8: replaceReportFile on manual report preserves metrics and extraction status")
    void replaceReportFile_manualReport_preservesMetricsAndStatus() {
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-1")
                .title("Manual Report")
                .documentId("doc-old-111")
                .fileName("Old_Doc.pdf")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .extractionStatus(ExtractionStatus.NOT_EXTRACTED)
                .build();
        research.getReports().add(manualReport);

        FinancialMetric metric = FinancialMetric.builder()
                .id("met-1")
                .label("Doanh thu thuần")
                .rawValue("500")
                .rawUnit("VND")
                .inputMethod(MetricInputMethod.MANUAL)
                .source(MetricSource.builder().reportEntryId("rep-manual-1").documentId("doc-old-111").build())
                .build();
        research.getMetrics().add(metric);

        MockMultipartFile newFile = new MockMultipartFile("file", "New_Reference.pdf", "application/pdf", "dummy pdf content".getBytes());
        ImportJobResponse importResponse = ImportJobResponse.builder().rawDocumentId("doc-new-222").build();
        when(documentService.uploadDocument(eq(projectId), eq(taskId), eq(newFile), any())).thenReturn(importResponse);

        FinancialResearchResponse response = researchService.replaceReportFile(projectId, taskId, "rep-manual-1", newFile, staffId);

        FinancialReportEntry updatedRep = response.getReports().get(0);
        assertThat(updatedRep.getDocumentId()).isEqualTo("doc-new-222");
        assertThat(updatedRep.getFileName()).isEqualTo("New_Reference.pdf");
        assertThat(updatedRep.getExtractionStatus()).isEqualTo(ExtractionStatus.NOT_EXTRACTED);
        // Manual metric preserved
        assertThat(response.getMetrics()).hasSize(1);
        assertThat(response.getMetrics().get(0).getSource().getDocumentId()).isEqualTo("doc-new-222");
        // Verify deleteDocument was called for old reference document
        verify(documentService).deleteDocument(eq(projectId), eq(taskId), eq("doc-old-111"), eq(staffId));
    }

    @Test
    @DisplayName("Test 9: extractReport on manual report is rejected")
    void extractReport_manualReport_rejected() {
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-1")
                .title("Manual Report")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .build();
        research.getReports().add(manualReport);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                researchService.extractReport(projectId, taskId, "rep-manual-1")
        );
        assertThat(ex.getMessage()).isEqualTo("Cannot run AI extraction on a Manual Entry report.");
    }

    @Test
    @DisplayName("Test 10: reExtractReport on manual report is rejected")
    void reExtractReport_manualReport_rejected() {
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-1")
                .title("Manual Report")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .build();
        research.getReports().add(manualReport);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                researchService.reExtractReport(projectId, taskId, "rep-manual-1")
        );
        assertThat(ex.getMessage()).isEqualTo("Cannot run AI extraction on a Manual Entry report.");
    }

    @Test
    @DisplayName("Test 11: submitForReview with zero metrics on manual report is rejected")
    void submitForReview_manualReport_zeroMetrics_rejected() {
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-1")
                .title("Manual Report")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .extractionStatus(ExtractionStatus.NOT_EXTRACTED)
                .build();
        research.getReports().add(manualReport);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                researchService.submitForReview(projectId, taskId, staffId, Collections.singletonList("rep-manual-1"))
        );
        assertThat(ex.getErrorCode()).isEqualTo("NO_METRICS");
    }

    @Test
    @DisplayName("Test 12: submitForReview with metrics succeeds for manual report without extraction/verification checks")
    void submitForReview_manualReport_withMetrics_bypassesExtractionAndVerificationChecks() {
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-1")
                .title("Manual Report")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .extractionStatus(ExtractionStatus.NOT_EXTRACTED)
                .build();
        research.getReports().add(manualReport);

        FinancialMetric metric = FinancialMetric.builder()
                .id("met-1")
                .label("Doanh thu thuần")
                .rawValue("500")
                .rawUnit("VND")
                .inputMethod(MetricInputMethod.MANUAL)
                .source(MetricSource.builder().reportEntryId("rep-manual-1").build())
                .qualityStatus(MetricQualityStatus.VALID)
                .verificationStatus(MetricVerificationStatus.VERIFIED)
                .build();
        research.getMetrics().add(metric);

        FinancialResearchResponse response = researchService.submitForReview(projectId, taskId, staffId, Collections.singletonList("rep-manual-1"));
        assertThat(response.getStatus()).isEqualTo(FinancialResearchStatus.SUBMITTED);
        assertThat(response.getReports().get(0).getReviewStatus()).isEqualTo(FinancialReportReviewStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("Test 13: reviewReport approves manual report without extraction or AI verification checks")
    void reviewReport_manualReport_approved() {
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-1")
                .title("Manual Report")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .extractionStatus(ExtractionStatus.NOT_EXTRACTED)
                .reviewStatus(FinancialReportReviewStatus.PENDING_REVIEW)
                .build();
        research.getReports().add(manualReport);
        research.setStatus(FinancialResearchStatus.SUBMITTED);
        research.setSubmittedReportIds(Collections.singletonList("rep-manual-1"));

        FinancialMetric metric = FinancialMetric.builder()
                .id("met-1")
                .label("Doanh thu thuần")
                .rawValue("500")
                .rawUnit("VND")
                .inputMethod(MetricInputMethod.MANUAL)
                .source(MetricSource.builder().reportEntryId("rep-manual-1").build())
                .build();
        research.getMetrics().add(metric);

        submission.setTargetItemIdList(Collections.singletonList("rep-manual-1"));

        ReviewFinancialReportRequest reviewReq = new ReviewFinancialReportRequest();
        reviewReq.setStatus(FinancialReportReviewStatus.APPROVED);

        FinancialResearchResponse response = researchService.reviewReport(projectId, taskId, "rep-manual-1", reviewReq);
        assertThat(response.getReports().get(0).getReviewStatus()).isEqualTo(FinancialReportReviewStatus.APPROVED);
        assertThat(response.getStatus()).isEqualTo(FinancialResearchStatus.APPROVED);
    }

    @Test
    @DisplayName("Test 14: reviewReport requests changes on manual report")
    void reviewReport_manualReport_changesRequested() {
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-1")
                .title("Manual Report")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .extractionStatus(ExtractionStatus.NOT_EXTRACTED)
                .reviewStatus(FinancialReportReviewStatus.PENDING_REVIEW)
                .build();
        research.getReports().add(manualReport);
        research.setStatus(FinancialResearchStatus.SUBMITTED);
        research.setSubmittedReportIds(Collections.singletonList("rep-manual-1"));

        FinancialMetric metric = FinancialMetric.builder()
                .id("met-1")
                .label("Doanh thu thuần")
                .rawValue("500")
                .rawUnit("VND")
                .inputMethod(MetricInputMethod.MANUAL)
                .source(MetricSource.builder().reportEntryId("rep-manual-1").build())
                .build();
        research.getMetrics().add(metric);

        submission.setTargetItemIdList(Collections.singletonList("rep-manual-1"));

        ReviewFinancialReportRequest reviewReq = new ReviewFinancialReportRequest();
        reviewReq.setStatus(FinancialReportReviewStatus.CHANGES_REQUESTED);
        reviewReq.setReason("Please check the Revenue numbers again.");

        FinancialResearchResponse response = researchService.reviewReport(projectId, taskId, "rep-manual-1", reviewReq);
        assertThat(response.getReports().get(0).getReviewStatus()).isEqualTo(FinancialReportReviewStatus.CHANGES_REQUESTED);
        assertThat(response.getStatus()).isEqualTo(FinancialResearchStatus.CHANGES_REQUESTED);
        assertThat(response.getReviewReason()).contains("Please check the Revenue numbers again.");
    }

    @Test
    @DisplayName("Test 15: Legacy report with null dataEntryMethod resolves as AI_EXTRACTION")
    void legacyReport_nullDataEntryMethod_resolvesAsAiExtraction() {
        FinancialReportEntry legacyReport = new FinancialReportEntry();
        legacyReport.setDataEntryMethod(null);
        assertThat(legacyReport.getDataEntryMethod()).isEqualTo(FinancialDataEntryMethod.AI_EXTRACTION);
    }

    @Test
    @DisplayName("Test 16: saveManualMetricsBatch persists only meaningful metrics with inputMethod MANUAL and null verificationStatus")
    void saveManualMetricsBatch_partialSave_persistsOnlyMeaningfulMetrics() {
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-1")
                .title("Manual Report")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .reportingPeriod(ReportingPeriod.builder().year(2026).period("Q1").build())
                .build();
        research.getReports().add(manualReport);

        List<CreateFinancialMetricRequest> batchList = List.of(
                CreateFinancialMetricRequest.builder().metricCode("TOTAL_ASSETS").label("Tổng tài sản").rawValue("1000000").rawUnit("MILLION_VND").build(),
                CreateFinancialMetricRequest.builder().metricCode("CURRENT_ASSETS").label("Tài sản ngắn hạn").rawValue("400000").rawUnit("MILLION_VND").build(),
                CreateFinancialMetricRequest.builder().metricCode("TOTAL_LIABILITIES").label("Tổng nợ phải trả").rawValue("500000").rawUnit("MILLION_VND").build(),
                CreateFinancialMetricRequest.builder().metricCode("NET_REVENUE").label("Doanh thu thuần").rawValue("800000").rawUnit("MILLION_VND").build(),
                CreateFinancialMetricRequest.builder().metricCode("PROFIT_AFTER_TAX").label("Lợi nhuận sau thuế").rawValue("100000").rawUnit("MILLION_VND").build(),
                // Blank / empty rows that should be ignored
                CreateFinancialMetricRequest.builder().metricCode("INVENTORIES").label("Hàng tồn kho").rawValue("").rawUnit("MILLION_VND").build(),
                CreateFinancialMetricRequest.builder().metricCode("GROSS_PROFIT").label("Lợi nhuận gộp").rawValue("   ").rawUnit("MILLION_VND").build()
        );

        BatchCreateFinancialMetricsRequest batchReq = BatchCreateFinancialMetricsRequest.builder()
                .metrics(batchList)
                .build();

        FinancialResearchResponse response = researchService.saveManualMetricsBatch(projectId, taskId, "rep-manual-1", batchReq);

        assertThat(response.getMetrics()).hasSize(5);
        for (FinancialMetricResponse m : response.getMetrics()) {
            assertThat(m.getInputMethod()).isEqualTo(MetricInputMethod.MANUAL);
            assertThat(m.getVerificationStatus()).isNull();
            assertThat(m.getSource().getReportEntryId()).isEqualTo("rep-manual-1");
            assertThat(m.getSource().getStatementType()).isNotNull();
        }
    }

    @Test
    @DisplayName("Test 17: saveManualMetricsBatch rejects AI_EXTRACTION report")
    void saveManualMetricsBatch_rejectsAiReport() {
        FinancialReportEntry aiReport = FinancialReportEntry.builder()
                .id("rep-ai-1")
                .title("AI Report")
                .dataEntryMethod(FinancialDataEntryMethod.AI_EXTRACTION)
                .build();
        research.getReports().add(aiReport);

        BatchCreateFinancialMetricsRequest batchReq = BatchCreateFinancialMetricsRequest.builder()
                .metrics(List.of(CreateFinancialMetricRequest.builder().metricCode("TOTAL_ASSETS").label("Tổng tài sản").rawValue("1000000").rawUnit("MILLION_VND").build()))
                .build();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                researchService.saveManualMetricsBatch(projectId, taskId, "rep-ai-1", batchReq));
        assertThat(ex.getMessage()).contains("Batch metrics entry is only supported for MANUAL reports.");
    }

    @Test
    @DisplayName("Test 18: saveManualMetricsBatch atomic validation rejects all if any value is invalid")
    void saveManualMetricsBatch_atomicValidation_rejectsAllWhenOneInvalid() {
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-1")
                .title("Manual Report")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .reportingPeriod(ReportingPeriod.builder().year(2026).period("Q1").build())
                .build();
        research.getReports().add(manualReport);

        List<CreateFinancialMetricRequest> batchList = List.of(
                CreateFinancialMetricRequest.builder().metricCode("TOTAL_ASSETS").label("Tổng tài sản").rawValue("1000000").rawUnit("MILLION_VND").build(),
                CreateFinancialMetricRequest.builder().metricCode("CURRENT_ASSETS").label("Tài sản ngắn hạn").rawValue("400000").rawUnit("MILLION_VND").build(),
                CreateFinancialMetricRequest.builder().metricCode("TOTAL_LIABILITIES").label("Tổng nợ phải trả").rawValue("invalid-number").rawUnit("MILLION_VND").build()
        );

        BatchCreateFinancialMetricsRequest batchReq = BatchCreateFinancialMetricsRequest.builder()
                .metrics(batchList)
                .build();

        assertThrows(BusinessValidationException.class, () ->
                researchService.saveManualMetricsBatch(projectId, taskId, "rep-manual-1", batchReq));

        // 0 metrics persisted
        assertThat(research.getMetrics()).isEmpty();
    }

    @Test
    @DisplayName("Test 19: saveManualMetricsBatch updates existing metric in-place")
    void saveManualMetricsBatch_updatesExistingMetricInPlace() {
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-1")
                .title("Manual Report")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .reportingPeriod(ReportingPeriod.builder().year(2026).period("Q1").build())
                .build();
        research.getReports().add(manualReport);

        FinancialMetric existing = FinancialMetric.builder()
                .id("met-existing")
                .label("Tổng tài sản")
                .metricCode("TOTAL_ASSETS")
                .normalizedKey("tongtaisan")
                .rawValue("500000")
                .rawUnit("MILLION_VND")
                .inputMethod(MetricInputMethod.MANUAL)
                .source(MetricSource.builder().reportEntryId("rep-manual-1").build())
                .build();
        research.getMetrics().add(existing);

        BatchCreateFinancialMetricsRequest batchReq = BatchCreateFinancialMetricsRequest.builder()
                .metrics(List.of(
                        CreateFinancialMetricRequest.builder().metricCode("TOTAL_ASSETS").label("Tổng tài sản").rawValue("888888").rawUnit("MILLION_VND").build()
                ))
                .build();

        FinancialResearchResponse response = researchService.saveManualMetricsBatch(projectId, taskId, "rep-manual-1", batchReq);

        assertThat(response.getMetrics()).hasSize(1);
        assertThat(response.getMetrics().get(0).getId()).isEqualTo("met-existing");
        assertThat(response.getMetrics().get(0).getRawValue()).isEqualTo("888888");
    }

    @Test
    @DisplayName("Test 20: addManualMetric rejects duplicate canonical or normalizedKey metric in same report")
    void addManualMetric_rejectsDuplicate() {
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-1")
                .title("Manual Report")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .reportingPeriod(ReportingPeriod.builder().year(2026).period("Q1").build())
                .build();
        research.getReports().add(manualReport);

        CreateFinancialMetricRequest req1 = CreateFinancialMetricRequest.builder()
                .reportEntryId("rep-manual-1")
                .metricCode("TOTAL_ASSETS")
                .label("Tổng tài sản")
                .rawValue("1000000")
                .rawUnit("MILLION_VND")
                .build();
        researchService.addManualMetric(projectId, taskId, req1);

        // Attempt duplicate
        CreateFinancialMetricRequest req2 = CreateFinancialMetricRequest.builder()
                .reportEntryId("rep-manual-1")
                .metricCode("TOTAL_ASSETS")
                .label("Tổng tài sản")
                .rawValue("2000000")
                .rawUnit("MILLION_VND")
                .build();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                researchService.addManualMetric(projectId, taskId, req2));
        assertThat(ex.getErrorCode()).isEqualTo("DUPLICATE_METRIC");
    }

    @Test
    @DisplayName("Test 21: addManualMetric custom metric persists statementType and inputMethod MANUAL")
    void addManualMetric_customMetric_persistsStatementType() {
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-1")
                .title("Manual Report")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .reportingPeriod(ReportingPeriod.builder().year(2026).period("Q1").build())
                .build();
        research.getReports().add(manualReport);

        CreateFinancialMetricRequest req = CreateFinancialMetricRequest.builder()
                .reportEntryId("rep-manual-1")
                .label("Chỉ số tùy chỉnh đặc biệt")
                .statementType("BALANCE_SHEET")
                .rawValue("99999")
                .rawUnit("MILLION_VND")
                .build();

        FinancialResearchResponse response = researchService.addManualMetric(projectId, taskId, req);

        assertThat(response.getMetrics()).hasSize(1);
        FinancialMetricResponse saved = response.getMetrics().get(0);
        assertThat(saved.getLabel()).isEqualTo("Chỉ số tùy chỉnh đặc biệt");
        assertThat(saved.getInputMethod()).isEqualTo(MetricInputMethod.MANUAL);
        assertThat(saved.getVerificationStatus()).isNull();
        assertThat(saved.getSource().getStatementType()).isEqualTo("BALANCE_SHEET");
    }

    @Test
    @DisplayName("Test 22: addManualMetric and saveManualMetricsBatch rejected when report is APPROVED")
    void addManualMetric_and_batch_rejectedWhenReportApproved() {
        FinancialReportEntry approvedReport = FinancialReportEntry.builder()
                .id("rep-app-1")
                .title("Approved Report")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .reviewStatus(FinancialReportReviewStatus.APPROVED)
                .build();
        research.getReports().add(approvedReport);

        CreateFinancialMetricRequest singleReq = CreateFinancialMetricRequest.builder()
                .reportEntryId("rep-app-1")
                .label("Doanh thu")
                .rawValue("100")
                .rawUnit("MILLION_VND")
                .build();

        assertThrows(BusinessValidationException.class, () ->
                researchService.addManualMetric(projectId, taskId, singleReq));

        BatchCreateFinancialMetricsRequest batchReq = BatchCreateFinancialMetricsRequest.builder()
                .metrics(List.of(singleReq))
                .build();

        assertThrows(BusinessValidationException.class, () ->
                researchService.saveManualMetricsBatch(projectId, taskId, "rep-app-1", batchReq));
    }

    @Test
    @DisplayName("Test 23: Clearing UI input in batch save leaves existing persisted metric unchanged")
    void saveManualMetricsBatch_clearingInputDoesNotDeletePersistedMetric() {
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-1")
                .title("Manual Report")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .build();
        research.getReports().add(manualReport);

        FinancialMetric existingTotalAssets = FinancialMetric.builder()
                .id("met-ta-1")
                .label("Tổng tài sản")
                .metricCode("TOTAL_ASSETS")
                .rawValue("1000000")
                .rawUnit("MILLION_VND")
                .inputMethod(MetricInputMethod.MANUAL)
                .source(MetricSource.builder().reportEntryId("rep-manual-1").build())
                .build();
        research.getMetrics().add(existingTotalAssets);

        // Staff enters NET_REVENUE and leaves TOTAL_ASSETS blank in batch
        List<CreateFinancialMetricRequest> batchList = List.of(
                CreateFinancialMetricRequest.builder().metricCode("TOTAL_ASSETS").label("Tổng tài sản").rawValue("").rawUnit("MILLION_VND").build(),
                CreateFinancialMetricRequest.builder().metricCode("NET_REVENUE").label("Doanh thu thuần").rawValue("500000").rawUnit("MILLION_VND").build()
        );

        BatchCreateFinancialMetricsRequest batchReq = BatchCreateFinancialMetricsRequest.builder()
                .metrics(batchList)
                .build();

        researchService.saveManualMetricsBatch(projectId, taskId, "rep-manual-1", batchReq);

        // Existing TOTAL_ASSETS remains with value 1000000
        FinancialMetric ta = research.getMetrics().stream()
                .filter(m -> "TOTAL_ASSETS".equals(m.getMetricCode()))
                .findFirst().orElseThrow();
        assertThat(ta.getRawValue()).isEqualTo("1000000");

        // And NET_REVENUE was added
        FinancialMetric rev = research.getMetrics().stream()
                .filter(m -> "NET_REVENUE".equals(m.getMetricCode()))
                .findFirst().orElseThrow();
        assertThat(rev.getRawValue()).isEqualTo("500000");
    }

    @Test
    @DisplayName("Test 24: Explicit removeMetric removes the metric completely")
    void removeMetric_explicitDeleteSucceeds() {
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-1")
                .title("Manual Report")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .build();
        research.getReports().add(manualReport);

        FinancialMetric existing = FinancialMetric.builder()
                .id("met-to-delete")
                .label("Tổng tài sản")
                .metricCode("TOTAL_ASSETS")
                .rawValue("1000000")
                .rawUnit("MILLION_VND")
                .inputMethod(MetricInputMethod.MANUAL)
                .source(MetricSource.builder().reportEntryId("rep-manual-1").build())
                .build();
        research.getMetrics().add(existing);

        researchService.removeMetric(projectId, taskId, "met-to-delete");
        assertThat(research.getMetrics()).noneMatch(m -> "met-to-delete".equals(m.getId()));
    }

    @Test
    @DisplayName("Test 25: Batch save Banking metrics saves with MANUAL inputMethod and BANKING category")
    void saveManualMetricsBatch_bankingMetrics() {
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-1")
                .title("Manual Report")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .build();
        research.getReports().add(manualReport);

        List<CreateFinancialMetricRequest> batchList = List.of(
                CreateFinancialMetricRequest.builder().metricCode("CUSTOMER_LOANS").label("Cho vay khách hàng").rawValue("2500000").rawUnit("MILLION_VND").build(),
                CreateFinancialMetricRequest.builder().metricCode("CUSTOMER_DEPOSITS").label("Tiền gửi của khách hàng").rawValue("3000000").rawUnit("MILLION_VND").build()
        );

        BatchCreateFinancialMetricsRequest batchReq = BatchCreateFinancialMetricsRequest.builder()
                .metrics(batchList)
                .build();

        researchService.saveManualMetricsBatch(projectId, taskId, "rep-manual-1", batchReq);

        FinancialMetric loans = research.getMetrics().stream()
                .filter(m -> "CUSTOMER_LOANS".equals(m.getMetricCode()))
                .findFirst().orElseThrow();
        assertThat(loans.getInputMethod()).isEqualTo(MetricInputMethod.MANUAL);
        assertThat(loans.getVerificationStatus()).isNull();
        assertThat(loans.getSource().getStatementType()).isEqualTo("BANKING");
    }

    @Test
    @DisplayName("Test 26: Batch save Ratio metric saves with PERCENT unit and normalized value")
    void saveManualMetricsBatch_ratioMetric() {
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-1")
                .title("Manual Report")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .build();
        research.getReports().add(manualReport);

        List<CreateFinancialMetricRequest> batchList = List.of(
                CreateFinancialMetricRequest.builder().metricCode("NPL").label("Tỷ lệ nợ xấu (NPL)").rawValue("2.1").rawUnit("PERCENT").build()
        );

        BatchCreateFinancialMetricsRequest batchReq = BatchCreateFinancialMetricsRequest.builder()
                .metrics(batchList)
                .build();

        researchService.saveManualMetricsBatch(projectId, taskId, "rep-manual-1", batchReq);

        FinancialMetric npl = research.getMetrics().stream()
                .filter(m -> "NPL".equals(m.getMetricCode()))
                .findFirst().orElseThrow();
        assertThat(npl.getInputMethod()).isEqualTo(MetricInputMethod.MANUAL);
        assertThat(npl.getRawUnit()).isEqualTo("PERCENT");
        assertThat(npl.getNormalizedUnit()).isEqualTo("PERCENT");
        assertThat(npl.getNormalizedValue()).isEqualByComparingTo("2.1");
    }

    @Test
    @DisplayName("Test 27: addManualMetric with alias resolves to canonical code and rejects duplicate")
    void addManualMetric_aliasResolvesToCanonicalAndRejectsDuplicate() {
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-1")
                .title("Manual Report")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .build();
        research.getReports().add(manualReport);

        FinancialMetric existing = FinancialMetric.builder()
                .id("met-ta")
                .label("Tổng tài sản")
                .metricCode("TOTAL_ASSETS")
                .rawValue("1000000")
                .rawUnit("MILLION_VND")
                .inputMethod(MetricInputMethod.MANUAL)
                .source(MetricSource.builder().reportEntryId("rep-manual-1").build())
                .build();
        research.getMetrics().add(existing);

        // Staff tries to add custom metric with alias "Tổng cộng tài sản"
        CreateFinancialMetricRequest req = CreateFinancialMetricRequest.builder()
                .reportEntryId("rep-manual-1")
                .label("Tổng cộng tài sản")
                .rawValue("2000000")
                .rawUnit("MILLION_VND")
                .build();

        assertThrows(BusinessValidationException.class, () ->
                researchService.addManualMetric(projectId, taskId, req));
    }

    @Test
    @DisplayName("Test 28: addManualMetric rejects duplicate custom metric with same normalizedKey")
    void addManualMetric_rejectsDuplicateCustomMetric() {
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-1")
                .title("Manual Report")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .build();
        research.getReports().add(manualReport);

        CreateFinancialMetricRequest req1 = CreateFinancialMetricRequest.builder()
                .reportEntryId("rep-manual-1")
                .label("Chi phí chuyển đổi số")
                .rawValue("1200")
                .rawUnit("MILLION_VND")
                .build();
        researchService.addManualMetric(projectId, taskId, req1);

        CreateFinancialMetricRequest req2 = CreateFinancialMetricRequest.builder()
                .reportEntryId("rep-manual-1")
                .label("Chi Phí Chuyển Đổi Số")
                .rawValue("1500")
                .rawUnit("MILLION_VND")
                .build();

        assertThrows(BusinessValidationException.class, () ->
                researchService.addManualMetric(projectId, taskId, req2));
    }

    @Test
    @DisplayName("Test 29: Tightened editability: rejected when report is PENDING_REVIEW or research is SUBMITTED")
    void editability_rejectedWhenPendingReviewOrSubmitted() {
        FinancialReportEntry pendingReport = FinancialReportEntry.builder()
                .id("rep-pending-1")
                .title("Pending Report")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .reviewStatus(FinancialReportReviewStatus.PENDING_REVIEW)
                .build();
        research.getReports().add(pendingReport);

        CreateFinancialMetricRequest req = CreateFinancialMetricRequest.builder()
                .reportEntryId("rep-pending-1")
                .label("Tiền và tương đương tiền")
                .rawValue("100")
                .rawUnit("MILLION_VND")
                .build();

        // Report is PENDING_REVIEW
        assertThrows(BusinessValidationException.class, () ->
                researchService.addManualMetric(projectId, taskId, req));

        // Now research is SUBMITTED
        pendingReport.setReviewStatus(null);
        research.setStatus(FinancialResearchStatus.SUBMITTED);
        assertThrows(BusinessValidationException.class, () ->
                researchService.addManualMetric(projectId, taskId, req));
    }

    @Test
    @DisplayName("Test 30: Tightened editability: allowed when research and report are in CHANGES_REQUESTED")
    void editability_allowedWhenChangesRequested() {
        research.setStatus(FinancialResearchStatus.CHANGES_REQUESTED);
        FinancialReportEntry revisionReport = FinancialReportEntry.builder()
                .id("rep-rev-1")
                .title("Revision Report")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .reviewStatus(FinancialReportReviewStatus.CHANGES_REQUESTED)
                .build();
        research.getReports().add(revisionReport);

        CreateFinancialMetricRequest req = CreateFinancialMetricRequest.builder()
                .reportEntryId("rep-rev-1")
                .label("Tiền và tương đương tiền")
                .rawValue("100")
                .rawUnit("MILLION_VND")
                .build();

        FinancialResearchResponse resp = researchService.addManualMetric(projectId, taskId, req);
        assertThat(resp).isNotNull();
        assertThat(research.getMetrics()).hasSize(1);
    }

    @Test
    @DisplayName("Test 31: extractReport on AI report without document throws DOCUMENT_REQUIRED")
    void extractReport_aiReportWithoutDocument_rejected() {
        FinancialReportEntry aiReport = FinancialReportEntry.builder()
                .id("rep-ai-no-doc")
                .title("AI Report Without Doc")
                .dataEntryMethod(FinancialDataEntryMethod.AI_EXTRACTION)
                .documentId(null)
                .build();
        research.getReports().add(aiReport);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                researchService.extractReport(projectId, taskId, "rep-ai-no-doc")
        );
        assertThat(ex.getErrorCode()).isEqualTo("DOCUMENT_REQUIRED");
    }

    @Test
    @DisplayName("Test 32: reExtractReport on AI report without document throws DOCUMENT_REQUIRED")
    void reExtractReport_aiReportWithoutDocument_rejected() {
        FinancialReportEntry aiReport = FinancialReportEntry.builder()
                .id("rep-ai-no-doc")
                .title("AI Report Without Doc")
                .dataEntryMethod(FinancialDataEntryMethod.AI_EXTRACTION)
                .documentId(null)
                .build();
        research.getReports().add(aiReport);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                researchService.reExtractReport(projectId, taskId, "rep-ai-no-doc")
        );
        assertThat(ex.getErrorCode()).isEqualTo("DOCUMENT_REQUIRED");
    }

    @Test
    @DisplayName("Test 33: replaceReportFile on manual report without prior document attaches PDF and preserves metrics")
    void replaceReportFile_manualReportWithoutPriorDocument_attachesDocumentAndPreservesMetrics() {
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-no-doc")
                .title("Manual Report No Doc")
                .documentId(null)
                .fileName(null)
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .extractionStatus(ExtractionStatus.NOT_EXTRACTED)
                .build();
        research.getReports().add(manualReport);

        FinancialMetric metric = FinancialMetric.builder()
                .id("met-1")
                .label("Doanh thu thuần")
                .rawValue("500")
                .rawUnit("VND")
                .inputMethod(MetricInputMethod.MANUAL)
                .source(MetricSource.builder().reportEntryId("rep-manual-no-doc").build())
                .build();
        research.getMetrics().add(metric);

        MockMultipartFile newFile = new MockMultipartFile("file", "Reference.pdf", "application/pdf", "dummy pdf content".getBytes());
        ImportJobResponse importResponse = ImportJobResponse.builder().rawDocumentId("doc-attached-101").build();
        when(documentService.uploadDocument(eq(projectId), eq(taskId), eq(newFile), any())).thenReturn(importResponse);

        FinancialResearchResponse response = researchService.replaceReportFile(projectId, taskId, "rep-manual-no-doc", newFile, staffId);

        FinancialReportEntry updatedRep = response.getReports().get(0);
        assertThat(updatedRep.getDocumentId()).isEqualTo("doc-attached-101");
        assertThat(updatedRep.getFileName()).isEqualTo("Reference.pdf");
        assertThat(updatedRep.getExtractionStatus()).isEqualTo(ExtractionStatus.NOT_EXTRACTED);
        assertThat(response.getMetrics()).hasSize(1);
        assertThat(response.getMetrics().get(0).getSource().getDocumentId()).isEqualTo("doc-attached-101");
        // No deleteDocument called since oldDocumentId was null
        verify(documentService, never()).deleteDocument(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Test 34: validateAndNormalizeFinancialNumber strictly validates numeric formats and rejects invalid characters or comma placement")
    void test34_validateAndNormalizeFinancialNumber_strictFormats() {
        // Valid cases
        assertThat(FinancialResearchService.validateAndNormalizeFinancialNumber("0", "Test")).isEqualByComparingTo("0");
        assertThat(FinancialResearchService.validateAndNormalizeFinancialNumber("100", "Test")).isEqualByComparingTo("100");
        assertThat(FinancialResearchService.validateAndNormalizeFinancialNumber("-100", "Test")).isEqualByComparingTo("-100");
        assertThat(FinancialResearchService.validateAndNormalizeFinancialNumber("12.5", "Test")).isEqualByComparingTo("12.5");
        assertThat(FinancialResearchService.validateAndNormalizeFinancialNumber("-12.5", "Test")).isEqualByComparingTo("-12.5");
        assertThat(FinancialResearchService.validateAndNormalizeFinancialNumber("0.5", "Test")).isEqualByComparingTo("0.5");
        assertThat(FinancialResearchService.validateAndNormalizeFinancialNumber("-0.5", "Test")).isEqualByComparingTo("-0.5");
        assertThat(FinancialResearchService.validateAndNormalizeFinancialNumber("1,000", "Test")).isEqualByComparingTo("1000");
        assertThat(FinancialResearchService.validateAndNormalizeFinancialNumber("1,000,000", "Test")).isEqualByComparingTo("1000000");
        assertThat(FinancialResearchService.validateAndNormalizeFinancialNumber("-1,000.50", "Test")).isEqualByComparingTo("-1000.50");
        assertThat(FinancialResearchService.validateAndNormalizeFinancialNumber("1,234.56", "Test")).isEqualByComparingTo("1234.56");
        assertThat(FinancialResearchService.validateAndNormalizeFinancialNumber("-1,234.56", "Test")).isEqualByComparingTo("-1234.56");

        // Null / blank is unentered (returns null)
        assertThat(FinancialResearchService.validateAndNormalizeFinancialNumber(null, "Test")).isNull();
        assertThat(FinancialResearchService.validateAndNormalizeFinancialNumber("", "Test")).isNull();
        assertThat(FinancialResearchService.validateAndNormalizeFinancialNumber("   ", "Test")).isNull();

        // Invalid cases
        List<String> invalidInputs = List.of(
                "1,2,3",
                "12,,34",
                "1,00,000",
                ",100",
                "100,",
                ".5",
                "1e3",
                "abc",
                "12 triệu",
                "1 tỷ",
                "100 USD",
                "NaN",
                "Infinity",
                "-Infinity",
                "--",
                "N/A",
                "unknown"
        );

        for (String invalid : invalidInputs) {
            BusinessValidationException ex = assertThrows(
                    BusinessValidationException.class,
                    () -> FinancialResearchService.validateAndNormalizeFinancialNumber(invalid, "Chỉ số test"),
                    "Expected invalid input to be rejected: " + invalid
            );
            assertThat(ex.getMessage()).contains("Giá trị chỉ số tài chính phải là số");
        }
    }

    @Test
    @DisplayName("Test 35: saveManualMetricsBatch accepts valid formatted numbers and rejects malformed values with atomic rollback")
    void test35_saveManualMetricsBatch_numericValidation() {
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-num-1")
                .title("Manual Report Numeric")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .reportingPeriod(ReportingPeriod.builder().year(2026).period("Q1").build())
                .build();
        research.getReports().add(manualReport);

        // 1. Successful batch with formatted thousands and decimals
        List<CreateFinancialMetricRequest> batchList = List.of(
                CreateFinancialMetricRequest.builder().metricCode("TOTAL_ASSETS").label("Tổng tài sản").rawValue("1,000,000").rawUnit("MILLION_VND").build(),
                CreateFinancialMetricRequest.builder().metricCode("CURRENT_ASSETS").label("Tài sản ngắn hạn").rawValue("-1,000.50").rawUnit("MILLION_VND").build(),
                CreateFinancialMetricRequest.builder().metricCode("CASH_AND_EQUIVALENTS").label("Tiền và tương đương tiền").rawValue("0").rawUnit("MILLION_VND").build()
        );
        BatchCreateFinancialMetricsRequest req = BatchCreateFinancialMetricsRequest.builder().metrics(batchList).build();

        FinancialResearchResponse response = researchService.saveManualMetricsBatch(projectId, taskId, "rep-manual-num-1", req);
        assertThat(response.getMetrics()).hasSize(3);
        FinancialMetricResponse mTotal = response.getMetrics().stream().filter(m -> "TOTAL_ASSETS".equals(m.getMetricCode())).findFirst().orElseThrow();
        // 1,000,000 MILLION_VND = 1,000,000,000,000 VND
        assertThat(mTotal.getNormalizedValue()).isEqualTo("1000000000000");
        assertThat(mTotal.getNormalizedUnit()).isEqualTo("VND");

        FinancialMetricResponse mCash = response.getMetrics().stream().filter(m -> "CASH_AND_EQUIVALENTS".equals(m.getMetricCode())).findFirst().orElseThrow();
        assertThat(mCash.getNormalizedValue()).isEqualTo("0");

        // 2. Rejected batch with mixed text (e.g. "12 triệu")
        List<CreateFinancialMetricRequest> invalidBatch = List.of(
                CreateFinancialMetricRequest.builder().metricCode("NET_REVENUE").label("Doanh thu thuần").rawValue("500 triệu").rawUnit("MILLION_VND").build()
        );
        BatchCreateFinancialMetricsRequest badReq = BatchCreateFinancialMetricsRequest.builder().metrics(invalidBatch).build();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                researchService.saveManualMetricsBatch(projectId, taskId, "rep-manual-num-1", badReq));
        assertThat(ex.getMessage()).contains("Giá trị chỉ số tài chính phải là số");
    }

    @Test
    @DisplayName("Test 36: addManualMetric strictly validates numeric values for custom metrics")
    void test36_addManualMetric_validatesNumericValue() {
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-custom-1")
                .title("Manual Report Custom")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .reportingPeriod(ReportingPeriod.builder().year(2026).period("Q1").build())
                .build();
        research.getReports().add(manualReport);

        // Rejected custom metric with invalid value "abc"
        CreateFinancialMetricRequest badReq = CreateFinancialMetricRequest.builder()
                .reportId("rep-manual-custom-1")
                .label("Chi phí chuyển đổi số")
                .rawValue("abc")
                .rawUnit("MILLION_VND")
                .build();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                researchService.addManualMetric(projectId, taskId, badReq));
        assertThat(ex.getMessage()).contains("Giá trị chỉ số tài chính phải là số");

        // Accepted custom metric with negative decimal "-125.5"
        CreateFinancialMetricRequest goodReq = CreateFinancialMetricRequest.builder()
                .reportId("rep-manual-custom-1")
                .label("Chi phí chuyển đổi số")
                .rawValue("-125.5")
                .rawUnit("MILLION_VND")
                .build();

        FinancialResearchResponse response = researchService.addManualMetric(projectId, taskId, goodReq);
        FinancialMetricResponse custom = response.getMetrics().stream()
                .filter(m -> "Chi phí chuyển đổi số".equals(m.getLabel()))
                .findFirst().orElseThrow();
        assertThat(custom.getNormalizedValue()).isEqualTo("-125500000.0");
    }

    @Test
    @DisplayName("Test 37: submitForReview rejects manual report containing malformed numeric values")
    void test37_submitForReview_rejectsMalformedNumericMetrics() {
        FinancialReportEntry manualReport = FinancialReportEntry.builder()
                .id("rep-manual-submit-err")
                .title("Báo cáo tài chính Q1")
                .dataEntryMethod(FinancialDataEntryMethod.MANUAL)
                .reportingPeriod(ReportingPeriod.builder().year(2026).period("Q1").build())
                .build();
        research.getReports().add(manualReport);

        // Legacy / malformed metric directly attached
        FinancialMetric badMetric = FinancialMetric.builder()
                .id("met-bad-1")
                .label("Lợi nhuận ròng")
                .rawValue("12 triệu")
                .rawUnit("VND")
                .inputMethod(MetricInputMethod.MANUAL)
                .source(MetricSource.builder().reportEntryId("rep-manual-submit-err").build())
                .build();
        research.getMetrics().add(badMetric);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                researchService.submitForReview(projectId, taskId, staffId, List.of("rep-manual-submit-err")));
        assertThat(ex.getMessage()).contains("Giá trị chỉ số tài chính phải là số");
        assertThat(ex.getMessage()).contains("Lợi nhuận ròng");
    }
}

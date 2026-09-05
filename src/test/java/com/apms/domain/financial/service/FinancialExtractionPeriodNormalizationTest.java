package com.apms.domain.financial.service;

import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.financial.*;
import com.apms.domain.financial.dto.AiFinancialMetricCandidate;
import com.apms.domain.financial.dto.FinancialMetricResponse;
import com.apms.domain.financial.dto.FinancialResearchResponse;
import com.apms.domain.financial.repository.FinancialResearchRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.user.repository.sql.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FinancialExtractionPeriodNormalizationTest {

    @Mock
    private FinancialResearchRepository researchRepository;
    @Mock
    private RawDocumentRepository documentRepository;
    @Mock
    private ProjectTaskRepository projectTaskRepository;
    @Mock
    private ProjectTaskSubmissionRepository submissionRepository;
    @Mock
    private FinancialExtractionService extractionService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private UserProfileRepository userProfileRepository;

    @InjectMocks
    private FinancialResearchService researchService;

    private FinancialReportEntry q2Report;
    private RawDocument dummyDoc;

    @BeforeEach
    void setUp() {
        q2Report = FinancialReportEntry.builder()
                .id("report-q2-2026")
                .documentId("doc-1")
                .title("Báo cáo tài chính Hợp nhất Q2 2026")
                .reportingPeriod(ReportingPeriod.builder()
                        .year(2026)
                        .period("Q2")
                        .periodType(ReportingPeriodType.QUARTER)
                        .asOfDate("2026-06-30")
                        .build())
                .reviewStatus(null)
                .build();

        dummyDoc = RawDocument.builder()
                .id("doc-1")
                .build();
    }

    @Test
    @DisplayName("Case 1: Report = Q2 2026, AI metric with null period & AS_OF_DATE normalized to Q2 2026 QUARTER")
    void testCase1_ReportQ2_OverridesAiMetricWithNullPeriodAndAsOfDate() {
        AiFinancialMetricCandidate candidate = AiFinancialMetricCandidate.builder()
                .label("Tổng tài sản")
                .rawValue("21017570967636")
                .rawUnit("VND")
                .period(ReportingPeriod.builder()
                        .year(2026)
                        .periodType(ReportingPeriodType.AS_OF_DATE)
                        .period(null)
                        .asOfDate(null)
                        .build())
                .confidence(0.95)
                .build();

        FinancialMetric metric = researchService.mapToMetric(candidate, dummyDoc, q2Report);

        assertThat(metric).isNotNull();
        assertThat(metric.getPeriod()).isNotNull();
        assertThat(metric.getPeriod().getPeriod()).isEqualTo("Q2");
        assertThat(metric.getPeriod().getYear()).isEqualTo(2026);
        assertThat(metric.getPeriod().getPeriodType()).isEqualTo(ReportingPeriodType.QUARTER);
    }

    @Test
    @DisplayName("Case 2: Report = Q2 2026, AI metric with conflicting period Q1 overridden to Q2 2026 QUARTER")
    void testCase2_ReportQ2_OverridesConflictingAiQuarter() {
        AiFinancialMetricCandidate candidate = AiFinancialMetricCandidate.builder()
                .label("Doanh thu thuần")
                .rawValue("8483734481474")
                .rawUnit("VND")
                .period(ReportingPeriod.builder()
                        .year(2026)
                        .period("Q1")
                        .periodType(ReportingPeriodType.QUARTER)
                        .build())
                .confidence(0.95)
                .build();

        FinancialMetric metric = researchService.mapToMetric(candidate, dummyDoc, q2Report);

        assertThat(metric).isNotNull();
        assertThat(metric.getPeriod()).isNotNull();
        // Report reportingPeriod is SOURCE OF TRUTH
        assertThat(metric.getPeriod().getPeriod()).isEqualTo("Q2");
        assertThat(metric.getPeriod().getYear()).isEqualTo(2026);
        assertThat(metric.getPeriod().getPeriodType()).isEqualTo(ReportingPeriodType.QUARTER);
    }

    @Test
    @DisplayName("Case 3: Report = Q2 2026, Income Statement with sourceColumn = 'Lũy kế 6 tháng Năm nay' is detected as cumulative and skipped")
    void testCase3_QuarterlyIncomeStatement_RejectsCumulativeColumn() {
        String cumulativeCol = "Lũy kế 6 tháng Năm nay";
        assertThat(FinancialResearchService.isCumulativeColumn(cumulativeCol)).isTrue();
        assertThat(FinancialResearchService.isIncomeStatementMetric("Doanh thu thuần", "INCOME_STATEMENT")).isTrue();

        // Additional variants check
        assertThat(FinancialResearchService.isCumulativeColumn("Lũy kế từ đầu năm")).isTrue();
        assertThat(FinancialResearchService.isCumulativeColumn("6 Tháng Năm 2026")).isTrue();
        assertThat(FinancialResearchService.isCumulativeColumn("Bán niên 2026")).isTrue();
        assertThat(FinancialResearchService.isCumulativeColumn("YTD 2026")).isTrue();
        assertThat(FinancialResearchService.isCumulativeColumn("Cumulative")).isTrue();
        assertThat(FinancialResearchService.isCumulativeColumn("Full year 2026")).isTrue();
        assertThat(FinancialResearchService.isCumulativeColumn("Cả năm 2026")).isTrue();
    }

    @Test
    @DisplayName("Case 4: Report = Q2 2026, Income Statement with sourceColumn = 'Quý 2 Năm nay' is accepted")
    void testCase4_QuarterlyIncomeStatement_AcceptsQuarterColumn() {
        String quarterCol = "Quý 2 Năm nay";
        assertThat(FinancialResearchService.isCumulativeColumn(quarterCol)).isFalse();
        assertThat(FinancialResearchService.isCumulativeColumn("Quý 2 Năm 2026")).isFalse();
        assertThat(FinancialResearchService.isCumulativeColumn("Số cuối kỳ")).isFalse();

        AiFinancialMetricCandidate candidate = AiFinancialMetricCandidate.builder()
                .label("Doanh thu thuần")
                .originalLabel("Doanh thu thuần về bán hàng và cung cấp dịch vụ")
                .metricCode("NET_REVENUE")
                .statementType("INCOME_STATEMENT")
                .sourceColumn(quarterCol)
                .rawValue("8483734481474")
                .rawUnit("VND")
                .confidence(0.95)
                .build();

        FinancialMetric metric = researchService.mapToMetric(candidate, dummyDoc, q2Report);

        assertThat(metric).isNotNull();
        assertThat(metric.getLabel()).isEqualTo("Doanh thu thuần");
        assertThat(metric.getSource().getSourceColumn()).isEqualTo(quarterCol);
        assertThat(metric.getPeriod().getPeriod()).isEqualTo("Q2");
        assertThat(metric.getPeriod().getYear()).isEqualTo(2026);
    }

    @Test
    @DisplayName("Case 5: Balance Sheet with sourceColumn = 'Số cuối kỳ' accepts Q2 and preserves asOfDate")
    void testCase5_BalanceSheet_SoCuoiKy_PreservesAsOfDate() {
        AiFinancialMetricCandidate candidate = AiFinancialMetricCandidate.builder()
                .label("Tổng tài sản")
                .statementType("BALANCE_SHEET")
                .sourceColumn("Số cuối kỳ")
                .rawValue("21017570967636")
                .rawUnit("VND")
                .period(ReportingPeriod.builder()
                        .year(2026)
                        .periodType(ReportingPeriodType.AS_OF_DATE)
                        .period(null)
                        .asOfDate("2026-06-30")
                        .build())
                .confidence(0.95)
                .build();

        FinancialMetric metric = researchService.mapToMetric(candidate, dummyDoc, q2Report);

        assertThat(metric).isNotNull();
        assertThat(metric.getPeriod().getPeriod()).isEqualTo("Q2");
        assertThat(metric.getPeriod().getYear()).isEqualTo(2026);
        assertThat(metric.getPeriod().getPeriodType()).isEqualTo(ReportingPeriodType.QUARTER);
        assertThat(metric.getPeriod().getAsOfDate()).isEqualTo("2026-06-30");
        assertThat(metric.getSource().getSourceColumn()).isEqualTo("Số cuối kỳ");
    }

    @Test
    @DisplayName("Case 6: getResearch() is read-only: falls back to Q2 2026 in response and does NOT call repository.save()")
    void testCase6_GetResearch_ReadOnlyFallback_DoesNotSaveToDb() {
        Long taskId = 100L;
        Long projectId = 1L;

        // Legacy metric in DB having periodType = AS_OF_DATE and null period
        FinancialMetric legacyMetric = FinancialMetric.builder()
                .id("m-legacy-1")
                .label("Tổng tài sản")
                .rawValue("21017570967636")
                .period(ReportingPeriod.builder()
                        .year(2026)
                        .periodType(ReportingPeriodType.AS_OF_DATE)
                        .period(null)
                        .build())
                .source(MetricSource.builder()
                        .reportEntryId("report-q2-2026")
                        .build())
                .build();

        FinancialResearch existingResearch = FinancialResearch.builder()
                .id("res-1")
                .taskId(taskId)
                .projectId(projectId)
                .status(FinancialResearchStatus.DRAFT)
                .reports(new ArrayList<>(List.of(q2Report)))
                .metrics(new ArrayList<>(List.of(legacyMetric)))
                .build();

        when(researchRepository.findByTaskId(taskId)).thenReturn(Optional.of(existingResearch));
        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.empty());

        FinancialResearchResponse response = researchService.getResearch(projectId, taskId).orElse(null);

        assertThat(response).isNotNull();
        assertThat(response.getMetrics()).hasSize(1);
        FinancialMetricResponse metricResp = response.getMetrics().get(0);

        // Response contains normalized Q2 2026
        assertThat(metricResp.getPeriod()).isNotNull();
        assertThat(metricResp.getPeriod().getPeriod()).isEqualTo("Q2");
        assertThat(metricResp.getPeriod().getYear()).isEqualTo(2026);
        assertThat(metricResp.getPeriod().getPeriodType()).isEqualTo(ReportingPeriodType.QUARTER);

        // CRITICAL: Verify researchRepository.save() was NEVER called during getResearch()
        verify(researchRepository, never()).save(any(FinancialResearch.class));
    }
}

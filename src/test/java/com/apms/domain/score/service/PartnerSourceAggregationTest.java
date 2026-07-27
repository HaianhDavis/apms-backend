package com.apms.domain.score.service;

import com.apms.domain.score.draft.ApprovedSourceReference;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.PartnerCriterionContext;
import com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse;
import com.apms.domain.score.enums.ApprovedSourceType;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.profile.CompanyProfileVersion;
import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import com.apms.domain.rolemetric.entity.RoleMetricRecordVersion;
import com.apms.domain.rolemetric.repository.RoleMetricRecordVersionRepository;
import com.apms.domain.contract.entity.PartnerContractVersion;
import com.apms.domain.contract.repository.sql.PartnerContractVersionRepository;
import com.apms.domain.contract.entity.PartnerContractClauseVersion;
import com.apms.domain.contract.repository.sql.PartnerContractClauseVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PartnerSourceAggregationTest {

    private PartnerEvaluationContextProvider contextProvider;
    private PartnerDataSufficiencyEvaluator evaluator;

    private CompanyProfileVersionRepository companyProfileRepo;
    private RoleMetricRecordVersionRepository metricRepo;
    private PartnerContractVersionRepository contractRepo;
    private PartnerContractClauseVersionRepository clauseRepo;

    @BeforeEach
    void setUp() {
        companyProfileRepo = Mockito.mock(CompanyProfileVersionRepository.class);
        metricRepo = Mockito.mock(RoleMetricRecordVersionRepository.class);
        contractRepo = Mockito.mock(PartnerContractVersionRepository.class);
        clauseRepo = Mockito.mock(PartnerContractClauseVersionRepository.class);

        contextProvider = new PartnerEvaluationContextProvider(
            companyProfileRepo, metricRepo, null, contractRepo, clauseRepo, new ObjectMapper()
        );
        evaluator = new PartnerDataSufficiencyEvaluator(contextProvider);
    }

    @Test
    void testMissingDataYieldsIncomplete() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setEvaluatedRole(com.apms.domain.company.enums.CompanyRole.PARTNER);

        ApprovedSourceReference ref = new ApprovedSourceReference();
        ref.setSourceType(ApprovedSourceType.COMPANY_PROFILE_VERSION);
        ref.setMongoSourceId("cp1");
        ref.setReferenceId("ref1");
        draft.setPinnedSourceReferences(List.of(ref));

        CompanyProfileVersion cpv = CompanyProfileVersion.builder()
                .snapshot(Map.of("name", "TestCorp"))
                .build();
        Mockito.when(companyProfileRepo.findById("cp1")).thenReturn(Optional.of(cpv));

        RoleEvaluationReadinessResponse readiness = evaluator.evaluate(draft);

        assertEquals(EvaluationCompletenessStatus.INCOMPLETE, readiness.getAggregateCompletenessStatus());
        assertFalse(readiness.isStaffMaySubmit());

        PartnerDataSufficiencyEvaluator.SufficiencyStatus opStatus = readiness.getCriterionResults().get("operationalPerformanceScore").getSufficiencyStatus();
        assertEquals(PartnerDataSufficiencyEvaluator.SufficiencyStatus.INCOMPLETE, opStatus);
    }

    @Test
    void testKpiMappingToOperationalPerformance() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setEvaluatedRole(com.apms.domain.company.enums.CompanyRole.PARTNER);

        ApprovedSourceReference ref = new ApprovedSourceReference();
        ref.setSourceType(ApprovedSourceType.ROLE_METRIC_VERSION);
        ref.setSqlSourceId(10L);
        ref.setReferenceId("ref-metric");
        draft.setPinnedSourceReferences(List.of(ref));

        RoleMetricRecordVersion metric = new RoleMetricRecordVersion();
        metric.setMetricKey("sla_uptime_percentage");
        metric.setActualNumericValue(new java.math.BigDecimal("99.9"));
        Mockito.when(metricRepo.findById(10L)).thenReturn(Optional.of(metric));

        RoleEvaluationReadinessResponse readiness = evaluator.evaluate(draft);

        // operationalPerformanceScore should be COMPLETE because sla_uptime_percentage is provided
        assertEquals(PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE, readiness.getCriterionResults().get("operationalPerformanceScore").getSufficiencyStatus());

        PartnerCriterionContext ctx = contextProvider.buildContext(draft, "operationalPerformanceScore");
        assertEquals(1, ctx.getPinnedSources().size());
        assertEquals("sla_uptime_percentage", ctx.getPinnedSources().get(0).get("metricKey"));
        assertEquals("ref-metric", ctx.getPinnedSources().get(0).get("referenceId"));
    }

    @Test
    void testMultipleSourcesAggregateDeterministically() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setEvaluatedRole(com.apms.domain.company.enums.CompanyRole.PARTNER);

        ApprovedSourceReference ref1 = new ApprovedSourceReference();
        ref1.setSourceType(ApprovedSourceType.ROLE_METRIC_VERSION);
        ref1.setSqlSourceId(1L);
        ref1.setReferenceId("ref-A");

        ApprovedSourceReference ref2 = new ApprovedSourceReference();
        ref2.setSourceType(ApprovedSourceType.PARTNER_CONTRACT_VERSION);
        ref2.setSqlSourceId(2L);
        ref2.setReferenceId("ref-B");

        // Pin in arbitrary order
        draft.setPinnedSourceReferences(List.of(ref1, ref2));

        RoleMetricRecordVersion metric = new RoleMetricRecordVersion();
        metric.setMetricKey("revenue_generated");
        metric.setActualNumericValue(new java.math.BigDecimal("5000000"));
        Mockito.when(metricRepo.findById(1L)).thenReturn(Optional.of(metric));

        PartnerContractVersion contract = new PartnerContractVersion();
        contract.setContractTitle("Master Services Agreement");
        Mockito.when(contractRepo.findById(2L)).thenReturn(Optional.of(contract));

        PartnerCriterionContext ctx = contextProvider.buildContext(draft, "businessValueContributionScore");
        assertEquals(2, ctx.getPinnedSources().size());

        // Assert reference IDs are preserved
        assertTrue(ctx.getPinnedSources().stream().anyMatch(s -> "ref-A".equals(s.get("referenceId"))));
        assertTrue(ctx.getPinnedSources().stream().anyMatch(s -> "ref-B".equals(s.get("referenceId"))));

        // Missing data fallback (no complete data for governance)
        RoleEvaluationReadinessResponse readiness = evaluator.evaluate(draft);
        assertEquals(PartnerDataSufficiencyEvaluator.SufficiencyStatus.INCOMPLETE, readiness.getCriterionResults().get("governanceAndRiskScore").getSufficiencyStatus());
    }

    @Test
    void testAllSixCanonicalCriteriaMappings() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setEvaluatedRole(com.apms.domain.company.enums.CompanyRole.PARTNER);

        // Setup sources
        ApprovedSourceReference refCp = new ApprovedSourceReference();
        refCp.setSourceType(ApprovedSourceType.COMPANY_PROFILE_VERSION);
        refCp.setMongoSourceId("cp-1");
        refCp.setReferenceId("ref-cp");

        ApprovedSourceReference refContract = new ApprovedSourceReference();
        refContract.setSourceType(ApprovedSourceType.PARTNER_CONTRACT_CLAUSE_VERSION);
        refContract.setSqlSourceId(101L);
        refContract.setReferenceId("ref-clause");

        ApprovedSourceReference refMetricRev = new ApprovedSourceReference();
        refMetricRev.setSourceType(ApprovedSourceType.ROLE_METRIC_VERSION);
        refMetricRev.setSqlSourceId(201L);
        refMetricRev.setReferenceId("ref-rev");

        ApprovedSourceReference refMetricNps = new ApprovedSourceReference();
        refMetricNps.setSourceType(ApprovedSourceType.ROLE_METRIC_VERSION);
        refMetricNps.setSqlSourceId(202L);
        refMetricNps.setReferenceId("ref-nps");

        draft.setPinnedSourceReferences(List.of(refCp, refContract, refMetricRev, refMetricNps));

        // Mocks
        CompanyProfileVersion cpv = CompanyProfileVersion.builder().snapshot(Map.of()).build();
        Mockito.when(companyProfileRepo.findById("cp-1")).thenReturn(Optional.of(cpv));

        PartnerContractClauseVersion clauseVersion = PartnerContractClauseVersion.builder().clauseType("SLA").build();
        Mockito.when(clauseRepo.findById(101L)).thenReturn(Optional.of(clauseVersion));

        RoleMetricRecordVersion metricRev = new RoleMetricRecordVersion();
        metricRev.setMetricKey("revenue_generated");
        metricRev.setActualNumericValue(new java.math.BigDecimal("1000"));
        Mockito.when(metricRepo.findById(201L)).thenReturn(Optional.of(metricRev));

        RoleMetricRecordVersion metricNps = new RoleMetricRecordVersion();
        metricNps.setMetricKey("nps_score");
        metricNps.setActualNumericValue(new java.math.BigDecimal("90"));
        Mockito.when(metricRepo.findById(202L)).thenReturn(Optional.of(metricNps));

        RoleEvaluationReadinessResponse readiness = evaluator.evaluate(draft);
        Map<String, com.apms.domain.score.dto.draft.CriterionReadinessResult> results = readiness.getCriterionResults();

        // 1. businessValueContributionScore -> COMPLETE (has revenue_generated)
        assertEquals(PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE, results.get("businessValueContributionScore").getSufficiencyStatus());

        // 2. strategicAlignmentScore -> COMPLETE (hasAnyMetric or hasContractClause)
        assertEquals(PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE, results.get("strategicAlignmentScore").getSufficiencyStatus());

        // 3. operationalPerformanceScore -> PARTIAL (hasAnyMetric, but not sla/delivery)
        assertEquals(PartnerDataSufficiencyEvaluator.SufficiencyStatus.PARTIAL, results.get("operationalPerformanceScore").getSufficiencyStatus());

        // 4. capabilityAndComplementarityScore -> COMPLETE (hasCompanyProfile and hasContractClause)
        assertEquals(PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE, results.get("capabilityAndComplementarityScore").getSufficiencyStatus());

        // 5. relationshipQualityScore -> COMPLETE (has nps_score)
        assertEquals(PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE, results.get("relationshipQualityScore").getSufficiencyStatus());

        // 6. governanceAndRiskScore -> COMPLETE (hasContractClause)
        assertEquals(PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE, results.get("governanceAndRiskScore").getSufficiencyStatus());
    }
}

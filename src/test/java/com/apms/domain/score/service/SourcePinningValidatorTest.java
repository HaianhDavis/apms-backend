package com.apms.domain.score.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.contract.repository.sql.PartnerContractClauseVersionRepository;
import com.apms.domain.contract.repository.sql.PartnerContractVersionRepository;
import com.apms.domain.profile.CompanyProfileVersion;
import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import com.apms.domain.rolemetric.repository.RoleMetricEvidenceVersionRepository;
import com.apms.domain.rolemetric.repository.RoleMetricRecordVersionRepository;
import com.apms.domain.score.draft.ApprovedSourceReference;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.SourceSelectionRequest;
import com.apms.domain.score.enums.ApprovedSourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SourcePinningValidatorTest {

    @Mock private RoleMetricRecordVersionRepository metricVersionRepository;
    @Mock private RoleMetricEvidenceVersionRepository evidenceVersionRepository;
    @Mock private PartnerContractVersionRepository contractVersionRepository;
    @Mock private PartnerContractClauseVersionRepository clauseVersionRepository;
    @Mock private CompanyProfileVersionRepository companyProfileVersionRepository;

    @InjectMocks
    private SourcePinningValidator validator;

    private RoleEvaluationDraft draft;

    @BeforeEach
    void setUp() {
        draft = new RoleEvaluationDraft();
        draft.setId("draft-123");
        draft.setProjectId(100L);
        draft.setTargetCompanyId("comp-456");
    }

    @Test
    void testUnsupportedSourceTypesRejected() {
        SourceSelectionRequest req1 = SourceSelectionRequest.builder()
                .sourceType(ApprovedSourceType.RAW_DOCUMENT_SEGMENT)
                .criterionKey("crit1")
                .build();
                
        SourceSelectionRequest req2 = SourceSelectionRequest.builder()
                .sourceType(ApprovedSourceType.EXTERNAL)
                .criterionKey("crit1")
                .build();
                
        SourceSelectionRequest req3 = SourceSelectionRequest.builder()
                .sourceType(ApprovedSourceType.MANUAL_NOTE)
                .criterionKey("crit1")
                .build();

        BusinessValidationException ex1 = assertThrows(BusinessValidationException.class, () ->
                validator.validateAndBuildReferences(List.of(req1), draft));
        assertTrue(ex1.getMessage().contains("unsupported"));
        
        BusinessValidationException ex2 = assertThrows(BusinessValidationException.class, () ->
                validator.validateAndBuildReferences(List.of(req2), draft));
        assertTrue(ex2.getMessage().contains("unsupported"));
        
        BusinessValidationException ex3 = assertThrows(BusinessValidationException.class, () ->
                validator.validateAndBuildReferences(List.of(req3), draft));
        assertTrue(ex3.getMessage().contains("unsupported"));
    }
    
    @Test
    void testRoleMetricVersionResolutionAndAlignment() {
        SourceSelectionRequest req = SourceSelectionRequest.builder()
                .sourceType(ApprovedSourceType.ROLE_METRIC_VERSION)
                .sqlSourceId(1L)
                .criterionKey("operationalPerformanceScore")
                .build();
                
        com.apms.domain.rolemetric.entity.RoleMetricRecordVersion metric = new com.apms.domain.rolemetric.entity.RoleMetricRecordVersion();
        metric.setProjectId(100L);
        metric.setCompanyId("comp-456");
        metric.setVersionNumber(2);
        metric.setStatus(com.apms.domain.rolemetric.enums.RoleMetricStatus.APPROVED);
        
        when(metricVersionRepository.findById(1L)).thenReturn(Optional.of(metric));
        
        List<ApprovedSourceReference> refs = validator.validateAndBuildReferences(List.of(req), draft);
        assertEquals(1, refs.size());
        
        ApprovedSourceReference ref = refs.get(0);
        assertNotNull(ref.getReferenceId()); // server-generated referenceId
        assertEquals(2, ref.getSourceVersionNumber()); // server-generated source version
        assertEquals(ApprovedSourceType.ROLE_METRIC_VERSION, ref.getSourceType());
        assertEquals("operationalPerformanceScore", ref.getCriterionKey());
    }
    
    @Test
    void testRoleMetricVersionAlignmentFailure() {
        SourceSelectionRequest req = SourceSelectionRequest.builder()
                .sourceType(ApprovedSourceType.ROLE_METRIC_VERSION)
                .sqlSourceId(1L)
                .criterionKey("crit1")
                .build();
                
        com.apms.domain.rolemetric.entity.RoleMetricRecordVersion metric = new com.apms.domain.rolemetric.entity.RoleMetricRecordVersion();
        metric.setProjectId(999L); // Mismatched project
        metric.setCompanyId("comp-456");
        metric.setStatus(com.apms.domain.rolemetric.enums.RoleMetricStatus.APPROVED);
        
        when(metricVersionRepository.findById(1L)).thenReturn(Optional.of(metric));
        
        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                validator.validateAndBuildReferences(List.of(req), draft));
        assertTrue(ex.getMessage().contains("Metric version project mismatch"));
    }
    
    @Test
    void testCompanyProfileVersionResolution() {
        SourceSelectionRequest req = SourceSelectionRequest.builder()
                .sourceType(ApprovedSourceType.COMPANY_PROFILE_VERSION)
                .mongoSourceId("profile1")
                .criterionKey("strategicAlignmentScore")
                .build();
                
        CompanyProfileVersion profile = CompanyProfileVersion.builder()
                .id("profile1")
                .companyId("comp-456")
                .version(3)
                .build();
        
        when(companyProfileVersionRepository.findById("profile1")).thenReturn(Optional.of(profile));
        
        List<ApprovedSourceReference> refs = validator.validateAndBuildReferences(List.of(req), draft);
        assertEquals(1, refs.size());
        ApprovedSourceReference ref = refs.get(0);
        assertNotNull(ref.getReferenceId());
        assertEquals(3, ref.getSourceVersionNumber());
    }
    
    @Test
    void testPartnerContractVersionResolution() {
        SourceSelectionRequest req = SourceSelectionRequest.builder()
                .sourceType(ApprovedSourceType.PARTNER_CONTRACT_VERSION)
                .sqlSourceId(2L)
                .criterionKey("capabilityAndComplementarityScore")
                .build();
                
        com.apms.domain.contract.entity.PartnerContractVersion contract = new com.apms.domain.contract.entity.PartnerContractVersion();
        contract.setId(2L);
        contract.setPartnerCompanyId("comp-456");
        contract.setSourceProjectId(100L);
        contract.setVersion(4);
        contract.setReviewStatus(com.apms.domain.contract.enums.ContractReviewStatus.APPROVED);
        
        when(contractVersionRepository.findById(2L)).thenReturn(Optional.of(contract));
        
        List<ApprovedSourceReference> refs = validator.validateAndBuildReferences(List.of(req), draft);
        assertEquals(1, refs.size());
        ApprovedSourceReference ref = refs.get(0);
        assertEquals(4, ref.getSourceVersionNumber());
        assertEquals(2L, ref.getSqlSourceId());
    }
    
    @Test
    void testPartnerContractClauseVersionResolution() {
        SourceSelectionRequest req = SourceSelectionRequest.builder()
                .sourceType(ApprovedSourceType.PARTNER_CONTRACT_CLAUSE_VERSION)
                .sqlSourceId(3L)
                .criterionKey("governanceAndRiskScore")
                .build();
                
        com.apms.domain.contract.entity.PartnerContractClauseVersion clause = new com.apms.domain.contract.entity.PartnerContractClauseVersion();
        clause.setId(3L);
        clause.setPartnerContractVersionId(2L);
        // clause requires parent contract for alignment validation
        com.apms.domain.contract.entity.PartnerContractVersion parentContract = new com.apms.domain.contract.entity.PartnerContractVersion();
        parentContract.setPartnerCompanyId("comp-456");
        parentContract.setSourceProjectId(100L);
        parentContract.setReviewStatus(com.apms.domain.contract.enums.ContractReviewStatus.APPROVED);
        parentContract.setVersion(5);
        
        when(contractVersionRepository.findById(2L)).thenReturn(Optional.of(parentContract));
        
        when(clauseVersionRepository.findById(3L)).thenReturn(Optional.of(clause));
        
        List<ApprovedSourceReference> refs = validator.validateAndBuildReferences(List.of(req), draft);
        assertEquals(1, refs.size());
        ApprovedSourceReference ref = refs.get(0);
        assertEquals(5, ref.getSourceVersionNumber());
        assertEquals(3L, ref.getSqlSourceId());
    }
}

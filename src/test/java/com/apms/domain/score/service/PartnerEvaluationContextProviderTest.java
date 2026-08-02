package com.apms.domain.score.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.profile.CompanyProfileVersion;
import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import com.apms.domain.score.draft.ApprovedSourceReference;
import com.apms.domain.score.draft.EvaluationPeriod;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.draft.RoleEvaluationVersion;
import com.apms.domain.score.dto.draft.PartnerCriterionContext;
import com.apms.domain.score.enums.ApprovedSourceType;
import com.apms.domain.score.repository.mongo.RoleEvaluationVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class PartnerEvaluationContextProviderTest {

    private CompanyProfileVersionRepository companyProfileVersionRepository;
    private ObjectMapper objectMapper;
    private PartnerEvaluationContextProvider provider;

    @BeforeEach
    void setUp() {
        companyProfileVersionRepository = Mockito.mock(CompanyProfileVersionRepository.class);
        objectMapper = new ObjectMapper();

        provider = new PartnerEvaluationContextProvider(
                companyProfileVersionRepository,
                Mockito.mock(com.apms.domain.rolemetric.repository.RoleMetricRecordVersionRepository.class),
                Mockito.mock(com.apms.domain.rolemetric.repository.RoleMetricEvidenceVersionRepository.class),
                Mockito.mock(com.apms.domain.contract.repository.sql.PartnerContractVersionRepository.class),
                Mockito.mock(com.apms.domain.contract.repository.sql.PartnerContractClauseVersionRepository.class),
                Mockito.mock(com.apms.domain.document.repository.mongo.RawDocumentRepository.class),
                Mockito.mock(com.apms.domain.document.service.DocumentTextExtractionService.class),
                objectMapper
        );
    }

    @Test
    void testRejectsEmptyPinnedSources() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setPinnedSourceReferences(new ArrayList<>());

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> provider.buildContext(draft, "crit1"));
        assertTrue(ex.getMessage().contains("has no selected evidence"));
    }

    @Test
    void testRejectsNonPartnerRole() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setEvaluatedRole(CompanyRole.COMPETITOR);

        ApprovedSourceReference ref = new ApprovedSourceReference();
        draft.setPinnedSourceReferences(List.of(ref));

        assertThrows(BusinessValidationException.class, () -> provider.buildContext(draft, "crit1"));
    }

    @Test
    void testRejectsPeriodIrrelevantSource() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setEvaluatedRole(CompanyRole.PARTNER);

        EvaluationPeriod period = new EvaluationPeriod();
        period.setPeriodStart(LocalDate.of(2025, 1, 1));
        period.setPeriodEnd(LocalDate.of(2025, 12, 31));
        draft.setEvaluationPeriod(period);

        ApprovedSourceReference ref = new ApprovedSourceReference();
        ref.setPeriodStart(LocalDate.of(2026, 1, 1)); // outside period
        ref.setPeriodEnd(LocalDate.of(2026, 12, 31));

        draft.setPinnedSourceReferences(List.of(ref));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> provider.buildContext(draft, "crit1"));
        assertTrue(ex.getMessage().contains("Source is not relevant"));
    }
}

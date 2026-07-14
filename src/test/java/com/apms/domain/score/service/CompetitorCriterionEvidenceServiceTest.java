package com.apms.domain.score.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.profile.CompanyProfileVersion;
import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.CompetitorCriterionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompetitorCriterionEvidenceServiceTest {

    private CompetitorCriterionEvidenceService service;
    private CompanyProfileVersionRepository versionRepo;
    private MongoTemplate mongoTemplate;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        versionRepo = mock(CompanyProfileVersionRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        objectMapper = new ObjectMapper();
        service = new CompetitorCriterionEvidenceService(versionRepo, mongoTemplate, objectMapper);
    }

    @Test
    void testPreconditions_PinnedVersionUsed() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setReferenceProfileDocumentId("refDoc");
        draft.setReferenceProfileVersion(1);
        draft.setReferenceCompanyId("refComp");

        draft.setTargetProfileDocumentId("tgtDoc");
        draft.setTargetProfileVersion(2);
        draft.setTargetCompanyId("tgtComp");

        CompanyProfileVersion refV = CompanyProfileVersion.builder()
            .companyId("refComp")
            .snapshot(new HashMap<>())
            .build();

        CompanyProfileVersion tgtV = CompanyProfileVersion.builder()
            .companyId("tgtComp")
            .snapshot(new HashMap<>())
            .build();

        when(versionRepo.findByCompanyProfileIdAndVersion("refDoc", 1)).thenReturn(Optional.of(refV));
        when(versionRepo.findByCompanyProfileIdAndVersion("tgtDoc", 2)).thenReturn(Optional.of(tgtV));

        CompetitorCriterionContext ctx = service.buildContext(draft, "marketPositionScore", LocalDate.now(), LocalDate.now());
        assertNotNull(ctx);
        verify(versionRepo, times(1)).findByCompanyProfileIdAndVersion("refDoc", 1);
        verify(versionRepo, times(1)).findByCompanyProfileIdAndVersion("tgtDoc", 2);
    }

    @Test
    void testPinnedVersionDoesNotFallbackToLatestProfile() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setReferenceProfileDocumentId("refDoc");
        draft.setReferenceProfileVersion(1);
        draft.setReferenceCompanyId("refComp");

        draft.setTargetProfileDocumentId("tgtDoc");
        draft.setTargetProfileVersion(2);
        draft.setTargetCompanyId("tgtComp");

        CompanyProfileVersion refV = CompanyProfileVersion.builder()
            .companyId("refComp")
            .snapshot(new HashMap<>(java.util.Map.of("business", java.util.Map.of("markets", java.util.List.of("PinnedRefMarket")))))
            .build();

        CompanyProfileVersion tgtV = CompanyProfileVersion.builder()
            .companyId("tgtComp")
            .snapshot(new HashMap<>(java.util.Map.of("business", java.util.Map.of("markets", java.util.List.of("PinnedTgtMarket")))))
            .build();

        when(versionRepo.findByCompanyProfileIdAndVersion("refDoc", 1)).thenReturn(Optional.of(refV));
        when(versionRepo.findByCompanyProfileIdAndVersion("tgtDoc", 2)).thenReturn(Optional.of(tgtV));

        CompetitorCriterionContext ctx = service.buildContext(draft, "marketPositionScore", LocalDate.now(), LocalDate.now());

        // Assert that context uses the pinned version
        assertEquals(java.util.List.of("PinnedRefMarket"), ctx.getReferenceFacts().get("markets"));
        assertEquals(java.util.List.of("PinnedTgtMarket"), ctx.getTargetFacts().get("markets"));

        // Assert no fallback method was called (since it doesn't even exist in evidenceService)
        verify(versionRepo, times(1)).findByCompanyProfileIdAndVersion("refDoc", 1);
        verify(versionRepo, times(1)).findByCompanyProfileIdAndVersion("tgtDoc", 2);
        verifyNoMoreInteractions(versionRepo);
    }

    @Test
    void testPreconditions_WrongCompanyRejected() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setReferenceProfileDocumentId("refDoc");
        draft.setReferenceProfileVersion(1);
        draft.setReferenceCompanyId("refComp");

        CompanyProfileVersion refV = CompanyProfileVersion.builder()
            .companyId("wrongComp")
            .snapshot(new HashMap<>())
            .build();

        when(versionRepo.findByCompanyProfileIdAndVersion("refDoc", 1)).thenReturn(Optional.of(refV));

        assertThrows(BusinessValidationException.class, () ->
            service.buildContext(draft, "marketPositionScore", LocalDate.now(), LocalDate.now())
        );
    }

    @Test
    void testPreconditions_MissingPinnedVersionRejected() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setReferenceProfileDocumentId(null);
        draft.setReferenceProfileVersion(null);
        draft.setReferenceCompanyId("refComp");

        assertThrows(BusinessValidationException.class, () ->
            service.buildContext(draft, "marketPositionScore", LocalDate.now(), LocalDate.now())
        );
    }

    @Test
    void testContext_DoesNotContainUnrelatedFields() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setReferenceProfileDocumentId("refDoc");
        draft.setReferenceProfileVersion(1);
        draft.setReferenceCompanyId("refComp");

        draft.setTargetProfileDocumentId("tgtDoc");
        draft.setTargetProfileVersion(2);
        draft.setTargetCompanyId("tgtComp");

        CompanyProfileVersion refV = CompanyProfileVersion.builder()
            .companyId("refComp")
            .build();

        HashMap<String, Object> snapshot = new HashMap<>();
        snapshot.put("id", "document123");
        snapshot.put("companyId", "refComp");
        snapshot.put("contact", new HashMap<>(java.util.Map.of("email", "test@test.com", "phone", "123")));
        snapshot.put("insights", new HashMap<>(java.util.Map.of("swot", "something")));
        snapshot.put("business", new HashMap<>(java.util.Map.of("markets", java.util.List.of("US"))));

        refV.setSnapshot(snapshot);

        CompanyProfileVersion tgtV = CompanyProfileVersion.builder()
            .companyId("tgtComp")
            .snapshot(snapshot)
            .build();

        when(versionRepo.findByCompanyProfileIdAndVersion("refDoc", 1)).thenReturn(Optional.of(refV));
        when(versionRepo.findByCompanyProfileIdAndVersion("tgtDoc", 2)).thenReturn(Optional.of(tgtV));

        CompetitorCriterionContext ctx = service.buildContext(draft, "marketPositionScore", null, null);

        java.util.Map<String, Object> targetFacts = ctx.getTargetFacts();
        assertTrue(targetFacts.containsKey("markets"));
        assertFalse(targetFacts.containsKey("contact"));
        assertFalse(targetFacts.containsKey("insights"));
        assertFalse(targetFacts.containsKey("id"));
        assertFalse(targetFacts.containsKey("companyId"));
    }
}

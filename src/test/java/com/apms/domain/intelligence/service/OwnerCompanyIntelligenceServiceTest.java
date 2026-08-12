package com.apms.domain.intelligence.service;

import com.apms.domain.externaldata.ExternalDataItem;
import com.apms.domain.externaldata.repository.mongo.ExternalDataRepository;
import com.apms.domain.graph.service.GraphService;
import com.apms.domain.intelligence.dto.OwnerCompanyIntelligenceResponse;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.project.repository.sql.ProjectRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OwnerCompanyIntelligenceServiceTest {

    @Test
    void returnsStableResponseWhenArticleHasOnlyOneCompanyReferenceAndNoAiSummary() {
        CompanyProfile profile = CompanyProfile.builder()
                .id("cmc-profile")
                .companyId("cmc-company")
                .identity(CompanyProfile.Identity.builder().legalName("CMC Corporation").build())
                .build();
        ExternalDataItem article = ExternalDataItem.builder()
                .id("cmc-news")
                .title("CMC update")
                .companyProfileId("cmc-profile")
                .relatedCompanyId(null)
                .aiSummary(null)
                .summary(null)
                .build();

        CompanyProfileRepository profiles = mock(CompanyProfileRepository.class);
        ExternalDataRepository externalData = mock(ExternalDataRepository.class);
        GraphService graph = mock(GraphService.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        when(profiles.findByCompanyId("cmc-company")).thenReturn(Optional.of(profile));
        when(externalData.findByCompanyProfileIdOrRelatedCompanyId("cmc-profile", "cmc-company"))
                .thenReturn(List.of(article));
        when(externalData.findAll()).thenReturn(List.of());
        when(projects.findByTargetCompanyProfileIdInAndStatus(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        when(graph.getCompaniesByRelationshipType(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());

        OwnerCompanyIntelligenceResponse response = new OwnerCompanyIntelligenceService(profiles, externalData, graph, projects)
                .get("cmc-company");

        assertNotNull(response.company());
        assertNotNull(response.relationship());
        assertEquals(null, response.relationship().type());
        assertEquals(null, response.relationship().businessImpact());
        assertEquals(null, response.relationship().strategicRelevance());
        assertEquals(null, response.relationship().impactTrend());
        assertNotNull(response.executiveBrief());
        assertNotNull(response.aiSummary());
        assertEquals("NO_DATA", response.aiSummary().status());
        assertTrue(!response.aiSummary().available());
        assertNotNull(response.metadata());
        assertEquals(List.of("cmc-profile"), response.news().get(0).companyIds());
        assertTrue(response.timeline().size() == 1);
        assertNotNull(response.marketExpansion());
        assertNotNull(response.hiring());
        assertNotNull(response.evidence());
    }
}

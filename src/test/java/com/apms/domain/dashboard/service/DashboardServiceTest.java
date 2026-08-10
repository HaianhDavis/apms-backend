package com.apms.domain.dashboard.service;

import com.apms.common.enums.CandidateStatus;
import com.apms.common.enums.ExternalDataCategory;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.dashboard.dto.DashboardSummaryDto;
import com.apms.domain.externaldata.ExternalDataItem;
import com.apms.domain.externaldata.repository.mongo.ExternalDataRepository;
import com.apms.domain.graph.service.GraphService;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.closeness.CompanyRelationshipCloseness;
import com.apms.domain.profile.closeness.CompanyRelationshipClosenessRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.project.repository.sql.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private CompanyProfileRepository profileRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private CompanyCandidateRepository candidateRepository;
    @Mock
    private GraphService graphService;
    @Mock
    private Neo4jClient neo4jClient;
    @Mock
    private OwnerOrganizationService ownerOrganizationService;
    @Mock
    private CompanyRelationshipClosenessRepository closenessRepository;
    @Mock
    private ExternalDataRepository externalDataRepository;

    @InjectMocks
    private DashboardService dashboardService;

    @Mock
    private Neo4jClient.RunnableSpec runnableSpec;
    @Mock
    private Neo4jClient.MappingSpec<Long> mappingSpecLong;
    @Mock
    private Neo4jClient.MappingSpec<String> mappingSpecString;
    @Mock
    private Neo4jClient.RecordFetchSpec<Long> fetchSpecLong;
    @Mock
    private Neo4jClient.RecordFetchSpec<String> fetchSpecString;

    @BeforeEach
    void setUp() {
    }

    @Test
    void testGetSummary() {
        when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("owner-business-id");
        when(ownerOrganizationService.getOwnerCompanyProfileId()).thenReturn("owner-mongo-id");

        // Mock counts
        when(profileRepository.count()).thenReturn(100L);
        when(projectRepository.count()).thenReturn(10L);
        when(candidateRepository.count()).thenReturn(50L);
        when(candidateRepository.countByStatus(CandidateStatus.APPROVED)).thenReturn(30L);
        when(candidateRepository.countByStatus(CandidateStatus.PENDING_REVIEW)).thenReturn(20L);

        // Mock Neo4j relationship counts (simulated by returning same runnable for all)
        when(neo4jClient.query(anyString())).thenAnswer(invocation -> {
            String q = invocation.getArgument(0);
            if (q.contains("RETURN count(r) as cnt")) {
                Neo4jClient.UnboundRunnableSpec rSpec = mock(Neo4jClient.UnboundRunnableSpec.class, withSettings().extraInterfaces(Neo4jClient.RunnableSpec.class));
                when(rSpec.bindAll(anyMap())).thenReturn((Neo4jClient.RunnableSpec) rSpec);
                when(((Neo4jClient.RunnableSpec) rSpec).fetchAs(Long.class)).thenReturn(mappingSpecLong);
                when(mappingSpecLong.mappedBy(any())).thenReturn(fetchSpecLong);
                when(fetchSpecLong.one()).thenReturn(Optional.of(5L));
                return rSpec;
            } else if (q.contains("RETURN DISTINCT t.companyId")) {
                Neo4jClient.UnboundRunnableSpec rSpec = mock(Neo4jClient.UnboundRunnableSpec.class, withSettings().extraInterfaces(Neo4jClient.RunnableSpec.class));
                when(rSpec.bindAll(anyMap())).thenReturn((Neo4jClient.RunnableSpec) rSpec);
                when(((Neo4jClient.RunnableSpec) rSpec).fetchAs(String.class)).thenReturn(mappingSpecString);
                when(mappingSpecString.mappedBy(any())).thenReturn(fetchSpecString);
                when(fetchSpecString.all()).thenReturn(List.of("target-1", "target-2"));
                return rSpec;
            }
            return null;
        });

        CompanyProfile p1 = new CompanyProfile(); p1.setId("t1"); p1.setCompanyId("target-1");
        CompanyProfile p2 = new CompanyProfile(); p2.setId("t2"); p2.setCompanyId("target-2");
        when(profileRepository.findByCompanyIdIn(anyCollection())).thenReturn(List.of(p1, p2));

        CompanyRelationshipCloseness c1 = new CompanyRelationshipCloseness();
        c1.setTargetCompanyProfileId("t1");
        c1.setStars(4);
        when(closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileIdIn(anyString(), anySet()))
                .thenReturn(List.of(c1));

        when(externalDataRepository.countByCategoryAndRelatedCompanyIdIn(any(), anySet())).thenReturn(2L);
        when(externalDataRepository.findTop5ByCategoryAndRelatedCompanyIdInOrderByPublishedAtDesc(any(), anySet()))
                .thenReturn(List.of(ExternalDataItem.builder().id("ext1").category(ExternalDataCategory.RISK).build()));

        DashboardSummaryDto result = dashboardService.getSummary();

        assertThat(result.getTotalCompanyProfiles()).isEqualTo(100L);
        assertThat(result.getPartnerCount()).isEqualTo(5L);
        assertThat(result.getCustomerCount()).isEqualTo(5L);

        assertThat(result.getTotalRelatedCompanies()).isEqualTo(2L); // 2 distinct targets
        assertThat(result.getRelationshipClosenessOverview().getRatedRelationshipCount()).isEqualTo(1L);
        assertThat(result.getRelationshipClosenessOverview().getUnratedRelationshipCount()).isEqualTo(1L);
        
        assertThat(result.getRiskOverview().getTotalCount()).isEqualTo(2L);
        assertThat(result.getRelationshipComposition()).hasSize(5);
        assertThat(result.getRecentActivities()).isEmpty(); // because we mocked findTop5ByRelatedCompanyIdInOrderByPublishedAtDesc to return empty if not stubbed, or we can just leave it as empty
    }
}

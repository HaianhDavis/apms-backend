package com.apms.domain.dashboard.service;

import com.apms.common.enums.ExternalDataCategory;
import com.apms.domain.contract.entity.PartnerContract;
import com.apms.domain.contract.enums.ContractLifecycleStatus;
import com.apms.domain.contract.enums.ContractReviewStatus;
import com.apms.domain.contract.repository.sql.PartnerContractRepository;
import com.apms.domain.dashboard.dto.InsightType;
import com.apms.domain.dashboard.dto.OwnerInsightDto;
import com.apms.domain.externaldata.ExternalDataItem;
import com.apms.domain.externaldata.repository.mongo.ExternalDataRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.closeness.CompanyRelationshipCloseness;
import com.apms.domain.profile.closeness.CompanyRelationshipClosenessRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.service.OwnerOrganizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OwnerInsightsServiceTest {

    @Mock
    private OwnerOrganizationService ownerOrganizationService;
    @Mock
    private Neo4jClient neo4jClient;
    @Mock
    private CompanyProfileRepository profileRepository;
    @Mock
    private ExternalDataRepository externalDataRepository;
    @Mock
    private CompanyRelationshipClosenessRepository closenessRepository;
    @Mock
    private PartnerContractRepository partnerContractRepository;

    @InjectMocks
    private OwnerInsightsService ownerInsightsService;
    
    @Mock
    private Neo4jClient.RunnableSpec runnableSpec;
    @Mock
    private Neo4jClient.MappingSpec<String> mappingSpec;
    @Mock
    private Neo4jClient.RecordFetchSpec<String> fetchSpec;

    @BeforeEach
    void setUp() {
        // leniency can be used if we don't stub everything for every test
    }

    @Test
    void testGetInsights_RiskAndOpportunityMappedCorrectly() {
        when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("owner-business-id");
        when(ownerOrganizationService.getOwnerCompanyProfileId()).thenReturn("owner-mongo-id");

        when(neo4jClient.query(anyString())).thenAnswer(inv -> {
            Neo4jClient.UnboundRunnableSpec rSpec = mock(Neo4jClient.UnboundRunnableSpec.class, withSettings().extraInterfaces(Neo4jClient.RunnableSpec.class));
            when(rSpec.bindAll(anyMap())).thenReturn((Neo4jClient.RunnableSpec) rSpec);
            when(((Neo4jClient.RunnableSpec) rSpec).fetchAs(String.class)).thenReturn(mappingSpec);
            return rSpec;
        });
        when(mappingSpec.mappedBy(any())).thenReturn(fetchSpec);
        when(fetchSpec.all()).thenReturn(List.of("target-uuid-1"));

        CompanyProfile targetProfile = new CompanyProfile();
        targetProfile.setId("target-id");
        targetProfile.setCompanyId("target-uuid-1");
        when(profileRepository.findByCompanyIdIn(List.of("target-uuid-1"))).thenReturn(List.of(targetProfile));

        // Mock External Data
        ExternalDataItem riskItem = ExternalDataItem.builder()
                .id("risk-1")
                .category(ExternalDataCategory.RISK)
                .title("Risk 1")
                .publishedAt(LocalDateTime.now().minusDays(1))
                .build();
        when(externalDataRepository.findTop5ByCategoryAndRelatedCompanyIdInOrderByPublishedAtDesc(
                eq(ExternalDataCategory.RISK), anySet())).thenReturn(List.of(riskItem));
        when(externalDataRepository.findTop5ByCategoryAndRelatedCompanyIdInOrderByPublishedAtDesc(
                eq(ExternalDataCategory.OPPORTUNITY), anySet())).thenReturn(List.of());

        // Mock empty for others
        when(closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileIdIn(anyString(), anySet()))
                .thenReturn(List.of());
        when(partnerContractRepository.findByReferenceCompanyIdAndPartnerCompanyIdInAndReviewStatusAndLifecycleStatus(
                anyString(), anyCollection(), any(), any())).thenReturn(List.of());

        Page<OwnerInsightDto> page = ownerInsightsService.getInsights(null, null, null, null, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(2); // Wait! Unrated creates one!
        
        Optional<OwnerInsightDto> riskDto = page.getContent().stream().filter(d -> d.getType() == InsightType.RISK).findFirst();
        assertThat(riskDto).isPresent();
        assertThat(riskDto.get().getId()).isEqualTo("EXTERNAL_RISK:risk-1");
        assertThat(riskDto.get().getGeneratedAt()).isEqualTo(riskItem.getPublishedAt());
    }

    @Test
    void testGetInsights_UnratedAndLowCloseness() {
        when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("owner-business-id");
        when(ownerOrganizationService.getOwnerCompanyProfileId()).thenReturn("owner-mongo-id");

        when(neo4jClient.query(anyString())).thenAnswer(inv -> {
            Neo4jClient.UnboundRunnableSpec rSpec = mock(Neo4jClient.UnboundRunnableSpec.class, withSettings().extraInterfaces(Neo4jClient.RunnableSpec.class));
            when(rSpec.bindAll(anyMap())).thenReturn((Neo4jClient.RunnableSpec) rSpec);
            when(((Neo4jClient.RunnableSpec) rSpec).fetchAs(String.class)).thenReturn(mappingSpec);
            return rSpec;
        });
        when(mappingSpec.mappedBy(any())).thenReturn(fetchSpec);
        when(fetchSpec.all()).thenReturn(List.of("target-uuid-1", "target-uuid-2", "target-uuid-3"));

        CompanyProfile p1 = new CompanyProfile(); p1.setId("target-id-1"); p1.setCompanyId("target-uuid-1");
        CompanyProfile p2 = new CompanyProfile(); p2.setId("target-id-2"); p2.setCompanyId("target-uuid-2");
        CompanyProfile p3 = new CompanyProfile(); p3.setId("target-id-3"); p3.setCompanyId("target-uuid-3");
        when(profileRepository.findByCompanyIdIn(anyCollection())).thenReturn(List.of(p1, p2, p3));

        // p1 = unrated (missing)
        // p2 = 1 star
        // p3 = 5 star (should not yield insight)
        CompanyRelationshipCloseness c2 = new CompanyRelationshipCloseness();
        c2.setId(2L);
        c2.setTargetCompanyProfileId("target-id-2");
        c2.setStars(1);
        c2.setUpdatedAt(LocalDateTime.now().minusDays(2));

        CompanyRelationshipCloseness c3 = new CompanyRelationshipCloseness();
        c3.setId(3L);
        c3.setTargetCompanyProfileId("target-id-3");
        c3.setStars(5);

        when(closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileIdIn(anyString(), anySet()))
                .thenReturn(List.of(c2, c3));

        when(externalDataRepository.findTop5ByCategoryAndRelatedCompanyIdInOrderByPublishedAtDesc(any(), anySet()))
                .thenReturn(List.of());
        when(partnerContractRepository.findByReferenceCompanyIdAndPartnerCompanyIdInAndReviewStatusAndLifecycleStatus(
                anyString(), anyCollection(), any(), any())).thenReturn(List.of());

        Page<OwnerInsightDto> page = ownerInsightsService.getInsights(null, null, null, null, PageRequest.of(0, 10));

        List<OwnerInsightDto> insights = page.getContent();
        assertThat(insights).hasSize(2);
        
        Optional<OwnerInsightDto> unrated = insights.stream().filter(i -> i.getId().contains("UNRATED")).findFirst();
        assertThat(unrated).isPresent();
        assertThat(unrated.get().getGeneratedAt()).isNull();

        Optional<OwnerInsightDto> low = insights.stream().filter(i -> i.getId().contains("LOW")).findFirst();
        assertThat(low).isPresent();
        assertThat(low.get().getGeneratedAt()).isEqualTo(c2.getUpdatedAt());
    }
}

package com.apms.domain.financial.service;

import com.apms.domain.financial.*;
import com.apms.domain.financial.dto.FinancialResearchResponse;
import com.apms.domain.financial.repository.FinancialResearchRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.dto.CompanyIdentity;
import com.apms.domain.profile.service.CompanyIdentityResolver;
import com.apms.domain.profile.service.CompanyProfileAccessService;
import com.apms.common.security.StaffCompanyScopeEvaluator;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FinancialResearchApprovedCompatibilityTest {

    @Mock
    private FinancialResearchRepository researchRepository;

    @Mock
    private ProjectTaskRepository projectTaskRepository;

    @Mock
    private CompanyIdentityResolver companyIdentityResolver;

    @Mock
    private CompanyProfileAccessService companyProfileAccessService;

    @Mock
    private StaffCompanyScopeEvaluator companyScope;

    @InjectMocks
    private FinancialResearchService researchService;

    private final String mongoId = "6aa26a1ac81945324bad1ee7";
    private final String companyUuid = "0730f796-a94f-42d0-9a9e-92b6acc8dcfb";
    private CompanyProfile profile;
    private CompanyIdentity identity;
    private FinancialResearch approvedResearch;
    private UserDetailsImpl managerUser;

    @BeforeEach
    void setUp() {
        profile = CompanyProfile.builder()
                .id(mongoId)
                .companyId(companyUuid)
                .build();

        identity = CompanyIdentity.builder()
                .profileId(mongoId)
                .companyId(companyUuid)
                .profile(profile)
                .build();

        FinancialReportEntry approvedReport = FinancialReportEntry.builder()
                .id("report-1")
                .reviewStatus(FinancialReportReviewStatus.APPROVED)
                .fileName("Report_Q1.pdf")
                .build();

        FinancialReportEntry draftReport = FinancialReportEntry.builder()
                .id("report-2")
                .reviewStatus(FinancialReportReviewStatus.PENDING_REVIEW)
                .fileName("Report_Draft.pdf")
                .build();

        FinancialMetric approvedMetric = FinancialMetric.builder()
                .id("metric-1")
                .label("Revenue")
                .source(MetricSource.builder().reportEntryId("report-1").build())
                .build();

        FinancialMetric draftMetric = FinancialMetric.builder()
                .id("metric-2")
                .label("Net Income")
                .source(MetricSource.builder().reportEntryId("report-2").build())
                .build();

        approvedResearch = FinancialResearch.builder()
                .id("research-123")
                .taskId(10L)
                .projectId(1L)
                .companyProfileId(companyUuid)
                .status(FinancialResearchStatus.APPROVED)
                .reports(new ArrayList<>(List.of(approvedReport, draftReport)))
                .metrics(new ArrayList<>(List.of(approvedMetric, draftMetric)))
                .build();

        managerUser = new UserDetailsImpl(
                3L,
                "manager@apms.com",
                "hash",
                List.of(new SimpleGrantedAuthority("ROLE_BUSINESS_DEVELOPMENT_MANAGER")),
                true
        );

        Authentication auth = mock(Authentication.class);
        lenient().when(auth.getPrincipal()).thenReturn(managerUser);
        SecurityContext context = mock(SecurityContext.class);
        lenient().when(context.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Query with universal company UUID returns approved financial research and filters out unapproved reports")
    void testGetApprovedFinancialsWithUniversalCompanyId() {
        when(companyIdentityResolver.resolve(companyUuid)).thenReturn(Optional.of(identity));
        when(companyProfileAccessService.isManagerAuthorizedForCompany(profile, 3L)).thenReturn(true);
        when(researchRepository.findByCompanyProfileIdInAndStatus(anyCollection(), eq(FinancialResearchStatus.APPROVED)))
                .thenReturn(List.of(approvedResearch));

        List<FinancialResearchResponse> result = researchService.getApprovedFinancials(companyUuid);

        assertThat(result).hasSize(1);
        FinancialResearchResponse response = result.get(0);
        assertThat(response.getId()).isEqualTo("research-123");
        // Only approved report entry must be present
        assertThat(response.getReports()).hasSize(1);
        assertThat(response.getReports().get(0).getId()).isEqualTo("report-1");
        // Only metric attached to approved report must be present
        assertThat(response.getMetrics()).hasSize(1);
        assertThat(response.getMetrics().get(0).getId()).isEqualTo("metric-1");
    }

    @Test
    @DisplayName("Query with legacy MongoDB _id resolves company identity and returns the exact same approved data")
    void testGetApprovedFinancialsWithMongoIdCompatibility() {
        when(companyIdentityResolver.resolve(mongoId)).thenReturn(Optional.of(identity));
        when(companyProfileAccessService.isManagerAuthorizedForCompany(profile, 3L)).thenReturn(true);
        when(researchRepository.findByCompanyProfileIdInAndStatus(anyCollection(), eq(FinancialResearchStatus.APPROVED)))
                .thenReturn(List.of(approvedResearch));

        List<FinancialResearchResponse> result = researchService.getApprovedFinancials(mongoId);

        assertThat(result).hasSize(1);
        FinancialResearchResponse response = result.get(0);
        assertThat(response.getId()).isEqualTo("research-123");
        assertThat(response.getReports()).hasSize(1);
        assertThat(response.getReports().get(0).getId()).isEqualTo("report-1");
    }

    @Test
    @DisplayName("Source Independence Invariant: Navigating via Company Profiles (UUID) and Monitoring (Mongo ID) yields identical reports for the same profile")
    void testSourceIndependence_CompanyProfilesVsMonitoringNavigation() {
        when(companyIdentityResolver.resolve(companyUuid)).thenReturn(Optional.of(identity));
        when(companyIdentityResolver.resolve(mongoId)).thenReturn(Optional.of(identity));
        when(companyProfileAccessService.isManagerAuthorizedForCompany(profile, 3L)).thenReturn(true);
        when(researchRepository.findByCompanyProfileIdInAndStatus(anyCollection(), eq(FinancialResearchStatus.APPROVED)))
                .thenReturn(List.of(approvedResearch));

        // 1. Simulation of Company Profiles navigation source
        List<FinancialResearchResponse> fromCompanyProfiles = researchService.getApprovedFinancials(companyUuid);

        // 2. Simulation of Monitoring Management navigation source
        List<FinancialResearchResponse> fromMonitoring = researchService.getApprovedFinancials(mongoId);

        // Both must be identical
        assertThat(fromCompanyProfiles).isNotEmpty();
        assertThat(fromMonitoring).isNotEmpty();
        assertThat(fromCompanyProfiles).usingRecursiveComparison().isEqualTo(fromMonitoring);
    }

    @Test
    @DisplayName("Manager out of scope throws AccessDeniedException and does not leak data")
    void testManagerOutOfScopeThrowsAccessDenied() {
        when(companyIdentityResolver.resolve(companyUuid)).thenReturn(Optional.of(identity));
        when(companyProfileAccessService.isManagerAuthorizedForCompany(profile, 3L)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> researchService.getApprovedFinancials(companyUuid));
        verify(researchRepository, never()).findByCompanyProfileIdInAndStatus(any(), any());
    }

    @Test
    @DisplayName("Multiple records matching candidate IDs are deduplicated by research ID")
    void testDeduplicationByResearchId() {
        when(companyIdentityResolver.resolve(companyUuid)).thenReturn(Optional.of(identity));
        when(companyProfileAccessService.isManagerAuthorizedForCompany(profile, 3L)).thenReturn(true);

        // Simulate repository returning duplicated references to the same research document
        when(researchRepository.findByCompanyProfileIdInAndStatus(anyCollection(), eq(FinancialResearchStatus.APPROVED)))
                .thenReturn(List.of(approvedResearch, approvedResearch));

        List<FinancialResearchResponse> result = researchService.getApprovedFinancials(companyUuid);

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("Empty or null company profile ID returns empty list gracefully")
    void testEmptyCompanyIdReturnsEmptyList() {
        List<FinancialResearchResponse> result1 = researchService.getApprovedFinancials(null);
        assertThat(result1).isEmpty();

        List<FinancialResearchResponse> result2 = researchService.getApprovedFinancials("   ");
        assertThat(result2).isEmpty();
    }
}

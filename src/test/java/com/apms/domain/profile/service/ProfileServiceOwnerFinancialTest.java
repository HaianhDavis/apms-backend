package com.apms.domain.profile.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.crawler.domain.TrackedCompany;
import com.apms.domain.crawler.repository.TrackedCompanyRepository;
import com.apms.domain.crawler.service.TrackedCompanyCache;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.dto.FinancialReportRequest;
import com.apms.domain.profile.dto.ProfileResponse;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileServiceOwnerFinancialTest {

    @Mock
    private CompanyProfileRepository profileRepository;
    @Mock
    private CompanyCandidateRepository candidateRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private Neo4jClient neo4jClient;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private OwnerOrganizationService ownerOrganizationService;
    @Mock
    private TrackedCompanyRepository trackedCompanyRepository;
    @Mock
    private TrackedCompanyCache trackedCompanyCache;
    @Mock
    private com.apms.domain.project.service.ProjectTargetProfileResolver projectTargetProfileResolver;

    @InjectMocks
    private ProfileService profileService;

    private static final String OWNER_ID = "owner-company-1";
    private static final String OWNER_DOC_ID = "owner-doc-1";

    private static final String ITEMS_JSON_2025 = """
            {"unit":"Tỷ đồng","templace":[{"code":"REVENUE","name":"Doanh thu"}],
            "data":[{"data":[{"time":"2025","data":[{"code":"REVENUE","value":15000}]}]}]}""";

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private CompanyProfile ownerProfile() {
        return CompanyProfile.builder()
                .id(OWNER_DOC_ID)
                .companyId(OWNER_ID)
                .identity(CompanyProfile.Identity.builder().legalName("FPT Corporation").build())
                .reviewStatus("APPROVED")
                .version(3)
                .metadata(CompanyProfile.Metadata.builder()
                        .createdBy("SYSTEM")
                        .createdAt(LocalDateTime.of(2020, 1, 1, 0, 0))
                        .build())
                .build();
    }

    private FinancialReportRequest request(String reportType, int reportYear, String itemsJson) {
        FinancialReportRequest r = new FinancialReportRequest();
        r.setReportType(reportType);
        r.setReportYear(reportYear);
        r.setPeriodType("YEAR");
        r.setItemsJson(itemsJson);
        return r;
    }

    private void loginAsAdmin() {
        UserDetailsImpl admin = new UserDetailsImpl(
                99L, "admin@apms.vn", "x", List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")), true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
    }

    // TEST 1 (Service): upsert mới → record được thêm đúng reportType/reportYear, version++, lưu + audit
    @Test
    void upsertOwnerFinancialReport_createsNewRecord() {
        CompanyProfile profile = ownerProfile();
        when(ownerOrganizationService.getRequiredOwnerCompanyProfile()).thenReturn(profile);
        when(profileRepository.save(profile)).thenReturn(profile);
        loginAsAdmin();

        ProfileResponse response = profileService.upsertOwnerFinancialReport(
                request("SUMMARY", 2025, ITEMS_JSON_2025));

        assertThat(response.getVersion()).isEqualTo(4);
        assertThat(profile.getFinancialReports()).hasSize(1);
        CompanyProfile.FinancialReport saved = profile.getFinancialReports().get(0);
        assertThat(saved.getReportType()).isEqualTo("SUMMARY");
        assertThat(saved.getReportYear()).isEqualTo(2025);
        assertThat(saved.getItemsJson()).contains("\"time\":\"2025\"");
        assertThat(saved.getPeriodType()).isEqualTo("YEAR");
        assertThat(profile.getMetadata().getUpdatedAt()).isNotNull();
        assertThat(profile.getMetadata().getLastModifiedBy()).isEqualTo("99");
        verify(profileRepository).save(profile);
        verify(auditLogService).log(eq(99L), eq(AuditAction.COMPANY_PROFILE_UPDATED), eq("CompanyProfile"),
                eq(OWNER_ID), anyString());
    }

    // TEST 2 + 3 (Service): upsert cùng (reportType, reportYear) → thay thế, không nhân đôi;
    // các năm khác được giữ nguyên
    @Test
    void upsertOwnerFinancialReport_replacesSameRecordAndKeepsOtherYears() {
        CompanyProfile profile = ownerProfile();
        profile.setFinancialReports(new java.util.ArrayList<>(List.of(
                CompanyProfile.FinancialReport.builder()
                        .reportType("SUMMARY").reportYear(2024).periodType("YEAR")
                        .itemsJson("{\"data\":[{\"data\":[{\"time\":\"2024\",\"data\":[]}]}]}")
                        .build(),
                CompanyProfile.FinancialReport.builder()
                        .reportType("SUMMARY").reportYear(2025).periodType("YEAR")
                        .itemsJson("{\"data\":[{\"data\":[{\"time\":\"2025\",\"data\":[]}]}]}")
                        .build())));
        when(ownerOrganizationService.getRequiredOwnerCompanyProfile()).thenReturn(profile);
        when(profileRepository.save(profile)).thenReturn(profile);

        ProfileResponse response = profileService.upsertOwnerFinancialReport(
                request("summary", 2025, ITEMS_JSON_2025));

        assertThat(profile.getFinancialReports()).hasSize(2);
        assertThat(profile.getFinancialReports()).noneMatch(report ->
                "SUMMARY".equalsIgnoreCase(report.getReportType()) && report.getReportYear() == 2025
                        && "{\"data\":[{\"data\":[{\"time\":\"2025\",\"data\":[]}]}]}".equals(report.getItemsJson()));
        CompanyProfile.FinancialReport updated2025 = profile.getFinancialReports().stream()
                .filter(report -> report.getReportYear() == 2025).findFirst().orElseThrow();
        assertThat(updated2025.getItemsJson()).contains("\"value\":15000");
        assertThat(profile.getFinancialReports().stream()
                .filter(report -> report.getReportYear() == 2024)).hasSize(1);
        assertThat(response.getVersion()).isEqualTo(4);
    }

    // TEST 4 (Service): reportType không nằm trong danh sách cho phép → lỗi, không save
    @Test
    void upsertOwnerFinancialReport_invalidReportType_throwsAndDoesNotSave() {
        CompanyProfile profile = ownerProfile();
        when(ownerOrganizationService.getRequiredOwnerCompanyProfile()).thenReturn(profile);

        assertThatThrownBy(() -> profileService.upsertOwnerFinancialReport(
                request("FOO_BAR", 2025, ITEMS_JSON_2025)))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("không hợp lệ");

        verify(profileRepository, never()).save(any(CompanyProfile.class));
    }

    // TEST 5 (Service): itemsJson không parse được / giá trị không phải số → lỗi, không save
    @Test
    void upsertOwnerFinancialReport_invalidItemsJson_throwsAndDoesNotSave() {
        CompanyProfile profile = ownerProfile();
        when(ownerOrganizationService.getRequiredOwnerCompanyProfile()).thenReturn(profile);

        assertThatThrownBy(() -> profileService.upsertOwnerFinancialReport(
                request("SUMMARY", 2025, "{\"data\":[{\"data\":[{\"time\":\"2025\",\"data\":[{\"code\":\"REVENUE\",\"value\":\"abc\"}]}]}]}")))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("giá trị phải là số");

        assertThatThrownBy(() -> profileService.upsertOwnerFinancialReport(
                request("SUMMARY", 2025, "not-json")))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("không hợp lệ");

        assertThatThrownBy(() -> profileService.upsertOwnerFinancialReport(
                request("SUMMARY", 2025, "{\"data\":[]}")))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("thiếu bảng dữ liệu");

        verify(profileRepository, never()).save(any(CompanyProfile.class));
    }

    // TEST 6 (Service): xóa record (reportType, reportYear) → xóa đúng, giữ các năm khác, version++
    @Test
    void deleteOwnerFinancialReport_removesOnlyMatchingRecord() {
        CompanyProfile profile = ownerProfile();
        profile.setFinancialReports(new java.util.ArrayList<>(List.of(
                CompanyProfile.FinancialReport.builder()
                        .reportType("SUMMARY").reportYear(2024).periodType("YEAR").itemsJson("{}").build(),
                CompanyProfile.FinancialReport.builder()
                        .reportType("SUMMARY").reportYear(2025).periodType("YEAR").itemsJson("{}").build(),
                CompanyProfile.FinancialReport.builder()
                        .reportType("BALANCE_SHEET").reportYear(2025).periodType("YEAR").itemsJson("{}").build())));
        when(ownerOrganizationService.getRequiredOwnerCompanyProfile()).thenReturn(profile);
        when(profileRepository.save(profile)).thenReturn(profile);

        ProfileResponse response = profileService.deleteOwnerFinancialReport("summary", 2025);

        assertThat(profile.getFinancialReports()).hasSize(2);
        assertThat(profile.getFinancialReports()).noneMatch(report ->
                report.getReportYear() == 2025 && "SUMMARY".equalsIgnoreCase(report.getReportType()));
        assertThat(profile.getFinancialReports()).anyMatch(report ->
                report.getReportYear() == 2024 && "SUMMARY".equalsIgnoreCase(report.getReportType()));
        assertThat(profile.getFinancialReports()).anyMatch(report ->
                report.getReportYear() == 2025 && "BALANCE_SHEET".equalsIgnoreCase(report.getReportType()));
        assertThat(response.getVersion()).isEqualTo(4);
        verify(profileRepository).save(profile);
    }

    // TEST 7 (Service): xóa record không tồn tại → lỗi, không save
    @Test
    void deleteOwnerFinancialReport_notFound_throwsAndDoesNotSave() {
        CompanyProfile profile = ownerProfile();
        profile.setFinancialReports(new java.util.ArrayList<>(List.of(
                CompanyProfile.FinancialReport.builder()
                        .reportType("SUMMARY").reportYear(2024).periodType("YEAR").itemsJson("{}").build())));
        when(ownerOrganizationService.getRequiredOwnerCompanyProfile()).thenReturn(profile);

        assertThatThrownBy(() -> profileService.deleteOwnerFinancialReport("SUMMARY", 2030))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("Không tìm thấy báo cáo tài chính");

        verify(profileRepository, never()).save(any(CompanyProfile.class));
    }
}

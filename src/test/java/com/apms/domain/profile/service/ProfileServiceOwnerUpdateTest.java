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
import com.apms.domain.profile.dto.ProfileResponse;
import com.apms.domain.profile.dto.UpdateOwnerCompanyProfileRequest;
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
class ProfileServiceOwnerUpdateTest {

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
                .identity(CompanyProfile.Identity.builder()
                        .legalName("Old Legal")
                        .tradeName("Old")
                        .taxCode("OLD-TAX")
                        .registrationNumber("OLD-REG")
                        .build())
                .business(CompanyProfile.Business.builder()
                        .industries(List.of("OLD"))
                        .markets(List.of("OLD"))
                        .build())
                .companySize(CompanyProfile.CompanySize.builder()
                        .employeeTier("OLD")
                        .employeeCount(10)
                        .revenueTier("OLD")
                        .build())
                .contact(CompanyProfile.Contact.builder()
                        .website("https://old.com")
                        .emails(List.of("old@old.com"))
                        .phones(List.of("+840"))
                        .addresses(List.of(CompanyProfile.Address.builder()
                                .type("HEADQUARTERS")
                                .fullAddress("Old Street")
                                .build()))
                        .build())
                .tags(List.of("old"))
                .reviewStatus("APPROVED")
                .version(3)
                .metadata(CompanyProfile.Metadata.builder()
                        .createdBy("SYSTEM")
                        .createdAt(LocalDateTime.of(2020, 1, 1, 0, 0))
                        .build())
                .build();
    }

    private UpdateOwnerCompanyProfileRequest fullRequest() {
        UpdateOwnerCompanyProfileRequest r = new UpdateOwnerCompanyProfileRequest();
        r.setLegalName("FPT Corporation");
        r.setTradeName("FPT");
        r.setTaxCode("0100100018");
        r.setRegistrationNumber("0100100018");
        r.setIndustries(List.of("Technology", "Telecommunications"));
        r.setBusinessModel("B2B Technology Services");
        r.setMarkets(List.of("Vietnam", "Global"));
        r.setEmployeeTier("LARGE");
        r.setEmployeeCount(30000);
        r.setRevenueTier("> 10B VND");
        r.setEmail("contact@fpt.com");
        r.setPhone("+84 24 7300 2222");
        r.setWebsite("https://fpt.com");
        r.setAddress("Lot 1, FPT Building, Hanoi");
        r.setTags(List.of("tech", "core"));
        return r;
    }

    private void loginAsAdmin() {
        UserDetailsImpl admin = new UserDetailsImpl(
                99L, "admin@apms.vn", "x", List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")), true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
    }

    // TEST 1 + 2 + 3 (Service): Admin update → hồ sơ owner được lưu, version++,
    // metadata & audit đầy đủ, response phản ánh dữ liệu mới
    @Test
    void updateOwnerCompanyProfile_appliesWhitelistAndPersistsOwnerProfile() {
        CompanyProfile profile = ownerProfile();
        when(ownerOrganizationService.getRequiredOwnerCompanyProfile()).thenReturn(profile);
        when(profileRepository.save(profile)).thenReturn(profile);
        loginAsAdmin();

        ProfileResponse response = profileService.updateOwnerCompanyProfile(fullRequest());

        assertThat(response.getCompanyId()).isEqualTo(OWNER_ID);
        assertThat(response.getVersion()).isEqualTo(4);
        assertThat(response.getIdentity().getLegalName()).isEqualTo("FPT Corporation");
        assertThat(response.getIdentity().getTaxCode()).isEqualTo("0100100018");
        assertThat(response.getBusiness().getIndustries()).containsExactly("Technology", "Telecommunications");
        assertThat(response.getCompanySize().getEmployeeCount()).isEqualTo(30000);
        assertThat(response.getContact().getWebsite()).isEqualTo("https://fpt.com");
        assertThat(response.getContact().getEmails()).containsExactly("contact@fpt.com");
        assertThat(response.getContact().getPhones()).containsExactly("+84 24 7300 2222");
        assertThat(response.getContact().getAddresses()).hasSize(1);
        assertThat(response.getContact().getAddresses().get(0).getType()).isEqualTo("HEADQUARTERS");
        assertThat(response.getContact().getAddresses().get(0).getFullAddress()).isEqualTo("Lot 1, FPT Building, Hanoi");
        assertThat(response.getTags()).containsExactly("tech", "core");

        verify(ownerOrganizationService).getRequiredOwnerCompanyProfile();
        verify(profileRepository).save(profile);
        assertThat(profile.getMetadata().getUpdatedAt()).isNotNull();
        assertThat(profile.getMetadata().getLastModifiedBy()).isEqualTo("99");
        verify(auditLogService).log(eq(99L), eq(AuditAction.COMPANY_PROFILE_UPDATED), eq("CompanyProfile"),
                eq(OWNER_ID), anyString());
    }

    // TEST 9 (Service): Whitelist — không thể đổi id/companyId/reviewStatus/createdAt qua request
    @Test
    void updateOwnerCompanyProfile_fieldsOutsideWhitelistPreserved() {
        CompanyProfile profile = ownerProfile();
        when(ownerOrganizationService.getRequiredOwnerCompanyProfile()).thenReturn(profile);

        profileService.updateOwnerCompanyProfile(fullRequest());

        assertThat(profile.getId()).isEqualTo(OWNER_DOC_ID);
        assertThat(profile.getCompanyId()).isEqualTo(OWNER_ID);
        assertThat(profile.getReviewStatus()).isEqualTo("APPROVED");
        assertThat(profile.getMetadata().getCreatedAt())
                .isEqualTo(LocalDateTime.of(2020, 1, 1, 0, 0));
        assertThat(profile.getMetadata().getCreatedBy()).isEqualTo("SYSTEM");
        assertThat(profile.getMetadata().getLastModifiedBy()).isEqualTo("SYSTEM");
    }

    // TEST 10 (Service): Chỉ dữ liệu hợp lệ được áp dụng; chuỗi rỗng → danh sách rỗng (xóa được)
    @Test
    void updateOwnerCompanyProfile_emptyContactFieldsClearLists() {
        CompanyProfile profile = ownerProfile();
        when(ownerOrganizationService.getRequiredOwnerCompanyProfile()).thenReturn(profile);

        UpdateOwnerCompanyProfileRequest r = new UpdateOwnerCompanyProfileRequest();
        r.setLegalName("FPT Corporation");
        r.setEmail("");
        r.setPhone("");
        r.setWebsite("");
        r.setAddress("");

        profileService.updateOwnerCompanyProfile(r);

        assertThat(profile.getContact().getEmails()).isEmpty();
        assertThat(profile.getContact().getPhones()).isEmpty();
        assertThat(profile.getContact().getAddresses()).isEmpty();
        assertThat(profile.getContact().getWebsite()).isEqualTo("https://old.com");
        assertThat(profile.getIdentity().getLegalName()).isEqualTo("FPT Corporation");
    }

    // TEST 1 (Service): Metadata null → không NPE, vẫn lưu + ghi SYSTEM
    @Test
    void updateOwnerCompanyProfile_nullMetadata_handled() {
        CompanyProfile profile = ownerProfile();
        profile.setMetadata(null);
        when(ownerOrganizationService.getRequiredOwnerCompanyProfile()).thenReturn(profile);
        when(profileRepository.save(profile)).thenReturn(profile);

        ProfileResponse response = profileService.updateOwnerCompanyProfile(fullRequest());

        assertThat(response.getVersion()).isEqualTo(4);
        verify(profileRepository).save(profile);
        assertThat(profile.getMetadata()).isNotNull();
        assertThat(profile.getMetadata().getUpdatedAt()).isNotNull();
        assertThat(profile.getMetadata().getLastModifiedBy()).isEqualTo("SYSTEM");
    }

    // TEST 2/3 (Service): Owner profile không tồn tại → BusinessValidationException, không save
    @Test
    void updateOwnerCompanyProfile_ownerMissing_throwsAndDoesNotSave() {
        when(ownerOrganizationService.getRequiredOwnerCompanyProfile())
                .thenThrow(new BusinessValidationException("Owner CompanyProfile not found for ID: " + OWNER_ID));

        assertThatThrownBy(() -> profileService.updateOwnerCompanyProfile(fullRequest()))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("not found");

        verify(profileRepository, never()).save(any(CompanyProfile.class));
        verify(auditLogService, never()).log(any(), any(), any(), any(), any());
    }

    // TEST 1 (Service): Không có user trong context → không gọi audit
    @Test
    void updateOwnerCompanyProfile_noAuthContext_skipsAudit() {
        CompanyProfile profile = ownerProfile();
        when(ownerOrganizationService.getRequiredOwnerCompanyProfile()).thenReturn(profile);

        profileService.updateOwnerCompanyProfile(fullRequest());

        verify(auditLogService, never()).log(any(), any(), any(), any(), any());
    }

    // SWOT + Business Fields + Leadership: admin cập nhật toàn bộ 4 module → lưu đúng, version++
    @Test
    void updateOwnerCompanyProfile_appliesSwotProductsTargetCustomersAndLeadership() {
        CompanyProfile profile = ownerProfile();
        when(ownerOrganizationService.getRequiredOwnerCompanyProfile()).thenReturn(profile);
        when(profileRepository.save(profile)).thenReturn(profile);
        loginAsAdmin();

        UpdateOwnerCompanyProfileRequest r = new UpdateOwnerCompanyProfileRequest();
        r.setLegalName("FPT Corporation");
        r.setStockTicker("FPT");
        r.setStockExchange("HOSE");

        UpdateOwnerCompanyProfileRequest.SwotRequest swot = new UpdateOwnerCompanyProfileRequest.SwotRequest();
        swot.setStrengths(List.of("Strong technology capability", "Global delivery network"));
        swot.setThreats(List.of("Intense competition"));
        r.setInsights(swot);

        r.setProducts(List.of(
                product("Software Outsourcing", "IT Services", "Custom software development..."),
                product("AI Solutions", "Technology", "AI platforms and machine learning")));
        r.setTargetCustomers(List.of("Banking", "Healthcare"));

        r.setCompanyMembers(List.of(
                member("Truong Gia Binh", "Chairman", "https://img/fpt.jpg", "https://fpt.com/binh", "Co-founder")));

        ProfileResponse response = profileService.updateOwnerCompanyProfile(r);

        assertThat(response.getVersion()).isEqualTo(4);
        assertThat(response.getIdentity().getStockTicker()).isEqualTo("FPT");
        assertThat(response.getIdentity().getStockExchange()).isEqualTo("HOSE");
        assertThat(response.getStockTicker()).isEqualTo("FPT");

        assertThat(profile.getInsights().getStrengths())
                .containsExactly("Strong technology capability", "Global delivery network");
        assertThat(profile.getInsights().getThreats()).containsExactly("Intense competition");
        assertThat(profile.getInsights().getWeaknesses()).isNull();

        assertThat(profile.getBusiness().getProducts()).hasSize(2);
        assertThat(profile.getBusiness().getProducts().get(0).getName()).isEqualTo("Software Outsourcing");
        assertThat(profile.getBusiness().getProducts().get(0).getCategory()).isEqualTo("IT Services");
        assertThat(profile.getBusiness().getProducts().get(0).getDescription()).contains("Custom software");
        assertThat(profile.getBusiness().getTargetCustomers()).containsExactly("Banking", "Healthcare");

        assertThat(profile.getCompanyMembers()).hasSize(1);
        assertThat(profile.getCompanyMembers().get(0).getFullName()).isEqualTo("Truong Gia Binh");
        assertThat(profile.getCompanyMembers().get(0).getPosition()).isEqualTo("Chairman");

        verify(profileRepository).save(profile);
        verify(auditLogService).log(eq(99L), eq(AuditAction.COMPANY_PROFILE_UPDATED), eq("CompanyProfile"),
                eq(OWNER_ID), anyString());
    }

    // SWOT field-wise: chỉ mục được gửi được cập nhật, các mục có sẵn khác được giữ nguyên
    @Test
    void updateOwnerCompanyProfile_swotAppliesOnlyProvidedLists() {
        CompanyProfile profile = ownerProfile();
        profile.setInsights(CompanyProfile.Insights.builder()
                .strengths(List.of("Existing strength"))
                .weaknesses(List.of("Existing weakness"))
                .build());
        when(ownerOrganizationService.getRequiredOwnerCompanyProfile()).thenReturn(profile);

        UpdateOwnerCompanyProfileRequest r = new UpdateOwnerCompanyProfileRequest();
        r.setLegalName("FPT Corporation");
        UpdateOwnerCompanyProfileRequest.SwotRequest swot = new UpdateOwnerCompanyProfileRequest.SwotRequest();
        swot.setWeaknesses(List.of("New weakness only"));
        r.setInsights(swot);

        profileService.updateOwnerCompanyProfile(r);

        assertThat(profile.getInsights().getStrengths()).containsExactly("Existing strength");
        assertThat(profile.getInsights().getWeaknesses()).containsExactly("New weakness only");
        assertThat(profile.getInsights().getOpportunities()).isNull();
        assertThat(profile.getInsights().getThreats()).isNull();
    }

    private static UpdateOwnerCompanyProfileRequest.ProductRequest product(String name, String category, String description) {
        UpdateOwnerCompanyProfileRequest.ProductRequest p = new UpdateOwnerCompanyProfileRequest.ProductRequest();
        p.setName(name);
        p.setCategory(category);
        p.setDescription(description);
        return p;
    }

    private static UpdateOwnerCompanyProfileRequest.CompanyMemberRequest member(String fullName, String position,
                                                                               String imageUrl, String sourceUrl, String notes) {
        UpdateOwnerCompanyProfileRequest.CompanyMemberRequest m = new UpdateOwnerCompanyProfileRequest.CompanyMemberRequest();
        m.setFullName(fullName);
        m.setPosition(position);
        m.setImageUrl(imageUrl);
        m.setSourceUrl(sourceUrl);
        m.setNotes(notes);
        return m;
    }
}

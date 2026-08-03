package com.apms.domain.news.controller;

import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.news.entity.CompanyIntelligenceArticle;
import com.apms.domain.news.repository.CompanyIntelligenceArticleRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.security.enums.StepUpPurpose;
import com.apms.domain.security.service.StepUpTokenService;
import com.apms.domain.document.service.StorageService;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfidentialNewsControllerTest {

    @Mock
    private CompanyIntelligenceArticleRepository articleRepository;
    @Mock
    private CompanyProfileRepository companyProfileRepository;
    @Mock
    private StepUpTokenService stepUpTokenService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private StorageService storageService;

    @InjectMocks
    private ConfidentialNewsController controller;

    @Mock
    private SecurityContext securityContext;
    @Mock
    private Authentication authentication;

    private UserDetailsImpl ownerUser;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        ownerUser = new UserDetailsImpl(
                1L, "owner@apms.com", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_BUSINESS_OWNER")),
                true
        );
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    private void mockSecurityContext(UserDetailsImpl user) {
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(user);
        SecurityContextHolder.setContext(securityContext);
    }

    @Mock
    private com.apms.domain.profile.service.OwnerOrganizationService ownerOrganizationService;

    @Test
    void listArticles_Success() {
        mockSecurityContext(ownerUser);
        request.addHeader("X-Step-Up-Token", "valid-token");
        
        when(ownerOrganizationService.isOwnerCompany("profile-1")).thenReturn(true);
        CompanyProfile profile = CompanyProfile.builder().id("profile-1").build();
        when(companyProfileRepository.findById("profile-1")).thenReturn(Optional.of(profile));
        when(stepUpTokenService.validateToken("valid-token", 1L, StepUpPurpose.CONFIDENTIAL_COMPANY_NEWS)).thenReturn(true);

        CompanyIntelligenceArticle article = CompanyIntelligenceArticle.builder().id("art-1").build();
        Page<CompanyIntelligenceArticle> page = new PageImpl<>(List.of(article));
        when(articleRepository.findByCompanyProfileIdAndIsDeletedFalseAndApprovedAtIsNotNull(eq("profile-1"), any(Pageable.class))).thenReturn(page);

        var result = controller.listArticles("profile-1", 0, 20, request, response);

        assertNotNull(result);
        assertEquals(200, result.getStatusCode().value());
        assertTrue(response.getHeader("Cache-Control").contains("no-store"));
    }

    @Test
    void listArticles_FailsIfNoToken() {
        mockSecurityContext(ownerUser);
        when(ownerOrganizationService.isOwnerCompany("profile-1")).thenReturn(true);
        CompanyProfile profile = CompanyProfile.builder().id("profile-1").build();
        when(companyProfileRepository.findById("profile-1")).thenReturn(Optional.of(profile));
        // No header added

        assertThrows(AccessDeniedException.class, () -> 
                controller.listArticles("profile-1", 0, 20, request, response));
    }

    @Test
    void listArticles_FailsIfNotOwnerCompanyScope() {
        mockSecurityContext(ownerUser);
        when(ownerOrganizationService.isOwnerCompany("profile-1")).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> 
                controller.listArticles("profile-1", 0, 20, request, response));
    }

    @Test
    void listArticles_FailsIfHiddenProfile() {
        mockSecurityContext(ownerUser);
        when(ownerOrganizationService.isOwnerCompany("profile-1")).thenReturn(true);
        CompanyProfile profile = CompanyProfile.builder().id("profile-1").isHidden(true).build();
        when(companyProfileRepository.findById("profile-1")).thenReturn(Optional.of(profile));

        assertThrows(AccessDeniedException.class, () -> 
                controller.listArticles("profile-1", 0, 20, request, response));
    }

    @Test
    void listArticles_FailsIfDeletedProfile() {
        mockSecurityContext(ownerUser);
        when(ownerOrganizationService.isOwnerCompany("profile-1")).thenReturn(true);
        CompanyProfile profile = CompanyProfile.builder().id("profile-1").isDeleted(true).build();
        when(companyProfileRepository.findById("profile-1")).thenReturn(Optional.of(profile));

        assertThrows(AccessDeniedException.class, () -> 
                controller.listArticles("profile-1", 0, 20, request, response));
    }

    @Test
    void listArticles_FailsIfSystemAdmin() {
        UserDetailsImpl admin = new UserDetailsImpl(
                2L, "admin@apms.com", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")),
                true
        );
        mockSecurityContext(admin);

        assertThrows(AccessDeniedException.class, () -> 
                controller.listArticles("profile-1", 0, 20, request, response));
    }

    @Test
    void getArticle_FailsIfFromAnotherCompany() {
        mockSecurityContext(ownerUser);
        request.addHeader("X-Step-Up-Token", "valid-token");
        
        when(ownerOrganizationService.isOwnerCompany("profile-1")).thenReturn(true);
        CompanyProfile profile = CompanyProfile.builder().id("profile-1").build();
        when(companyProfileRepository.findById("profile-1")).thenReturn(Optional.of(profile));
        when(stepUpTokenService.validateToken("valid-token", 1L, StepUpPurpose.CONFIDENTIAL_COMPANY_NEWS)).thenReturn(true);

        when(articleRepository.findByIdAndCompanyProfileIdAndIsDeletedFalseAndApprovedAtIsNotNull("art-1", "profile-1"))
                .thenReturn(Optional.empty()); // simulate article belongs to another company or not found

        assertThrows(ResourceNotFoundException.class, () -> 
                controller.getArticle("profile-1", "art-1", request, response));
    }

    @Test
    void getArticleImage_Success() throws Exception {
        mockSecurityContext(ownerUser);
        request.addHeader("X-Step-Up-Token", "valid-token");
        
        when(ownerOrganizationService.isOwnerCompany("profile-1")).thenReturn(true);
        CompanyProfile profile = CompanyProfile.builder().id("profile-1").build();
        when(companyProfileRepository.findById("profile-1")).thenReturn(Optional.of(profile));
        when(stepUpTokenService.validateToken("valid-token", 1L, StepUpPurpose.CONFIDENTIAL_COMPANY_NEWS)).thenReturn(true);

        CompanyIntelligenceArticle article = CompanyIntelligenceArticle.builder()
                .id("art-1")
                .imageStorageKey("image.jpg")
                .build();
        when(articleRepository.findByIdAndCompanyProfileIdAndIsDeletedFalseAndApprovedAtIsNotNull("art-1", "profile-1"))
                .thenReturn(Optional.of(article));

        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("test", ".jpg");
        when(storageService.load("image.jpg")).thenReturn(tempFile);
        org.springframework.core.io.Resource mockResource = mock(org.springframework.core.io.Resource.class);
        when(mockResource.getFilename()).thenReturn("image.jpg");
        when(storageService.loadAsResource("image.jpg")).thenReturn(mockResource);

        var result = controller.getArticleImage("profile-1", "art-1", request, response);

        assertNotNull(result);
        assertEquals(200, result.getStatusCode().value());
        assertTrue(response.getHeader("Cache-Control").contains("no-store"));
        assertTrue(response.getHeader("Cache-Control").contains("private"));
        
        java.nio.file.Files.deleteIfExists(tempFile);
    }

    @Test
    void getArticleImage_FailsIfNoToken() {
        mockSecurityContext(ownerUser);
        when(ownerOrganizationService.isOwnerCompany("profile-1")).thenReturn(true);
        CompanyProfile profile = CompanyProfile.builder().id("profile-1").build();
        when(companyProfileRepository.findById("profile-1")).thenReturn(Optional.of(profile));

        assertThrows(AccessDeniedException.class, () -> 
                controller.getArticleImage("profile-1", "art-1", request, response));
    }

    @Test
    void getArticleImage_FailsIfDeletedArticle() {
        mockSecurityContext(ownerUser);
        request.addHeader("X-Step-Up-Token", "valid-token");
        
        when(ownerOrganizationService.isOwnerCompany("profile-1")).thenReturn(true);
        CompanyProfile profile = CompanyProfile.builder().id("profile-1").build();
        when(companyProfileRepository.findById("profile-1")).thenReturn(Optional.of(profile));
        when(stepUpTokenService.validateToken("valid-token", 1L, StepUpPurpose.CONFIDENTIAL_COMPANY_NEWS)).thenReturn(true);

        when(articleRepository.findByIdAndCompanyProfileIdAndIsDeletedFalseAndApprovedAtIsNotNull("art-1", "profile-1"))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> 
                controller.getArticleImage("profile-1", "art-1", request, response));
    }
}

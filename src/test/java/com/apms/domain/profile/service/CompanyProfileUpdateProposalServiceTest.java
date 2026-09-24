package com.apms.domain.profile.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.ProposalOrigin;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.profile.CompanyProfileUpdateProposal;
import com.apms.domain.profile.dto.CompanyProfileUpdateProposalResponse;
import com.apms.domain.profile.dto.CreateCompanyProfileUpdateProposalRequest;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileUpdateProposalRepository;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.security.UserDetailsImpl;
import com.apms.common.enums.SystemRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyProfileUpdateProposalServiceTest {

    @Mock
    private CompanyProfileUpdateProposalRepository proposalRepository;

    @Mock
    private CompanyProfileRepository companyProfileRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CompanyProfileVersionService versionService;

    @Mock
    private com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository versionRepository;

    @Mock
    private com.apms.domain.user.repository.sql.UserProfileRepository userProfileRepository;

    @InjectMocks
    private CompanyProfileUpdateProposalService service;

    private UserDetailsImpl staffUser;
    private CreateCompanyProfileUpdateProposalRequest request;

    @BeforeEach
    void setUp() {
        java.util.List<org.springframework.security.core.GrantedAuthority> authorities = java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_BUSINESS_DEVELOPMENT_STAFF"));
        staffUser = new UserDetailsImpl(1L, "staff@apms.com", "password", authorities, true);

        SecurityContext securityContext = mock(SecurityContext.class);
        Authentication authentication = mock(Authentication.class);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        lenient().when(authentication.getPrincipal()).thenReturn(staffUser);
        SecurityContextHolder.setContext(securityContext);

        request = new CreateCompanyProfileUpdateProposalRequest();
        request.setCompanyProfileId("profile-1");
        request.setChangeSummary("Test Update");
    }

    @Test
    void createProposal_ProjectOrigin_Success() {
        when(projectRepository.existsByIdAndMembersAccountId(100L, 1L)).thenReturn(true);
        when(companyProfileRepository.existsById("profile-1")).thenReturn(true);

        when(proposalRepository.save(any(CompanyProfileUpdateProposal.class))).thenAnswer(invocation -> {
            CompanyProfileUpdateProposal p = invocation.getArgument(0);
            p.setId("proposal-1");
            return p;
        });

        CompanyProfileUpdateProposalResponse response = service.createProposal(100L, 200L, request);

        assertNotNull(response);
        assertEquals("proposal-1", response.getId());
        assertEquals(100L, response.getProjectId());
        
        verify(projectRepository).existsByIdAndMembersAccountId(100L, 1L);
        verify(proposalRepository).save(argThat(p -> p.getOrigin() == ProposalOrigin.PROJECT));
        verify(auditLogService).log(eq(1L), eq(AuditAction.PROFILE_UPDATE_PROPOSAL_CREATED), eq("CompanyProfileUpdateProposal"), eq("proposal-1"), anyString());
    }

    @Test
    void createMonitoringProposal_MonitoringOrigin_Success() {
        when(companyProfileRepository.existsById("profile-1")).thenReturn(true);

        when(proposalRepository.save(any(CompanyProfileUpdateProposal.class))).thenAnswer(invocation -> {
            CompanyProfileUpdateProposal p = invocation.getArgument(0);
            p.setId("proposal-2");
            return p;
        });

        CompanyProfileUpdateProposalResponse response = service.createMonitoringProposal(request);

        assertNotNull(response);
        assertEquals("proposal-2", response.getId());
        assertNull(response.getProjectId());

        verify(projectRepository, never()).existsByIdAndMembersAccountId(anyLong(), anyLong());
        verify(proposalRepository).save(argThat(p -> p.getOrigin() == ProposalOrigin.MONITORING));
        verify(auditLogService).log(eq(1L), eq(AuditAction.PROFILE_UPDATE_PROPOSAL_CREATED), eq("CompanyProfileUpdateProposal"), eq("proposal-2"), anyString());
    }

    @Test
    void getProposalDetails_ApprovedHistorical_RecoversBeforeValuesFromVersion() {
        CompanyProfileUpdateProposal proposal = CompanyProfileUpdateProposal.builder()
                .id("prop-approved")
                .origin(ProposalOrigin.MONITORING)
                .companyProfileId("profile-1")
                .status(com.apms.common.enums.SubmissionStatus.APPROVED)
                .changedFieldPaths(java.util.List.of("contact.phones"))
                .originalValues(null)
                .build();

        when(proposalRepository.findById("prop-approved")).thenReturn(java.util.Optional.of(proposal));

        com.apms.domain.profile.CompanyProfileVersion version = com.apms.domain.profile.CompanyProfileVersion.builder()
                .id("version-1")
                .createdFromProposalId("prop-approved")
                .beforeValues(java.util.Map.of("contact.phones", java.util.List.of("024 2220 5544", "19009247")))
                .afterValues(java.util.Map.of("contact.phones", java.util.List.of("024 2220 5555")))
                .build();

        when(versionRepository.findFirstByCreatedFromProposalId("prop-approved")).thenReturn(java.util.Optional.of(version));

        CompanyProfileUpdateProposalResponse response = service.getProposalDetails("prop-approved");

        assertNotNull(response);
        assertNotNull(response.getOriginalValues());
        assertEquals(java.util.List.of("024 2220 5544", "19009247"), response.getOriginalValues().get("contact.phones"));
    }
}

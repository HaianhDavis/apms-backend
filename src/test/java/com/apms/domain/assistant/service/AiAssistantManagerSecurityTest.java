package com.apms.domain.assistant.service;

import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.TaskStatus;
import com.apms.common.security.ProjectSecurityEvaluator;
import com.apms.domain.assistant.dto.AiChatRequest;
import com.apms.domain.assistant.dto.AiChatResponse;
import com.apms.domain.assistant.repository.mongo.AiChatMessageRepository;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.externaldata.ExternalDataItem;
import com.apms.domain.externaldata.repository.mongo.ExternalDataRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AiAssistantManagerSecurityTest {

    @Mock private AssistantContextService contextService;
    @Mock private GeminiAssistantProvider assistantProvider;
    @Mock private AiChatMessageRepository chatMessageRepository;
    @Mock private ProjectSecurityEvaluator projectSecurity;
    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectTaskRepository projectTaskRepository;
    @Mock private ProjectTaskSubmissionRepository projectTaskSubmissionRepository;
    @Mock private ExternalDataRepository externalDataRepository;
    @Mock private CompanyCandidateRepository companyCandidateRepository;
    @Mock private CompanyProfileRepository companyProfileRepository;
    @Mock private com.apms.domain.graph.service.GraphService graphService;
    @Mock private com.apms.domain.profile.service.OwnerOrganizationService ownerOrganizationService;

    @InjectMocks
    private AiAssistantService aiAssistantService;

    private UserDetailsImpl managerUser;

    @BeforeEach
    void setUp() {
        managerUser = mock(UserDetailsImpl.class);
        lenient().when(managerUser.getId()).thenReturn(200L);
        lenient().when((java.util.Collection<GrantedAuthority>) managerUser.getAuthorities())
                 .thenReturn(List.of((GrantedAuthority) () -> "ROLE_BUSINESS_DEVELOPMENT_MANAGER"));

        Authentication authentication = mock(Authentication.class);
        lenient().when(authentication.getPrincipal()).thenReturn(managerUser);
        SecurityContext securityContext = mock(SecurityContext.class);
        lenient().when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);
    }

    @Test
    void managerCanQueryManagedProjects() {
        when(projectSecurity.isMemberOrOwner(any())).thenReturn(true);
        when(projectSecurity.isManager(any())).thenReturn(true);
        Project p1 = new Project();
        p1.setId(1L);
        p1.setProjectName("Manager Project 1");
        p1.setStatus(com.apms.common.enums.ProjectStatus.ACTIVE);
        
        when(projectRepository.findByMemberAccountId(eq(200L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(p1)));

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("What projects am I managing?");

        AiChatResponse response = aiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("You are currently managing 1 project"));
        assertTrue(response.getAnswer().contains("Manager Project 1"));
        verify(assistantProvider, never()).answer(anyString(), any());
    }

    @Test
    void managerAsksForInternalNews_returnsProtectedResponse_withoutCallingGemini() {
        when(projectSecurity.isMemberOrOwner(any())).thenReturn(true);
        
        AiChatRequest request = new AiChatRequest();
        request.setQuestion("Show me the internal news about FPT");

        AiChatResponse response = aiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("Internal News is protected data"));
        verify(assistantProvider, never()).answer(anyString(), any());
    }

    @Test
    void managerGeneralKnowledgeQuestion_returnsOutOfScope_withoutCallingGemini() {
        when(projectSecurity.isMemberOrOwner(any())).thenReturn(true);
        
        AiChatRequest request = new AiChatRequest();
        request.setQuestion("What is SWOT analysis?");

        AiChatResponse response = aiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("outside your available"));
        verify(assistantProvider, never()).answer(anyString(), any());
    }
    
    @Test
    void managerCanQueryApprovedCompanyProfile() {
        when(projectSecurity.isMemberOrOwner(any())).thenReturn(true);
        when(assistantProvider.answer(anyString(), any())).thenReturn("Gemini answer for approved profile");
        
        CompanyProfile cp = new CompanyProfile();
        cp.setId("cp-123");
        cp.setReviewStatus("APPROVED");
        CompanyProfile.Identity id = new CompanyProfile.Identity();
        id.setLegalName("FPT Software");
        cp.setIdentity(id);
        
        when(companyProfileRepository.searchByName(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(cp)));

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("What do we know about FPT?");

        aiAssistantService.chat(request);

        verify(assistantProvider).answer(anyString(), argThat(context -> 
            context.getContextText().contains("Approved Company Profile Facts for FPT Software")
        ));
    }

    @Test
    void managerCompanyProfileResponse_containsNavigationAction() {
        when(projectSecurity.isMemberOrOwner(any())).thenReturn(true);
        when(assistantProvider.answer(anyString(), any())).thenReturn("Gemini answer for approved profile");
        
        CompanyProfile cp = new CompanyProfile();
        cp.setId("cp-123");
        cp.setCompanyId("C-123");
        cp.setReviewStatus("APPROVED");
        CompanyProfile.Identity id = new CompanyProfile.Identity();
        id.setLegalName("FPT Software");
        cp.setIdentity(id);
        
        when(companyProfileRepository.searchByName(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(cp)));

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("What do we know about FPT?");

        AiChatResponse response = aiAssistantService.chat(request);

        assertNotNull(response.getNavigationActions());
        assertEquals(1, response.getNavigationActions().size());
        assertEquals("COMPANY_PROFILE", response.getNavigationActions().get(0).getType());
        assertEquals("cp-123", response.getNavigationActions().get(0).getCompanyProfileId());
    }

    @Test
    void managerCompanyCompareResponse_containsTwoNavigationActions() {
        when(projectSecurity.isMemberOrOwner(any())).thenReturn(true);
        when(assistantProvider.answer(anyString(), any())).thenReturn("Gemini answer");
        
        CompanyProfile cp1 = new CompanyProfile();
        cp1.setId("cp-1");
        cp1.setReviewStatus("APPROVED");
        CompanyProfile.Identity id1 = new CompanyProfile.Identity();
        id1.setLegalName("FPT Software");
        cp1.setIdentity(id1);

        CompanyProfile cp2 = new CompanyProfile();
        cp2.setId("cp-2");
        cp2.setReviewStatus("APPROVED");
        CompanyProfile.Identity id2 = new CompanyProfile.Identity();
        id2.setLegalName("CMC Telecom");
        cp2.setIdentity(id2);
        
        when(companyProfileRepository.searchByName(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(cp1, cp2)));

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("Compare FPT and CMC");

        AiChatResponse response = aiAssistantService.chat(request);

        assertNotNull(response.getNavigationActions());
        assertEquals(2, response.getNavigationActions().size());
    }

    @Test
    void unapprovedProfile_hasNoNavigationAction() {
        when(projectSecurity.isMemberOrOwner(any())).thenReturn(true);
        when(assistantProvider.answer(anyString(), any())).thenReturn("Gemini answer");
        
        CompanyProfile cp = new CompanyProfile();
        cp.setId("cp-123");
        cp.setReviewStatus("PENDING");
        CompanyProfile.Identity id = new CompanyProfile.Identity();
        id.setLegalName("FPT Software");
        cp.setIdentity(id);
        
        when(companyProfileRepository.searchByName(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(cp)));

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("What do we know about FPT?");

        AiChatResponse response = aiAssistantService.chat(request);

        assertTrue(response.getNavigationActions() == null || response.getNavigationActions().isEmpty());
    }

    @Test
    void unresolvedProfile_hasNoNavigationAction() {
        when(projectSecurity.isMemberOrOwner(any())).thenReturn(true);
        when(assistantProvider.answer(anyString(), any())).thenReturn("Gemini answer");
        
        when(companyProfileRepository.searchByName(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("What do we know about FPT?");

        AiChatResponse response = aiAssistantService.chat(request);

        assertTrue(response.getNavigationActions() == null || response.getNavigationActions().isEmpty());
    }

    @Test
    void internalNewsProtected_hasNoNavigationAction() {
        when(projectSecurity.isMemberOrOwner(any())).thenReturn(true);
        
        AiChatRequest request = new AiChatRequest();
        request.setQuestion("Show me the internal news about FPT");

        AiChatResponse response = aiAssistantService.chat(request);

        assertTrue(response.getNavigationActions() == null || response.getNavigationActions().isEmpty());
    }

    @Test
    void whatIsRelationshipBetweenOurCompanyAndFpt_mapsToCompanyRelationships() {
        when(projectSecurity.isMemberOrOwner(any())).thenReturn(true);
        
        CompanyProfile ownerProfile = new CompanyProfile();
        ownerProfile.setId("owner-id");
        ownerProfile.setCompanyId("O-123");
        CompanyProfile.Identity ownerId = new CompanyProfile.Identity();
        ownerId.setLegalName("OurCompany");
        ownerProfile.setIdentity(ownerId);

        CompanyProfile targetProfile = new CompanyProfile();
        targetProfile.setId("target-id");
        targetProfile.setCompanyId("T-123");
        targetProfile.setReviewStatus("APPROVED");
        CompanyProfile.Identity targetId = new CompanyProfile.Identity();
        targetId.setLegalName("FPT Software");
        targetProfile.setIdentity(targetId);

        when(ownerOrganizationService.resolveApprovedOwnerProfile()).thenReturn(ownerProfile);
        when(companyProfileRepository.searchByName(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(targetProfile)));

        com.apms.domain.graph.dto.CompanyRelationshipDto rel = com.apms.domain.graph.dto.CompanyRelationshipDto.builder()
                .sourceCompanyId("O-123")
                .targetCompanyId("T-123")
                .relationshipType("PARTNER_WITH")
                .build();
        
        when(graphService.getPairRelationships("O-123", "T-123")).thenReturn(List.of(rel));

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("What is the relationship between our company and FPT?");

        AiChatResponse response = aiAssistantService.chat(request);

        // Verify GraphService is called with correct Neo4j business companyId, NOT Mongo ID
        verify(graphService).getPairRelationships("O-123", "T-123");
        
        // Verify Gemini is bypassed and relationship is returned deterministically
        verify(assistantProvider, never()).answer(anyString(), any());
        assertTrue(response.getAnswer().contains("FPT Software is currently recorded as a Partner of your company in APMS"));
    }

    @Test
    public void whatRelationshipDoWeHaveWithMomo_returnsPotentialPartnerRelationship() {
        when(projectSecurity.isMemberOrOwner(any())).thenReturn(true);
        
        CompanyProfile ownerProfile = new CompanyProfile();
        ownerProfile.setId("owner-id");
        ownerProfile.setCompanyId("O-123");
        CompanyProfile.Identity ownerId = new CompanyProfile.Identity();
        ownerId.setLegalName("OurCompany");
        ownerProfile.setIdentity(ownerId);

        CompanyProfile targetProfile = new CompanyProfile();
        targetProfile.setId("target-id");
        targetProfile.setCompanyId("T-123");
        targetProfile.setReviewStatus("APPROVED");
        CompanyProfile.Identity targetId = new CompanyProfile.Identity();
        targetId.setLegalName("MoMo");
        targetProfile.setIdentity(targetId);

        when(ownerOrganizationService.resolveApprovedOwnerProfile()).thenReturn(ownerProfile);
        when(companyProfileRepository.searchByName(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(targetProfile)));

        when(graphService.getPairRelationships("O-123", "T-123")).thenReturn(List.of(
            com.apms.domain.graph.dto.CompanyRelationshipDto.builder()
                .sourceCompanyId("O-123")
                .targetCompanyId("T-123")
                .relationshipType("POTENTIAL_PARTNER_OF")
                .build()
        ));

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("What relationship do we have with momo");

        AiChatResponse response = aiAssistantService.chat(request);

        verify(assistantProvider, never()).answer(anyString(), any());
        assertTrue(response.getAnswer().contains("Potential Partner"));
    }

    @Test
    void pairRelationshipNoEdge_doesNotCallGemini() {
        when(projectSecurity.isMemberOrOwner(any())).thenReturn(true);
        
        CompanyProfile ownerProfile = new CompanyProfile();
        ownerProfile.setId("owner-id");
        ownerProfile.setCompanyId("O-123");
        CompanyProfile.Identity ownerId = new CompanyProfile.Identity();
        ownerId.setLegalName("OurCompany");
        ownerProfile.setIdentity(ownerId);

        CompanyProfile targetProfile = new CompanyProfile();
        targetProfile.setId("target-id");
        targetProfile.setCompanyId("T-123");
        targetProfile.setReviewStatus("APPROVED");
        CompanyProfile.Identity targetId = new CompanyProfile.Identity();
        targetId.setLegalName("VNPT");
        targetProfile.setIdentity(targetId);

        when(ownerOrganizationService.resolveApprovedOwnerProfile()).thenReturn(ownerProfile);
        when(companyProfileRepository.searchByName(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(targetProfile)));

        when(graphService.getPairRelationships("O-123", "T-123")).thenReturn(List.of());

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("What relationship do we have with VNPT?");

        AiChatResponse response = aiAssistantService.chat(request);

        // Verify Gemini is NEVER called for a no-relationship pair query
        verify(assistantProvider, never()).answer(anyString(), any());
        assertTrue(response.getAnswer().contains("No approved relationship between your company and VNPT is currently recorded"));
        
        // Verify Navigation action is STILL preserved!
        assertEquals(1, response.getNavigationActions().size());
        assertEquals("COMPANY_PROFILE", response.getNavigationActions().get(0).getType());
    }

    @Test
    void relationshipOverviewDoesNotBecomePairQuery() {
        when(projectSecurity.isMemberOrOwner(any())).thenReturn(true);
        
        CompanyProfile targetProfile = new CompanyProfile();
        targetProfile.setId("target-id");
        targetProfile.setCompanyId("T-123");
        targetProfile.setReviewStatus("APPROVED");
        CompanyProfile.Identity targetId = new CompanyProfile.Identity();
        targetId.setLegalName("FPT Software");
        targetProfile.setIdentity(targetId);

        when(companyProfileRepository.searchByName(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(targetProfile)));

        com.apms.domain.graph.dto.GraphCompanyDto graphData = com.apms.domain.graph.dto.GraphCompanyDto.builder()
            .relationships(List.of(com.apms.domain.graph.dto.CompanyRelationshipDto.builder()
                .relationshipType("CUSTOMER_OF")
                .targetCompanyId("Other-Company")
                .build()))
            .build();

        when(graphService.getCompanyNodeWithRelationships("T-123")).thenReturn(graphData);
        when(assistantProvider.answer(anyString(), any())).thenReturn("Overview answer");

        AiChatRequest request = new AiChatRequest();
        request.setQuestion("What relationships does FPT have?");

        aiAssistantService.chat(request);

        // Should not fetch owner profile or call pair relationship
        verify(ownerOrganizationService, never()).resolveApprovedOwnerProfile();
        verify(graphService, never()).getPairRelationships(anyString(), anyString());
        
        verify(assistantProvider).answer(anyString(), argThat(context -> 
            context.getContextText().contains("CUSTOMER_OF -> Other-Company")
        ));
    }
}

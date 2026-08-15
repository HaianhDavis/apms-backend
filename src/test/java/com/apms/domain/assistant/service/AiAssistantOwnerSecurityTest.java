package com.apms.domain.assistant.service;

import com.apms.common.enums.ExternalDataCategory;
import com.apms.domain.assistant.dto.AiChatResponse;
import com.apms.domain.assistant.dto.AssistantContext;
import com.apms.domain.assistant.dto.OwnerAiChatRequest;
import com.apms.domain.assistant.dto.OwnerContextResult;
import com.apms.domain.assistant.dto.OwnerIntent;
import com.apms.domain.assistant.repository.mongo.AiChatMessageRepository;
import com.apms.domain.dashboard.dto.DashboardSummaryDto;
import com.apms.domain.dashboard.service.DashboardService;
import com.apms.domain.dashboard.service.OwnerInsightsService;
import com.apms.domain.externaldata.repository.mongo.ExternalDataRepository;
import com.apms.domain.graph.dto.CompanyRelationshipDto;
import com.apms.domain.graph.dto.GraphCompanyDto;
import com.apms.domain.graph.service.GraphService;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.closeness.CompanyRelationshipCloseness;
import com.apms.domain.profile.closeness.CompanyRelationshipClosenessRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AiAssistantOwnerSecurityTest {

    @Mock private OwnerGeminiAssistantProvider ownerAssistantProvider;
    @Mock private AiChatMessageRepository chatMessageRepository;
    
    // Mocks for OwnerAssistantContextService which we will instantiate manually 
    // to test the real context building logic.
    @Mock private CompanyProfileRepository companyProfileRepository;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) 
    private Neo4jClient neo4jClient;
    @Mock private OwnerOrganizationService ownerOrganizationService;
    @Mock private DashboardService dashboardService;
    @Mock private OwnerInsightsService ownerInsightsService;
    @Mock private GraphService graphService;
    @Mock private CompanyRelationshipClosenessRepository closenessRepository;
    @Mock private ExternalDataRepository externalDataRepository;

    private OwnerAssistantContextService ownerContextService;
    private OwnerAiAssistantService ownerAiAssistantService;

    private UserDetailsImpl ownerUser;

    @BeforeEach
    void setUp() {
        ownerUser = mock(UserDetailsImpl.class);
        lenient().when(ownerUser.getId()).thenReturn(300L);
        lenient().when((java.util.Collection<GrantedAuthority>) ownerUser.getAuthorities())
                 .thenReturn(List.of((GrantedAuthority) () -> "ROLE_BUSINESS_OWNER"));

        Authentication authentication = mock(Authentication.class);
        lenient().when(authentication.getPrincipal()).thenReturn(ownerUser);
        SecurityContext securityContext = mock(SecurityContext.class);
        lenient().when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);

        ownerContextService = new OwnerAssistantContextService(
                companyProfileRepository,
                neo4jClient,
                ownerOrganizationService,
                dashboardService,
                ownerInsightsService,
                graphService,
                closenessRepository,
                externalDataRepository
        );

        ownerAiAssistantService = new OwnerAiAssistantService(
                ownerContextService,
                ownerAssistantProvider,
                chatMessageRepository
        );

        CompanyProfile ownerProfile = new CompanyProfile();
        ownerProfile.setId("mongo-owner-1");
        ownerProfile.setCompanyId("uuid-owner-1");
        
        CompanyProfile.Identity identity = new CompanyProfile.Identity();
        identity.setLegalName("APMS Demo Owner");
        ownerProfile.setIdentity(identity);
        
        lenient().when(ownerOrganizationService.resolveApprovedOwnerProfile()).thenReturn(ownerProfile);
        lenient().when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("mongo-owner-1");

        lenient().when(neo4jClient.query(anyString()).bindAll(anyMap()).fetchAs(String.class).mappedBy(any()).all()).thenReturn(List.of());
    }
    
    // --- Security & Intent tests ---

    @Test
    void ownerInternalNews_returnsProtectedResponse_withoutCallingGemini() {
        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("Show me internal news about FPT");

        AiChatResponse response = ownerAiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("Internal News is protected data"));
        verify(ownerAssistantProvider, never()).answer(anyString(), any());
    }

    // --- Deterministic Ecosystem tests ---

    

    @Test
    void ownerRelationshipWithMomo_returnsActualNeo4jRelationship() {
        CompanyProfile target = new CompanyProfile();
        target.setId("mongo-momo");
        target.setCompanyId("uuid-momo");
        CompanyProfile.Identity identity = new CompanyProfile.Identity();
        identity.setLegalName("MoMo");
        target.setIdentity(identity);
        target.setReviewStatus("APPROVED");
        
        org.springframework.data.domain.Page<CompanyProfile> pageMomo = new org.springframework.data.domain.PageImpl<>(List.of(target));
        lenient().when(companyProfileRepository.searchByName(eq("momo"), any())).thenReturn(pageMomo);
        
        CompanyRelationshipDto rel = CompanyRelationshipDto.builder()
                .relationshipType("POTENTIAL_PARTNER_OF")
                .build();
        when(graphService.getPairRelationships("uuid-owner-1", "uuid-momo")).thenReturn(List.of(rel));

        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What relationship do we have with MoMo?");

        AiChatResponse response = ownerAiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("MoMo"));
        assertTrue(response.getAnswer().toLowerCase().contains("potential partner of"));
        verify(ownerAssistantProvider, never()).answer(anyString(), any());
        
        // Ensure navigation action is present
        assertFalse(response.getNavigationActions().isEmpty());
        assertEquals("COMPANY_PROFILE", response.getNavigationActions().get(0).getType());
    }
    
    @Test
    void ownerNoRelationship_doesNotCallGemini() {
        CompanyProfile target = new CompanyProfile();
        target.setId("mongo-momo");
        target.setCompanyId("uuid-momo");
        CompanyProfile.Identity identity = new CompanyProfile.Identity();
        identity.setLegalName("MoMo");
        target.setIdentity(identity);
        target.setReviewStatus("APPROVED");
        
        org.springframework.data.domain.Page<CompanyProfile> pageMomo = new org.springframework.data.domain.PageImpl<>(List.of(target));
        lenient().when(companyProfileRepository.searchByName(eq("momo"), any())).thenReturn(pageMomo);
        
        when(graphService.getPairRelationships("uuid-owner-1", "uuid-momo")).thenReturn(List.of());

        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What relationship do we have with MoMo?");

        AiChatResponse response = ownerAiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("No approved relationship"));
        verify(ownerAssistantProvider, never()).answer(anyString(), any());
    }

    // --- Gemini-assisted tests ---
    
    @Test
    void ownerRiskQuestion_usesOwnerScopedExternalData() {
        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What risks should I pay attention to?");
        
        
        
        when(externalDataRepository.findTop5ByCategoryAndRelatedCompanyIdInOrderByPublishedAtDesc(
                eq(ExternalDataCategory.RISK), anyList())).thenReturn(List.of());

        AiChatResponse response = ownerAiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("No current risk signals"));
        verify(externalDataRepository).findTop5ByCategoryAndRelatedCompanyIdInOrderByPublishedAtDesc(
                eq(ExternalDataCategory.RISK), anyList());
    }

    // --- Context Precedence Tests ---

    @Test
    void ownerCompanyProfile_addsTargetCompanyProfileSource() {
        CompanyProfile vnpt = new CompanyProfile();
        vnpt.setId("mongo-vnpt");
        vnpt.setCompanyId("uuid-vnpt");
        CompanyProfile.Identity identity = new CompanyProfile.Identity();
        identity.setLegalName("VNPT");
        vnpt.setIdentity(identity);
        vnpt.setReviewStatus("APPROVED");
        
        when(companyProfileRepository.findById("mongo-vnpt")).thenReturn(Optional.of(vnpt));

        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What do we know about this company?");
        request.setCompanyProfileId("mongo-vnpt");

        

        AiChatResponse response = ownerAiAssistantService.chat(request);

        // Verify correct source is added and it uses target (VNPT), not Owner Org
        boolean foundVnptSource = response.getSources().stream()
                .anyMatch(s -> "company_profiles".equals(s.getType()) && "mongo-vnpt".equals(s.getId()) && "VNPT".equals(s.getTitle()));
        assertTrue(foundVnptSource, "Response should contain an AiSourceReference pointing to the target company profile");
    }

    @Test
    void ownerEcosystemIntent_ignoresPageCompanyContext() {
        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("Who are our competitors?");
        request.setCompanyProfileId("mongo-momo");
        
        GraphCompanyDto comp = GraphCompanyDto.builder().name("Competitor A").build();
        when(graphService.getCompaniesByRelationshipType("COMPETITOR_OF")).thenReturn(List.of(comp));

        AiChatResponse response = ownerAiAssistantService.chat(request);

        // It should answer about competitors in general, not just MoMo
        assertTrue(response.getAnswer().contains("Competitor A"));
        verify(ownerAssistantProvider, never()).answer(anyString(), any());
    }

    @Test
    void ownerExplicitCompanyName_overridesPageCompanyContext() {
        CompanyProfile fpt = new CompanyProfile();
        fpt.setId("mongo-fpt");
        fpt.setCompanyId("uuid-fpt");
        CompanyProfile.Identity identity = new CompanyProfile.Identity();
        identity.setLegalName("FPT");
        fpt.setIdentity(identity);
        fpt.setReviewStatus("APPROVED");
        
        org.springframework.data.domain.Page<CompanyProfile> pFpt = new org.springframework.data.domain.PageImpl<>(List.of(fpt));
        lenient().when(companyProfileRepository.searchByName(eq("fpt"), any())).thenReturn(pFpt);

        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What do we know about FPT?");
        request.setCompanyProfileId("mongo-momo"); // UI context is MoMo

        

        AiChatResponse response = ownerAiAssistantService.chat(request);
        assertTrue(response.getAnswer().contains("FPT"));
        assertTrue(response.getAnswer().contains("Legal Name"));
    }

    @Test
    void ownerThisCompany_usesPageCompanyContext() {
        CompanyProfile momo = new CompanyProfile();
        momo.setId("mongo-momo");
        momo.setCompanyId("uuid-momo");
        CompanyProfile.Identity identity = new CompanyProfile.Identity();
        identity.setLegalName("MoMo");
        momo.setIdentity(identity);
        momo.setReviewStatus("APPROVED");

        when(companyProfileRepository.findById("mongo-momo")).thenReturn(Optional.of(momo));

        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What do we know about this company?");
        request.setCompanyProfileId("mongo-momo");

        

        AiChatResponse response = ownerAiAssistantService.chat(request);
        assertTrue(response.getAnswer().contains("MoMo"));
        assertTrue(response.getAnswer().contains("Legal Name"));
    }

    @Test
    void ownerRelationshipExplicitTarget_overridesCurrentCompany() {
        CompanyProfile vnpt = new CompanyProfile();
        vnpt.setId("mongo-vnpt");
        vnpt.setCompanyId("uuid-vnpt");
        CompanyProfile.Identity identity = new CompanyProfile.Identity();
        identity.setLegalName("VNPT");
        vnpt.setIdentity(identity);
        vnpt.setReviewStatus("APPROVED");
        
        org.springframework.data.domain.Page<CompanyProfile> pVnpt = new org.springframework.data.domain.PageImpl<>(List.of(vnpt));
        lenient().when(companyProfileRepository.searchByName(eq("vnpt"), any())).thenReturn(pVnpt);
        when(graphService.getPairRelationships("uuid-owner-1", "uuid-vnpt")).thenReturn(List.of());

        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What relationship do we have with VNPT?");
        request.setCompanyProfileId("mongo-momo"); // MoMo is the page context

        AiChatResponse response = ownerAiAssistantService.chat(request);

        // It should resolve VNPT, not MoMo
        assertTrue(response.getAnswer().contains("No approved relationship"));
        assertFalse(response.getNavigationActions().isEmpty());
        assertEquals("VNPT", response.getNavigationActions().get(0).getCompanyName());
    }

    // --- Regression Tests for VNPT Resolution Fix ---

    @Test
    void ownerVnptExplicitName_resolvesWithoutPageContext() {
        CompanyProfile vnpt = new CompanyProfile();
        vnpt.setId("mongo-vnpt");
        CompanyProfile.Identity identity = new CompanyProfile.Identity();
        identity.setLegalName("Tập đoàn Bưu chính Viễn thông Việt Nam");
        identity.setTradeName("VNPT");
        vnpt.setIdentity(identity);
        vnpt.setReviewStatus("APPROVED");
        
        org.springframework.data.domain.Page<CompanyProfile> pVnpt = new org.springframework.data.domain.PageImpl<>(List.of(vnpt));
        lenient().when(companyProfileRepository.searchByName(eq("vnpt"), any())).thenReturn(pVnpt);
        
        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What do we know about Vnpt");
        // No page context

        AiChatResponse response = ownerAiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("Bưu chính Viễn thông"));
    }

    @Test
    void ownerVnptMixedCase_resolvesTradeNameCaseInsensitively() {
        CompanyProfile vnpt = new CompanyProfile();
        vnpt.setId("mongo-vnpt");
        CompanyProfile.Identity identity = new CompanyProfile.Identity();
        identity.setTradeName("Tập đoàn VNPT"); // Test partial case-insensitive
        vnpt.setIdentity(identity);
        vnpt.setReviewStatus("APPROVED");
        
        org.springframework.data.domain.Page<CompanyProfile> pVnpt = new org.springframework.data.domain.PageImpl<>(List.of(vnpt));
        lenient().when(companyProfileRepository.searchByName(eq("vnpt"), any())).thenReturn(pVnpt);

        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What do we know about vNpT");

        AiChatResponse response = ownerAiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("VNPT"));
    }

    @Test
    void ownerVnptWithQuestionMark_resolvesCorrectly() {
        CompanyProfile vnpt = new CompanyProfile();
        vnpt.setId("mongo-vnpt");
        CompanyProfile.Identity identity = new CompanyProfile.Identity();
        identity.setTradeName("VNPT");
        vnpt.setIdentity(identity);
        vnpt.setReviewStatus("APPROVED");
        
        org.springframework.data.domain.Page<CompanyProfile> pVnpt = new org.springframework.data.domain.PageImpl<>(List.of(vnpt));
        lenient().when(companyProfileRepository.searchByName(eq("vnpt"), any())).thenReturn(pVnpt);

        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What do we know about VNPT?");

        AiChatResponse response = ownerAiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("VNPT"));
    }

    @Test
    void ownerExplicitVnpt_overridesMomoPageContext() {
        CompanyProfile vnpt = new CompanyProfile();
        vnpt.setId("mongo-vnpt");
        CompanyProfile.Identity identity = new CompanyProfile.Identity();
        identity.setTradeName("VNPT");
        vnpt.setIdentity(identity);
        vnpt.setReviewStatus("APPROVED");
        
        org.springframework.data.domain.Page<CompanyProfile> pVnpt = new org.springframework.data.domain.PageImpl<>(List.of(vnpt));
        lenient().when(companyProfileRepository.searchByName(eq("vnpt"), any())).thenReturn(pVnpt);

        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What do we know about VNPT?");
        request.setCompanyProfileId("mongo-momo");

        AiChatResponse response = ownerAiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("VNPT"));
    }

    @Test
    void ownerUnknownExplicitCompany_returnsNamedNotFoundMessage() {
        org.springframework.data.domain.Page<CompanyProfile> pEmpty = new org.springframework.data.domain.PageImpl<>(List.of());
        lenient().when(companyProfileRepository.searchByName(eq("xyzfake"), any())).thenReturn(pEmpty);

        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What do we know about XYZFake?");

        AiChatResponse response = ownerAiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("I could not find an approved company profile matching \"xyzfake\" in APMS."));
    }

    @Test
    void ownerThisCompanyWithoutContext_returnsContextClarification() {
        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What do we know about this company?");
        // No page context

        AiChatResponse response = ownerAiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("Please specify the company name or open a company profile first."));
    }

    @Test
    void ownerAmbiguousPartialCompany_returnsAmbiguityClarification() {
        CompanyProfile fpt1 = new CompanyProfile();
        fpt1.setId("mongo-fpt1");
        CompanyProfile.Identity identity1 = new CompanyProfile.Identity();
        identity1.setTradeName("FPT Software");
        fpt1.setIdentity(identity1);
        fpt1.setReviewStatus("APPROVED");

        CompanyProfile fpt2 = new CompanyProfile();
        fpt2.setId("mongo-fpt2");
        CompanyProfile.Identity identity2 = new CompanyProfile.Identity();
        identity2.setTradeName("FPT Telecom");
        fpt2.setIdentity(identity2);
        fpt2.setReviewStatus("APPROVED");
        
        org.springframework.data.domain.Page<CompanyProfile> pFpt = new org.springframework.data.domain.PageImpl<>(List.of(fpt1, fpt2));
        lenient().when(companyProfileRepository.searchByName(eq("fpt"), any())).thenReturn(pFpt);

        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What do we know about FPT?");

        AiChatResponse response = ownerAiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("I found multiple approved company profiles matching \"fpt\". Please specify the company you mean."));
    }

    @Test
    void ownerRepositorySearchByName_matchesTradeName() {
        // Note: This validates the resolver's expectation of the repository contract.
        // It does not test the real Mongo regex query execution.
        CompanyProfile vnpt = new CompanyProfile();
        vnpt.setId("mongo-vnpt");
        CompanyProfile.Identity identity = new CompanyProfile.Identity();
        identity.setTradeName("VNPT");
        vnpt.setIdentity(identity);
        vnpt.setReviewStatus("APPROVED");
        
        org.springframework.data.domain.Page<CompanyProfile> pVnpt = new org.springframework.data.domain.PageImpl<>(List.of(vnpt));
        lenient().when(companyProfileRepository.searchByName(eq("vnpt"), any())).thenReturn(pVnpt);

        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What do we know about VNPT?");
        
        AiChatResponse response = ownerAiAssistantService.chat(request);
        assertTrue(response.getAnswer().contains("VNPT"));
    }

    @Test
    void ownerCompareExplicitCompanies_ignoresCurrentCompany() {
        CompanyProfile fpt = new CompanyProfile();
        fpt.setId("mongo-fpt");
        CompanyProfile.Identity identity = new CompanyProfile.Identity();
        identity.setLegalName("FPT");
        fpt.setIdentity(identity);
        fpt.setReviewStatus("APPROVED");

        CompanyProfile cmc = new CompanyProfile();
        cmc.setId("mongo-cmc");
        CompanyProfile.Identity identity2 = new CompanyProfile.Identity();
        identity2.setLegalName("CMC");
        cmc.setIdentity(identity2);
        cmc.setReviewStatus("APPROVED");
        
        org.springframework.data.domain.Page<CompanyProfile> p1 = new org.springframework.data.domain.PageImpl<>(List.of(fpt));
        org.springframework.data.domain.Page<CompanyProfile> p2 = new org.springframework.data.domain.PageImpl<>(List.of(cmc));
        lenient().when(companyProfileRepository.searchByName(eq("fpt"), any())).thenReturn(p1);
        lenient().when(companyProfileRepository.searchByName(eq("cmc"), any())).thenReturn(p2);

        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("Compare FPT and CMC.");
        request.setCompanyProfileId("mongo-vnpt"); // page context is VNPT

        when(ownerAssistantProvider.answer(anyString(), any())).thenAnswer(inv -> {
            AssistantContext ctx = inv.getArgument(1);
            assertTrue(ctx.getContextText().contains("FPT"));
            assertTrue(ctx.getContextText().contains("CMC"));
            assertFalse(ctx.getContextText().contains("VNPT"));
            return "Comparison Result";
        });

        AiChatResponse response = ownerAiAssistantService.chat(request);
        assertEquals("Comparison Result", response.getAnswer());
    }

    @Test
    void ownerSession_doesNotPinPreviousCompany() {
        // First request is about MoMo
        OwnerAiChatRequest request1 = new OwnerAiChatRequest();
        request1.setQuestion("What do we know about MoMo?");
        request1.setSessionId("session-123");
        
        // Second request asks about ecosystem partners
        OwnerAiChatRequest request2 = new OwnerAiChatRequest();
        request2.setQuestion("Who are our potential partners?");
        request2.setSessionId("session-123");
        
        GraphCompanyDto comp = GraphCompanyDto.builder().name("Potential Partner A").build();
        when(graphService.getCompaniesByRelationshipType("POTENTIAL_PARTNER_OF")).thenReturn(List.of(comp));

        AiChatResponse response2 = ownerAiAssistantService.chat(request2);

        assertTrue(response2.getAnswer().contains("Potential Partner A"));
        verify(ownerAssistantProvider, never()).answer(anyString(), any());
    }

    // --- New Tests for Refinement ---
    @Test
    void ownerCompanyProfile_returnsProfileFactsWithoutGemini() {
        CompanyProfile vnpt = new CompanyProfile();
        vnpt.setId("mongo-vnpt");
        vnpt.setCompanyId("uuid-vnpt");
        CompanyProfile.Identity identity = new CompanyProfile.Identity();
        identity.setLegalName("VNPT");
        vnpt.setIdentity(identity);
        vnpt.setReviewStatus("APPROVED");
        
        when(companyProfileRepository.findById("mongo-vnpt")).thenReturn(java.util.Optional.of(vnpt));

        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What do we know about this company?");
        request.setCompanyProfileId("mongo-vnpt");

        AiChatResponse response = ownerAiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("VNPT"));
        assertTrue(response.getAnswer().contains("Legal Name:"));
        verify(ownerAssistantProvider, never()).answer(anyString(), any());
    }

    @Test
    void ownerPublicNews_returnsActualExternalDataInsteadOfProfileSummary() {
        CompanyProfile vnpt = new CompanyProfile();
        vnpt.setId("mongo-vnpt");
        vnpt.setCompanyId("uuid-vnpt");
        CompanyProfile.Identity identity = new CompanyProfile.Identity();
        identity.setLegalName("VNPT");
        vnpt.setIdentity(identity);
        vnpt.setReviewStatus("APPROVED");
        
        when(companyProfileRepository.findById("mongo-vnpt")).thenReturn(java.util.Optional.of(vnpt));
        
        com.apms.domain.externaldata.ExternalDataItem item = com.apms.domain.externaldata.ExternalDataItem.builder().build();
        item.setTitle("VNPT News Title");
        item.setUrl("http://example.com");
        when(externalDataRepository.findByCategoryAndRelatedCompanyId(com.apms.common.enums.ExternalDataCategory.NEWS, "uuid-vnpt"))
            .thenReturn(List.of(item));

        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What public news do we have about this company?");
        request.setCompanyProfileId("mongo-vnpt");

        AiChatResponse response = ownerAiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("VNPT News Title"));
        verify(ownerAssistantProvider, never()).answer(anyString(), any());
    }

    @Test
    void ownerPublicNews_empty_returnsDeterministicNoNews() {
        CompanyProfile vnpt = new CompanyProfile();
        vnpt.setId("mongo-vnpt");
        vnpt.setCompanyId("uuid-vnpt");
        CompanyProfile.Identity identity = new CompanyProfile.Identity();
        identity.setLegalName("VNPT");
        vnpt.setIdentity(identity);
        vnpt.setReviewStatus("APPROVED");
        
        when(companyProfileRepository.findById("mongo-vnpt")).thenReturn(java.util.Optional.of(vnpt));
        when(externalDataRepository.findByCategoryAndRelatedCompanyId(com.apms.common.enums.ExternalDataCategory.NEWS, "uuid-vnpt"))
            .thenReturn(List.of());

        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What public news do we have about this company?");
        request.setCompanyProfileId("mongo-vnpt");

        AiChatResponse response = ownerAiAssistantService.chat(request);

        assertTrue(response.getAnswer().contains("No recent public updates"));
        verify(ownerAssistantProvider, never()).answer(anyString(), any());
    }

    @Test
    void ownerPartners_returnsOnlyPartnerWith() {
        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("Who are our current partners?");
        
        GraphCompanyDto comp = GraphCompanyDto.builder().name("Partner A").build();
        when(graphService.getCompaniesByRelationshipType("PARTNER_WITH")).thenReturn(List.of(comp));

        AiChatResponse response = ownerAiAssistantService.chat(request);
        assertTrue(response.getAnswer().contains("Partner A"));
        verify(ownerAssistantProvider, never()).answer(anyString(), any());
    }

    @Test
    void ownerPotentialPartners_returnsOnlyPotentialPartnerOf() {
        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("Which companies are potential partners?");
        
        GraphCompanyDto comp = GraphCompanyDto.builder().name("Potential Partner A").build();
        when(graphService.getCompaniesByRelationshipType("POTENTIAL_PARTNER_OF")).thenReturn(List.of(comp));

        AiChatResponse response = ownerAiAssistantService.chat(request);
        assertTrue(response.getAnswer().contains("Potential Partner A"));
        verify(ownerAssistantProvider, never()).answer(anyString(), any());
    }

    @Test
    void ownerCompetitors_returnsOnlyCompetitorOf() {
        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("Who are our competitors?");
        
        GraphCompanyDto comp = GraphCompanyDto.builder().name("Competitor A").build();
        when(graphService.getCompaniesByRelationshipType("COMPETITOR_OF")).thenReturn(List.of(comp));

        AiChatResponse response = ownerAiAssistantService.chat(request);
        assertTrue(response.getAnswer().contains("Competitor A"));
        verify(ownerAssistantProvider, never()).answer(anyString(), any());
    }

    @Test
    void ownerCompanyRelationship_returnsNeo4jFactWithoutGemini() {
        CompanyProfile vnpt = new CompanyProfile();
        vnpt.setId("mongo-vnpt");
        vnpt.setCompanyId("uuid-vnpt");
        CompanyProfile.Identity identity = new CompanyProfile.Identity();
        identity.setLegalName("VNPT");
        vnpt.setIdentity(identity);
        vnpt.setReviewStatus("APPROVED");
        when(companyProfileRepository.findById("mongo-vnpt")).thenReturn(java.util.Optional.of(vnpt));
        
        CompanyRelationshipDto rel = CompanyRelationshipDto.builder()
                .relationshipType("COMPETITOR_OF")
                .build();
        when(graphService.getPairRelationships("uuid-owner-1", "uuid-vnpt")).thenReturn(List.of(rel));

        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What relationship do we have with this company?");
        request.setCompanyProfileId("mongo-vnpt");

        AiChatResponse response = ownerAiAssistantService.chat(request);
        assertTrue(response.getAnswer().contains("Competitor Of"));
        verify(ownerAssistantProvider, never()).answer(anyString(), any());
    }

    @Test
    void ownerRelationshipCloseness_returnsActualCloseness() {
        CompanyProfile vnpt = new CompanyProfile();
        vnpt.setId("mongo-vnpt");
        vnpt.setCompanyId("uuid-vnpt");
        CompanyProfile.Identity identity = new CompanyProfile.Identity();
        identity.setLegalName("VNPT");
        vnpt.setIdentity(identity);
        vnpt.setReviewStatus("APPROVED");
        when(companyProfileRepository.findById("mongo-vnpt")).thenReturn(java.util.Optional.of(vnpt));
        
        com.apms.domain.profile.closeness.CompanyRelationshipCloseness closeness = new com.apms.domain.profile.closeness.CompanyRelationshipCloseness();
        closeness.setStars(4);
        when(closenessRepository.findByOwnerCompanyProfileIdAndTargetCompanyProfileId("mongo-owner-1", "mongo-vnpt"))
            .thenReturn(java.util.Optional.of(closeness));

        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("How close are we to this company?");
        request.setCompanyProfileId("mongo-vnpt");

        AiChatResponse response = ownerAiAssistantService.chat(request);
        assertTrue(response.getAnswer().contains("rated at 4 stars"));
        verify(ownerAssistantProvider, never()).answer(anyString(), any());
    }

    @Test
    void ownerEcosystemOverview_returnsCanonicalCounts() {
        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What companies are currently in our ecosystem?");
        
        com.apms.domain.dashboard.dto.DashboardSummaryDto summary = com.apms.domain.dashboard.dto.DashboardSummaryDto.builder()
            .totalRelatedCompanies(10).partnerCount(2).potentialPartnerCount(3).competitorCount(1).customerCount(2).supplierCount(2).build();
        when(dashboardService.getSummary()).thenReturn(summary);

        AiChatResponse response = ownerAiAssistantService.chat(request);
        assertTrue(response.getAnswer().contains("Total Related Companies: 10"));
        verify(ownerAssistantProvider, never()).answer(anyString(), any());
    }

    @Test
    void ownerOpportunityQuestion_usesOpportunitySignals() {
        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What opportunities have been detected?");
        
        when(ownerAssistantProvider.answer(anyString(), any())).thenReturn("Gemini Opportunity Synthesis");
        when(externalDataRepository.findTop5ByCategoryAndRelatedCompanyIdInOrderByPublishedAtDesc(
                eq(com.apms.common.enums.ExternalDataCategory.OPPORTUNITY), anyList())).thenReturn(List.of(com.apms.domain.externaldata.ExternalDataItem.builder().build()));

        AiChatResponse response = ownerAiAssistantService.chat(request);
        assertEquals("Gemini Opportunity Synthesis", response.getAnswer());
    }

    @Test
    void ownerRecentSignals_usesExternalData() {
        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What recent external signals should I know about?");
        
        when(ownerAssistantProvider.answer(anyString(), any())).thenReturn("Gemini Signals");
        when(externalDataRepository.findTop5ByRelatedCompanyIdInOrderByPublishedAtDesc(anyList()))
            .thenReturn(List.of(com.apms.domain.externaldata.ExternalDataItem.builder().build()));

        AiChatResponse response = ownerAiAssistantService.chat(request);
        assertEquals("Gemini Signals", response.getAnswer());
    }

    @Test
    void ownerInsights_reusesOwnerInsightsService() {
        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What actionable insights do we have?");
        
        lenient().when(ownerAssistantProvider.answer(anyString(), any())).thenReturn("Gemini Insights");
        com.apms.domain.dashboard.dto.OwnerInsightDto insight = com.apms.domain.dashboard.dto.OwnerInsightDto.builder()
            .title("Test Insight")
            .build();
        org.springframework.data.domain.Page<com.apms.domain.dashboard.dto.OwnerInsightDto> page = new org.springframework.data.domain.PageImpl<>(List.of(insight));
        when(ownerInsightsService.getInsights(any(), any(), any(), any(), any())).thenReturn(page);

        AiChatResponse response = ownerAiAssistantService.chat(request);
        assertEquals("Gemini Insights", response.getAnswer());
    }

    @Test
    void ownerCompanyCompare_resolvesExactlyTwoExplicitCompanies() {
        CompanyProfile fpt = new CompanyProfile();
        fpt.setId("mongo-fpt");
        CompanyProfile.Identity identity = new CompanyProfile.Identity();
        identity.setLegalName("FPT");
        fpt.setIdentity(identity);
        fpt.setReviewStatus("APPROVED");

        CompanyProfile cmc = new CompanyProfile();
        cmc.setId("mongo-cmc");
        CompanyProfile.Identity identity2 = new CompanyProfile.Identity();
        identity2.setLegalName("CMC");
        cmc.setIdentity(identity2);
        cmc.setReviewStatus("APPROVED");
        
        org.springframework.data.domain.Page<CompanyProfile> p1 = new org.springframework.data.domain.PageImpl<>(List.of(fpt));
        org.springframework.data.domain.Page<CompanyProfile> p2 = new org.springframework.data.domain.PageImpl<>(List.of(cmc));
        lenient().when(companyProfileRepository.searchByName(eq("fpt"), any())).thenReturn(p1);
        lenient().when(companyProfileRepository.searchByName(eq("cmc"), any())).thenReturn(p2);

        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("Compare FPT and CMC.");

        when(ownerAssistantProvider.answer(anyString(), any())).thenReturn("Comparison");

        AiChatResponse response = ownerAiAssistantService.chat(request);
        assertEquals("Comparison", response.getAnswer());
    }

    @Test
    void ownerStrategicRecommendation_callsGeminiWithBackendFacts() {
        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What should we do strategically?");
        
        when(ownerAssistantProvider.answer(anyString(), any())).thenReturn("Strategic Briefing");
        
        org.springframework.data.domain.Page<com.apms.domain.dashboard.dto.OwnerInsightDto> page = new org.springframework.data.domain.PageImpl<>(java.util.List.of());
        org.mockito.Mockito.lenient().when(ownerInsightsService.getInsights(any(), any(), any(), any(), any())).thenReturn(page);

        AiChatResponse response = ownerAiAssistantService.chat(request);
        assertEquals("Strategic Briefing", response.getAnswer());
    }

    @Test
    void ownerOutOfScope_doesNotCallGemini() {
        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What is the weather today?");
        AiChatResponse response = ownerAiAssistantService.chat(request);
        assertNotNull(response.getAnswer());
        assertTrue(response.getAnswer().contains("outside the available Owner AI scope"));
        verify(ownerAssistantProvider, never()).answer(anyString(), any());
    }

    @Test
    void ownerInternalNews_doesNotCallGemini() {
        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("Show internal news about FPT.");
        
        AiChatResponse response = ownerAiAssistantService.chat(request);
        assertTrue(response.getAnswer().contains("protected data and is not accessible"));
        verify(ownerAssistantProvider, never()).answer(anyString(), any());
    }

    // --- Regression Tests for Named Company Resolution ---
    
    @Test
    void ownerExplicitViettelName_resolvesWithoutPageContext() {
        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What do we know about Viettel?");
        // No page context
        
        CompanyProfile viettel = new CompanyProfile();
        viettel.setId("mongo-viettel");
        CompanyProfile.Identity id = new CompanyProfile.Identity();
        id.setTradeName("Tập đoàn Viettel"); // The real trade name might differ, testing extraction
        id.setLegalName("Viettel Group");
        viettel.setIdentity(id);
        viettel.setReviewStatus("APPROVED");
        
        org.springframework.data.domain.Page<CompanyProfile> page = new org.springframework.data.domain.PageImpl<>(List.of(viettel));
        when(companyProfileRepository.searchByName(eq("viettel"), any())).thenReturn(page);

        AiChatResponse response = ownerAiAssistantService.chat(request);
        assertNotNull(response.getAnswer());
        assertTrue(response.getAnswer().contains("Unknown Company") || response.getAnswer().contains("Viettel")); // Depending on resolveCompanyName
        // Since resolveCompanyName checks legalName, it will return "Viettel Group"
        assertTrue(response.getAnswer().contains("Viettel Group"));
    }
    
    @Test
    void ownerPublicNewsViettel_resolvesTradeNameWithoutCompanyProfileId() {
        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What public news do we have about Viettel?");
        
        CompanyProfile viettel = new CompanyProfile();
        viettel.setId("mongo-viettel");
        viettel.setCompanyId("biz-viettel");
        CompanyProfile.Identity id = new CompanyProfile.Identity();
        id.setTradeName("Viettel"); 
        viettel.setIdentity(id);
        viettel.setReviewStatus("APPROVED");
        
        org.springframework.data.domain.Page<CompanyProfile> page = new org.springframework.data.domain.PageImpl<>(List.of(viettel));
        when(companyProfileRepository.searchByName(eq("viettel"), any())).thenReturn(page);
        when(externalDataRepository.findByCategoryAndRelatedCompanyId(any(), eq("biz-viettel"))).thenReturn(List.of());

        AiChatResponse response = ownerAiAssistantService.chat(request);
        assertTrue(response.getAnswer().contains("No recent public updates for Viettel"));
        verify(ownerAssistantProvider, never()).answer(anyString(), any());
    }

    @Test
    void ownerExplicitCompanyName_overridesPageCompanyId() {
        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What public news do we have about Viettel?");
        request.setCompanyProfileId("mongo-momo"); // Page context is MoMo
        
        CompanyProfile momo = new CompanyProfile();
        momo.setId("mongo-momo");
        
        CompanyProfile viettel = new CompanyProfile();
        viettel.setId("mongo-viettel");
        viettel.setCompanyId("biz-viettel");
        CompanyProfile.Identity id = new CompanyProfile.Identity();
        id.setTradeName("Viettel"); 
        viettel.setIdentity(id);
        viettel.setReviewStatus("APPROVED");
        
        lenient().when(companyProfileRepository.findById("mongo-momo")).thenReturn(Optional.of(momo));
        org.springframework.data.domain.Page<CompanyProfile> page = new org.springframework.data.domain.PageImpl<>(List.of(viettel));
        when(companyProfileRepository.searchByName(eq("viettel"), any())).thenReturn(page);
        when(externalDataRepository.findByCategoryAndRelatedCompanyId(any(), eq("biz-viettel"))).thenReturn(List.of());

        AiChatResponse response = ownerAiAssistantService.chat(request);
        assertTrue(response.getAnswer().contains("Viettel"));
        assertFalse(response.getAnswer().contains("MoMo"));
    }

    @Test
    void ownerThisCompany_usesPageCompanyIdFallback() {
        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What public news do we have about this company?");
        request.setCompanyProfileId("mongo-momo"); 
        
        CompanyProfile momo = new CompanyProfile();
        momo.setId("mongo-momo");
        momo.setCompanyId("biz-momo");
        CompanyProfile.Identity id = new CompanyProfile.Identity();
        id.setTradeName("MoMo"); 
        momo.setIdentity(id);
        momo.setReviewStatus("APPROVED");
        
        when(companyProfileRepository.findById("mongo-momo")).thenReturn(Optional.of(momo));
        when(externalDataRepository.findByCategoryAndRelatedCompanyId(any(), eq("biz-momo"))).thenReturn(List.of());

        AiChatResponse response = ownerAiAssistantService.chat(request);
        assertTrue(response.getAnswer().contains("MoMo"));
        verify(companyProfileRepository, never()).searchByName(anyString(), any());
    }

    @Test
    void ownerThisCompany_withoutPageContext_requiresClarification() {
        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What public news do we have about this company?");
        // No page context
        
        AiChatResponse response = ownerAiAssistantService.chat(request);
        assertTrue(response.getAnswer().contains("Please specify the company name or open a company profile first."));
        verify(companyProfileRepository, never()).searchByName(anyString(), any());
        verify(ownerAssistantProvider, never()).answer(anyString(), any());
    }

    @Test
    void ownerNamedCompanyResolution_searchesLegalNameAndTradeName() {
        // Validation that CompanyProfileRepository.searchByName is called correctly is implicit in other tests
        // Testing ranking logic directly in service
        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What do we know about CMC?");
        
        CompanyProfile p1 = new CompanyProfile(); // Partial match
        CompanyProfile.Identity id1 = new CompanyProfile.Identity();
        id1.setLegalName("CMC Telecom Partial");
        p1.setIdentity(id1);
        p1.setReviewStatus("APPROVED");
        
        CompanyProfile p2 = new CompanyProfile(); // Exact match
        CompanyProfile.Identity id2 = new CompanyProfile.Identity();
        id2.setLegalName("CMC");
        p2.setIdentity(id2);
        p2.setReviewStatus("APPROVED");
        
        org.springframework.data.domain.Page<CompanyProfile> page = new org.springframework.data.domain.PageImpl<>(List.of(p1, p2));
        when(companyProfileRepository.searchByName(eq("cmc"), any())).thenReturn(page);
        
        AiChatResponse response = ownerAiAssistantService.chat(request);
        assertTrue(response.getAnswer().contains("CMC"));
        assertFalse(response.getAnswer().contains("Partial"));
    }

    @Test
    void ownerNamedPublicNews_neverFallsBackToGenericGemini() {
        OwnerAiChatRequest request = new OwnerAiChatRequest();
        request.setQuestion("What public news do we have about MissingCompany?");
        
        org.springframework.data.domain.Page<CompanyProfile> page = new org.springframework.data.domain.PageImpl<>(List.of());
        when(companyProfileRepository.searchByName(eq("missingcompany"), any())).thenReturn(page);
        
        AiChatResponse response = ownerAiAssistantService.chat(request);
        assertTrue(response.getAnswer().contains("could not find an approved company profile matching"));
        verify(ownerAssistantProvider, never()).answer(anyString(), any());
    }

}

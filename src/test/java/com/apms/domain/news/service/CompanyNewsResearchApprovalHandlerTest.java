package com.apms.domain.news.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionType;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.news.entity.CompanyIntelligenceArticle;
import com.apms.domain.news.entity.CompanyNewsResearchDraft;
import com.apms.domain.news.entity.CompanyNewsResearchSubmissionPayload;
import com.apms.domain.news.enums.ConfidentialityLevel;
import com.apms.domain.news.enums.NewsDraftStatus;
import com.apms.domain.news.repository.CompanyIntelligenceArticleRepository;
import com.apms.domain.news.repository.CompanyNewsResearchDraftRepository;
import com.apms.domain.news.repository.CompanyNewsResearchSubmissionPayloadRepository;
import com.apms.domain.project.ProjectTaskSubmission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyNewsResearchApprovalHandlerTest {

    @Mock
    private CompanyNewsResearchSubmissionPayloadRepository payloadRepository;
    @Mock
    private CompanyNewsResearchDraftRepository draftRepository;
    @Mock
    private CompanyIntelligenceArticleRepository articleRepository;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private CompanyNewsResearchApprovalHandler handler;

    private ProjectTaskSubmission submission;
    private CompanyNewsResearchSubmissionPayload payload;
    private CompanyNewsResearchDraft draft1;
    private CompanyNewsResearchDraft draft2;

    @BeforeEach
    void setUp() {
        submission = new ProjectTaskSubmission();
        submission.setId(100L);

        payload = CompanyNewsResearchSubmissionPayload.builder()
                .id("payload1")
                .submissionId(100L)
                .projectId(10L)
                .taskId(20L)
                .targetCompanyProfileId("COMP1")
                .newsDraftIds(List.of("draft1", "draft2"))
                .build();

        draft1 = new CompanyNewsResearchDraft();
        draft1.setId("draft1");
        draft1.setTitle("Title 1");
        draft1.setContent("Content 1");
        draft1.setImageStorageKey("img1");
        draft1.setReviewStatus(NewsDraftStatus.SUBMITTED);
        draft1.setCreatedByAccountId(99L);

        draft2 = new CompanyNewsResearchDraft();
        draft2.setId("draft2");
        draft2.setTitle("Title 2");
        draft2.setContent("Content 2");
        draft2.setReviewStatus(NewsDraftStatus.SUBMITTED);
        draft2.setCreatedByAccountId(99L);
    }

    @Test
    void supportsOnlyCompanyNewsResearch() {
        assertTrue(handler.supports(SubmissionType.COMPANY_NEWS_RESEARCH));
        assertFalse(handler.supports(SubmissionType.DOCUMENT_COLLECTION));
    }

    @Test
    void approvedSubmissionCreatesOfficialArticles() {
        when(payloadRepository.findBySubmissionId(100L)).thenReturn(Optional.of(payload));
        when(draftRepository.findById("draft1")).thenReturn(Optional.of(draft1));
        when(draftRepository.findById("draft2")).thenReturn(Optional.of(draft2));
        
        when(articleRepository.existsBySourceDraftId("draft1")).thenReturn(false);
        when(articleRepository.existsBySourceDraftId("draft2")).thenReturn(false);
        
        when(articleRepository.save(any(CompanyIntelligenceArticle.class))).thenAnswer(invocation -> {
            CompanyIntelligenceArticle article = invocation.getArgument(0);
            article.setId("dummy_id");
            return article;
        });

        handler.handleApproval(submission, 1L, "Looks good");

        ArgumentCaptor<CompanyIntelligenceArticle> articleCaptor = ArgumentCaptor.forClass(CompanyIntelligenceArticle.class);
        verify(articleRepository, times(2)).save(articleCaptor.capture());

        List<CompanyIntelligenceArticle> articles = articleCaptor.getAllValues();
        assertEquals(2, articles.size());

        CompanyIntelligenceArticle a1 = articles.get(0);
        assertEquals("COMP1", a1.getCompanyProfileId());
        assertEquals("Title 1", a1.getTitle());
        assertEquals("Content 1", a1.getContent());
        assertEquals("img1", a1.getImageStorageKey());
        assertEquals(ConfidentialityLevel.CONFIDENTIAL, a1.getConfidentialityLevel());
        assertEquals(10L, a1.getSourceProjectId());
        assertEquals(20L, a1.getSourceTaskId());
        assertEquals(100L, a1.getSourceSubmissionId());
        assertEquals("draft1", a1.getSourceDraftId());
        assertEquals(99L, a1.getCreatedByAccountId());
        assertEquals(1L, a1.getApprovedByAccountId());

        CompanyIntelligenceArticle a2 = articles.get(1);
        assertEquals("Title 2", a2.getTitle());
        assertEquals("draft2", a2.getSourceDraftId());

        assertEquals(NewsDraftStatus.APPROVED, draft1.getReviewStatus());
        assertEquals(NewsDraftStatus.APPROVED, draft2.getReviewStatus());
        verify(draftRepository).saveAll(any());
        verify(auditLogService).log(eq(1L), eq(AuditAction.COMPANY_NEWS_RESEARCH_APPROVED), any(), any(), any());
    }

    @Test
    void repeatedApprovalDoesNotCreateDuplicateArticles() {
        when(payloadRepository.findBySubmissionId(100L)).thenReturn(Optional.of(payload));
        when(draftRepository.findById("draft1")).thenReturn(Optional.of(draft1));
        when(draftRepository.findById("draft2")).thenReturn(Optional.of(draft2));
        
        when(articleRepository.existsBySourceDraftId("draft1")).thenReturn(true); // already exists
        when(articleRepository.existsBySourceDraftId("draft2")).thenReturn(true);

        handler.handleApproval(submission, 1L, "Looks good");

        verify(articleRepository, never()).save(any());
        
        assertEquals(NewsDraftStatus.APPROVED, draft1.getReviewStatus());
        assertEquals(NewsDraftStatus.APPROVED, draft2.getReviewStatus());
        verify(draftRepository).saveAll(any());
    }

    @Test
    void rejectionRestoresSubmittedDraftsToDraftPreservingContent() {
        when(payloadRepository.findBySubmissionId(100L)).thenReturn(Optional.of(payload));
        when(draftRepository.findById("draft1")).thenReturn(Optional.of(draft1));
        when(draftRepository.findById("draft2")).thenReturn(Optional.of(draft2));

        handler.handleRejection(submission, 1L, "Needs more detail");

        assertEquals(NewsDraftStatus.DRAFT, draft1.getReviewStatus());
        assertEquals("Content 1", draft1.getContent()); // preserved
        assertEquals("img1", draft1.getImageStorageKey()); // preserved

        assertEquals(NewsDraftStatus.DRAFT, draft2.getReviewStatus());
        
        verify(draftRepository).saveAll(any());
        verify(auditLogService).log(eq(1L), eq(AuditAction.COMPANY_NEWS_RESEARCH_REJECTED), any(), any(), any());
    }
}

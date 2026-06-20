package com.apms.domain.assistant.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.common.security.ProjectSecurityEvaluator;
import com.apms.domain.assistant.AiChatMessage;
import com.apms.domain.assistant.dto.AiChatRequest;
import com.apms.domain.assistant.dto.AiChatResponse;
import com.apms.domain.assistant.dto.AssistantContext;
import com.apms.domain.assistant.repository.mongo.AiChatMessageRepository;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates the AI assistant flow:
 *   1. Authenticate & authorise (project membership via ProjectSecurityEvaluator)
 *   2. Build approved context (company profile, graph, score)
 *   3. Call Gemini assistant provider
 *   4. Persist chat message to MongoDB
 *   5. Return AiChatResponse
 *
 * This service NEVER touches raw_documents, ai_extraction_results,
 * or draft/pending/rejected company_candidates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAssistantService {

    private final AssistantContextService contextService;
    private final GeminiAssistantProvider assistantProvider;
    private final AiChatMessageRepository chatMessageRepository;
    private final ProjectSecurityEvaluator projectSecurity;

    public AiChatResponse chat(AiChatRequest request) {
        // ── 1. Authentication ─────────────────────────────────────────────────
        UserDetailsImpl currentUser = currentUser();
        if (currentUser == null) {
            throw new BusinessValidationException("User not authenticated.");
        }

        // ── 2. Authorisation — project access ─────────────────────────────────
        if (!projectSecurity.isMemberOrOwner(request.getProjectId())) {
            throw new BusinessValidationException(
                    "Access denied: you do not have access to project " + request.getProjectId());
        }

        // ── 3. Session ID ─────────────────────────────────────────────────────
        String sessionId = StringUtils.hasText(request.getSessionId())
                ? request.getSessionId()
                : UUID.randomUUID().toString();

        // ── 4. Build approved context ─────────────────────────────────────────
        AssistantContext context = contextService.buildContext(
                request.getProjectId(),
                request.getCompanyProfileId()
        );

        // ── 5. Generate answer ────────────────────────────────────────────────
        String answer = assistantProvider.answer(request.getQuestion(), context);

        // ── 6. Suggested actions ──────────────────────────────────────────────
        List<String> suggestedActions = buildSuggestedActions(context);

        // ── 7. Persist chat message to MongoDB ────────────────────────────────
        List<String> sourceLabels = context.getSources().stream()
                .map(s -> s.getType())
                .distinct()
                .collect(Collectors.toList());

        AiChatMessage message = AiChatMessage.builder()
                .sessionId(sessionId)
                .userId(currentUser.getId())
                .projectId(request.getProjectId())
                .companyProfileId(request.getCompanyProfileId())
                .question(request.getQuestion())
                .answer(answer)
                .sources(sourceLabels)
                .suggestedActions(suggestedActions)
                .createdAt(LocalDateTime.now())
                .build();

        chatMessageRepository.save(message);
        log.info("AI assistant chat saved: sessionId={}, userId={}, projectId={}",
                sessionId, currentUser.getId(), request.getProjectId());

        // ── 8. Return response ─────────────────────────────────────────────────
        return AiChatResponse.builder()
                .sessionId(sessionId)
                .answer(answer)
                .sources(context.getSources())
                .suggestedActions(suggestedActions)
                .build();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private UserDetailsImpl currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserDetailsImpl)) return null;
        return (UserDetailsImpl) auth.getPrincipal();
    }

    private List<String> buildSuggestedActions(AssistantContext context) {
        if (context.getCompanyProfile() == null) {
            return List.of("Select a company to get detailed insights.");
        }

        List<String> actions = new java.util.ArrayList<>();

        if (context.getLatestScore() != null) {
            actions.add("View full score breakdown for this company");
        } else {
            actions.add("Generate a score snapshot for this company");
        }

        if (context.getFormattedRelationships() != null && !context.getFormattedRelationships().isEmpty()) {
            actions.add("Explore company relationship graph");
        }

        actions.add("View approved company profile details");
        return actions;
    }
}

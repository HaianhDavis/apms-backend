package com.apms.domain.assistant.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.assistant.AiChatMessage;
import com.apms.domain.assistant.dto.AiChatResponse;
import com.apms.domain.assistant.dto.AssistantContext;
import com.apms.domain.assistant.dto.OwnerAiChatRequest;
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
 * Orchestrates the Business Owner AI Assistant flow.
 *
 * Design:
 *   - Business Owner does NOT belong to a project.
 *   - Owner org is the context root: APMS Demo Organization (dev hardcode).
 *   - No project resolution. No ProjectSecurityEvaluator.
 *   - Context is assembled from Neo4j (relationships), MongoDB (profiles),
 *     and SQL Server (score snapshots) for the owner org's ecosystem.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OwnerAiAssistantService {

    private final OwnerAssistantContextService ownerContextService;
    private final OwnerGeminiAssistantProvider ownerAssistantProvider;
    private final AiChatMessageRepository chatMessageRepository;

    public AiChatResponse chat(OwnerAiChatRequest request) {
        // ── 1. Authentication ─────────────────────────────────────────────────
        UserDetailsImpl currentUser = currentUser();
        if (currentUser == null) {
            throw new BusinessValidationException("User not authenticated.");
        }

        // ── 2. Session ────────────────────────────────────────────────────────
        String sessionId = StringUtils.hasText(request.getSessionId())
                ? request.getSessionId()
                : UUID.randomUUID().toString();

        // ── 3. Build owner-org-rooted context ─────────────────────────────────
        AssistantContext context;
        try {
            context = ownerContextService.buildContext(request.getCompanyProfileId(), request.getQuestion());
        } catch (ClarificationRequiredException e) {
            return AiChatResponse.builder()
                    .sessionId(sessionId)
                    .answer(e.getMessage())
                    .sources(List.of())
                    .suggestedActions(List.of("Please specify the exact company name you mean."))
                    .build();
        }

        // ── 4. Generate answer ────────────────────────────────────────────────
        String answer = ownerAssistantProvider.answer(request.getQuestion(), context);
        List<String> suggestedActions = buildSuggestedActions(context);

        // ── 5. Persist to MongoDB ─────────────────────────────────────────────
        List<String> sourceLabels = context.getSources().stream()
                .map(s -> s.getType())
                .distinct()
                .collect(Collectors.toList());

        String companyProfileId = context.getCompanyProfile() != null
                ? context.getCompanyProfile().getId()
                : request.getCompanyProfileId(); // keep explicit hint even if profile not resolved

        AiChatMessage message = AiChatMessage.builder()
                .sessionId(sessionId)
                .userId(currentUser.getId())
                .projectId(null)       // Owner assistant is not scoped to a project
                .companyProfileId(companyProfileId)
                .question(request.getQuestion())
                .answer(answer)
                .sources(sourceLabels)
                .suggestedActions(suggestedActions)
                .createdAt(LocalDateTime.now())
                .build();

        chatMessageRepository.save(message);
        log.info("Owner AI chat saved: sessionId={}, userId={}", sessionId, currentUser.getId());

        // ── 6. Response ───────────────────────────────────────────────────────
        return AiChatResponse.builder()
                .sessionId(sessionId)
                .answer(answer)
                .sources(context.getSources())
                .suggestedActions(suggestedActions)
                .build();
    }

    private UserDetailsImpl currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserDetailsImpl)) return null;
        return (UserDetailsImpl) auth.getPrincipal();
    }

    private List<String> buildSuggestedActions(AssistantContext context) {
        if (context.getCompanyProfile() == null) {
            return List.of(
                "Who are our strongest partners?",
                "Which companies are our biggest competitors?",
                "What are the biggest opportunities in our ecosystem?"
            );
        } else {
            return List.of(
                "What is this company's overall risk level?",
                "How does this company fit into our partner strategy?",
                "What are this company's main strengths and threats?"
            );
        }
    }
}

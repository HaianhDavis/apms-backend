package com.apms.domain.assistant.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.assistant.AiChatMessage;
import com.apms.domain.assistant.dto.AiChatResponse;
import com.apms.domain.assistant.dto.AiNavigationAction;
import com.apms.domain.assistant.dto.OwnerContextResult;
import com.apms.domain.assistant.dto.OwnerAiChatRequest;
import com.apms.domain.assistant.dto.OwnerIntent;
import com.apms.domain.assistant.repository.mongo.AiChatMessageRepository;
import com.apms.domain.profile.CompanyProfile;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class OwnerAiAssistantService {

    private final OwnerAssistantContextService ownerContextService;
    private final OwnerGeminiAssistantProvider ownerAssistantProvider;
    private final AiChatMessageRepository chatMessageRepository;

    public AiChatResponse chat(OwnerAiChatRequest request) {
        UserDetailsImpl currentUser = currentUser();
        if (currentUser == null) {
            throw new BusinessValidationException("User not authenticated.");
        }

        String sessionId = StringUtils.hasText(request.getSessionId())
                ? request.getSessionId()
                : UUID.randomUUID().toString();

        OwnerContextResult result;
        try {
            result = ownerContextService.buildContext(request.getCompanyProfileId(), request.getQuestion());
        } catch (ClarificationRequiredException e) {
            return AiChatResponse.builder()
                    .sessionId(sessionId)
                    .answer(e.getMessage())
                    .sources(List.of())
                    .suggestedActions(List.of("Please specify the exact company name you mean."))
                    .build();
        }

        String answer;
        if (result.isDeterministic()) {
            answer = result.getDirectAnswer();
        } else {
            answer = ownerAssistantProvider.answer(request.getQuestion(), result.getContext());
        }

        List<String> suggestedActions = buildSuggestedActions(result.getIntent(), result);

        List<String> sourceLabels = result.getContext().getSources().stream()
                .map(s -> s.getType())
                .distinct()
                .collect(Collectors.toList());

        String companyProfileId = result.getContext().getCompanyProfile() != null
                ? result.getContext().getCompanyProfile().getId()
                : null;

        List<AiNavigationAction> navigationActions = result.getNavigationActions() != null ? result.getNavigationActions() : List.of();

        AiChatMessage message = AiChatMessage.builder()
                .sessionId(sessionId)
                .userId(currentUser.getId())
                .projectId(null)
                .companyProfileId(companyProfileId)
                .question(request.getQuestion())
                .answer(answer)
                .sources(sourceLabels)
                .suggestedActions(suggestedActions)
                .navigationActions(navigationActions)
                .createdAt(LocalDateTime.now())
                .build();

        chatMessageRepository.save(message);
        log.info("Owner AI chat saved: sessionId={}, userId={}, deterministic={}", sessionId, currentUser.getId(), result.isDeterministic());

        return AiChatResponse.builder()
                .sessionId(sessionId)
                .answer(answer)
                .sources(result.getContext().getSources())
                .suggestedActions(suggestedActions)
                .navigationActions(navigationActions)
                .build();
    }

    private UserDetailsImpl currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserDetailsImpl)) return null;
        return (UserDetailsImpl) auth.getPrincipal();
    }

    private List<String> buildSuggestedActions(OwnerIntent intent, OwnerContextResult result) {
        String companyName = "this company";
        if (result.getContext() != null && result.getContext().getCompanyProfile() != null) {
            CompanyProfile p = result.getContext().getCompanyProfile();
            if (p.getIdentity() != null) {
                if (StringUtils.hasText(p.getIdentity().getLegalName())) {
                    companyName = p.getIdentity().getLegalName();
                } else if (StringUtils.hasText(p.getIdentity().getTradeName())) {
                    companyName = p.getIdentity().getTradeName();
                }
            }
        }

        return switch (intent) {
            case GREETING -> List.of(
                    "Who are our current partners?",
                    "Who are our current competitors?",
                    "What risks should I pay attention to?",
                    "What should I focus on strategically?"
            );
case COMPANY_PROFILE -> List.of(
                    "What is our relationship with " + companyName + "?",
                    "How close is our relationship with " + companyName + "?",
                    "Should we strengthen our relationship with " + companyName + "?",
                    "Show recent public updates about " + companyName + "."
            );
            case COMPANY_RELATIONSHIP -> List.of(
                    "How close is our relationship with " + companyName + "?",
                    "Show recent public updates about " + companyName + ".",
                    "What opportunities do we have with " + companyName + "?"
            );
            case COMPANY_PUBLIC_NEWS -> List.of(
                    "What do we know about " + companyName + "?",
                    "What is our relationship with " + companyName + "?",
                    "What risks should I know about " + companyName + "?"
            );
            case RELATIONSHIP_CLOSENESS -> List.of(
                    "What is our relationship with " + companyName + "?",
                    "Should we strengthen our relationship with " + companyName + "?",
                    "What risks should I know about " + companyName + "?"
            );
            case RELATIONSHIP_STRENGTHEN -> List.of(
                    "How close is our relationship with " + companyName + "?",
                    "What risks should I know about " + companyName + "?",
                    "What opportunities do we have with " + companyName + "?"
            );
            case PARTNERS -> List.of(
                    "Which partners should I prioritize?",
                    "Are there any partner relationships that need attention?",
                    "What should I focus on strategically?"
            );
            case PARTNER_PRIORITY -> List.of(
                    "Which relationships need my attention?",
                    "What should I focus on strategically?"
            );
            case RELATIONSHIP_ATTENTION -> List.of(
                    "Which partners should I prioritize?",
                    "What should I focus on strategically?"
            );
            case POTENTIAL_PARTNERS -> List.of(
                    "Which potential partners have opportunity signals?",
                    "Which potential partner relationships are strongest?",
                    "What should I focus on strategically?"
            );
            case COMPETITORS -> List.of(
                    "Which competitors have recent risk signals?",
                    "Compare two competitors.",
                    "What should I focus on strategically?"
            );
            case COMPANY_COMPARE -> List.of(
                    "What are the biggest risks in our ecosystem?",
                    "What opportunities should we pursue?",
                    "What should I focus on strategically?"
            );
            case RISKS -> {
                if (result.getContext() != null && result.getContext().getCompanyProfile() != null) {
                    yield List.of(
                            "What opportunities do we have with " + companyName + "?",
                            "What is our relationship with " + companyName + "?",
                            "How close is our relationship with " + companyName + "?",
                            "Show recent public updates about " + companyName + "."
                    );
                }
                yield List.of(
                        "What opportunities should we pursue?",
                        "Which relationships need my attention?",
                        "What should I focus on strategically?"
                );
            }
            case OPPORTUNITIES -> {
                if (result.getContext() != null && result.getContext().getCompanyProfile() != null) {
                    yield List.of(
                            "What risks should I know about " + companyName + "?",
                            "What is our relationship with " + companyName + "?",
                            "How close is our relationship with " + companyName + "?",
                            "Show recent public updates about " + companyName + "."
                    );
                }
                yield List.of(
                        "What are the biggest risks in our ecosystem?",
                        "Which partners should I prioritize?",
                        "What should I focus on strategically?"
                );
            }
            case STRATEGIC_RECOMMENDATION -> List.of(
                    "What risks should I review?",
                    "What opportunities have been detected?",
                    "Which relationships need attention?"
            );
            default -> List.of(
                    "What risks should I pay attention to?",
                    "What opportunities have been detected?",
                    "Which relationships need attention?",
                    "What should I focus on strategically?"
            );
        };
    }
}

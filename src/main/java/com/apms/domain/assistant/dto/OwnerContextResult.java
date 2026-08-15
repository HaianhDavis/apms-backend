package com.apms.domain.assistant.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class OwnerContextResult {
    private AssistantContext context;
    private OwnerIntent intent;
    private boolean deterministic;
    private String directAnswer;
    private List<AiNavigationAction> navigationActions;
}

package com.apms.domain.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NetworkAiRecommendationDto {

    private RecommendationItem highPriority;
    private RecommendationItem mediumPriority;
    private RecommendationItem opportunity;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecommendationItem {
        private String title;
        private String reason;
        private String evidence;
        private String action;
    }
}

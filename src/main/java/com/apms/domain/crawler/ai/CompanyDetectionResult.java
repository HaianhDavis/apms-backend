package com.apms.domain.crawler.ai;

import com.apms.domain.crawler.domain.CompanyMatch;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * DTO representing the result of AI company detection for a single article.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyDetectionResult {

    /**
     * List of matched companies with confidence scores.
     * Empty if article is not relevant to any tracked company.
     */
    @Builder.Default
    private List<CompanyMatch> matches = new ArrayList<>();

    /**
     * Whether the AI determined this article is relevant to any tracked company.
     */
    @Builder.Default
    private boolean relevant = false;

    /**
     * Raw AI response JSON string (for debugging/audit).
     */
    private String rawAiOutput;

    /**
     * Time taken for AI processing in milliseconds.
     */
    private long processingTimeMs;

    /**
     * Error message if processing failed.
     */
    private String errorMessage;

    /**
     * Whether this result came from fast pre-filter (alias matching)
     * rather than AI semantic analysis.
     */
    @Builder.Default
    private boolean fromPreFilter = false;
}

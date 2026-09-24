package com.apms.domain.contract.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Typed configuration properties for partner contract extraction.
 *
 * <p>All properties live under the {@code apms.contract-extraction} namespace.
 * Defaults are defined here in Java; any may be overridden via
 * {@code application.yml}, {@code application.properties}, environment
 * variables ({@code APMS_CONTRACT_EXTRACTION_SEGMENT_CHARS}), or
 * Spring Cloud Config.</p>
 *
 * <table>
 *   <tr><th>Property</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>segment-chars</td><td>5000</td><td>Maximum characters per segment sent to the AI provider</td></tr>
 *   <tr><td>segment-overlap-chars</td><td>200</td><td>Overlap between consecutive segments to preserve context</td></tr>
 *   <tr><td>max-segments</td><td>20</td><td>Maximum number of segments to process per extraction</td></tr>
 *   <tr><td>max-total-chars</td><td>100000</td><td>Maximum total characters to process before marking PARTIAL</td></tr>
 *   <tr><td>max-clauses</td><td>200</td><td>Maximum clause candidates per extraction</td></tr>
 *   <tr><td>provider-timeout-seconds</td><td>30</td><td>Timeout per AI provider call</td></tr>
 *   <tr><td>max-retries</td><td>3</td><td>Maximum retries per AI provider call on transient failure</td></tr>
 *   <tr><td>apply-recovery-timeout-seconds</td><td>300</td><td>Time window to recover a stuck APPLY_PENDING draft</td></tr>
 * </table>
 */
@Component
@ConfigurationProperties(prefix = "apms.contract-extraction")
@Getter
@Setter
public class ContractExtractionProperties {

    /** Maximum characters per segment sent to the AI provider. */
    private int segmentChars = 5000;

    /** Overlap between consecutive segments to preserve cross-boundary context. */
    private int segmentOverlapChars = 200;

    /** Maximum number of segments to process per extraction. */
    private int maxSegments = 20;

    /** Maximum total characters to process before marking PARTIAL. */
    private int maxTotalChars = 100_000;

    /** Maximum clause candidates per extraction. */
    private int maxClauses = 200;

    /** Timeout per AI provider call in seconds. */
    private int providerTimeoutSeconds = 30;

    /** Maximum retries per AI provider call on transient failure. */
    private int maxRetries = 3;

    /** Time window in seconds to recover a stuck APPLY_PENDING draft. */
    private int applyRecoveryTimeoutSeconds = 300;
}

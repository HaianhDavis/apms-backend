package com.apms.domain.ai.service.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

/**
 * Concurrency limiter that bounds concurrent calls to the Gemini API during extractions.
 *
 * Ensures large document extractions do not run concurrently and exhaust the provider's
 * Tokens Per Minute (TPM) or Requests Per Minute (RPM) limits.
 *
 * Unrelated non-AI tasks are not affected.
 */
@Slf4j
@Component
public class GeminiExtractionConcurrencyLimiter {

    private final Semaphore semaphore;
    private final int maxConcurrent;

    public GeminiExtractionConcurrencyLimiter(
            @Value("${app.ai.gemini.extraction.max-concurrent:1}") int maxConcurrent) {
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.semaphore = new Semaphore(this.maxConcurrent, true);
        log.info("Initialized Gemini extraction concurrency limiter with max-concurrent: {}", this.maxConcurrent);
    }

    /**
     * Executes the given action while holding an extraction permit.
     * The permit covers the ENTIRE logical operation including HTTP calls, transient retries,
     * backoff sleep, and credential failovers.
     * The permit is strictly released in a finally block upon completion or failure.
     */
    public <T> T executeWithPermit(Callable<T> action) throws Exception {
        log.debug("Acquiring Gemini extraction permit. Available permits before: {}", semaphore.availablePermits());
        semaphore.acquire();
        log.debug("Acquired Gemini extraction permit. In-flight extractions: {}", maxConcurrent - semaphore.availablePermits());
        try {
            return action.call();
        } finally {
            semaphore.release();
            log.debug("Released Gemini extraction permit. Available permits now: {}", semaphore.availablePermits());
        }
    }

    public int availablePermits() {
        return semaphore.availablePermits();
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }
}

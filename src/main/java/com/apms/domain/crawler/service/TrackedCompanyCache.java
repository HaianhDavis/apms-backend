package com.apms.domain.crawler.service;

import com.apms.domain.crawler.domain.TrackedCompany;
import com.apms.domain.crawler.repository.TrackedCompanyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * In-memory cache for tracked companies with TTL-based auto-refresh.
 * Ensures the crawler uses the latest company list without querying MongoDB
 * on every article, while still picking up admin changes within TTL seconds.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrackedCompanyCache {

    private final TrackedCompanyRepository trackedCompanyRepository;

    @Value("${crawler.company-cache.ttl-seconds:300}")
    private long ttlSeconds;

    private volatile List<TrackedCompany> cachedCompanies = Collections.emptyList();
    private volatile Instant lastRefresh = Instant.EPOCH;

    /**
     * Get the list of active tracked companies.
     * Returns cached data if within TTL, otherwise refreshes from MongoDB.
     */
    public List<TrackedCompany> getActiveCompanies() {
        if (isExpired()) {
            refresh();
        }
        return cachedCompanies;
    }

    /**
     * Force a cache refresh regardless of TTL.
     * Called when admin adds/removes companies via REST API.
     */
    public synchronized void forceRefresh() {
        log.info("TrackedCompanyCache: Force refreshing...");
        refresh();
    }

    /**
     * Get the number of cached companies.
     */
    public int size() {
        return cachedCompanies.size();
    }

    /**
     * Check if the cache has expired.
     */
    private boolean isExpired() {
        return Instant.now().isAfter(lastRefresh.plusSeconds(ttlSeconds));
    }

    /**
     * Refresh the cache from MongoDB.
     */
    private synchronized void refresh() {
        try {
            List<TrackedCompany> companies = trackedCompanyRepository.findByIsActiveTrue();
            this.cachedCompanies = Collections.unmodifiableList(companies);
            this.lastRefresh = Instant.now();
            log.info("TrackedCompanyCache: Refreshed. {} active companies loaded.", companies.size());
        } catch (Exception e) {
            log.error("TrackedCompanyCache: Failed to refresh from MongoDB: {}", e.getMessage(), e);
            // Keep stale data rather than failing — better to use old list than none
        }
    }
}

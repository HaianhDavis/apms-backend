package com.apms.domain.crawler.repository;

import com.apms.domain.crawler.domain.TrackedCompany;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TrackedCompanyRepository extends MongoRepository<TrackedCompany, String> {

    /**
     * Find all actively tracked companies.
     */
    List<TrackedCompany> findByIsActiveTrue();

    /**
     * Find a tracked company by its primary name (case-insensitive).
     */
    Optional<TrackedCompany> findByCompanyNameIgnoreCase(String companyName);

    /**
     * Check if a company name already exists.
     */
    boolean existsByCompanyNameIgnoreCase(String companyName);
}


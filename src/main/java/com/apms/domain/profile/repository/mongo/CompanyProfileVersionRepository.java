package com.apms.domain.profile.repository.mongo;

import com.apms.domain.profile.CompanyProfileVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface CompanyProfileVersionRepository extends MongoRepository<CompanyProfileVersion, String> {
    Page<CompanyProfileVersion> findByCompanyProfileIdOrderByVersionDesc(String companyProfileId, Pageable pageable);
    Optional<CompanyProfileVersion> findByCompanyProfileIdAndVersion(String companyProfileId, Integer version);
}

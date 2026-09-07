package com.apms.domain.profile.repository.mongo;

import com.apms.domain.profile.CompanyProfileVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CompanyProfileVersionRepository extends MongoRepository<CompanyProfileVersion, String> {
    Page<CompanyProfileVersion> findByCompanyProfileIdOrderByCreatedAtDesc(String companyProfileId, Pageable pageable);
    
    @Query("{ '$or': [ { 'companyProfileId': ?0 }, { 'companyId': ?1 } ] }")
    Page<CompanyProfileVersion> findByCompanyProfileIdOrCompanyIdOrderByCreatedAtDesc(String companyProfileId, String companyId, Pageable pageable);

    @Query(value = "{ '$or': [ { 'companyProfileId': ?0 }, { 'companyId': ?1 } ] }", sort = "{ 'createdAt': -1 }")
    List<CompanyProfileVersion> findByCompanyProfileIdOrCompanyIdOrderByCreatedAtDesc(String companyProfileId, String companyId);

    Optional<CompanyProfileVersion> findByCompanyProfileIdAndVersion(String companyProfileId, String version);

    @Query("{ 'companyProfileId': ?0, '$or': [ { 'version': ?1 }, { 'versionLabel': ?1 } ] }")
    Optional<CompanyProfileVersion> findByCompanyProfileIdAndVersionOrVersionLabel(String companyProfileId, String version);

    boolean existsByCompanyProfileId(String companyProfileId);
    boolean existsByCreatedFromProposalId(String createdFromProposalId);
    boolean existsByCompanyProfileIdAndCreatedFromProjectId(String companyProfileId, Long createdFromProjectId);
    List<CompanyProfileVersion> findByCompanyProfileIdOrderByCreatedAtDesc(String companyProfileId);
    Optional<CompanyProfileVersion> findTopByCompanyProfileIdOrderByCreatedAtDesc(String companyProfileId);
}

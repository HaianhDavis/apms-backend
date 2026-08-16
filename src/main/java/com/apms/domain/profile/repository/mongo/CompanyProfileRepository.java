package com.apms.domain.profile.repository.mongo;

import com.apms.domain.profile.CompanyProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.Optional;

public interface CompanyProfileRepository extends MongoRepository<CompanyProfile, String> {

    Optional<CompanyProfile> findByCompanyId(String companyId);
    
    java.util.List<CompanyProfile> findByCompanyIdIn(java.util.Collection<String> companyIds);

    @Query("{ $or: [ { 'identity.legalName': { $regex: ?0, $options: 'i' } }, { 'identity.tradeName': { $regex: ?0, $options: 'i' } } ] }")
    Page<CompanyProfile> searchByName(String name, Pageable pageable);

    @Query("{ 'sourceRefs.candidateIds': ?0 }")
    Optional<CompanyProfile> findByCandidateId(String candidateId);

    @Query("{ 'sourceRefs.projectIds': ?0 }")
    java.util.List<CompanyProfile> findByProjectId(String projectId);

    @Query("{ 'isDeleted': { $ne: true }, $or: [ { 'isHidden': false }, { 'isHidden': { $exists: false } } ] }")
    Page<CompanyProfile> findActiveProfiles(Pageable pageable);

    @Query("{ 'isDeleted': { $ne: true }, 'isHidden': true }")
    Page<CompanyProfile> findHiddenProfiles(Pageable pageable);

    @Query("{ '$and': [ { 'isDeleted': { $ne: true } }, { '$or': [ { 'isHidden': false }, { 'isHidden': { $exists: false } } ] }, { '$or': [ { 'identity.legalName': { $regex: ?0, $options: 'i' } }, { 'identity.tradeName': { $regex: ?0, $options: 'i' } }, { 'companyId': { $regex: ?0, $options: 'i' } } ] } ] }")
    Page<CompanyProfile> searchActiveProfiles(String keyword, Pageable pageable);

    @Query("{ '$and': [ { 'isDeleted': { $ne: true } }, { 'isHidden': true }, { '$or': [ { 'identity.legalName': { $regex: ?0, $options: 'i' } }, { 'identity.tradeName': { $regex: ?0, $options: 'i' } }, { 'companyId': { $regex: ?0, $options: 'i' } } ] } ] }")
    Page<CompanyProfile> searchHiddenProfiles(String keyword, Pageable pageable);
}

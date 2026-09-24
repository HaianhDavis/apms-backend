package com.apms.domain.profile.closeness;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CompanyRelationshipClosenessRepository extends JpaRepository<CompanyRelationshipCloseness, Long> {

    Optional<CompanyRelationshipCloseness> findByOwnerCompanyProfileIdAndTargetCompanyProfileId(String ownerCompanyProfileId, String targetCompanyProfileId);

    java.util.List<CompanyRelationshipCloseness> findByOwnerCompanyProfileIdAndTargetCompanyProfileIdIn(String ownerCompanyProfileId, java.util.Collection<String> targetCompanyProfileIds);
}

package com.apms.domain.profile.repository.mongo;

import com.apms.domain.profile.OwnerCompanyProfileSnapshot;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface OwnerCompanyProfileSnapshotRepository extends MongoRepository<OwnerCompanyProfileSnapshot, String> {
    Optional<OwnerCompanyProfileSnapshot> findFirstByCompanyProfileIdOrderByFetchedAtDesc(String companyProfileId);
}

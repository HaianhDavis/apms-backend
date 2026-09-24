package com.apms.domain.profile.repository.mongo;

import com.apms.domain.profile.OwnerOrganization;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OwnerOrganizationRepository extends MongoRepository<OwnerOrganization, String> {
}

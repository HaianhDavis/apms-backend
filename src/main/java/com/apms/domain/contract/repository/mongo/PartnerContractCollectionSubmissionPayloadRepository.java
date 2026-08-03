package com.apms.domain.contract.repository.mongo;

import com.apms.domain.contract.entity.PartnerContractCollectionSubmissionPayload;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PartnerContractCollectionSubmissionPayloadRepository extends MongoRepository<PartnerContractCollectionSubmissionPayload, String> {
    Optional<PartnerContractCollectionSubmissionPayload> findBySubmissionId(Long submissionId);
}

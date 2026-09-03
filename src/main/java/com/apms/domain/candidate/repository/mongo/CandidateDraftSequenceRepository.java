package com.apms.domain.candidate.repository.mongo;

import com.apms.domain.candidate.CandidateDraftSequence;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CandidateDraftSequenceRepository extends MongoRepository<CandidateDraftSequence, String> {
}

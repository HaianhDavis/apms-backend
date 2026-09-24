package com.apms.domain.contract.repository.mongo;

import com.apms.domain.contract.entity.PartnerContractExtractionDraft;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface  PartnerContractExtractionDraftRepository extends MongoRepository<PartnerContractExtractionDraft, String> {
    List<PartnerContractExtractionDraft> findByPartnerContractId(Long partnerContractId);
    List<PartnerContractExtractionDraft> findByPartnerContractIdOrderByGeneratedAtDesc(Long partnerContractId);
    Optional<PartnerContractExtractionDraft> findByIdAndPartnerContractId(String id, Long partnerContractId);
    List<PartnerContractExtractionDraft> findBySourceTaskIdOrderByGeneratedAtDesc(Long sourceTaskId);
    Optional<PartnerContractExtractionDraft> findByIdAndSourceTaskId(String id, Long sourceTaskId);
}

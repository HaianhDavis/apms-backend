package com.apms.domain.profile.repository.mongo;

import com.apms.domain.profile.CompanyProfileManagerHistory;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CompanyProfileManagerHistoryRepository extends MongoRepository<CompanyProfileManagerHistory, String> {

    List<CompanyProfileManagerHistory> findByCompanyProfileIdOrderByTransferredAtDesc(String companyProfileId);

    List<CompanyProfileManagerHistory> findByCompanyIdOrderByTransferredAtDesc(String companyId);

    List<CompanyProfileManagerHistory> findByPreviousManagerAccountIdOrNewManagerAccountId(Long previousId, Long newId);
}

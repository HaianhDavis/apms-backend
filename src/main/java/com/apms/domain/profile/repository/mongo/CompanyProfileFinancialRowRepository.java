package com.apms.domain.profile.repository.mongo;

import com.apms.domain.profile.CompanyProfileFinancialRow;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CompanyProfileFinancialRowRepository extends MongoRepository<CompanyProfileFinancialRow, String> {

    List<CompanyProfileFinancialRow> findByCompanyProfileIdOrderByYearDescQuarterDescDisplayOrderAsc(String companyProfileId);

    List<CompanyProfileFinancialRow> findByCompanyProfileId(String companyProfileId);

    boolean existsByCompanyProfileIdAndSourceMetricId(String companyProfileId, String sourceMetricId);

    Optional<CompanyProfileFinancialRow> findByCompanyProfileIdAndSourceMetricId(String companyProfileId, String sourceMetricId);

    void deleteByCompanyProfileIdAndIdIn(String companyProfileId, List<String> ids);
}

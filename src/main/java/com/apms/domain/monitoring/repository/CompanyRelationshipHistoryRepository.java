package com.apms.domain.monitoring.repository;

import com.apms.domain.monitoring.model.CompanyRelationshipHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CompanyRelationshipHistoryRepository extends JpaRepository<CompanyRelationshipHistory, Long> {
    
    List<CompanyRelationshipHistory> findByCompanyProfileIdOrderByChangedAtDesc(String companyProfileId);
}

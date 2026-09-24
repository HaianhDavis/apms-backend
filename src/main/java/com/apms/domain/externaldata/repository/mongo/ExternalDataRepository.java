package com.apms.domain.externaldata.repository.mongo;

import com.apms.common.enums.ExternalDataCategory;
import com.apms.domain.externaldata.ExternalDataItem;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface ExternalDataRepository extends MongoRepository<ExternalDataItem, String> {
    List<ExternalDataItem> findByRelatedCompanyId(String relatedCompanyId);
    List<ExternalDataItem> findByCategoryAndRelatedCompanyId(ExternalDataCategory category, String relatedCompanyId);
    
    long countByCategoryAndRelatedCompanyIdIn(ExternalDataCategory category, java.util.Collection<String> companyIds);
    List<ExternalDataItem> findTop5ByCategoryAndRelatedCompanyIdInOrderByPublishedAtDesc(ExternalDataCategory category, java.util.Collection<String> companyIds);
    List<ExternalDataItem> findTop5ByRelatedCompanyIdInOrderByPublishedAtDesc(java.util.Collection<String> companyIds);
}

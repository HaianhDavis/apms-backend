package com.apms.domain.externaldata.repository.mongo;

import com.apms.domain.externaldata.ExternalDataItem;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface ExternalDataRepository extends MongoRepository<ExternalDataItem, String> {
    List<ExternalDataItem> findByRelatedCompanyId(String relatedCompanyId);

    long countByCategoryAndRelatedCompanyIdIn(com.apms.common.enums.ExternalDataCategory category, java.util.Collection<String> companyIds);
    List<ExternalDataItem> findTop5ByCategoryAndRelatedCompanyIdInOrderByPublishedAtDesc(com.apms.common.enums.ExternalDataCategory category, java.util.Collection<String> companyIds);
    List<ExternalDataItem> findTop5ByRelatedCompanyIdInOrderByPublishedAtDesc(java.util.Collection<String> companyIds);

    List<ExternalDataItem> findByProjectId(Long projectId);

    boolean existsByProjectIdAndUrl(Long projectId, String url);

    boolean existsByCompanyProfileIdAndUrl(String companyProfileId, String url);

    List<ExternalDataItem> findByCompanyProfileIdOrRelatedCompanyId(String companyProfileId, String relatedCompanyId);
}

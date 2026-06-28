package com.apms.domain.externaldata.repository.mongo;

import com.apms.domain.externaldata.ExternalDataItem;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ExternalDataRepository extends MongoRepository<ExternalDataItem, String> {
}

package com.apms.domain.reference.repository;

import com.apms.domain.reference.entity.IndustryCatalog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IndustryCatalogRepository extends MongoRepository<IndustryCatalog, String> {

    Optional<IndustryCatalog> findByNormalizedName(String normalizedName);

    boolean existsByNormalizedName(String normalizedName);

    List<IndustryCatalog> findByStatusOrderByNameAsc(String status);

    List<IndustryCatalog> findByNameContainingIgnoreCaseAndStatusOrderByNameAsc(String name, String status);
}

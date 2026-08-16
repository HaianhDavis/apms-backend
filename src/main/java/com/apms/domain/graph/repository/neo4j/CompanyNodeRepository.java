package com.apms.domain.graph.repository.neo4j;

import com.apms.domain.graph.CompanyNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

import java.util.List;
import java.util.Optional;

public interface CompanyNodeRepository extends Neo4jRepository<CompanyNode, String> {

    @Query("MATCH (c:Company {companyId: $companyId}) RETURN c")
    Optional<CompanyNode> findByCompanyId(String companyId);

    @Query("MATCH (c:Company) RETURN c")
    List<CompanyNode> findAllNodes();
}

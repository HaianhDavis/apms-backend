//package com.apms.domain.profile.closeness;
//
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
//import org.springframework.dao.DataIntegrityViolationException;
//
//import java.time.LocalDateTime;
//import java.util.Optional;
//
//import com.apms.ApmsIntegrationTestBase;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.AfterEach;
//import static org.junit.jupiter.api.Assertions.*;
//
//public class CompanyRelationshipClosenessRepositoryTest extends ApmsIntegrationTestBase {
//
//    @Autowired
//    private CompanyRelationshipClosenessRepository repository;
//
//    @Autowired
//    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
//
//    @BeforeEach
//    void setUp() {
//        jdbcTemplate.execute("IF OBJECT_ID('company_relationship_closeness', 'U') IS NOT NULL " +
//                "AND NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'chk_stars_range') " +
//                "ALTER TABLE company_relationship_closeness ADD CONSTRAINT chk_stars_range CHECK (stars BETWEEN 1 AND 5)");
//    }
//
//    @AfterEach
//    void tearDown() {
//        repository.deleteAll();
//    }
//
//    @Test
//    void testInsertSucceedsAndPopulatesAuditingFields() {
//        CompanyRelationshipCloseness entity = new CompanyRelationshipCloseness();
//        entity.setOwnerCompanyProfileId("owner1");
//        entity.setTargetCompanyProfileId("target1");
//        entity.setStars(4);
//        entity.setRatedByAccountId(10L);
//
//        CompanyRelationshipCloseness saved = repository.saveAndFlush(entity);
//
//        assertNotNull(saved.getId());
//        assertEquals(0L, saved.getVersion()); // Initially 0 based on default constraint or Hibernate @Version start
//        assertNotNull(saved.getRatedAt());
//        assertNotNull(saved.getUpdatedAt());
//        assertEquals(4, saved.getStars());
//    }
//
//    @Test
//    void testUpdatePreservesRatedAtAndChangesUpdatedAt() throws InterruptedException {
//        CompanyRelationshipCloseness entity = new CompanyRelationshipCloseness();
//        entity.setOwnerCompanyProfileId("owner2");
//        entity.setTargetCompanyProfileId("target2");
//        entity.setStars(3);
//        entity.setRatedByAccountId(10L);
//
//        CompanyRelationshipCloseness saved = repository.saveAndFlush(entity);
//        LocalDateTime originalRatedAt = saved.getRatedAt();
//        LocalDateTime originalUpdatedAt = saved.getUpdatedAt();
//        Long originalVersion = saved.getVersion();
//
//        // Delay slightly to ensure timestamp change
//        Thread.sleep(100);
//
//        saved.setStars(5);
//        CompanyRelationshipCloseness updated = repository.saveAndFlush(saved);
//
//        assertEquals(originalRatedAt, updated.getRatedAt());
//        assertTrue(updated.getUpdatedAt().isAfter(originalUpdatedAt));
//        assertEquals(originalVersion + 1, updated.getVersion());
//    }
//
//    @Test
//    void testStarsConstraint() {
//        CompanyRelationshipCloseness entity = new CompanyRelationshipCloseness();
//        entity.setOwnerCompanyProfileId("owner3");
//        entity.setTargetCompanyProfileId("target3");
//        entity.setStars(6); // Invalid
//        entity.setRatedByAccountId(10L);
//
//        assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(entity));
//    }
//}

package com.apms.domain.profile.assessment.repository;

import com.apms.domain.profile.assessment.CompanyRelationshipAssessment;
import com.apms.domain.profile.assessment.RelationshipAssessmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CompanyRelationshipAssessmentRepository extends JpaRepository<CompanyRelationshipAssessment, Long> {

    Optional<CompanyRelationshipAssessment> findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusInOrderByVersionNumberDesc(
            String ownerCompanyProfileId,
            String companyProfileId,
            Collection<RelationshipAssessmentStatus> statuses
    );

    Optional<CompanyRelationshipAssessment> findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByVersionNumberDesc(
            String ownerCompanyProfileId,
            String companyProfileId,
            RelationshipAssessmentStatus status
    );

    Optional<CompanyRelationshipAssessment> findFirstByOwnerCompanyProfileIdAndCompanyProfileIdOrderByVersionNumberDesc(
            String ownerCompanyProfileId,
            String companyProfileId
    );

    List<CompanyRelationshipAssessment> findAllByOwnerCompanyProfileIdAndCompanyProfileIdOrderByVersionNumberDesc(
            String ownerCompanyProfileId,
            String companyProfileId
    );

    Optional<CompanyRelationshipAssessment> findFirstByOwnerCompanyProfileIdAndCompanyProfileIdInAndStatusInOrderByVersionNumberDesc(
            String ownerCompanyProfileId,
            Collection<String> companyProfileIds,
            Collection<RelationshipAssessmentStatus> statuses
    );

    Optional<CompanyRelationshipAssessment> findFirstByOwnerCompanyProfileIdAndCompanyProfileIdInAndStatusOrderByVersionNumberDesc(
            String ownerCompanyProfileId,
            Collection<String> companyProfileIds,
            RelationshipAssessmentStatus status
    );

    Optional<CompanyRelationshipAssessment> findFirstByOwnerCompanyProfileIdAndCompanyProfileIdInOrderByVersionNumberDesc(
            String ownerCompanyProfileId,
            Collection<String> companyProfileIds
    );

    List<CompanyRelationshipAssessment> findAllByOwnerCompanyProfileIdAndCompanyProfileIdInOrderByVersionNumberDesc(
            String ownerCompanyProfileId,
            Collection<String> companyProfileIds
    );

    boolean existsByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusIn(
            String ownerCompanyProfileId,
            String companyProfileId,
            Collection<RelationshipAssessmentStatus> statuses
    );

    boolean existsByOwnerCompanyProfileIdAndCompanyProfileIdInAndStatusIn(
            String ownerCompanyProfileId,
            Collection<String> companyProfileIds,
            Collection<RelationshipAssessmentStatus> statuses
    );

    boolean existsByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusAndIdNot(
            String ownerCompanyProfileId,
            String companyProfileId,
            RelationshipAssessmentStatus status,
            Long id
    );

    Optional<CompanyRelationshipAssessment> findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByFinalizedAtDescMajorVersionDescMinorRevisionDesc(
            String ownerCompanyProfileId,
            String companyProfileId,
            RelationshipAssessmentStatus status
    );

    Optional<CompanyRelationshipAssessment> findFirstByOwnerCompanyProfileIdAndCompanyProfileIdInAndStatusOrderByFinalizedAtDescMajorVersionDescMinorRevisionDesc(
            String ownerCompanyProfileId,
            Collection<String> companyProfileIds,
            RelationshipAssessmentStatus status
    );

    Optional<CompanyRelationshipAssessment> findFirstByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByMajorVersionDescMinorRevisionDesc(
            String ownerCompanyProfileId,
            String companyProfileId,
            RelationshipAssessmentStatus status
    );

    Optional<CompanyRelationshipAssessment> findFirstByOwnerCompanyProfileIdAndCompanyProfileIdInAndStatusOrderByMajorVersionDescMinorRevisionDesc(
            String ownerCompanyProfileId,
            Collection<String> companyProfileIds,
            RelationshipAssessmentStatus status
    );

    List<CompanyRelationshipAssessment> findAllByOwnerCompanyProfileIdAndCompanyProfileIdOrderByFinalizedAtDescMajorVersionDescMinorRevisionDesc(
            String ownerCompanyProfileId,
            String companyProfileId
    );

    List<CompanyRelationshipAssessment> findAllByOwnerCompanyProfileIdAndCompanyProfileIdInOrderByFinalizedAtDescMajorVersionDescMinorRevisionDesc(
            String ownerCompanyProfileId,
            Collection<String> companyProfileIds
    );

    List<CompanyRelationshipAssessment> findAllByOwnerCompanyProfileIdAndCompanyProfileIdAndStatusOrderByFinalizedAtDescMajorVersionDescMinorRevisionDesc(
            String ownerCompanyProfileId,
            String companyProfileId,
            RelationshipAssessmentStatus status
    );

    List<CompanyRelationshipAssessment> findAllByOwnerCompanyProfileIdAndCompanyProfileIdInAndStatusOrderByFinalizedAtDescMajorVersionDescMinorRevisionDesc(
            String ownerCompanyProfileId,
            Collection<String> companyProfileIds,
            RelationshipAssessmentStatus status
    );

    List<CompanyRelationshipAssessment> findAllByOwnerCompanyProfileIdAndCompanyProfileIdOrderByMajorVersionDescMinorRevisionDesc(
            String ownerCompanyProfileId,
            String companyProfileId
    );

    List<CompanyRelationshipAssessment> findAllByOwnerCompanyProfileIdAndCompanyProfileIdInOrderByMajorVersionDescMinorRevisionDesc(
            String ownerCompanyProfileId,
            Collection<String> companyProfileIds
    );

    @org.springframework.data.jpa.repository.Query(value = """
        WITH RankedAssessments AS (
            SELECT *, ROW_NUMBER() OVER (PARTITION BY company_profile_id ORDER BY finalized_at DESC, major_version DESC, minor_revision DESC) AS rn
            FROM company_relationship_assessments
            WHERE owner_company_profile_id = :ownerId AND status = 'FINALIZED'
        )
        SELECT * FROM RankedAssessments WHERE rn <= 2 ORDER BY company_profile_id, finalized_at DESC, major_version DESC, minor_revision DESC
    """, nativeQuery = true)
    List<CompanyRelationshipAssessment> findTop2FinalizedPerCompany(
            @org.springframework.data.repository.query.Param("ownerId") String ownerId
    );

    List<CompanyRelationshipAssessment> findAllByOwnerCompanyProfileIdAndStatusOrderByFinalizedAtDescMajorVersionDescMinorRevisionDesc(
            String ownerCompanyProfileId,
            RelationshipAssessmentStatus status
    );

    List<CompanyRelationshipAssessment> findAllByOwnerCompanyProfileIdAndStatusOrderByVersionNumberDesc(
            String ownerCompanyProfileId,
            RelationshipAssessmentStatus status
    );

    @org.springframework.data.jpa.repository.Query("""
        SELECT COALESCE(MAX(a.versionNumber), 0)
        FROM CompanyRelationshipAssessment a
        WHERE a.ownerCompanyProfileId = :ownerId
          AND a.companyProfileId IN :companyProfileIds
          AND a.status = com.apms.domain.profile.assessment.RelationshipAssessmentStatus.FINALIZED
    """)
    int findMaxFinalizedVersionNumber(
            @org.springframework.data.repository.query.Param("ownerId") String ownerId,
            @org.springframework.data.repository.query.Param("companyProfileIds") Collection<String> companyProfileIds
    );

    @org.springframework.data.jpa.repository.Query("""
        SELECT COALESCE(MAX(a.majorVersion), 0)
        FROM CompanyRelationshipAssessment a
        WHERE a.ownerCompanyProfileId = :ownerId
          AND a.companyProfileId IN :companyProfileIds
          AND a.status = com.apms.domain.profile.assessment.RelationshipAssessmentStatus.FINALIZED
    """)
    int findMaxFinalizedMajorVersion(
            @org.springframework.data.repository.query.Param("ownerId") String ownerId,
            @org.springframework.data.repository.query.Param("companyProfileIds") Collection<String> companyProfileIds
    );

    @org.springframework.data.jpa.repository.Query("""
        SELECT COALESCE(MAX(a.minorRevision), 0)
        FROM CompanyRelationshipAssessment a
        WHERE a.ownerCompanyProfileId = :ownerId
          AND a.companyProfileId IN :companyProfileIds
          AND a.majorVersion = :majorVersion
          AND a.status = com.apms.domain.profile.assessment.RelationshipAssessmentStatus.FINALIZED
    """)
    int findMaxFinalizedMinorRevision(
            @org.springframework.data.repository.query.Param("ownerId") String ownerId,
            @org.springframework.data.repository.query.Param("companyProfileIds") Collection<String> companyProfileIds,
            @org.springframework.data.repository.query.Param("majorVersion") Integer majorVersion
    );
}

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
}

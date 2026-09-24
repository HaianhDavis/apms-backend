package com.apms.domain.profile.assessment.repository;

import com.apms.domain.profile.assessment.RelationshipAssessmentDraft;
import com.apms.domain.profile.assessment.RelationshipAssessmentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RelationshipAssessmentDraftRepository extends JpaRepository<RelationshipAssessmentDraft, Long> {

    Optional<RelationshipAssessmentDraft> findByOwnerCompanyProfileIdAndCompanyProfileIdAndActorAccountIdAndDraftType(
            String ownerCompanyProfileId,
            String companyProfileId,
            Long actorAccountId,
            RelationshipAssessmentType draftType
    );

    @Modifying
    @Query("DELETE FROM RelationshipAssessmentDraft d WHERE d.ownerCompanyProfileId = :ownerId AND d.companyProfileId = :companyProfileId AND d.actorAccountId = :actorAccountId AND d.draftType = :draftType")
    void deleteByOwnerCompanyProfileIdAndCompanyProfileIdAndActorAccountIdAndDraftType(
            @Param("ownerId") String ownerId,
            @Param("companyProfileId") String companyProfileId,
            @Param("actorAccountId") Long actorAccountId,
            @Param("draftType") RelationshipAssessmentType draftType
    );

    @Modifying
    @Query("DELETE FROM RelationshipAssessmentDraft d WHERE d.ownerCompanyProfileId = :ownerId AND d.companyProfileId = :companyProfileId AND d.draftType = :draftType")
    void deleteByOwnerCompanyProfileIdAndCompanyProfileIdAndDraftType(
            @Param("ownerId") String ownerId,
            @Param("companyProfileId") String companyProfileId,
            @Param("draftType") RelationshipAssessmentType draftType
    );
}

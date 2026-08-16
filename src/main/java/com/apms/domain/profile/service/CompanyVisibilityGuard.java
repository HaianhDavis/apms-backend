package com.apms.domain.profile.service;

import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CompanyVisibilityGuard {

    private final CompanyProfileRepository profileRepository;
    private final MongoTemplate mongoTemplate;

    public Set<String> existingCompanyIds() {
        return mongoTemplate.find(new Query(), CompanyProfile.class).stream()
                .map(CompanyProfile::getCompanyId)
                .collect(Collectors.toSet());
    }

    public Set<String> nonVisibleCompanyIds() {
        Criteria criteria = new Criteria().orOperator(
                Criteria.where("isDeleted").is(true),
                Criteria.where("isHidden").is(true));
        return mongoTemplate.find(Query.query(criteria), CompanyProfile.class).stream()
                .map(CompanyProfile::getCompanyId)
                .collect(Collectors.toSet());
    }

    public boolean isVisibleToNonAdmin(String companyId) {
        return profileRepository.findByCompanyId(companyId)
                .map(profile -> !Boolean.TRUE.equals(profile.getIsDeleted())
                        && !Boolean.TRUE.equals(profile.getIsHidden()))
                .orElse(false);
    }
}

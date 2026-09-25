package com.apms.domain.profile.service;

import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.dto.CompanyIdentity;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyIdentityResolverTest {

    @Mock
    private CompanyProfileRepository profileRepository;

    @InjectMocks
    private CompanyIdentityResolver resolver;

    private final String mongoId = "6aa26a1ac81945324bad1ee7";
    private final String companyUuid = "0730f796-a94f-42d0-9a9e-92b6acc8dcfb";
    private CompanyProfile profile;

    @BeforeEach
    void setUp() {
        profile = CompanyProfile.builder()
                .id(mongoId)
                .companyId(companyUuid)
                .build();
    }

    @Test
    @DisplayName("Resolve by universal company UUID returns canonical identity")
    void testResolveByCompanyUuid() {
        when(profileRepository.findByCompanyId(companyUuid)).thenReturn(Optional.of(profile));

        Optional<CompanyIdentity> result = resolver.resolve(companyUuid);

        assertThat(result).isPresent();
        CompanyIdentity identity = result.get();
        assertThat(identity.getProfileId()).isEqualTo(mongoId);
        assertThat(identity.getCompanyId()).isEqualTo(companyUuid);
        assertThat(identity.getCanonicalCompanyId()).isEqualTo(companyUuid);
        assertThat(identity.allIdentifiers()).containsExactlyInAnyOrder(companyUuid, mongoId);
    }

    @Test
    @DisplayName("Resolve by MongoDB _id returns canonical identity with universal company UUID")
    void testResolveByMongoId() {
        when(profileRepository.findByCompanyId(mongoId)).thenReturn(Optional.empty());
        when(profileRepository.findById(mongoId)).thenReturn(Optional.of(profile));

        Optional<CompanyIdentity> result = resolver.resolve(mongoId);

        assertThat(result).isPresent();
        CompanyIdentity identity = result.get();
        assertThat(identity.getProfileId()).isEqualTo(mongoId);
        assertThat(identity.getCompanyId()).isEqualTo(companyUuid);
        assertThat(identity.getCanonicalCompanyId()).isEqualTo(companyUuid);
        assertThat(identity.allIdentifiers()).containsExactlyInAnyOrder(companyUuid, mongoId);
    }

    @Test
    @DisplayName("Null or empty input returns Optional.empty")
    void testResolveNullOrEmpty() {
        assertThat(resolver.resolve(null)).isEmpty();
        assertThat(resolver.resolve("")).isEmpty();
        assertThat(resolver.resolve("   ")).isEmpty();
    }
}

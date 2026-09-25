package com.apms.domain.profile.service;

import com.apms.domain.crawler.domain.TrackedCompany;
import com.apms.domain.crawler.repository.TrackedCompanyRepository;
import com.apms.domain.crawler.service.TrackedCompanyCache;
import com.apms.domain.profile.CompanyProfile;

import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyTrackingSyncRunnerTest {

    @Mock
    private CompanyProfileRepository companyProfileRepository;

    @Mock
    private TrackedCompanyRepository trackedCompanyRepository;

    @Mock
    private TrackedCompanyCache trackedCompanyCache;

    @Mock
    private MongoTemplate mongoTemplate;

    private CompanyTrackingSyncRunner runner;

    @BeforeEach
    void setUp() {
        runner = new CompanyTrackingSyncRunner(
                companyProfileRepository,
                trackedCompanyRepository,
                trackedCompanyCache,
                mongoTemplate
        );
    }

    @Test
    void whenEmptyTrackedCollection_thenRunnerCreatesTrackedCompany() throws Exception {
        CompanyProfile profile = new CompanyProfile();
        profile.setId("prof-1");
        CompanyProfile.Identity identity = new CompanyProfile.Identity();
        identity.setLegalName("Alpha Corp");
        profile.setIdentity(identity);

        when(companyProfileRepository.findAll()).thenReturn(Arrays.asList(profile));
        when(trackedCompanyRepository.findByCompanyNameIgnoreCase("Alpha Corp")).thenReturn(Optional.empty());

        runner.run();

        ArgumentCaptor<TrackedCompany> captor = ArgumentCaptor.forClass(TrackedCompany.class);
        verify(trackedCompanyRepository).save(captor.capture());

        TrackedCompany saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo("prof-1");
        assertThat(saved.getCompanyName()).isEqualTo("Alpha Corp");
        verify(trackedCompanyCache).forceRefresh();
    }

    @Test
    void whenTrackedCompanyAlreadyExists_thenRunnerUpdatesReusesExistingDocument() throws Exception {
        CompanyProfile profile = new CompanyProfile();
        profile.setId("prof-1");
        CompanyProfile.Identity identity = new CompanyProfile.Identity();
        identity.setLegalName("Alpha Corp");
        identity.setTradeName("Alpha Trade");
        profile.setIdentity(identity);

        TrackedCompany existing = TrackedCompany.builder()
                .id("existing-id")
                .companyName("Alpha Corp")
                .build();

        when(companyProfileRepository.findAll()).thenReturn(Arrays.asList(profile));
        when(trackedCompanyRepository.findByCompanyNameIgnoreCase("Alpha Corp")).thenReturn(Optional.of(existing));

        runner.run();

        ArgumentCaptor<TrackedCompany> captor = ArgumentCaptor.forClass(TrackedCompany.class);
        verify(trackedCompanyRepository).save(captor.capture());

        TrackedCompany saved = captor.getValue();
        // Uses existing ID
        assertThat(saved.getId()).isEqualTo("existing-id");
        assertThat(saved.getCompanyName()).isEqualTo("Alpha Corp");
        assertThat(saved.getAliases()).containsExactly("Alpha Trade");
        verify(trackedCompanyCache).forceRefresh();
    }

    @Test
    void whenRunnerExecutedTwice_thenSecondRunSucceedsWithoutDuplicate() throws Exception {
        CompanyProfile profile = new CompanyProfile();
        profile.setId("prof-1");
        CompanyProfile.Identity identity = new CompanyProfile.Identity();
        identity.setLegalName("Beta Corp");
        profile.setIdentity(identity);

        when(companyProfileRepository.findAll()).thenReturn(Arrays.asList(profile));
        when(trackedCompanyRepository.findByCompanyNameIgnoreCase("Beta Corp")).thenReturn(Optional.empty());

        // First run
        runner.run();
        verify(trackedCompanyRepository, times(1)).save(any(TrackedCompany.class));

        // Setup for second run: it should now find the existing document
        TrackedCompany createdCompany = TrackedCompany.builder()
                .id("prof-1")
                .companyName("Beta Corp")
                .build();
        when(trackedCompanyRepository.findByCompanyNameIgnoreCase("Beta Corp")).thenReturn(Optional.of(createdCompany));

        // Second run
        runner.run();

        // The second run should update the existing document, resulting in a save operation with the SAME id
        ArgumentCaptor<TrackedCompany> captor = ArgumentCaptor.forClass(TrackedCompany.class);
        verify(trackedCompanyRepository, times(2)).save(captor.capture());
        
        assertThat(captor.getAllValues().get(1).getId()).isEqualTo("prof-1");
    }

    @Test
    void whenDuplicateSourceItemsInSameRun_thenOnlyOneTrackedDocument() throws Exception {
        CompanyProfile profile1 = new CompanyProfile();
        profile1.setId("prof-1");
        CompanyProfile.Identity identity1 = new CompanyProfile.Identity();
        identity1.setLegalName("Duplicate Corp");
        profile1.setIdentity(identity1);

        CompanyProfile profile2 = new CompanyProfile();
        profile2.setId("prof-2");
        CompanyProfile.Identity identity2 = new CompanyProfile.Identity();
        identity2.setLegalName("duplicate corp"); // case insensitivity test
        profile2.setIdentity(identity2);

        when(companyProfileRepository.findAll()).thenReturn(Arrays.asList(profile1, profile2));
        when(trackedCompanyRepository.findByCompanyNameIgnoreCase(anyString())).thenReturn(Optional.empty());

        runner.run();

        ArgumentCaptor<TrackedCompany> captor = ArgumentCaptor.forClass(TrackedCompany.class);
        verify(trackedCompanyRepository, times(1)).save(captor.capture());

        TrackedCompany saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo("prof-1");
        assertThat(saved.getCompanyName()).isEqualTo("Duplicate Corp");
    }
}

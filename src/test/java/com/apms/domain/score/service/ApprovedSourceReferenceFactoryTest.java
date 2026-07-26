package com.apms.domain.score.service;

import com.apms.domain.profile.CompanyProfileVersion;
import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import com.apms.domain.score.draft.ApprovedSourceReference;
import com.apms.domain.score.enums.ApprovedSourceType;
import com.apms.common.exception.BusinessValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ApprovedSourceReferenceFactoryTest {

    @Mock
    private CompanyProfileVersionRepository repository;

    private ApprovedSourceReferenceFactory factory;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        factory = new ApprovedSourceReferenceFactory(repository, mapper);
    }

    @Test
    void shouldCreateReferenceFromCompanyProfileVersion() {
        CompanyProfileVersion version = CompanyProfileVersion.builder()
                .id("vId")
                .companyId("comp1")
                .companyProfileId("prof1")
                .version(2)
                .snapshot(Map.of("key", "value"))
                .build();
        when(repository.findById("vId")).thenReturn(Optional.of(version));

        ApprovedSourceReference ref = factory.createFromCompanyProfileVersion("vId", 100L, "comp1");

        assertNotNull(ref);
        assertEquals(ApprovedSourceType.COMPANY_PROFILE_VERSION, ref.getSourceType());
        assertEquals("vId", ref.getMongoSourceId());
        assertEquals(2, ref.getSourceVersionNumber());
        assertEquals(100L, ref.getProjectId());
        assertNotNull(ref.getSourceHash());
        assertNotNull(ref.getPinnedAt());
        assertNull(ref.getSqlSourceId());
    }

    @Test
    void shouldRejectMismatchedCompanyId() {
        CompanyProfileVersion version = CompanyProfileVersion.builder()
                .id("vId")
                .companyId("comp2") // mismatched
                .build();
        when(repository.findById("vId")).thenReturn(Optional.of(version));

        assertThrows(BusinessValidationException.class, () ->
            factory.createFromCompanyProfileVersion("vId", 100L, "comp1")
        );
    }

    @Test
    void shouldRejectWhenVersionNotFound() {
        when(repository.findById("nonExistent")).thenReturn(Optional.empty());
        assertThrows(com.apms.common.exception.ResourceNotFoundException.class, () ->
            factory.createFromCompanyProfileVersion("nonExistent", 100L, "comp1")
        );
    }

    @Test
    void shouldRejectWhenVersionIdIsNull() {
        assertThrows(IllegalArgumentException.class, () ->
            factory.createFromCompanyProfileVersion(null, 100L, "comp1")
        );
    }

    @Test
    void shouldRejectNullOrEmptySnapshot() {
        CompanyProfileVersion nullSnapshot = CompanyProfileVersion.builder()
                .id("vId")
                .companyId("comp1")
                .snapshot(null)
                .build();
        when(repository.findById("vId")).thenReturn(Optional.of(nullSnapshot));

        assertThrows(BusinessValidationException.class, () ->
                factory.createFromCompanyProfileVersion("vId", 100L, "comp1")
        );

        CompanyProfileVersion emptySnapshot = CompanyProfileVersion.builder()
                .id("vId2")
                .companyId("comp1")
                .snapshot(Collections.emptyMap())
                .build();
        when(repository.findById("vId2")).thenReturn(Optional.of(emptySnapshot));

        assertThrows(BusinessValidationException.class, () ->
                factory.createFromCompanyProfileVersion("vId2", 100L, "comp1")
        );
    }

    @Test
    void shouldProduceDeterministicSha256Hash() {
        Map<String, Object> map1 = new HashMap<>();
        map1.put("b", 2);
        map1.put("a", 1); // Reverse insertion order

        CompanyProfileVersion version1 = CompanyProfileVersion.builder()
                .id("vId")
                .companyId("comp1")
                .companyProfileId("prof1")
                .version(2)
                .snapshot(map1)
                .build();
        when(repository.findById("vId")).thenReturn(Optional.of(version1));

        ApprovedSourceReference ref1 = factory.createFromCompanyProfileVersion("vId", 100L, "comp1");

        Map<String, Object> map2 = new HashMap<>();
        map2.put("a", 1);
        map2.put("b", 2);

        CompanyProfileVersion version2 = CompanyProfileVersion.builder()
                .id("vId2")
                .companyId("comp1")
                .companyProfileId("prof1")
                .version(2)
                .snapshot(map2)
                .build();
        when(repository.findById("vId2")).thenReturn(Optional.of(version2));

        ApprovedSourceReference ref2 = factory.createFromCompanyProfileVersion("vId2", 100L, "comp1");

        // Same content, different insertion order -> should have same hash due to deterministic JSON
        assertEquals(ref1.getSourceHash(), ref2.getSourceHash());
        // Verify SHA-256 representation (64 hex characters)
        assertEquals(64, ref1.getSourceHash().length());
        assertTrue(ref1.getSourceHash().matches("^[0-9a-f]{64}$"));

        Map<String, Object> mapChanged = new HashMap<>();
        mapChanged.put("a", 1);
        mapChanged.put("b", 3); // Changed value

        CompanyProfileVersion version3 = CompanyProfileVersion.builder()
                .id("vId3")
                .companyId("comp1")
                .companyProfileId("prof1")
                .version(2)
                .snapshot(mapChanged)
                .build();
        when(repository.findById("vId3")).thenReturn(Optional.of(version3));

        ApprovedSourceReference ref3 = factory.createFromCompanyProfileVersion("vId3", 100L, "comp1");

        assertNotEquals(ref1.getSourceHash(), ref3.getSourceHash());
    }
}

package com.apms.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = {OwnerOrganizationConfig.class})
@ActiveProfiles("test") // Use a profile that doesn't load application-dev.properties if needed, though default is fine
class OwnerOrganizationPropertiesTest {

    @Autowired
    private OwnerOrganizationProperties properties;

    @Test
    void shouldLoadDefaultOwnerOrganizationId() {
        assertNotNull(properties);
        assertEquals("6a31a0000000000000000001", properties.getCompanyProfileId());
    }
}

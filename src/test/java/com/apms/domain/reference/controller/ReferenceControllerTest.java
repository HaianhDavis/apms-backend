package com.apms.domain.reference.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
public class ReferenceControllerTest {

    private MockMvc mockMvc;

    @InjectMocks
    private ReferenceController referenceController;

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(referenceController).build();
    }

    @Test
    public void testGetKeyResults() throws Exception {
        mockMvc.perform(get("/api/v1/reference/key-results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[?(@.type == 'BASIC_COMPANY_INFORMATION')]").exists())
                .andExpect(jsonPath("$.data[?(@.type == 'CONTRACT_INFORMATION')].supportedRelationshipTypes[0]").value("PARTNER_WITH"));
    }
}

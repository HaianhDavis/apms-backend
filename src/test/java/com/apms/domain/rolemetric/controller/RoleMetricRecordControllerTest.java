package com.apms.domain.rolemetric.controller;

import com.apms.common.security.ProjectSecurityEvaluator;
import com.apms.domain.rolemetric.dto.CreateRoleMetricRequest;
import com.apms.domain.rolemetric.dto.RoleMetricResponse;
import com.apms.domain.rolemetric.dto.RoleMetricEvidenceRequest;
import com.apms.domain.rolemetric.dto.ReviewRoleMetricRequest;
import com.apms.domain.rolemetric.service.RoleMetricRecordService;
import com.apms.domain.rolemetric.enums.RoleMetricReviewDecision;
import com.apms.security.UserDetailsImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RoleMetricRecordController.class)
@AutoConfigureMockMvc(addFilters = false)
class RoleMetricRecordControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean(name = "projectSecurity")
    private ProjectSecurityEvaluator projectSecurityEvaluator;

    @MockitoBean
    private RoleMetricRecordService roleMetricRecordService;

    @MockitoBean
    private com.apms.domain.rolemetric.repository.RoleMetricRecordRepository recordRepository;

    @MockitoBean
    private com.apms.domain.rolemetric.repository.RoleMetricRecordVersionRepository versionRepository;

    @MockitoBean
    private com.apms.domain.rolemetric.repository.RoleMetricEvidenceVersionRepository evidenceVersionRepository;

    @MockitoBean
    private com.apms.security.UserDetailsServiceImpl userDetailsService;

    @MockitoBean
    private com.apms.security.AuthTokenFilter authTokenFilter;

    @MockitoBean
    private com.apms.security.AuthEntryPointJwt authEntryPointJwt;

    @MockitoBean
    private com.apms.security.JwtUtils jwtUtils;

    @BeforeEach
    void setup() {
        when(projectSecurityEvaluator.isStaff(any())).thenReturn(true);
        when(projectSecurityEvaluator.isManager(any())).thenReturn(true);
        when(projectSecurityEvaluator.isStaffOrManager(any())).thenReturn(true);
        when(projectSecurityEvaluator.isProjectReadable(any())).thenReturn(true);
    }

    @Test
    void createDraft() throws Exception {
        RoleMetricResponse resp = new RoleMetricResponse();
        resp.setId(10L);
        when(roleMetricRecordService.createDraft(org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(resp);

        CreateRoleMetricRequest req = new CreateRoleMetricRequest();
        req.setMetricKey("revenue_generated");
        req.setPeriodStart(LocalDate.of(2025, 1, 1));
        req.setPeriodEnd(LocalDate.of(2025, 12, 31));
        req.setTargetNumericValue(new BigDecimal("1000"));

        mockMvc.perform(post("/api/v1/projects/1/role-metrics")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10));
    }

    @Test
    void createDraft_MalformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/projects/1/role-metrics")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"targetNumericValue\": \"not-a-number\" }"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createDraft_InvalidEnum() throws Exception {
        String json = "{ \"projectId\": 1, \"metricKey\": \"revenue_generated\", \"sourceType\": \"INVALID_ENUM\" }";
        mockMvc.perform(post("/api/v1/projects/1/role-metrics/1/evidences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(400));
    }

    @Test
    void getWorkingList() throws Exception {
        when(recordRepository.findByProjectId(1L)).thenReturn(Collections.emptyList());
        mockMvc.perform(get("/api/v1/projects/1/role-metrics")).andExpect(status().isOk());
    }

    @Test
    void getApprovedList() throws Exception {
        when(versionRepository.findByProjectId(1L)).thenReturn(Collections.emptyList());
        mockMvc.perform(get("/api/v1/projects/1/role-metrics/approved")).andExpect(status().isOk());
    }

    @Test
    void getWorkingDetail() throws Exception {
        com.apms.domain.rolemetric.entity.RoleMetricRecord record = new com.apms.domain.rolemetric.entity.RoleMetricRecord();
        record.setId(10L);
        record.setStatus(com.apms.domain.rolemetric.enums.RoleMetricStatus.DRAFT);
        when(recordRepository.findById(10L)).thenReturn(java.util.Optional.of(record));
        mockMvc.perform(get("/api/v1/projects/1/role-metrics/10")).andExpect(status().isOk());
    }

    @Test
    void updateDraft() throws Exception {
        RoleMetricResponse resp = new RoleMetricResponse();
        resp.setId(10L);
        when(roleMetricRecordService.updateDraft(eq(1L), eq(10L), any())).thenReturn(resp);

        CreateRoleMetricRequest req = new CreateRoleMetricRequest();
        req.setTargetNumericValue(new BigDecimal("2000"));

        mockMvc.perform(patch("/api/v1/projects/1/role-metrics/10")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
        
        verify(roleMetricRecordService).updateDraft(eq(1L), eq(10L), any());
    }

    @Test
    void attachEvidence() throws Exception {
        com.apms.domain.rolemetric.dto.RoleMetricEvidenceResponse evResp = new com.apms.domain.rolemetric.dto.RoleMetricEvidenceResponse();
        evResp.setId(5L);
        when(roleMetricRecordService.attachEvidence(eq(1L), eq(10L), any())).thenReturn(evResp);
        
        RoleMetricEvidenceRequest req = new RoleMetricEvidenceRequest();
        req.setSourceType(com.apms.domain.rolemetric.enums.RoleMetricEvidenceSourceType.MANUAL_NOTE);
        
        mockMvc.perform(post("/api/v1/projects/1/role-metrics/10/evidences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        verify(roleMetricRecordService).attachEvidence(eq(1L), eq(10L), any());
    }

    @Test
    void deleteEvidence() throws Exception {
        mockMvc.perform(delete("/api/v1/projects/1/role-metrics/10/evidences/5")).andExpect(status().isNoContent());
    }

    @Test
    void submit() throws Exception {
        mockMvc.perform(post("/api/v1/projects/1/role-metrics/10/submit")).andExpect(status().isOk());
    }

    @Test
    void review() throws Exception {
        ReviewRoleMetricRequest req = new ReviewRoleMetricRequest();
        req.setDecision(RoleMetricReviewDecision.APPROVE);
        mockMvc.perform(post("/api/v1/projects/1/role-metrics/10/review")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    void reopen() throws Exception {
        mockMvc.perform(post("/api/v1/projects/1/role-metrics/10/reopen")).andExpect(status().isOk());
    }

    @Test
    void revisions() throws Exception {
        mockMvc.perform(post("/api/v1/projects/1/role-metrics/10/revisions")).andExpect(status().isOk());
    }

    @Test
    void currentApproved() throws Exception {
        com.apms.domain.rolemetric.entity.RoleMetricRecord record = new com.apms.domain.rolemetric.entity.RoleMetricRecord();
        record.setId(10L);
        record.setCurrentApprovedVersionId(100L);
        when(recordRepository.findById(10L)).thenReturn(java.util.Optional.of(record));

        com.apms.domain.rolemetric.entity.RoleMetricRecordVersion version = new com.apms.domain.rolemetric.entity.RoleMetricRecordVersion();
        version.setId(100L);
        when(versionRepository.findById(100L)).thenReturn(java.util.Optional.of(version));
        when(evidenceVersionRepository.findByRoleMetricRecordVersionId(100L)).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/v1/projects/1/role-metrics/10/current-approved")).andExpect(status().isOk());
    }

    @Test
    void versionList() throws Exception {
        com.apms.domain.rolemetric.entity.RoleMetricRecordVersion version = new com.apms.domain.rolemetric.entity.RoleMetricRecordVersion();
        version.setId(100L);
        when(versionRepository.findByRoleMetricRecordIdOrderByVersionNumberDesc(10L))
                .thenReturn(Collections.singletonList(version));
        when(evidenceVersionRepository.findByRoleMetricRecordVersionId(100L)).thenReturn(Collections.emptyList());
        mockMvc.perform(get("/api/v1/projects/1/role-metrics/10/versions")).andExpect(status().isOk());
    }

    @Test
    void versionDetail() throws Exception {
        com.apms.domain.rolemetric.entity.RoleMetricRecordVersion version = new com.apms.domain.rolemetric.entity.RoleMetricRecordVersion();
        version.setId(100L);
        when(versionRepository.findByRoleMetricRecordIdAndVersionNumber(10L, 2)).thenReturn(java.util.Optional.of(version));
        when(evidenceVersionRepository.findByRoleMetricRecordVersionId(100L)).thenReturn(Collections.emptyList());
        mockMvc.perform(get("/api/v1/projects/1/role-metrics/10/versions/2")).andExpect(status().isOk());
    }
}

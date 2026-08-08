package com.apms.domain.contract.controller;

import com.apms.common.response.PageResponse;
import com.apms.domain.contract.dto.PartnerContractResponse;
import com.apms.domain.contract.service.PartnerContractService;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PartnerContractProfileControllerTest {

    private MockMvc mockMvc;

    @Mock
    private PartnerContractService partnerContractService;

    @InjectMocks
    private PartnerContractProfileController controller;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private UserDetailsImpl userDetails;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new org.springframework.web.method.support.HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(org.springframework.core.MethodParameter parameter) {
                        return parameter.getParameterAnnotation(org.springframework.security.core.annotation.AuthenticationPrincipal.class) != null;
                    }

                    @Override
                    public Object resolveArgument(org.springframework.core.MethodParameter parameter, org.springframework.web.method.support.ModelAndViewContainer mavContainer, org.springframework.web.context.request.NativeWebRequest webRequest, org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {
                        return userDetails;
                    }
                })
                .build();

        userDetails = new UserDetailsImpl(
                1L,
                "test@example.com",
                "password",
                Collections.singleton(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")),
                true
        );
    }

    @Test
    void listContracts_defaultPagination() throws Exception {
        when(partnerContractService.listContractsByProfile(eq("profile-123"), any(), any()))
                .thenReturn(PageResponse.<PartnerContractResponse>builder()
                        .content(Collections.emptyList())
                        .pageNumber(0)
                        .pageSize(10)
                        .totalElements(0L)
                        .totalPages(0)
                        .build());

        mockMvc.perform(get("/api/v1/company-profiles/profile-123/partner-contracts")
                        .principal(new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(partnerContractService).listContractsByProfile(eq("profile-123"), any(), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertEquals(0, pageable.getPageNumber());
        assertEquals(10, pageable.getPageSize());
    }

    @Test
    void listContracts_requestedPagination() throws Exception {
        when(partnerContractService.listContractsByProfile(eq("profile-123"), any(), any()))
                .thenReturn(PageResponse.<PartnerContractResponse>builder()
                        .content(Collections.emptyList())
                        .pageNumber(2)
                        .pageSize(20)
                        .totalElements(0L)
                        .totalPages(0)
                        .build());

        mockMvc.perform(get("/api/v1/company-profiles/profile-123/partner-contracts")
                        .param("page", "2")
                        .param("size", "20")
                        .principal(new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(partnerContractService).listContractsByProfile(eq("profile-123"), any(), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertEquals(2, pageable.getPageNumber());
        assertEquals(20, pageable.getPageSize());
    }

    @Test
    void listContracts_pageSizeCapped() throws Exception {
        when(partnerContractService.listContractsByProfile(eq("profile-123"), any(), any()))
                .thenReturn(PageResponse.<PartnerContractResponse>builder()
                        .content(Collections.emptyList())
                        .pageNumber(0)
                        .pageSize(100)
                        .totalElements(0L)
                        .totalPages(0)
                        .build());

        mockMvc.perform(get("/api/v1/company-profiles/profile-123/partner-contracts")
                        .param("page", "0")
                        .param("size", "500")
                        .principal(new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(partnerContractService).listContractsByProfile(eq("profile-123"), any(), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertEquals(0, pageable.getPageNumber());
        assertEquals(100, pageable.getPageSize());
    }
}

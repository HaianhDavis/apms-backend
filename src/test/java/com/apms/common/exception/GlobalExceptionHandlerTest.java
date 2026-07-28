package com.apms.common.exception;

import com.apms.common.response.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @RestController
    static class TestController {

        static class TestDto {
            @NotNull
            public String name;
        }

        @GetMapping("/resource-not-found")
        public void resourceNotFound() {
            throw new ResourceNotFoundException("Item not found");
        }

        @GetMapping("/business-validation")
        public void businessValidation() {
            throw new BusinessValidationException("Business rule violated");
        }

        @GetMapping("/access-denied")
        public void accessDenied() {
            throw new AccessDeniedException("Access Denied");
        }

        @GetMapping("/authorization-denied")
        public void authorizationDenied() {
            throw new AuthorizationDeniedException("Authorization Denied", new org.springframework.security.authorization.AuthorizationDecision(false));
        }

        @GetMapping("/unexpected")
        public void unexpected() {
            throw new RuntimeException("Unexpected error");
        }

        @PostMapping("/validation")
        public void validation(@Valid @RequestBody TestDto dto) {
            // Spring handles MethodArgumentNotValidException if @Valid fails
        }

        @PostMapping("/malformed")
        public void malformed(@RequestBody TestDto dto) {
            // Just to accept body
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void testResourceNotFound() throws Exception {
        mockMvc.perform(get("/resource-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void testBusinessValidation() throws Exception {
        mockMvc.perform(get("/business-validation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Business rule violated"));
    }

    @Test
    void testAccessDenied() throws Exception {
        mockMvc.perform(get("/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Access Denied"));
    }

    @Test
    void testAuthorizationDenied() throws Exception {
        mockMvc.perform(get("/authorization-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Access Denied"));
    }

    @Test
    void testUnexpectedException() throws Exception {
        mockMvc.perform(get("/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }

    @Test
    void testValidationException() throws Exception {
        mockMvc.perform(post("/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Validation failed: name: must not be null"));
    }

    @Test
    void testMalformedJson() throws Exception {
        mockMvc.perform(post("/malformed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ malformed }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Malformed JSON payload"));
    }
}

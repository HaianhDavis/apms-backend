package com.apms.common;

import com.apms.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.extension.ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
public class JacksonIntegrationTest {

    @RestController
    static class TestController {
        @GetMapping("/test/success")
        public ApiResponse<String> success() {
            return ApiResponse.success("Hello", "Success");
        }

        @GetMapping("/test/error")
        public ApiResponse<String> error() {
            throw new RuntimeException("Test Exception");
        }
    }

    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @org.junit.jupiter.api.BeforeEach
    public void setup() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        org.springframework.http.converter.json.MappingJackson2HttpMessageConverter converter = new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(mapper);

        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new com.apms.common.exception.GlobalExceptionHandler())
                .setMessageConverters(converter)
                .build();
    }

    @Test
    public void testLocalDateTimeSerialization() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/test/success"))
               .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
               .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("\"timestamp\":")))
               .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.matchesPattern(".*\"timestamp\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*\".*")));
    }

    @Test
    public void testGlobalExceptionHandlerSerialization() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/test/error"))
               .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isInternalServerError())
               .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("\"timestamp\":")))
               .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.matchesPattern(".*\"timestamp\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*\".*")))
               .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("\"message\":\"RuntimeException: Test Exception\"")));
    }
}

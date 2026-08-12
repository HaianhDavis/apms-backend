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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(JacksonIntegrationTest.Config.class)
public class JacksonIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        public TestController testController() {
            return new TestController();
        }
    }

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

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    public void testLocalDateTimeSerialization() {
        ResponseEntity<String> response = restTemplate.getForEntity("/test/success", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"timestamp\":");
        assertThat(response.getBody()).matches(".*\"timestamp\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*\".*");
    }

    @Test
    public void testGlobalExceptionHandlerSerialization() {
        ResponseEntity<String> response = restTemplate.getForEntity("/test/error", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).contains("\"timestamp\":");
        assertThat(response.getBody()).matches(".*\"timestamp\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*\".*");
        assertThat(response.getBody()).contains("\"message\":\"RuntimeException: Test Exception\"");
    }
}

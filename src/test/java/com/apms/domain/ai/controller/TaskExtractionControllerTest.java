package com.apms.domain.ai.controller;

import com.apms.common.enums.AiExtractionJobStage;
import com.apms.common.enums.AiExtractionJobStatus;
import com.apms.common.response.ApiResponse;
import com.apms.domain.ai.dto.AiExtractionJobResponse;
import com.apms.domain.ai.entity.AiExtractionJob;
import com.apms.domain.ai.service.TaskExtractionOrchestrator;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskExtractionControllerTest {

    @Mock
    private TaskExtractionOrchestrator orchestrator;

    @InjectMocks
    private TaskExtractionController controller;

    private UserDetailsImpl currentUser;

    @BeforeEach
    void setUp() {
        currentUser = new UserDetailsImpl(10L, "staff@test.com", "secret", List.of(), true);
    }

    @Test
    @DisplayName("getLatestExtractionJob returns mapped job when exists")
    void getLatestExtractionJob_found() {
        AiExtractionJob job = AiExtractionJob.builder()
                .id("job-123")
                .taskId(200L)
                .status(AiExtractionJobStatus.PROCESSING)
                .stage(AiExtractionJobStage.EXTRACTING)
                .progress(45)
                .totalDocuments(2)
                .processedDocuments(1)
                .startedAt(LocalDateTime.now())
                .build();

        when(orchestrator.getLatestExtractionJob(1L, 200L, 10L)).thenReturn(job);

        ResponseEntity<ApiResponse<AiExtractionJobResponse>> response =
                controller.getLatestExtractionJob(1L, 200L, currentUser);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isNotNull();
        assertThat(response.getBody().getData().getJobId()).isEqualTo("job-123");
        assertThat(response.getBody().getData().getStatus()).isEqualTo(AiExtractionJobStatus.PROCESSING);
        assertThat(response.getBody().getData().getStage()).isEqualTo(AiExtractionJobStage.EXTRACTING);
        assertThat(response.getBody().getData().getProgress()).isEqualTo(45);
        verify(orchestrator).getLatestExtractionJob(1L, 200L, 10L);
    }

    @Test
    @DisplayName("getLatestExtractionJob returns null data when no job exists")
    void getLatestExtractionJob_notFound() {
        when(orchestrator.getLatestExtractionJob(1L, 200L, 10L)).thenReturn(null);

        ResponseEntity<ApiResponse<AiExtractionJobResponse>> response =
                controller.getLatestExtractionJob(1L, 200L, currentUser);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isNull();
    }

    @Test
    @DisplayName("getActiveExtractionJob returns active job when in progress")
    void getActiveExtractionJob_found() {
        AiExtractionJob job = AiExtractionJob.builder()
                .id("job-active-1")
                .taskId(200L)
                .status(AiExtractionJobStatus.PROCESSING)
                .stage(AiExtractionJobStage.MERGING)
                .progress(70)
                .build();

        when(orchestrator.getActiveExtractionJob(1L, 200L, 10L)).thenReturn(job);

        ResponseEntity<ApiResponse<AiExtractionJobResponse>> response =
                controller.getActiveExtractionJob(1L, 200L, currentUser);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData().getJobId()).isEqualTo("job-active-1");
        assertThat(response.getBody().getData().getProgress()).isEqualTo(70);
    }

    @Test
    @DisplayName("getActiveExtractionJob returns null when no active job")
    void getActiveExtractionJob_null() {
        when(orchestrator.getActiveExtractionJob(1L, 200L, 10L)).thenReturn(null);

        ResponseEntity<ApiResponse<AiExtractionJobResponse>> response =
                controller.getActiveExtractionJob(1L, 200L, currentUser);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().getData()).isNull();
    }

    @Test
    @DisplayName("getExtractionJobStatus delegates with user validation")
    void getExtractionJobStatus_delegates() {
        AiExtractionJob job = AiExtractionJob.builder()
                .id("job-999")
                .taskId(200L)
                .status(AiExtractionJobStatus.COMPLETED)
                .stage(AiExtractionJobStage.COMPLETED)
                .progress(100)
                .build();

        when(orchestrator.getExtractionJob(1L, 200L, "job-999", 10L)).thenReturn(job);

        ResponseEntity<ApiResponse<AiExtractionJobResponse>> response =
                controller.getExtractionJobStatus(1L, 200L, "job-999", currentUser);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().getData().getJobId()).isEqualTo("job-999");
        assertThat(response.getBody().getData().getStatus()).isEqualTo(AiExtractionJobStatus.COMPLETED);
        assertThat(response.getBody().getData().getProgress()).isEqualTo(100);
    }

    @Test
    @DisplayName("cancelExtractionJob cancels active job and returns mapped response")
    void cancelExtractionJob_success() {
        LocalDateTime cancelledTime = LocalDateTime.now();
        AiExtractionJob job = AiExtractionJob.builder()
                .id("job-cancel-1")
                .taskId(200L)
                .status(AiExtractionJobStatus.CANCELLED)
                .stage(AiExtractionJobStage.CANCELLED)
                .progress(45)
                .cancelledAt(cancelledTime)
                .cancelledBy(10L)
                .build();

        when(orchestrator.cancelExtractionJob(1L, 200L, "job-cancel-1", 10L)).thenReturn(job);

        ResponseEntity<ApiResponse<AiExtractionJobResponse>> response =
                controller.cancelExtractionJob(1L, 200L, "job-cancel-1", currentUser);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isNotNull();
        assertThat(response.getBody().getData().getJobId()).isEqualTo("job-cancel-1");
        assertThat(response.getBody().getData().getStatus()).isEqualTo(AiExtractionJobStatus.CANCELLED);
        assertThat(response.getBody().getData().getStage()).isEqualTo(AiExtractionJobStage.CANCELLED);
        assertThat(response.getBody().getData().getCancelledBy()).isEqualTo(10L);
        assertThat(response.getBody().getData().getCancelledAt()).isEqualTo(cancelledTime);
        verify(orchestrator).cancelExtractionJob(1L, 200L, "job-cancel-1", 10L);
    }
}

package com.apms.domain.dashboard.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class SignalEntryDto {
    private String id;
    private String title;
    private String summary;
    private String companyId;
    private String companyProfileId;
    private String companyName;
    private String source;
    private LocalDateTime occurredAt;
}

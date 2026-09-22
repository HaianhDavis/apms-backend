package com.apms.domain.profile;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "company_profile_manager_history")
public class CompanyProfileManagerHistory {

    @Id
    private String id;

    @Indexed
    private String companyProfileId;

    @Indexed
    private String companyId;

    private Long previousManagerAccountId;
    private String previousManagerDisplayName;

    private Long newManagerAccountId;
    private String newManagerDisplayName;

    private Long transferredByAccountId;
    private String transferredByDisplayName;

    private String reason;

    @CreatedDate
    private LocalDateTime transferredAt;
}

package com.apms.domain.listing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyNews {
    private String id;
    private Integer newsType;
    private String title;
    private String summary;
    private String sourceUrl;
    private String imageUrl;
    private LocalDateTime publishedAt;
    private LocalDateTime crawledAt;
}

package com.apms.domain.companymember.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemberImageUploadResponse {
    private String imageUrl;
    private String filename;
}

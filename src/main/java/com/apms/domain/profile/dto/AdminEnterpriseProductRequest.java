package com.apms.domain.profile.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.Length;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdminEnterpriseProductRequest {

    @NotBlank(message = "Tên sản phẩm/dịch vụ không được để trống")
    @Length(max = 255, message = "Tên sản phẩm/dịch vụ tối đa 255 ký tự")
    private String name;
}

package com.apms.domain.profile.dto;

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
public class AdminEnterpriseProductRequest {

    @NotBlank(message = "Tên sản phẩm/dịch vụ không được để trống")
    @Length(max = 255, message = "Tên sản phẩm/dịch vụ tối đa 255 ký tự")
    private String name;

    @Length(max = 100, message = "Phân loại sản phẩm tối đa 100 ký tự")
    private String category;

    @Length(max = 2000, message = "Mô tả sản phẩm tối đa 2000 ký tự")
    private String description;
}

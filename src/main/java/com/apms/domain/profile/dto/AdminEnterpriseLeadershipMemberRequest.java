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
public class AdminEnterpriseLeadershipMemberRequest {

    @NotBlank(message = "Tên thành viên ban lãnh đạo không được để trống")
    @Length(max = 255, message = "Tên thành viên tối đa 255 ký tự")
    private String fullName;

    @NotBlank(message = "Chức vụ không được để trống")
    @Length(max = 255, message = "Chức vụ tối đa 255 ký tự")
    private String position;

    @Length(max = 1000, message = "Đường dẫn ảnh tối đa 1000 ký tự")
    private String imageUrl;

    @Length(max = 1000, message = "Đường dẫn nguồn tối đa 1000 ký tự")
    private String sourceUrl;

    @Length(max = 2000, message = "Ghi chú tối đa 2000 ký tự")
    private String notes;
}

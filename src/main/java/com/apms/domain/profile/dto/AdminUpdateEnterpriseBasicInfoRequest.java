package com.apms.domain.profile.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.Length;
import org.hibernate.validator.constraints.URL;

/**
 * Whitelist DTO for SYSTEM_ADMIN to edit basic information of the canonical Owner Enterprise.
 * Strictly limited to Overview basic information. Does NOT permit modifying financials,
 * leadership, business fields, contracts, relationship closeness, news, or approval status.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUpdateEnterpriseBasicInfoRequest {

    @Length(max = 255, message = "Tên viết tắt tối đa 255 ký tự")
    private String tradeName;

    @Length(max = 255, message = "Tên pháp lý tối đa 255 ký tự")
    private String legalName;

    @Length(max = 50, message = "Mã số thuế tối đa 50 ký tự")
    private String taxCode;

    @URL(message = "Website không hợp lệ")
    @Length(max = 500, message = "Website tối đa 500 ký tự")
    private String website;

    @Email(message = "Email không hợp lệ")
    @Length(max = 255, message = "Email tối đa 255 ký tự")
    private String email;

    @Length(max = 50, message = "Số điện thoại tối đa 50 ký tự")
    private String phone;

    @Min(value = 0, message = "Số nhân viên phải lớn hơn hoặc bằng 0")
    private Integer employeeCount;

    @Length(max = 100, message = "Quy mô nhân sự tối đa 100 ký tự")
    private String employeeTier;

    @Length(max = 500, message = "Địa chỉ trụ sở tối đa 500 ký tự")
    private String headOfficeAddress;

    @Length(max = 2000, message = "Mô hình kinh doanh tối đa 2000 ký tự")
    private String businessModel;

    private Integer expectedMajorVersion;
    private Integer expectedRevision;
}

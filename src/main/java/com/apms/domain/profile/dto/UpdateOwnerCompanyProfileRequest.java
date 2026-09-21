package com.apms.domain.profile.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.hibernate.validator.constraints.Length;
import org.hibernate.validator.constraints.URL;

import java.util.List;

/**
 * Whitelist DTO for SYSTEM_ADMIN to update the Owner Organization's profile.
 * The backend resolves the Owner Company itself; callers cannot choose a companyId.
 * Field-wise update: only fields present (non-empty for strings) in the request are applied.
 * Fields not present here (id, companyId, createdAt, reviewStatus, security fields, ...) cannot be modified.
 */
@Data
public class UpdateOwnerCompanyProfileRequest {

    @Length(max = 255, message = "Tên công ty tối đa 255 ký tự")
    private String legalName;

    @Length(max = 255, message = "Tên viết tắt tối đa 255 ký tự")
    private String tradeName;

    @Length(max = 50, message = "Mã số thuế tối đa 50 ký tự")
    private String taxCode;

    @Length(max = 50, message = "Số đăng ký kinh doanh tối đa 50 ký tự")
    private String registrationNumber;

    private List<@Length(max = 255, message = "Ngành nghề tối đa 255 ký tự") String> industries;

    @Length(max = 500, message = "Mô hình kinh doanh tối đa 500 ký tự")
    private String businessModel;

    private Integer foundedYear;

    @Length(max = 2000, message = "Mô tả công ty tối đa 2000 ký tự")
    private String companyDescription;

    private List<@Length(max = 255, message = "Thị trường tối đa 255 ký tự") String> markets;

    private List<@Length(max = 255, message = "Khách hàng mục tiêu tối đa 255 ký tự") String> targetCustomers;

    @Valid
    private List<@Valid ProductRequest> products;

    @Valid
    private SwotRequest insights;

    @Valid
    private List<@Valid CompanyMemberRequest> companyMembers;

    @Length(max = 50, message = "Mã cổ phiếu tối đa 50 ký tự")
    private String stockTicker;

    @Length(max = 50, message = "Sàn giao dịch tối đa 50 ký tự")
    private String stockExchange;

    @Length(max = 50, message = "Quy mô nhân sự tối đa 50 ký tự")
    private String employeeTier;

    @Min(value = 1, message = "Số nhân viên phải lớn hơn 0")
    private Integer employeeCount;

    @Length(max = 50, message = "Nhóm doanh thu tối đa 50 ký tự")
    private String revenueTier;

    @Email(message = "Email không hợp lệ")
    @Length(max = 255, message = "Email tối đa 255 ký tự")
    private String email;

    @Length(max = 50, message = "Số điện thoại tối đa 50 ký tự")
    private String phone;

    @URL(message = "Website không hợp lệ")
    @Length(max = 500, message = "Website tối đa 500 ký tự")
    private String website;

    @Length(max = 500, message = "Địa chỉ tối đa 500 ký tự")
    private String address;

    private List<@Length(max = 500, message = "Địa chỉ tối đa 500 ký tự") String> addresses;

    private List<@Length(max = 100, message = "Thẻ phân loại tối đa 100 ký tự") String> tags;

    /**
     * SWOT analysis (strengths, weaknesses, opportunities, threats) managed by SYSTEM_ADMIN.
     * Only the lists present in the request are applied.
     */
    @Data
    public static class SwotRequest {
        private List<@Length(max = 1000, message = "Mục phân tích tối đa 1000 ký tự") String> strengths;
        private List<@Length(max = 1000, message = "Mục phân tích tối đa 1000 ký tự") String> weaknesses;
        private List<@Length(max = 1000, message = "Mục phân tích tối đa 1000 ký tự") String> opportunities;
        private List<@Length(max = 1000, message = "Mục phân tích tối đa 1000 ký tự") String> threats;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProductRequest {
        @Length(max = 255, message = "Tên sản phẩm tối đa 255 ký tự")
        private String name;
    }

    @Data
    public static class CompanyMemberRequest {
        @Length(max = 255, message = "Họ tên tối đa 255 ký tự")
        private String fullName;
        @Length(max = 255, message = "Chức vụ tối đa 255 ký tự")
        private String position;
        @Length(max = 500, message = "URL ảnh đại diện tối đa 500 ký tự")
        private String imageUrl;
        @Length(max = 500, message = "URL nguồn tối đa 500 ký tự")
        private String sourceUrl;
        @Length(max = 2000, message = "Ghi chú tối đa 2000 ký tự")
        private String notes;
    }
}

package com.apms.domain.profile.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

/**
 * Whitelist DTO for SYSTEM_ADMIN to create/update one financial statement of the
 * Owner Organization. The backend resolves the Owner Company itself; callers
 * cannot choose a companyId. A record is identified by (reportType, reportYear).
 */
@Data
public class FinancialReportRequest {

    @NotBlank(message = "Loại báo cáo tài chính không được để trống")
    @Length(max = 50, message = "Loại báo cáo tối đa 50 ký tự")
    private String reportType;

    @NotNull(message = "Năm tài chính là bắt buộc")
    @Min(value = 1900, message = "Năm tài chính phải từ 1900")
    @Max(value = 2100, message = "Năm tài chính phải đến 2100")
    private Integer reportYear;

    @Length(max = 20, message = "Kỳ báo cáo tối đa 20 ký tự")
    private String periodType;

    @Length(max = 50, message = "Kỳ báo cáo cụ thể tối đa 50 ký tự")
    private String reportPeriod;

    @NotBlank(message = "Dữ liệu báo cáo tài chính không được để trống")
    private String itemsJson;

    @Length(max = 500, message = "URL nguồn tối đa 500 ký tự")
    private String sourceUrl;
}

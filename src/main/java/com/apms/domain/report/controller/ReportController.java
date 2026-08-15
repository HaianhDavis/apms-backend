//package com.apms.domain.report.controller;
//
//import com.apms.common.response.ApiResponse;
//import com.apms.common.response.PageResponse;
//import com.apms.domain.report.dto.CompanyReportItemResponse;
//import com.apms.domain.report.service.ReportService;
//import lombok.RequiredArgsConstructor;
//import org.springframework.data.domain.PageRequest;
//import org.springframework.format.annotation.DateTimeFormat;
//import org.springframework.http.HttpHeaders;
//import org.springframework.http.MediaType;
//import org.springframework.http.ResponseEntity;
//import org.springframework.security.access.prepost.PreAuthorize;
//import org.springframework.web.bind.annotation.*;
//
//import java.time.LocalDateTime;
//
//@RestController
//@RequestMapping("/api/v1/reports")
//@RequiredArgsConstructor
//public class ReportController {
//
//    private final ReportService reportService;
//
//    @GetMapping("/companies")
//    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','BUSINESS_OWNER','BUSINESS_DEVELOPMENT_MANAGER','BUSINESS_DEVELOPMENT_STAFF')")
//    public ResponseEntity<ApiResponse<PageResponse<CompanyReportItemResponse>>> getCompanyReports(
//            @RequestParam(required = false) String relationshipType,
//            @RequestParam(required = false) String companyProfileId,
//            @RequestParam(required = false) String keyword,
//            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
//            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
//            @RequestParam(defaultValue = "0") int page,
//            @RequestParam(defaultValue = "20") int size) {
//
//        PageResponse<CompanyReportItemResponse> response = PageResponse.of(
//                reportService.getCompanyReports(relationshipType, companyProfileId, keyword, fromDate, toDate, PageRequest.of(page, size)));
//        return ResponseEntity.ok(ApiResponse.success(response));
//    }
//
//    @GetMapping("/companies/export")
//    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','BUSINESS_OWNER','BUSINESS_DEVELOPMENT_MANAGER')")
//    public ResponseEntity<byte[]> exportCompanyReports(
//            @RequestParam(required = false) String relationshipType,
//            @RequestParam(required = false) String companyProfileId,
//            @RequestParam(required = false) String keyword,
//            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
//            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate) {
//
//        byte[] csvBytes = reportService.exportCompanyReports(relationshipType, companyProfileId, keyword, fromDate, toDate);
//
//        HttpHeaders headers = new HttpHeaders();
//        headers.setContentType(MediaType.parseMediaType("text/csv"));
//        headers.setContentDispositionFormData("attachment", "company_report.csv");
//
//        return ResponseEntity.ok()
//                .headers(headers)
//                .body(csvBytes);
//    }
//}

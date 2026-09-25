package com.apms.domain.profile.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.dto.CreateOwnerEnterpriseRequest;
import com.apms.domain.profile.dto.AdminUpdateEnterpriseBasicInfoRequest;
import com.apms.domain.profile.dto.AdminUpdateEnterpriseBusinessFieldsRequest;
import com.apms.domain.profile.dto.AdminUpdateEnterpriseLeadershipRequest;
import com.apms.domain.profile.dto.ProfileResponse;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.profile.service.ProfileService;
import com.apms.security.UserDetailsImpl;
import jakarta.validation.Valid;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.apms.domain.profile.dto.CompanyProfileFinancialRowDto;
import com.apms.domain.profile.service.AdminMyEnterpriseFinancialService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.companymember.dto.MemberImageUploadResponse;
import com.apms.domain.document.service.StorageService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import java.util.List;

/**
 * Controller for SYSTEM_ADMIN to view and manage basic enterprise information,
 * business fields, leadership, and canonical financials of the canonical Owner Enterprise (FPT Corporation).
 * Strictly anti-IDOR: backend resolves the canonical owner enterprise itself;
 * clients cannot supply a company ID.
 */
@RestController
@RequestMapping("/api/v1/admin/my-enterprise")
@RequiredArgsConstructor
public class AdminMyEnterpriseController {

    private final OwnerOrganizationService ownerOrganizationService;
    private final ProfileService profileService;
    private final AdminMyEnterpriseFinancialService enterpriseFinancialService;
    private final StorageService storageService;

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ProfileResponse>> getAdminMyEnterprise() {
        Optional<CompanyProfile> ownerOpt = ownerOrganizationService.findOwnerCompanyProfile();
        if (ownerOpt.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(null, "Owner Enterprise has not been configured yet"));
        }
        return ResponseEntity.ok(ApiResponse.success(profileService.toResponse(ownerOpt.get())));
    }

    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ProfileResponse>> createAdminMyEnterprise(
            @Valid @RequestBody CreateOwnerEnterpriseRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        CompanyProfile created = ownerOrganizationService.createOwnerEnterprise(request, currentUser);
        return ResponseEntity.ok(ApiResponse.success(profileService.toResponse(created), "Owner Enterprise created successfully"));
    }

    @PatchMapping("/basic-info")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ProfileResponse>> updateAdminEnterpriseBasicInfo(
            @Valid @RequestBody AdminUpdateEnterpriseBasicInfoRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        ProfileResponse response = profileService.updateAdminEnterpriseBasicInfo(request, currentUser);
        return ResponseEntity.ok(ApiResponse.success(response, "Enterprise basic information updated successfully"));
    }

    @PatchMapping("/business-fields")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ProfileResponse>> updateAdminEnterpriseBusinessFields(
            @Valid @RequestBody AdminUpdateEnterpriseBusinessFieldsRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        ProfileResponse response = profileService.updateAdminEnterpriseBusinessFields(request, currentUser);
        return ResponseEntity.ok(ApiResponse.success(response, "Enterprise business fields updated successfully"));
    }

    @PutMapping("/leadership")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ProfileResponse>> updateAdminEnterpriseLeadership(
            @Valid @RequestBody AdminUpdateEnterpriseLeadershipRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        ProfileResponse response = profileService.updateAdminEnterpriseLeadership(request, currentUser);
        return ResponseEntity.ok(ApiResponse.success(response, "Enterprise leadership updated successfully"));
    }

    @PostMapping(value = "/financials/reports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<CompanyProfileFinancialRowDto>>> createFinancialReport(
            @RequestParam("title") String title,
            @RequestParam("year") Integer year,
            @RequestParam("period") String period,
            @RequestParam(value = "dataEntryMethod", defaultValue = "MANUAL") String dataEntryMethod,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        List<CompanyProfileFinancialRowDto> rows = enterpriseFinancialService.createFinancialReport(
                title, year, period, dataEntryMethod, file, currentUser);
        return ResponseEntity.ok(ApiResponse.success(rows, "Financial report created successfully"));
    }

    @PutMapping(value = "/financials/reports/{reportId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<CompanyProfileFinancialRowDto>>> updateFinancialReport(
            @PathVariable String reportId,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "year", required = false) Integer year,
            @RequestParam(value = "period", required = false) String period,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        List<CompanyProfileFinancialRowDto> rows = enterpriseFinancialService.updateFinancialReport(
                reportId, title, year, period, file, currentUser);
        return ResponseEntity.ok(ApiResponse.success(rows, "Financial report updated successfully"));
    }

    @PostMapping("/financials/reports/{reportId}/extract")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<CompanyProfileFinancialRowDto>>> extractFinancialReport(
            @PathVariable String reportId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        List<CompanyProfileFinancialRowDto> rows = enterpriseFinancialService.reExtractFinancialReport(
                reportId, currentUser);
        return ResponseEntity.ok(ApiResponse.success(rows, "Financial report extracted successfully"));
    }

    @PostMapping("/financials/reports/{reportId}/re-extract")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<CompanyProfileFinancialRowDto>>> reExtractFinancialReport(
            @PathVariable String reportId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        List<CompanyProfileFinancialRowDto> rows = enterpriseFinancialService.reExtractFinancialReport(
                reportId, currentUser);
        return ResponseEntity.ok(ApiResponse.success(rows, "Financial report re-extracted successfully"));
    }

    @PostMapping("/financials/reports/{reportId}/cancel-extract")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> cancelExtractFinancialReport(
            @PathVariable String reportId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        enterpriseFinancialService.cancelExtractFinancialReport(reportId, currentUser);
        return ResponseEntity.ok(ApiResponse.success(null, "Financial extraction cancelled successfully"));
    }

    @DeleteMapping("/financials/reports/{reportId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteFinancialReport(
            @PathVariable String reportId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        enterpriseFinancialService.deleteFinancialReport(reportId, currentUser);
        return ResponseEntity.ok(ApiResponse.success(null, "Financial report deleted successfully"));
    }

    @PostMapping(value = "/leadership/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<MemberImageUploadResponse>> uploadLeadershipImage(
            @RequestParam("file") MultipartFile file) {
        long maxImageSizeBytes = 5 * 1024 * 1024; // 5 MB
        if (file == null || file.isEmpty()) {
            throw new BusinessValidationException("Image file cannot be empty");
        }
        if (file.getSize() > maxImageSizeBytes) {
            throw new BusinessValidationException("Image size must not exceed 5 MB.");
        }

        String contentType = file.getContentType();
        if (contentType == null || (!contentType.equals("image/jpeg") && !contentType.equals("image/png") && !contentType.equals("image/webp"))) {
            throw new BusinessValidationException("Please select a JPG, PNG, or WEBP image.");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename != null) {
            String lower = originalFilename.toLowerCase();
            if (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg") && !lower.endsWith(".png") && !lower.endsWith(".webp")) {
                throw new BusinessValidationException("Please select a JPG, PNG, or WEBP image.");
            }
        }

        String filename = storageService.store(file);
        String imageUrl = String.format("/api/v1/admin/my-enterprise/leadership/images/%s", filename);

        return ResponseEntity.ok(ApiResponse.success(
                MemberImageUploadResponse.builder()
                        .imageUrl(imageUrl)
                        .filename(filename)
                        .build(),
                "Leadership image uploaded successfully"
        ));
    }

    @GetMapping("/leadership/images/{filename:.+}")
    public ResponseEntity<Resource> getLeadershipImage(@PathVariable String filename) {
        try {
            Resource resource = storageService.loadAsResource(filename);
            MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
            String lower = filename.toLowerCase();
            if (lower.endsWith(".png")) {
                mediaType = MediaType.IMAGE_PNG;
            } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                mediaType = MediaType.IMAGE_JPEG;
            } else if (lower.endsWith(".webp")) {
                mediaType = MediaType.parseMediaType("image/webp");
            }
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                    .body(resource);
        } catch (Exception e) {
            throw new ResourceNotFoundException("Image not found: " + filename);
        }
    }
}

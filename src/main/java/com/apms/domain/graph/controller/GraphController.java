package com.apms.domain.graph.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.graph.dto.GraphCompanyDto;
import com.apms.domain.graph.service.GraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/graph")
@RequiredArgsConstructor
public class GraphController {

    private final GraphService graphService;

    @GetMapping("/companies/{companyId}")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<GraphCompanyDto>> getCompanyNode(@PathVariable String companyId) {
        GraphCompanyDto result = graphService.getCompanyNodeWithRelationships(companyId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/network")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<GraphCompanyDto>>> getNetwork() {
        return ResponseEntity.ok(ApiResponse.success(graphService.getNetwork()));
    }

    @GetMapping("/partners")
    @PreAuthorize("hasRole('BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<List<GraphCompanyDto>>> getPartners() {
        return ResponseEntity.ok(ApiResponse.success(graphService.getCompaniesByRelationshipType("PARTNER_WITH")));
    }

    @GetMapping("/competitors")
    @PreAuthorize("hasRole('BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<List<GraphCompanyDto>>> getCompetitors() {
        return ResponseEntity.ok(ApiResponse.success(graphService.getCompaniesByRelationshipType("COMPETITOR_OF")));
    }

    @GetMapping("/suppliers")
    @PreAuthorize("hasRole('BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<List<GraphCompanyDto>>> getSuppliers() {
        return ResponseEntity.ok(ApiResponse.success(graphService.getCompaniesByRelationshipType("SUPPLIER_OF")));
    }

    @GetMapping("/customers")
    @PreAuthorize("hasRole('BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<List<GraphCompanyDto>>> getCustomers() {
        return ResponseEntity.ok(ApiResponse.success(graphService.getCompaniesByRelationshipType("CUSTOMER_OF")));
    }

    @GetMapping("/potential-partners")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<List<GraphCompanyDto>>> getPotentialPartners() {
        return ResponseEntity.ok(ApiResponse.success(graphService.getCompaniesByRelationshipType("POTENTIAL_PARTNER_OF")));
    }
}

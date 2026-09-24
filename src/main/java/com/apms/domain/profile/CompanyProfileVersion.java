package com.apms.domain.profile;

import com.apms.domain.profile.enums.CompanyProfileChangeSource;
import com.apms.domain.profile.service.CompanyProfileVersionHelper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "company_profile_versions")
public class CompanyProfileVersion {

    @Id
    private String id;

    @Indexed
    private String companyProfileId;

    @Indexed
    private String companyId;

    private Integer majorVersion;

    private Integer revision;

    private String version;

    private String versionLabel;

    private CompanyProfileChangeSource changeSource;

    private Map<String, Object> snapshot;

    private List<String> changedFieldPaths;

    private Map<String, Object> beforeValues;

    private Map<String, Object> afterValues;

    private String changeNote;

    private String createdFromProposalId;
    private Long createdFromProjectId;
    private Long createdFromTaskId;

    private List<String> sourceDocumentIds;
    private String changeSummary;

    private Long createdBy;

    @CreatedDate
    private LocalDateTime createdAt;

    public String getVersionLabel() {
        if (versionLabel != null) {
            return versionLabel;
        }
        if (majorVersion != null && revision != null) {
            return CompanyProfileVersionHelper.formatVersionLabel(majorVersion, revision);
        }
        int[] parsed = CompanyProfileVersionHelper.parseLegacyVersion(version);
        return CompanyProfileVersionHelper.formatVersionLabel(parsed[0], parsed[1]);
    }
}

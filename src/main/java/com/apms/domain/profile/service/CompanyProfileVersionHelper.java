package com.apms.domain.profile.service;

import com.apms.domain.profile.CompanyProfile;
import org.springframework.util.StringUtils;

public final class CompanyProfileVersionHelper {

    private CompanyProfileVersionHelper() {}

    public record VersionState(int majorVersion, int revision, String versionLabel, String legacyVersion) {}

    /**
     * Single canonical version state resolver for both GET responses and stale guard comparisons.
     */
    public static VersionState resolveVersion(CompanyProfile profile) {
        if (profile == null) {
            return new VersionState(1, 0, "v1.00", "1.00");
        }
        int major;
        int rev;
        if (profile.getMajorVersion() != null && profile.getRevision() != null) {
            major = profile.getMajorVersion();
            rev = profile.getRevision();
        } else if (StringUtils.hasText(profile.getVersion())) {
            int[] parsed = parseLegacyVersion(profile.getVersion());
            major = profile.getMajorVersion() != null ? profile.getMajorVersion() : parsed[0];
            rev = profile.getRevision() != null ? profile.getRevision() : parsed[1];
        } else {
            major = profile.getMajorVersion() != null ? profile.getMajorVersion() : 1;
            rev = profile.getRevision() != null ? profile.getRevision() : 0;
        }

        String label = formatVersionLabel(major, rev);
        String legacy = formatLegacyVersion(major, rev);
        return new VersionState(major, rev, label, legacy);
    }

    /**
     * Derives canonical version label, e.g. "v1.00", "v1.01", "v1.10", "v1.1112", "v2.00".
     * Always pads revision to at least 2 digits.
     */
    public static String formatVersionLabel(Integer majorVersion, Integer revision) {
        int major = majorVersion != null ? majorVersion : 1;
        int rev = revision != null ? revision : 0;
        return String.format("v%d.%02d", major, rev);
    }

    /**
     * Legacy string version format, e.g. "1.00", "1.01", "1.10", "1.1112", "2.00".
     */
    public static String formatLegacyVersion(Integer majorVersion, Integer revision) {
        int major = majorVersion != null ? majorVersion : 1;
        int rev = revision != null ? revision : 0;
        return String.format("%d.%02d", major, rev);
    }

    /**
     * Resolves authoritative majorVersion from profile, falling back safely to legacy String version.
     */
    public static int resolveMajorVersion(CompanyProfile profile) {
        return resolveVersion(profile).majorVersion();
    }

    /**
     * Resolves authoritative revision from profile, falling back safely to legacy String version.
     */
    public static int resolveRevision(CompanyProfile profile) {
        return resolveVersion(profile).revision();
    }

    /**
     * Parses legacy version string into [major, revision].
     * Examples:
     * - "1.0"  -> [1, 0]
     * - "1.1"  -> [1, 1]
     * - "1.09" -> [1, 9]
     * - "1.9"  -> [1, 9]
     * - "1.10" -> [1, 10]
     * - "1.1112" -> [1, 1112]
     * - "2.0"  -> [2, 0]
     * - null or invalid -> [1, 0]
     */
    public static int[] parseLegacyVersion(String versionStr) {
        if (!StringUtils.hasText(versionStr)) {
            return new int[]{1, 0};
        }
        String clean = versionStr.trim();
        if (clean.startsWith("v") || clean.startsWith("V")) {
            clean = clean.substring(1);
        }
        try {
            String[] parts = clean.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int rev = 0;
            if (parts.length > 1 && StringUtils.hasText(parts[1])) {
                rev = Integer.parseInt(parts[1]);
            }
            return new int[]{Math.max(1, major), Math.max(0, rev)};
        } catch (Exception e) {
            return new int[]{1, 0};
        }
    }

    /**
     * Initializes initial version (v1.00) on a new official profile.
     */
    public static void applyInitialVersion(CompanyProfile profile) {
        profile.setMajorVersion(1);
        profile.setRevision(0);
        profile.setVersion(formatLegacyVersion(1, 0));
    }

    /**
     * Increments revision for an existing profile (e.g. v1.00 -> v1.01).
     */
    public static void applyNextRevision(CompanyProfile profile) {
        VersionState current = resolveVersion(profile);
        int currentMajor = current.majorVersion();
        int nextRev = current.revision() + 1;

        profile.setMajorVersion(currentMajor);
        profile.setRevision(nextRev);
        profile.setVersion(formatLegacyVersion(currentMajor, nextRev));
    }

    /**
     * Increments major version for UPDATE_EXISTING_COMPANY (e.g. v1.07 -> v2.00).
     */
    public static void applyNextMajor(CompanyProfile profile) {
        VersionState current = resolveVersion(profile);
        int nextMajor = current.majorVersion() + 1;

        profile.setMajorVersion(nextMajor);
        profile.setRevision(0);
        profile.setVersion(formatLegacyVersion(nextMajor, 0));
    }

    /**
     * Derives versionLabel for a profile.
     */
    public static String getVersionLabel(CompanyProfile profile) {
        return resolveVersion(profile).versionLabel();
    }
}

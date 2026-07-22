package com.apms.domain.score.util;

import com.apms.domain.rolemetric.entity.RoleMetricRecordVersion;
import com.apms.domain.rolemetric.enums.MetricPeriodType;
import com.apms.domain.rolemetric.enums.RoleMetricStatus;
import com.apms.domain.score.draft.EvaluationPeriod;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public final class RoleMetricSourceSelectionUtil {

    private RoleMetricSourceSelectionUtil() {}

    public static List<RoleMetricRecordVersion> selectForPeriod(List<RoleMetricRecordVersion> candidates, EvaluationPeriod evaluationPeriod) {
        if (evaluationPeriod == null) {
            return List.of();
        }

        return candidates.stream()
                .filter(record -> record.getStatus() == RoleMetricStatus.APPROVED)
                .filter(record -> isFullyContainedOrExact(record, evaluationPeriod))
                // Group by roleMetricRecordId and find highest version
                .collect(Collectors.groupingBy(
                        RoleMetricRecordVersion::getRoleMetricRecordId,
                        Collectors.maxBy(Comparator.comparing(RoleMetricRecordVersion::getVersionNumber))
                ))
                .values().stream()
                .filter(opt -> opt.isPresent())
                .map(opt -> opt.get())
                // Deterministic ordering: metricKey, then date, then id, then version
                .sorted(Comparator.comparing(RoleMetricRecordVersion::getMetricKey)
                        .thenComparing(r -> r.getPeriodType() == MetricPeriodType.POINT_IN_TIME ? r.getMeasurementDate() : r.getPeriodStart())
                        .thenComparing(RoleMetricRecordVersion::getRoleMetricRecordId)
                        .thenComparing(RoleMetricRecordVersion::getVersionNumber))
                .collect(Collectors.toList());
    }

    private static boolean isFullyContainedOrExact(RoleMetricRecordVersion record, EvaluationPeriod evalPeriod) {
        switch (evalPeriod.getType()) {
            case AS_OF_DATE:
                if (record.getPeriodType() == MetricPeriodType.POINT_IN_TIME) {
                    return evalPeriod.getAsOfDate().equals(record.getMeasurementDate());
                }
                return false;
            case ANNUAL:
            case QUARTERLY:
            case CUSTOM:
                if (record.getPeriodType() == MetricPeriodType.POINT_IN_TIME) {
                    return record.getMeasurementDate() != null &&
                            !record.getMeasurementDate().isBefore(evalPeriod.getPeriodStart()) &&
                            !record.getMeasurementDate().isAfter(evalPeriod.getPeriodEnd());
                } else {
                    return record.getPeriodStart() != null && record.getPeriodEnd() != null &&
                            !record.getPeriodStart().isBefore(evalPeriod.getPeriodStart()) &&
                            !record.getPeriodEnd().isAfter(evalPeriod.getPeriodEnd());
                }
            default:
                return false;
        }
    }
}

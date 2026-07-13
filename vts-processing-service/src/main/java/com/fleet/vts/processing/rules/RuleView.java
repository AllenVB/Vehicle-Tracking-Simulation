package com.fleet.vts.processing.rules;

import com.fleet.vts.common.enums.RuleType;
import com.fleet.vts.common.enums.Severity;

/**
 * A rule as it applies to a specific vehicle: metadata plus the effective
 * threshold after scope (group) overrides have been resolved.
 */
public record RuleView(
        Long ruleId,
        String code,
        RuleType type,
        Severity severity,
        double threshold,
        boolean enabled) {
}

package com.fleet.vts.common.enums;

/**
 * Types of rules the platform evaluates. Values mirror the {@code rule.type}
 * CHECK constraint in the database. Stateless rules are evaluated in the
 * processing service; stateful ones in the stream-analytics topology.
 */
public enum RuleType {
    // Stateless (processing-service)
    SPEED_LIMIT,
    LOW_BATTERY,
    LOW_FUEL,
    // Stateful (stream-analytics)
    HARSH_BRAKING,
    SUSTAINED_SPEEDING,
    IDLING,
    GEOFENCE_ENTER,
    GEOFENCE_EXIT,
    // Fleet-management (scheduler-service): odometer past the maintenance interval.
    // Not a driving behaviour, so it stays out of the trip score; it is still a violation.
    MAINTENANCE_OVERDUE
}

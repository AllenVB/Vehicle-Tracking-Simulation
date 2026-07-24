package com.fleet.vts.gateway.web.dto;

import java.util.List;

/** Fleet-wide time series for the analytics panel: violations per day and average score per day. */
public record FleetAnalyticsDto(List<DailyPointDto> violationsByDay, List<DailyPointDto> scoresByDay) {
}

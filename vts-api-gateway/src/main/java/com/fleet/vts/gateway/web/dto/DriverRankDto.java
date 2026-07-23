package com.fleet.vts.gateway.web.dto;

import java.math.BigDecimal;

/**
 * One driver's standing on the scoreboard: their 1-based {@code rank} out of {@code total}
 * scored drivers in the window, and the {@code score} that placed them there. Answered from
 * a Redis sorted set ({@code ZREVRANK}), so it costs a lookup rather than a re-sort.
 */
public record DriverRankDto(Long driverId, int rank, int total, BigDecimal score) {
}

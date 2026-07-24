package com.fleet.vts.gateway.web.dto;

/**
 * One day of a time series: {@code day} (yyyy-MM-dd), the {@code value} plotted (a count or an
 * average), and {@code count} of underlying rows. One shape for both the violations-per-day and
 * the average-score-per-day charts, so the client draws them with the same code.
 */
public record DailyPointDto(String day, double value, long count) {
}

package com.fleet.vts.gateway.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.vts.gateway.repository.ReportingQueryRepository;
import com.fleet.vts.gateway.web.dto.DriverRankDto;
import com.fleet.vts.gateway.web.dto.DriverScoreDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * A Redis-backed read model over the driver scoreboard.
 *
 * <p>The scoreboard is polled hard — the live map pulls the whole thing to label every vehicle
 * popup, on top of the sidebar's own refresh — and each pull was an aggregate over {@code trip}
 * joined to {@code driver}. Here that aggregate is computed once per window and cached for
 * {@link #TTL}: subsequent reads are a single {@code GET} of the pre-sorted list, and any driver's
 * standing is a {@code ZREVRANK} against a sorted set rather than a re-sort of the whole board.
 *
 * <p>Cache-aside and fail-open: on a cold window the query runs and fills Redis; if Redis is
 * unreachable the query runs anyway, so the board is never worse than the direct path, only
 * usually faster. Eventual within the TTL — a just-closed trip moves a driver's average at most
 * {@link #TTL} later, which for a standing (not an alarm) is the right trade.
 */
@Component
public class DriverLeaderboardCache {

    private static final Logger log = LoggerFactory.getLogger(DriverLeaderboardCache.class);
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final TypeReference<List<DriverScoreDto>> LIST_TYPE = new TypeReference<>() {
    };

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final ReportingQueryRepository reporting;

    public DriverLeaderboardCache(StringRedisTemplate redis, ObjectMapper mapper,
                                  ReportingQueryRepository reporting) {
        this.redis = redis;
        this.mapper = mapper;
        this.reporting = reporting;
    }

    /** Top {@code limit} drivers for the window, served from Redis when warm. */
    public List<DriverScoreDto> topDrivers(long tenantId, int days, int limit) {
        try {
            List<DriverScoreDto> all = warm(tenantId, days);
            return all.size() > limit ? List.copyOf(all.subList(0, limit)) : all;
        } catch (Exception ex) {
            log.warn("Leaderboard cache unavailable ({}); serving from Postgres", ex.getMessage());
            return reporting.findDriverScores(tenantId, days, limit);
        }
    }

    /** One driver's rank (1-based) out of everyone scored in the window, or null if unscored. */
    public DriverRankDto rank(long tenantId, int days, long driverId) {
        try {
            warm(tenantId, days);   // ensure the sorted set exists
            String zkey = zkey(tenantId, days);
            String member = String.valueOf(driverId);
            Long rank = redis.opsForZSet().reverseRank(zkey, member);
            if (rank == null) {
                return null;   // driver has no scored trips in the window
            }
            Long total = redis.opsForZSet().zCard(zkey);
            Double score = redis.opsForZSet().score(zkey, member);
            return new DriverRankDto(driverId, rank.intValue() + 1,
                    total == null ? 0 : total.intValue(),
                    score == null ? null : BigDecimal.valueOf(score));
        } catch (Exception ex) {
            log.warn("Leaderboard rank unavailable: {}", ex.getMessage());
            return null;
        }
    }

    /** The cached sorted list, populating Redis from Postgres on a cold window. */
    private List<DriverScoreDto> warm(long tenantId, int days) throws Exception {
        String json = redis.opsForValue().get(lkey(tenantId, days));
        if (json != null) {
            return mapper.readValue(json, LIST_TYPE);
        }
        List<DriverScoreDto> all = reporting.findAllDriverScores(tenantId, days);
        populate(tenantId, days, all);
        return all;
    }

    /** Write the sorted list plus the ranking set in one pipeline, both under the same TTL. */
    private void populate(long tenantId, int days, List<DriverScoreDto> all) throws Exception {
        String lkey = lkey(tenantId, days);
        String zkey = zkey(tenantId, days);
        String json = mapper.writeValueAsString(all);
        redis.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                ops.opsForValue().set(lkey, json, TTL);
                ops.delete(zkey);   // rebuild from scratch: a driver may have dropped out of the window
                for (DriverScoreDto d : all) {
                    double score = d.score() == null ? 0 : d.score().doubleValue();
                    ops.opsForZSet().add(zkey, String.valueOf(d.driverId()), score);
                }
                ops.expire(zkey, TTL);
                return null;
            }
        });
    }

    private static String lkey(long tenantId, int days) {
        return "vts:lb:" + tenantId + ":" + days + ":list";
    }

    private static String zkey(long tenantId, int days) {
        return "vts:lb:" + tenantId + ":" + days + ":z";
    }
}

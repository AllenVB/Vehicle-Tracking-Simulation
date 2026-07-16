package com.fleet.vts.gateway.web.dto;

import java.util.List;

/**
 * One page of a keyset-paginated result. {@code nextCursor} is null on the last page;
 * callers pass it back verbatim to fetch the next one.
 */
public record CursorPage<T>(List<T> items, String nextCursor) {
}

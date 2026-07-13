package com.fleet.vts.common.tenant;

/**
 * Holds the current tenant id for the executing thread. Set on the way in
 * (JWT claim, Kafka header, or resolved from the device) and cleared in a
 * finally block. Backed by an inheritable-free ThreadLocal; each virtual thread
 * that handles a request/record gets its own value.
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Long tenantId) {
        CURRENT.set(tenantId);
    }

    /** @return the current tenant id, or {@code null} if none is bound. */
    public static Long get() {
        return CURRENT.get();
    }

    /** @return the current tenant id, or throws if none is bound. */
    public static long require() {
        Long value = CURRENT.get();
        if (value == null) {
            throw new IllegalStateException("No tenant bound to the current thread");
        }
        return value;
    }

    public static void clear() {
        CURRENT.remove();
    }
}

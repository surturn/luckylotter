package com.lucklotter.security;

import java.util.UUID;

/**
 * The authenticated admin, as carried by the JWT (NFR-1).
 *
 * <p>{@link #businessId()} is the <em>only</em> trusted tenant scope in the
 * application. Services take it from here — never from a path variable, query
 * parameter, or request body, any of which the caller controls.
 */
public record AdminPrincipal(UUID adminUserId, UUID businessId, String email) {
}

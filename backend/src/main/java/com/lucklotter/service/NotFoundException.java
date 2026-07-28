package com.lucklotter.service;

/**
 * A resource does not exist, or does not belong to the caller's business.
 *
 * <p>The two are deliberately indistinguishable to the caller: answering "that
 * flag exists, but not for you" with a 403 would leak the existence of another
 * tenant's rows (NFR-1).
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}

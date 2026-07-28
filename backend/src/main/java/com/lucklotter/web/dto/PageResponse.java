package com.lucklotter.web.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * A page of results with an explicit, stable shape.
 *
 * <p>Serializing Spring's {@code Page} directly would leak its internal JSON
 * structure into the API contract, and Boot warns about exactly that.
 */
public record PageResponse<T>(
    List<T> items,
    int page,
    int size,
    long totalItems,
    int totalPages
) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}

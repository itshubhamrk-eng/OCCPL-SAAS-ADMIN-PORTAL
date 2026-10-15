package com.occpl.platform.shared.paging;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** Standard paginated response body (API_SPEC.md §1). */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<? super E, ? extends T> mapper) {
        return new PageResponse<>(page.getContent().stream().<T>map(mapper).toList(), page.getNumber(),
                page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}

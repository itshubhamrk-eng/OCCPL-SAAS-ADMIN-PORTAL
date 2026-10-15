package com.occpl.platform.shared.paging;

import java.util.List;

/**
 * Raw {@code sort} query values such as {@code createdAt,desc}. Spring would split a plain
 * {@code List<String>} parameter at the comma, so controllers take this type instead (see
 * {@link SortParamsResolver}).
 */
public record SortParams(List<String> values) {

    public SortParams {
        values = values == null ? List.of() : List.copyOf(values);
    }
}

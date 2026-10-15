package com.occpl.platform.shared.paging;

import com.occpl.platform.shared.error.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Builds bounded, deterministic {@link PageRequest}s from untrusted query parameters. Sort fields
 * are mapped through an allowlist of API names to entity properties, and {@code id} is always
 * appended as a tie-breaker so pages are stable.
 */
public final class PageRequests {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private PageRequests() {
    }

    /**
     * @param sortParams values like {@code "createdAt,desc"}; may be null or empty
     * @param allowedSorts API sort name → entity property
     * @param defaultSort applied when no sort is requested
     */
    public static PageRequest of(Integer page, Integer size, List<String> sortParams,
            Map<String, String> allowedSorts, Sort defaultSort) {
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_SIZE : size;
        if (pageNumber < 0) {
            throw ApiException.validation("page must be greater than or equal to 0.");
        }
        if (pageSize < 1 || pageSize > MAX_SIZE) {
            throw ApiException.validation("size must be between 1 and " + MAX_SIZE + ".");
        }
        Sort sort = sortParams == null || sortParams.isEmpty() ? defaultSort : parse(sortParams, allowedSorts);
        if (sort.getOrderFor("id") == null) {
            sort = sort.and(Sort.by(Sort.Direction.ASC, "id"));
        }
        return PageRequest.of(pageNumber, pageSize, sort);
    }

    private static Sort parse(List<String> sortParams, Map<String, String> allowedSorts) {
        List<Sort.Order> orders = new ArrayList<>();
        for (String param : sortParams) {
            String[] parts = param.split(",", -1);
            if (parts.length > 2) {
                throw ApiException.validation("Invalid sort parameter.");
            }
            String property = allowedSorts.get(parts[0].trim());
            if (property == null) {
                throw ApiException.validation("Sorting by '" + parts[0].trim() + "' is not supported.");
            }
            Sort.Direction direction = parts.length == 2
                    ? Sort.Direction.fromOptionalString(parts[1].trim())
                            .orElseThrow(() -> ApiException.validation("Sort direction must be asc or desc."))
                    : Sort.Direction.ASC;
            orders.add(new Sort.Order(direction, property));
        }
        return Sort.by(orders);
    }
}

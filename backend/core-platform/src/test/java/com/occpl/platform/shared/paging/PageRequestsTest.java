package com.occpl.platform.shared.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.occpl.platform.shared.error.ApiException;
import com.occpl.platform.shared.error.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

class PageRequestsTest {

    private static final Map<String, String> SORTS = Map.of("createdAt", "createdAt", "name", "displayName");
    private static final Sort DEFAULT = Sort.by(Sort.Direction.DESC, "createdAt");

    @Test
    void appliesDefaultsAndIdTieBreaker() {
        PageRequest request = PageRequests.of(null, null, null, SORTS, DEFAULT);
        assertThat(request.getPageNumber()).isZero();
        assertThat(request.getPageSize()).isEqualTo(PageRequests.DEFAULT_SIZE);
        assertThat(request.getSort()).containsExactly(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
    }

    @Test
    void mapsAllowedSortNamesToProperties() {
        PageRequest request = PageRequests.of(2, 50, List.of("name,desc", "createdAt"), SORTS, DEFAULT);
        assertThat(request.getPageNumber()).isEqualTo(2);
        assertThat(request.getSort()).containsExactly(Sort.Order.desc("displayName"), Sort.Order.asc("createdAt"),
                Sort.Order.asc("id"));
    }

    @Test
    void rejectsUnknownSortField() {
        assertValidationError(() -> PageRequests.of(0, 20, List.of("passwordHash"), SORTS, DEFAULT));
    }

    @Test
    void rejectsInvalidDirection() {
        assertValidationError(() -> PageRequests.of(0, 20, List.of("name,sideways"), SORTS, DEFAULT));
    }

    @Test
    void rejectsOutOfRangePageAndSize() {
        assertValidationError(() -> PageRequests.of(-1, 20, null, SORTS, DEFAULT));
        assertValidationError(() -> PageRequests.of(0, 0, null, SORTS, DEFAULT));
        assertValidationError(() -> PageRequests.of(0, PageRequests.MAX_SIZE + 1, null, SORTS, DEFAULT));
    }

    private static void assertValidationError(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}

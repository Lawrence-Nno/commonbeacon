package com.lawrencenno.commonbeacon.search;

import java.util.List;
import java.util.Map;
import com.lawrencenno.commonbeacon.shared.ApiFailure;
import com.lawrencenno.commonbeacon.shared.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SearchService {
    private final SearchRepository searches;
    public SearchService(SearchRepository searches) { this.searches = searches; }

    public PageResponse<SearchHit> search(String input, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new ApiFailure(400, "INVALID_PAGE", "Use a nonnegative page and a size from 1 to 100; the offset must fit a 32-bit integer.");
        String query = input == null ? "" : input.trim();
        if (query.length() > 200)
            throw new ApiFailure(400, "VALIDATION_FAILED", "Search text is too long.", Map.of("q", "Use at most 200 characters after trimming."));
        if (query.isBlank()) return new PageResponse<>(List.of(), page, size, 0, 0);
        long total = searches.count(query);
        return new PageResponse<>(searches.hits(query, size, (long) page * size), page, size, total,
                (int) Math.min(Integer.MAX_VALUE, (total + size - 1) / size));
    }
}

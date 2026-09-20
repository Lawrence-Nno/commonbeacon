package com.lawrencenno.commonbeacon.search;

import java.util.Set;
import com.lawrencenno.commonbeacon.shared.ApiFailure;
import com.lawrencenno.commonbeacon.shared.PageResponse;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/search")
public class SearchController {
    private final SearchService searches;
    public SearchController(SearchService searches) { this.searches = searches; }

    @GetMapping
    public PageResponse<SearchHit> search(@RequestParam MultiValueMap<String, String> parameters) {
        if (!Set.of("q", "page", "size").containsAll(parameters.keySet())
                || parameters.values().stream().anyMatch(values -> values.size() != 1))
            throw new ApiFailure(400, "INVALID_REQUEST", "Use only one value for each of q, page, and size.");
        return searches.search(parameters.getFirst("q"), integer(parameters.getFirst("page"), 0),
                integer(parameters.getFirst("size"), 20));
    }
    private static int integer(String value, int fallback) {
        if (value == null) return fallback;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException exception) { throw new ApiFailure(400, "INVALID_PAGE", "Page and size must be integers."); }
    }
}

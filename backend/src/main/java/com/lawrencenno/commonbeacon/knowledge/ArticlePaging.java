package com.lawrencenno.commonbeacon.knowledge;

import java.util.Map;
import java.util.Set;
import com.lawrencenno.commonbeacon.shared.ApiFailure;

final class ArticlePaging {
    private ArticlePaging() {}
    static void validate(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new ApiFailure(400, "INVALID_PAGE", "Use a nonnegative page and a size from 1 to 100; the offset must fit a 32-bit integer.");
    }
    static int totalPages(long total, int size) { return (int) Math.min(Integer.MAX_VALUE, (total + size - 1) / size); }
    static void parameters(Map<String, String> parameters, boolean admin) {
        var allowed = admin ? Set.of("page", "size", "status") : Set.of("page", "size");
        if (!allowed.containsAll(parameters.keySet()))
            throw new ApiFailure(400, "INVALID_REQUEST", "Unsupported article list parameter.");
        if ((parameters.containsKey("page") && parameters.get("page").isBlank())
                || (parameters.containsKey("size") && parameters.get("size").isBlank()))
            throw new ApiFailure(400, "INVALID_PAGE", "Page and size must be integers.");
    }
}

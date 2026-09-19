package com.lawrencenno.commonbeacon.moderation;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.lawrencenno.commonbeacon.shared.ApiFailure;
import com.lawrencenno.commonbeacon.shared.PageResponse;

@RestController
@RequestMapping("/api/v1/moderation/{kind:questions|replies}/{id}")
public class ModerationContentController {
    private final ModerationContentService content;
    public ModerationContentController(ModerationContentService content) { this.content = content; }
    @GetMapping
    public ResponseEntity<ModerationContext> get(@PathVariable String kind, @PathVariable UUID id) {
        return noStore(content.get(id, kind.equals("replies")));
    }
    @GetMapping("/actions")
    public ResponseEntity<PageResponse<ModerationAction>> history(@PathVariable String kind, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam Map<String, String> parameters) {
        if (!Set.of("page", "size").containsAll(parameters.keySet()))
            throw new ApiFailure(400, "INVALID_REQUEST", "Only page and size are supported.");
        if (parameters.values().stream().anyMatch(String::isBlank))
            throw new ApiFailure(400, "INVALID_PAGE", "Page and size must be integers.");
        return noStore(content.history(id, kind.equals("replies"), page, size));
    }
    @PostMapping("/restore")
    public ResponseEntity<ModerationContext> restore(@PathVariable String kind, @PathVariable UUID id,
            @Valid @RequestBody RestoreContentRequest request, Authentication authentication) {
        return noStore(content.restore(id, kind.equals("replies"), request, authentication));
    }
    private static <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}

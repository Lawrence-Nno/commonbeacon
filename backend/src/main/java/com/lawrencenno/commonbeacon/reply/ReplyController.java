package com.lawrencenno.commonbeacon.reply;

import com.lawrencenno.commonbeacon.shared.PageResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ReplyController {
    private final ReplyService replies;
    public ReplyController(ReplyService replies) { this.replies = replies; }

    @GetMapping("/questions/{questionId}/replies")
    public PageResponse<ReplySummary> list(@PathVariable UUID questionId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return replies.list(questionId, page, size);
    }
    @GetMapping("/replies/{id}")
    public ReplySummary get(@PathVariable UUID id) { return replies.get(id); }

    @PostMapping("/questions/{questionId}/replies")
    public ResponseEntity<ReplySummary> create(@PathVariable UUID questionId,
            @Valid @RequestBody CreateReplyRequest request, Authentication authentication) {
        var reply = replies.create(questionId, request, authentication);
        return ResponseEntity.created(URI.create("/api/v1/replies/" + reply.id())).body(reply);
    }
    @PatchMapping("/replies/{id}")
    public ReplySummary update(@PathVariable UUID id, @Valid @RequestBody UpdateReplyRequest request,
                               Authentication authentication) {
        return replies.update(id, request, authentication);
    }
}

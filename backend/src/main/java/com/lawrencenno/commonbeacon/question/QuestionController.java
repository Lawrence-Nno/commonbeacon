package com.lawrencenno.commonbeacon.question;

import com.lawrencenno.commonbeacon.shared.PageResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class QuestionController {
    private final QuestionService questions;

    public QuestionController(QuestionService questions) { this.questions = questions; }

    @GetMapping("/boards/{boardId}/questions")
    public PageResponse<QuestionSummary> list(@PathVariable UUID boardId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "all") String status) {
        return questions.list(boardId, page, size, status);
    }

    @GetMapping("/questions/{id}")
    public QuestionDetail get(@PathVariable UUID id) { return questions.get(id); }

    @PostMapping("/boards/{boardId}/questions")
    public ResponseEntity<QuestionDetail> create(@PathVariable UUID boardId,
            @Valid @RequestBody CreateQuestionRequest request, Authentication authentication) {
        var question = questions.create(boardId, request, authentication);
        return ResponseEntity.created(URI.create("/api/v1/questions/" + question.id())).body(question);
    }

    @PatchMapping("/questions/{id}")
    public QuestionDetail update(@PathVariable UUID id,
            @Valid @RequestBody UpdateQuestionRequest request, Authentication authentication) {
        return questions.update(id, request, authentication);
    }

    @PutMapping("/questions/{id}/accepted-reply")
    public QuestionDetail accept(@PathVariable UUID id, @Valid @RequestBody AcceptReplyRequest request,
                                 Authentication authentication) {
        return questions.accept(id, request.replyId(), request.expectedVersion(), authentication);
    }

    @DeleteMapping("/questions/{id}/accepted-reply")
    public QuestionDetail clear(@PathVariable UUID id, @RequestParam long expectedVersion,
                                Authentication authentication) {
        return questions.accept(id, null, expectedVersion, authentication);
    }
}

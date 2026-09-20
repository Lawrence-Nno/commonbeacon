package com.lawrencenno.commonbeacon.knowledge;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.lawrencenno.commonbeacon.shared.PageResponse;

@RestController
@RequestMapping("/api/v1/admin/articles")
public class AdminArticleController {
    private final AdminArticleService articles;
    public AdminArticleController(AdminArticleService articles) { this.articles = articles; }
    @GetMapping
    public ResponseEntity<PageResponse<ArticleViews.AdminSummary>> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String status,
            @RequestParam Map<String, String> parameters) {
        ArticlePaging.parameters(parameters, true);
        return noStore(articles.list(status, page, size));
    }
    @GetMapping("/{id}")
    public ResponseEntity<ArticleViews.AdminDetail> get(@PathVariable UUID id) { return noStore(articles.get(id)); }
    @PostMapping
    public ResponseEntity<ArticleViews.AdminDetail> create(@Valid @RequestBody ArticleRequests.Create request, Authentication authentication) {
        var article = articles.create(request, authentication);
        return ResponseEntity.created(URI.create("/api/v1/admin/articles/" + article.id())).cacheControl(CacheControl.noStore()).body(article);
    }
    @PatchMapping("/{id}")
    public ResponseEntity<ArticleViews.AdminDetail> edit(@PathVariable UUID id, @Valid @RequestBody ArticleRequests.Edit request) {
        return noStore(articles.edit(id, request));
    }
    @PostMapping("/{id}/publish")
    public ResponseEntity<ArticleViews.AdminDetail> publish(@PathVariable UUID id, @Valid @RequestBody ArticleRequests.Version request) {
        return noStore(articles.publish(id, request));
    }
    @PostMapping("/{id}/archive")
    public ResponseEntity<ArticleViews.AdminDetail> archive(@PathVariable UUID id, @Valid @RequestBody ArticleRequests.Version request) {
        return noStore(articles.archive(id, request));
    }
    private static <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}

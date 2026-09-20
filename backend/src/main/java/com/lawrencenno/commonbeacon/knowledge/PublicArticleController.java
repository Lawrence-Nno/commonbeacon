package com.lawrencenno.commonbeacon.knowledge;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import com.lawrencenno.commonbeacon.shared.PageResponse;

@RestController
@RequestMapping("/api/v1/articles")
public class PublicArticleController {
    private final PublicArticleService articles;
    public PublicArticleController(PublicArticleService articles) { this.articles = articles; }
    @GetMapping
    public PageResponse<ArticleViews.Summary> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam Map<String, String> parameters) {
        ArticlePaging.parameters(parameters, false);
        return articles.list(page, size);
    }
    @GetMapping("/{slug}")
    public ArticleViews.Detail get(@PathVariable String slug) { return articles.get(slug); }
}

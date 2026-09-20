package com.lawrencenno.commonbeacon.knowledge;

import com.lawrencenno.commonbeacon.shared.ApiFailure;
import com.lawrencenno.commonbeacon.shared.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PublicArticleService {
    private final ArticleRepository articles;
    private final ArticleReadRepository reads;
    public PublicArticleService(ArticleRepository articles, ArticleReadRepository reads) { this.articles = articles; this.reads = reads; }
    public PageResponse<ArticleViews.Summary> list(int page, int size) {
        ArticlePaging.validate(page, size);
        long total = reads.count("PUBLISHED");
        return new PageResponse<>(reads.published(size, (long) page * size), page, size, total, ArticlePaging.totalPages(total, size));
    }
    public ArticleViews.Detail get(String slug) {
        return ArticleViews.Detail.from(articles.findBySlugAndStatus(slug, KnowledgeArticle.Status.PUBLISHED).orElseThrow(PublicArticleService::missing));
    }
    static ApiFailure missing() { return new ApiFailure(404, "ARTICLE_NOT_FOUND", "This article could not be found."); }
}

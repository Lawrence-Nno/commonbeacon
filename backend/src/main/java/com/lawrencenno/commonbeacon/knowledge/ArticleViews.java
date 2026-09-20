package com.lawrencenno.commonbeacon.knowledge;

import java.time.Instant;
import java.util.UUID;

/** Explicit public/admin projections keep private lifecycle metadata out of public responses. */
public final class ArticleViews {
    private ArticleViews() {}
    public record Author(UUID id, String displayName) {}
    public record Summary(UUID id, String slug, String title, Author author, Instant publishedAt, Instant updatedAt) {}
    public record Detail(UUID id, String slug, String title, Author author, Instant publishedAt, Instant updatedAt, String body) {
        static Detail from(KnowledgeArticle article) {
            return new Detail(article.getId(), article.getSlug(), article.getTitle(), ArticleViews.author(article),
                    article.getPublishedAt(), article.getUpdatedAt(), article.getBody());
        }
    }
    public record AdminSummary(UUID id, String slug, String title, KnowledgeArticle.Status status, Author author,
            Instant createdAt, Instant updatedAt, Instant publishedAt, long version) {}
    public record AdminDetail(UUID id, String slug, String title, KnowledgeArticle.Status status, Author author,
            Instant createdAt, Instant updatedAt, Instant publishedAt, long version, String body) {
        static AdminDetail from(KnowledgeArticle article) {
            return new AdminDetail(article.getId(), article.getSlug(), article.getTitle(), article.getStatus(), ArticleViews.author(article),
                    article.getCreatedAt(), article.getUpdatedAt(), article.getPublishedAt(), article.getVersion(), article.getBody());
        }
    }
    private static Author author(KnowledgeArticle article) { return new Author(article.getAuthor().getId(), article.getAuthor().getDisplayName()); }
}

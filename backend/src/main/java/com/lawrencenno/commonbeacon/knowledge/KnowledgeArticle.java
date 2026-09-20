package com.lawrencenno.commonbeacon.knowledge;

import com.lawrencenno.commonbeacon.identity.AppUser;
import com.lawrencenno.commonbeacon.shared.ApiFailure;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "knowledge_article")
public class KnowledgeArticle {
    public enum Status { DRAFT, PUBLISHED, ARCHIVED }
    @Id private UUID id;
    @Column(nullable = false, length = 100, updatable = false) private String slug;
    @Column(nullable = false, length = 200) private String title;
    @Column(nullable = false, columnDefinition = "text") private String body;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false, updatable = false) private AppUser author;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "published_at") private Instant publishedAt;
    @Version private long version;
    protected KnowledgeArticle() {}
    static KnowledgeArticle create(ArticleRequests.Create request, AppUser author) {
        var article = new KnowledgeArticle();
        article.id = UUID.randomUUID(); article.slug = request.slug(); article.title = request.title();
        article.body = request.body(); article.author = author; article.status = Status.DRAFT;
        article.createdAt = now(); article.updatedAt = article.createdAt;
        return article;
    }
    void edit(ArticleRequests.Edit request) {
        if (status == Status.ARCHIVED) throw stateConflict();
        title = request.title(); body = request.body(); updatedAt = now();
    }
    void publish() {
        if (status != Status.DRAFT) throw stateConflict();
        status = Status.PUBLISHED; publishedAt = now(); updatedAt = publishedAt;
    }
    void archive() {
        if (status == Status.ARCHIVED) throw stateConflict();
        status = Status.ARCHIVED; updatedAt = now();
    }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static ApiFailure stateConflict() {
        return new ApiFailure(409, "ARTICLE_STATE_CONFLICT", "This action is not available for the article's current status.");
    }
    public UUID getId() { return id; }
    public String getSlug() { return slug; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public Status getStatus() { return status; }
    public AppUser getAuthor() { return author; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public long getVersion() { return version; }
}

package com.lawrencenno.commonbeacon.knowledge;

import java.util.Set;
import java.util.UUID;
import jakarta.validation.Valid;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import com.lawrencenno.commonbeacon.identity.IdentityService;
import com.lawrencenno.commonbeacon.identity.UserRepository;
import com.lawrencenno.commonbeacon.shared.ApiFailure;
import com.lawrencenno.commonbeacon.shared.PageResponse;

@Service
@Validated
@PreAuthorize("hasRole('ADMINISTRATOR')")
public class AdminArticleService {
    private final ArticleRepository articles;
    private final ArticleReadRepository reads;
    private final IdentityService identity;
    private final UserRepository users;
    public AdminArticleService(ArticleRepository articles, ArticleReadRepository reads, IdentityService identity, UserRepository users) {
        this.articles = articles; this.reads = reads; this.identity = identity; this.users = users;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<ArticleViews.AdminSummary> list(String status, int page, int size) {
        if (status != null && !Set.of("DRAFT", "PUBLISHED", "ARCHIVED").contains(status))
            throw new ApiFailure(400, "INVALID_STATUS", "Use DRAFT, PUBLISHED, or ARCHIVED; omit status to list all articles.");
        ArticlePaging.validate(page, size);
        long total = reads.count(status);
        return new PageResponse<>(reads.admin(status, size, (long) page * size), page, size, total, ArticlePaging.totalPages(total, size));
    }
    @Transactional(readOnly = true)
    public ArticleViews.AdminDetail get(UUID id) {
        return ArticleViews.AdminDetail.from(articles.findById(id).orElseThrow(PublicArticleService::missing));
    }
    @Transactional
    public ArticleViews.AdminDetail create(@Valid ArticleRequests.Create request, Authentication authentication) {
        var author = users.getReferenceById(identity.current(authentication).id());
        try {
            return ArticleViews.AdminDetail.from(articles.saveAndFlush(KnowledgeArticle.create(request, author)));
        } catch (DataIntegrityViolationException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException constraint && "ux_article_slug".equals(constraint.getConstraintName()))
                    throw new ApiFailure(409, "ARTICLE_SLUG_CONFLICT", "An article already uses this slug. Choose another slug.");
            }
            throw failure;
        }
    }
    @Transactional
    public ArticleViews.AdminDetail edit(UUID id, @Valid ArticleRequests.Edit request) {
        var article = reviewed(id, request.expectedVersion());
        article.edit(request);
        return ArticleViews.AdminDetail.from(articles.saveAndFlush(article));
    }
    @Transactional
    public ArticleViews.AdminDetail publish(UUID id, @Valid ArticleRequests.Version request) {
        var article = reviewed(id, request.expectedVersion()); article.publish();
        return ArticleViews.AdminDetail.from(articles.saveAndFlush(article));
    }
    @Transactional
    public ArticleViews.AdminDetail archive(UUID id, @Valid ArticleRequests.Version request) {
        var article = reviewed(id, request.expectedVersion()); article.archive();
        return ArticleViews.AdminDetail.from(articles.saveAndFlush(article));
    }
    private KnowledgeArticle reviewed(UUID id, long expectedVersion) {
        // Article writes have one lock and never acquire community thread/report locks.
        var article = articles.findForUpdate(id).orElseThrow(PublicArticleService::missing);
        if (article.getVersion() != expectedVersion)
            throw new ApiFailure(409, "STALE_EDIT", "This article changed. Reload and review it before saving again.");
        return article;
    }
}

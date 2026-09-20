package com.lawrencenno.commonbeacon.knowledge;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ArticleRepository extends JpaRepository<KnowledgeArticle, UUID> {
    @EntityGraph(attributePaths = "author")
    Optional<KnowledgeArticle> findBySlugAndStatus(String slug, KnowledgeArticle.Status status);
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from KnowledgeArticle a where a.id = :id")
    Optional<KnowledgeArticle> findForUpdate(@Param("id") UUID id);
}

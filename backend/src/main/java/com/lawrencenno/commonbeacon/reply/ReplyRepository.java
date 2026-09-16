package com.lawrencenno.commonbeacon.reply;

import com.lawrencenno.commonbeacon.shared.ContentVisibility;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ReplyRepository extends JpaRepository<Reply, UUID> {
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reply r where r.id = :id and r.question.id = :questionId and r.visibility = 'VISIBLE'")
    Optional<Reply> findVisibleForUpdate(@Param("id") UUID id, @Param("questionId") UUID questionId);
    @EntityGraph(attributePaths = "author")
    Page<Reply> findByQuestionIdAndVisibilityAndQuestionVisibility(
            UUID questionId, ContentVisibility visibility, ContentVisibility questionVisibility, Pageable page);

    @EntityGraph(attributePaths = "author")
    Optional<Reply> findByIdAndVisibilityAndQuestionVisibility(
            UUID id, ContentVisibility visibility, ContentVisibility questionVisibility);

    @Query("select r.question.id from Reply r where r.id = :id and r.visibility = 'VISIBLE' and r.question.visibility = 'VISIBLE'")
    Optional<UUID> findVisibleQuestionId(@Param("id") UUID id);
}

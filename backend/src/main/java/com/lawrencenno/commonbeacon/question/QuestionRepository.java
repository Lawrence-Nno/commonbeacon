package com.lawrencenno.commonbeacon.question;

import com.lawrencenno.commonbeacon.shared.ContentVisibility;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionRepository extends JpaRepository<Question, UUID> {
    @EntityGraph(attributePaths = "author")
    Page<Question> findByBoardIdAndVisibility(UUID boardId, ContentVisibility visibility, Pageable page);

    @EntityGraph(attributePaths = {"author", "board"})
    Optional<Question> findDetailedByIdAndVisibility(UUID id, ContentVisibility visibility);

    Optional<Question> findByIdAndVisibility(UUID id, ContentVisibility visibility);
    @org.springframework.data.jpa.repository.Query("select q.board.id from Question q where q.id = :id and q.visibility = 'VISIBLE'")
    Optional<UUID> findVisibleBoardId(@org.springframework.data.repository.query.Param("id") UUID id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select q from Question q where q.id = :id and q.visibility = 'VISIBLE'")
    Optional<Question> findVisibleForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
}

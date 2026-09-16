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
}

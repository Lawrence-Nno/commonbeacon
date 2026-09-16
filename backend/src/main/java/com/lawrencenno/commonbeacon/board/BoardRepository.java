package com.lawrencenno.commonbeacon.board;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BoardRepository extends JpaRepository<Board, UUID> {
    List<Board> findAllByOrderByNameAscIdAsc();

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select board from Board board where board.id = :id")
    java.util.Optional<Board> findForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
}

package com.lawrencenno.commonbeacon.board;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "board")
public class Board {
    @Id private UUID id;
    @Column(nullable = false, length = 80) private String slug;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false, length = 2000) private String description;
    @Column(nullable = false) private boolean archived;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Version private long version;

    protected Board() {}

    static Board create(CreateBoardRequest request) {
        var board = new Board();
        board.id = UUID.randomUUID();
        board.slug = request.slug();
        board.name = request.name();
        board.description = request.description();
        board.createdAt = Instant.now();
        return board;
    }

    void update(UpdateBoardRequest request) {
        if (request.slug() != null) slug = request.slug();
        if (request.name() != null) name = request.name();
        if (request.description() != null) description = request.description();
        if (request.archived() != null) archived = request.archived();
    }

    public UUID getId() { return id; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isArchived() { return archived; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
}

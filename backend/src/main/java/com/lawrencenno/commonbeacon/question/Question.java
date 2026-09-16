package com.lawrencenno.commonbeacon.question;

import com.lawrencenno.commonbeacon.board.Board;
import com.lawrencenno.commonbeacon.identity.AppUser;
import com.lawrencenno.commonbeacon.shared.ContentVisibility;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "question")
public class Question {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "board_id", nullable = false) private Board board;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false) private AppUser author;
    @Column(nullable = false, length = 200) private String title;
    @Column(nullable = false, columnDefinition = "text") private String body;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) private ContentVisibility visibility;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accepted_reply_id") private com.lawrencenno.commonbeacon.reply.Reply acceptedReply;

    protected Question() {}

    static Question create(Board board, AppUser author, CreateQuestionRequest request) {
        var question = new Question();
        question.id = UUID.randomUUID();
        question.board = board;
        question.author = author;
        question.title = request.title();
        question.body = request.body();
        question.visibility = ContentVisibility.VISIBLE;
        question.createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        question.updatedAt = question.createdAt;
        return question;
    }

    void edit(UpdateQuestionRequest request) {
        title = request.title();
        body = request.body();
        updatedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    public UUID getId() { return id; }
    public com.lawrencenno.commonbeacon.reply.Reply getAcceptedReply() { return acceptedReply; }
    public boolean isSolved() { return acceptedReply != null && acceptedReply.isVisible(); }
    void accept(com.lawrencenno.commonbeacon.reply.Reply reply) {
        acceptedReply = reply;
        updatedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
    public Board getBoard() { return board; }
    public AppUser getAuthor() { return author; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}

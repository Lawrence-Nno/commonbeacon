package com.lawrencenno.commonbeacon.reply;

import com.lawrencenno.commonbeacon.identity.AppUser;
import com.lawrencenno.commonbeacon.question.Question;
import com.lawrencenno.commonbeacon.shared.ContentVisibility;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "reply")
public class Reply {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false) private Question question;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false) private AppUser author;
    @Column(nullable = false, columnDefinition = "text") private String body;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) private ContentVisibility visibility;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;

    protected Reply() {}

    static Reply create(Question question, AppUser author, String body) {
        var reply = new Reply();
        reply.id = UUID.randomUUID();
        reply.question = question;
        reply.author = author;
        reply.body = body;
        reply.visibility = ContentVisibility.VISIBLE;
        reply.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        reply.updatedAt = reply.createdAt;
        return reply;
    }

    void edit(String body) {
        this.body = body;
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public boolean isVisible() { return visibility == ContentVisibility.VISIBLE; }
    public UUID getId() { return id; }
    public Question getQuestion() { return question; }
    public AppUser getAuthor() { return author; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}

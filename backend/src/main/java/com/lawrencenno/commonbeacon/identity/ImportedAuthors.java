package com.lawrencenno.commonbeacon.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal activation primitive. Caller supplies a validated, reviewed archive inside
 * the fenced activation transaction. No endpoint, account matching, or claim API. */
@Service
public class ImportedAuthors {
    private final JdbcTemplate jdbc;
    public ImportedAuthors(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID create(UUID sourceInstance, UUID sourceUser, String displayName, Instant createdAt,
                       boolean includeContacts, String sourceEmail) {
        Objects.requireNonNull(sourceInstance);
        Objects.requireNonNull(sourceUser);
        Objects.requireNonNull(createdAt);
        if (displayName == null || displayName.isBlank() || displayName.length() > 80
                || !displayName.equals(displayName.trim())
                || (includeContacts && sourceEmail != null && sourceEmail.length() > 254)) {
            throw new IllegalArgumentException("Invalid imported author");
        }
        UUID local = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO app_user(id,display_name,role,account_state,created_at)
            VALUES (?,?,'MEMBER','IMPORTED_INACTIVE',?)
            """, local, displayName, Timestamp.from(createdAt));
        jdbc.update("""
            INSERT INTO imported_author(source_instance_id,source_user_id,local_user_id,source_email)
            VALUES (?,?,?,?)
            """, sourceInstance, sourceUser, local, includeContacts ? sourceEmail : null);
        return local;
    }
}

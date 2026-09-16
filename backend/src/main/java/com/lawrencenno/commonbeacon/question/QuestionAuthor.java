package com.lawrencenno.commonbeacon.question;

import com.lawrencenno.commonbeacon.identity.AppUser;
import java.util.UUID;

public record QuestionAuthor(UUID id, String displayName) {
    static QuestionAuthor from(AppUser user) {
        return new QuestionAuthor(user.getId(), user.getDisplayName());
    }
}

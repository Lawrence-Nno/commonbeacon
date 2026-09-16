package com.lawrencenno.commonbeacon.question;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

public record AcceptReplyRequest(@NotNull UUID replyId, @NotNull @PositiveOrZero Long expectedVersion) {}

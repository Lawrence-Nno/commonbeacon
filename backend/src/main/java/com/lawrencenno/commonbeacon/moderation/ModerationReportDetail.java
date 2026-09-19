package com.lawrencenno.commonbeacon.moderation;

import java.util.List;

public record ModerationReportDetail(ModerationReport report, ModerationContext context,
                                     List<String> availableDecisions) {}

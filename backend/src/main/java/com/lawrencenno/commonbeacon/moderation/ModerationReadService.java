package com.lawrencenno.commonbeacon.moderation;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.lawrencenno.commonbeacon.shared.ApiFailure;
import com.lawrencenno.commonbeacon.shared.ContentVisibility;
import com.lawrencenno.commonbeacon.shared.PageResponse;

@Service
@PreAuthorize("hasAnyRole('MODERATOR', 'ADMINISTRATOR')")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ModerationReadService {
    private final ModerationReadRepository reports;
    public ModerationReadService(ModerationReadRepository reports) { this.reports = reports; }

    public PageResponse<ModerationReport> list(String status, int page, int size) {
        if (status == null || !Set.of("OPEN", "RESOLVED").contains(status))
            throw new ApiFailure(400, "INVALID_STATUS", "Use OPEN or RESOLVED.");
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new ApiFailure(400, "INVALID_PAGE", "Use a nonnegative page and a size from 1 to 100; the offset must fit a 32-bit integer.");
        long total = reports.count(status);
        return new PageResponse<>(reports.list(status, size, (long) page * size), page, size, total,
                (int) Math.min(Integer.MAX_VALUE, (total + size - 1) / size));
    }
    public ModerationReportDetail get(UUID id) {
        var report = reports.find(id).orElseThrow(() -> new ApiFailure(404, "REPORT_NOT_FOUND", "This report could not be found."));
        var context = reports.context(report).orElseThrow(() -> new ApiFailure(404, "CONTENT_NOT_FOUND", "This content could not be found."));
        var visibility = context.reply() == null ? context.question().visibility() : context.reply().visibility();
        var decisions = "OPEN".equals(report.status())
                ? List.of("DISMISS", visibility == ContentVisibility.HIDDEN ? "ACKNOWLEDGE_HIDDEN" : "HIDE") : List.<String>of();
        return new ModerationReportDetail(report, context, decisions);
    }
}

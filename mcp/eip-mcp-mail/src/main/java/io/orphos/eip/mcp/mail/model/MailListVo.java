package io.orphos.eip.mcp.mail.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The {@code /api/mail-insight/tasks} and {@code /api/mail-insight/notifications} response envelope:
 * {@code {"result": [ ...items ], "count": n}}. One type for both because they are the same upstream
 * shape returning the same {@link MailItemVo} — {@code kind} distinguishes a task from a
 * notification.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MailListVo(List<MailItemVo> result, int count) {
}

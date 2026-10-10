package com.orphos.mobile.data.model;

import java.util.List;

/** Wrapper returned by GET /api/mail-insight/meetings and /tasks. */
public class MeetingsResponse {
    public List<MailInsightDto> result;
    public Integer count;
    public String from;
    public String to;
}

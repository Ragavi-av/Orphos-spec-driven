package com.orphos.mobile.data.model;

/**
 * mail-insight-service item (tasks / meetings / notifications). Field names match
 * MailInsightDto (record component names).
 */
public class MailInsightDto {
    public String id;
    public String mailboxId;
    public String kind;            // TASK | NOTIFICATION
    public String sourceType;      // EMAIL | EVENT | MEETING
    public String title;
    public String summary;
    public String senderDisplay;
    public String priority;        // CRITICAL | HIGH | MEDIUM | LOW
    public String criticality;     // ALERT | WARNING | INFORMATION
    public Integer score;
    public String rationale;
    public String dueDate;
    public String receivedAt;
    public String status;          // OPEN | IN_PROGRESS | DONE | DISMISSED
    public String reminderAt;
    public String inferenceSource; // RULES | LLM | DATA
    public String source;          // EMAIL | MS_TEAMS | SLACK | DATA

    public MailInsightDto() { }
}

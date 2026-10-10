package io.orphos.eip.docingest.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The mobile handwriting review step shows whatever this returns, so a reply it cannot
 * trust must come back empty (the user then writes the summary) rather than as junk.
 */
class CaptureSummarizerTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String NOTE = "Meeting with the client tomorrow at 10 AM";

    private NlQueryClient nlQuery;
    private CaptureSummarizer summarizer;

    @BeforeEach
    void setUp() {
        nlQuery = mock(NlQueryClient.class);
        when(nlQuery.promptText(anyString(), anyString())).thenReturn(Optional.empty());
        summarizer = new CaptureSummarizer(nlQuery);
    }

    @Test
    void parsesAFencedJsonReply() {
        when(nlQuery.complete(eq(TENANT), contains(NOTE), eq(DocumentAiEnricher.USAGE_TYPE)))
                .thenReturn(Optional.of("""
                        ```json
                        {"title":"Client meeting","summary":"A client meeting is planned.",
                         "keyPoints":["Client meeting","Tomorrow at 10 AM",""]}
                        ```"""));

        CaptureSummarizer.Summary s = summarizer.summarize(TENANT, NOTE).orElseThrow();

        assertEquals("Client meeting", s.title());
        assertEquals("A client meeting is planned.", s.summary());
        assertEquals(List.of("Client meeting", "Tomorrow at 10 AM"), s.keyPoints());
        assertEquals(List.of(), s.actions());
        assertEquals(List.of(), s.decisions());
    }

    @Test
    void actionsAndDecisionsAreParsedAndNeverDuplicated() {
        when(nlQuery.complete(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of("""
                        {"title":"Vendor review","summary":"Vendor shortlist.",
                         "keyPoints":["Two vendors shortlisted"],
                         "actions":["Send the RFP to both vendors","Pick a vendor",""],
                         "decisions":["Pick a vendor"]}"""));

        CaptureSummarizer.Summary s = summarizer.summarize(TENANT, NOTE).orElseThrow();

        assertEquals(List.of("Send the RFP to both vendors"), s.actions());
        assertEquals(List.of("Pick a vendor"), s.decisions());
    }

    @Test
    void proseReplyIsDiscarded() {
        when(nlQuery.complete(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of("Sure! This note is about a meeting."));

        assertTrue(summarizer.summarize(TENANT, NOTE).isEmpty());
    }

    @Test
    void replyWithNeitherSummaryNorPointsIsDiscarded() {
        when(nlQuery.complete(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of("{\"title\":\"Note\",\"summary\":\"\",\"keyPoints\":[]}"));

        assertTrue(summarizer.summarize(TENANT, NOTE).isEmpty());
    }

    @Test
    void blankTextNeverReachesTheModel() {
        assertTrue(summarizer.summarize(TENANT, "   ").isEmpty());
        verify(nlQuery, never()).complete(anyString(), anyString(), anyString());
    }

    @Test
    void anAdminEditedTemplateIsUsed() {
        when(nlQuery.promptText(TENANT, CaptureSummarizer.PROMPT_KEY))
                .thenReturn(Optional.of("Summarise: {{TEXT}}"));
        when(nlQuery.complete(eq(TENANT), eq("Summarise: " + NOTE), anyString()))
                .thenReturn(Optional.of("{\"summary\":\"Client meeting tomorrow.\"}"));

        assertEquals("Client meeting tomorrow.", summarizer.summarize(TENANT, NOTE).orElseThrow().summary());
    }
}

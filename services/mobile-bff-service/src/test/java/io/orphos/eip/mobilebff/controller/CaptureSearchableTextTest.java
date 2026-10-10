package io.orphos.eip.mobilebff.controller;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What doc-ingest indexes for a reviewed capture, which is what Web Document Search matches. */
class CaptureSearchableTextTest {

    @Test
    void voiceNoteIndexesTheOriginalTranscriptWhenTheUserEditedIt() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("title", "Supplier call");
        c.put("content", "Renew the Kawasaki contract in March.");
        c.put("transcript", "Renew the kawa saki contract in march");
        c.put("summary", "Contract renewal is due in March.");
        c.put("keyPoints", List.of("Renewal in March"));

        String text = MobileController.searchableText(c);

        assertThat(text)
                .contains("Renew the Kawasaki contract in March.")
                .contains("Original transcription:\nRenew the kawa saki contract in march")
                .contains("Summary:\nContract renewal is due in March.")
                .contains("- Renewal in March");
        // The reviewed text leads; the summary never stands in for it.
        assertThat(text.indexOf("Renew the Kawasaki")).isLessThan(text.indexOf("Summary:"));
    }

    @Test
    void uploadedImageIndexesCorrectedAndOriginalOcrTextBeforeTheSummary() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("title", "Whiteboard Q3 plan");
        c.put("content", "Q3 roadmap: launch billing portal by August 14.");
        c.put("transcript", "Q3 roadrnap: launch bil1ing portal by August 14.");
        c.put("summary", "The Q3 plan targets a billing portal launch in August.");

        String text = MobileController.searchableText(c);

        assertThat(text)
                .contains("Q3 roadmap: launch billing portal by August 14.")
                .contains("Original transcription:\nQ3 roadrnap: launch bil1ing portal by August 14.")
                .contains("Summary:\nThe Q3 plan targets a billing portal launch in August.");
        assertThat(text.indexOf("Q3 roadmap")).isLessThan(text.indexOf("Summary:"));
    }

    @Test
    void imageWithNoReadableTextStillIndexesItsNameAndSummary() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("title", "Office floor plan");
        c.put("content", "");
        c.put("summary", "Sketch of the third floor seating layout.");

        assertThat(MobileController.searchableText(c))
                .startsWith("Office floor plan")
                .contains("Summary:\nSketch of the third floor seating layout.");
    }

    @Test
    void aiActionsAndDecisionsAreIndexed() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("title", "Vendor review");
        c.put("content", "Shortlist two vendors.");
        c.put("actions", List.of("Send the RFP"));
        c.put("decisions", List.of("Pick a vendor"));
        c.put("keyPoints", List.of());

        assertThat(MobileController.searchableText(c))
                .contains("Actions:\n- Send the RFP")
                .contains("Decisions needed:\n- Pick a vendor")
                .doesNotContain("Key points:");
    }

    @Test
    void unchangedTranscriptIsNotIndexedTwice() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("title", "Standup");
        c.put("content", "Ship the release on Friday.");
        c.put("transcript", "Ship the release on Friday.");

        assertThat(MobileController.searchableText(c)).doesNotContain("Original transcription");
    }
}

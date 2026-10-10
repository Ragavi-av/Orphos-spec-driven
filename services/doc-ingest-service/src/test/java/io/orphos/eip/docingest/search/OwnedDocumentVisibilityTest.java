package io.orphos.eip.docingest.search;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A mobile capture is stamped owner_sub and must surface in search only for its owner. */
class OwnedDocumentVisibilityTest {

    private static TextSegment chunk(String ownerSub) {
        Metadata md = new Metadata();
        md.put("tenant_id", "t1");
        if (ownerSub != null) md.put("owner_sub", ownerSub);
        return TextSegment.from("body", md);
    }

    @Test
    void sharedCorpusIsVisibleToEveryone() {
        assertTrue(DocSearchController.visibleTo(chunk(null), "alice"));
        assertTrue(DocSearchController.visibleTo(chunk(""), "alice"));
        assertTrue(DocSearchController.visibleTo(chunk(""), null));
    }

    @Test
    void ownedCaptureIsVisibleOnlyToItsOwner() {
        assertTrue(DocSearchController.visibleTo(chunk("alice"), "alice"));
        assertFalse(DocSearchController.visibleTo(chunk("alice"), "bob"));
        assertFalse(DocSearchController.visibleTo(chunk("alice"), null));
    }
}

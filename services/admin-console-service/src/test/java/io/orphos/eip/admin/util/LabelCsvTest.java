package io.orphos.eip.admin.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LabelCsvTest {

    @Test
    void parsesSimpleRows() {
        var r = LabelCsv.parse("key,language,value\ncopilot.title,en,Ask Orphos\napp.nav.aiHistory,ja,AI履歴\n");
        assertTrue(r.errors().isEmpty());
        assertEquals(2, r.rows().size());
        assertEquals("copilot.title", r.rows().get(0).key());
        assertEquals("en", r.rows().get(0).language());
        assertEquals("Ask Orphos", r.rows().get(0).value());
        assertEquals("AI履歴", r.rows().get(1).value());
    }

    @Test
    void ignoresExtraDefaultColumnFromExportedTemplate() {
        var r = LabelCsv.parse("key,language,default,value\ncopilot.title,en,AI Co-pilot,Ask Orphos\n");
        assertTrue(r.errors().isEmpty());
        assertEquals(1, r.rows().size());
        assertEquals("Ask Orphos", r.rows().get(0).value());
    }

    @Test
    void handlesQuotedFieldsWithCommasQuotesAndNewlines() {
        var r = LabelCsv.parse("key,language,value\r\n"
                + "copilot.welcome,en,\"Good morning, I can brief you on \"\"everything\"\".\r\nSecond line\"\r\n");
        assertTrue(r.errors().isEmpty());
        assertEquals(1, r.rows().size());
        assertEquals("Good morning, I can brief you on \"everything\".\nSecond line",
                r.rows().get(0).value().replace("\r\n", "\n"));
    }

    @Test
    void stripsUtf8Bom() {
        var r = LabelCsv.parse("﻿key,language,value\ncopilot.title,en,Ask Orphos\n");
        assertTrue(r.errors().isEmpty());
        assertEquals(1, r.rows().size());
    }

    @Test
    void reportsMissingHeaderColumns() {
        var r = LabelCsv.parse("foo,bar\nx,y\n");
        assertFalse(r.errors().isEmpty());
        assertTrue(r.rows().isEmpty());
    }

    @Test
    void normalisesLanguageToLowerCase() {
        var r = LabelCsv.parse("key,language,value\ncopilot.title,EN,Ask Orphos\n");
        assertEquals("en", r.rows().get(0).language());
    }

    @Test
    void writeAndParseRoundTrip() {
        var rows = List.of(
                new LabelCsv.Row(0, "copilot.title", "en", "Ask, \"Orphos\""),
                new LabelCsv.Row(0, "app.nav.aiHistory", "ja", "AI履歴"));
        var parsed = LabelCsv.parse(LabelCsv.write(rows));
        assertTrue(parsed.errors().isEmpty());
        assertEquals(2, parsed.rows().size());
        assertEquals("Ask, \"Orphos\"", parsed.rows().get(0).value());
    }
}

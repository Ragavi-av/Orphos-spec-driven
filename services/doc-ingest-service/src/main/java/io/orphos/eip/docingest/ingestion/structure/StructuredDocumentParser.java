package io.orphos.eip.docingest.ingestion.structure;

import io.orphos.eip.docingest.ingestion.IngestionException;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToXMLContentHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Structure-preserving document parser.
 *
 * Uses Tika's ToXMLContentHandler to keep the XHTML representation, then
 * converts it into an ordered list of {@link Block}s (headings, paragraphs,
 * list items, tables, page markers). For PDFs, PdfTableExtractor recovers
 * table structure from text-position analysis and merges the blocks in.
 *
 * Works for every format Tika handles: DOCX, PPTX, XLSX, HTML give true
 * tables and headings; PDF gives pages and paragraphs plus extracted tables.
 */
@Component
public class StructuredDocumentParser {

    private static final Logger log = LogManager.getLogger(StructuredDocumentParser.class);

    private final PdfTableExtractor pdfTableExtractor = new PdfTableExtractor();

    public record ParsedDocument(List<Block> blocks, String plainText, int pageCount,
                                 String embeddedTitle) {}

    public ParsedDocument parse(Path filePath) {
        String xhtml;
        String embeddedTitle;
        try (InputStream stream = Files.newInputStream(filePath)) {
            ToXMLContentHandler handler = new ToXMLContentHandler();
            org.apache.tika.metadata.Metadata tikaMetadata = new org.apache.tika.metadata.Metadata();
            new AutoDetectParser().parse(stream, handler, tikaMetadata, new ParseContext());
            xhtml = handler.toString();
            embeddedTitle = tikaMetadata.get(TikaCoreProperties.TITLE);
        } catch (Exception e) {
            throw new IngestionException("Structured parse failed for: " + filePath, e);
        }

        ParsedDocument doc = parseXhtml(xhtml);

        if (isPdf(filePath)) {
            List<PdfTableExtractor.ExtractedTable> pdfTables = pdfTableExtractor.extract(filePath);
            if (!pdfTables.isEmpty()) {
                List<Block> merged = mergePdfTables(doc.blocks(), pdfTables);
                StringBuilder plain = new StringBuilder();
                for (Block b : merged) plain.append(b.text()).append("\n\n");
                return new ParsedDocument(merged, plain.toString().trim(), doc.pageCount(), embeddedTitle);
            }
        }

        return new ParsedDocument(doc.blocks(), doc.plainText(), doc.pageCount(), embeddedTitle);
    }

    private List<Block> mergePdfTables(List<Block> existing,
                                        List<PdfTableExtractor.ExtractedTable> pdfTables) {
        Map<Integer, List<PdfTableExtractor.ExtractedTable>> byPage = new LinkedHashMap<>();
        for (PdfTableExtractor.ExtractedTable t : pdfTables) {
            byPage.computeIfAbsent(t.page(), k -> new ArrayList<>()).add(t);
        }

        List<Block> merged = new ArrayList<>(existing);
        List<Integer> pages = new ArrayList<>(byPage.keySet());
        pages.sort(Collections.reverseOrder());

        for (int page : pages) {
            int insertAt = merged.size();
            for (int i = merged.size() - 1; i >= 0; i--) {
                if (merged.get(i).pageNumber() == page) {
                    insertAt = i + 1;
                    break;
                }
            }
            List<Block> tableBlocks = new ArrayList<>();
            for (PdfTableExtractor.ExtractedTable t : byPage.get(page)) {
                boolean firstRowIsHeader = looksLikeHeader(t.rows());
                String markdown = TableSerializer.toMarkdown(t.rows(), firstRowIsHeader);
                tableBlocks.add(Block.table(markdown, t.rows(), firstRowIsHeader, page));
            }
            merged.addAll(insertAt, tableBlocks);
        }
        return merged;
    }

    private static boolean looksLikeHeader(List<List<String>> rows) {
        if (rows.size() < 2) return false;
        return rows.get(0).stream().anyMatch(cell ->
            !cell.isBlank() && Character.isUpperCase(cell.charAt(0)));
    }

    private static boolean isPdf(Path filePath) {
        return filePath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    public ParsedDocument parseXhtml(String xhtml) {
        Document dom = Jsoup.parse(xhtml);
        Walker walker = new Walker();
        walker.walk(dom.body());

        StringBuilder plain = new StringBuilder();
        for (Block b : walker.blocks) plain.append(b.text()).append("\n\n");
        return new ParsedDocument(walker.blocks, plain.toString().trim(), walker.maxPage, null);
    }

    private static final class Walker {
        final List<Block> blocks = new ArrayList<>();
        int currentPage = 0;
        int maxPage = 0;

        void walk(Element element) {
            for (Element child : element.children()) {
                String tag = child.tagName();
                switch (tag) {
                    case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        String text = clean(child.text());
                        if (!text.isEmpty()) {
                            blocks.add(Block.heading(text, tag.charAt(1) - '0', currentPage));
                        }
                    }
                    case "p" -> {
                        String text = clean(child.text());
                        if (!text.isEmpty()) blocks.add(Block.paragraph(text, currentPage));
                    }
                    case "li" -> {
                        String text = clean(child.ownText());
                        if (!text.isEmpty()) blocks.add(Block.listItem(text, currentPage));
                        walk(child);
                    }
                    case "table" -> {
                        Block table = extractTable(child, currentPage);
                        if (table != null) blocks.add(table);
                    }
                    case "div" -> {
                        if (child.hasClass("page")) currentPage = ++maxPage;
                        String ownText = clean(child.ownText());
                        if (!ownText.isEmpty()) blocks.add(Block.paragraph(ownText, currentPage));
                        walk(child);
                    }
                    default -> walk(child);
                }
            }
        }

        private Block extractTable(Element table, int page) {
            List<List<String>> rows = new ArrayList<>();
            boolean firstRowIsHeader = false;
            for (Element tr : table.select("tr")) {
                List<String> cells = new ArrayList<>();
                boolean headerRow = false;
                for (Element cell : tr.children()) {
                    if (cell.tagName().equals("td") || cell.tagName().equals("th")) {
                        cells.add(clean(cell.text()));
                        if (cell.tagName().equals("th")) headerRow = true;
                    }
                }
                if (!cells.isEmpty()) {
                    if (rows.isEmpty()) firstRowIsHeader = headerRow;
                    rows.add(cells);
                }
            }
            boolean hasContent = rows.stream().flatMap(List::stream).anyMatch(c -> !c.isBlank());
            if (rows.isEmpty() || !hasContent) return null;
            return Block.table(TableSerializer.toMarkdown(rows, firstRowIsHeader),
                rows, firstRowIsHeader, page);
        }

        private static String clean(String text) {
            return text == null ? "" : text.replace(' ', ' ').replaceAll("\\s+", " ").trim();
        }
    }
}

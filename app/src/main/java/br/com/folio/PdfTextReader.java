package br.com.folio;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Extracts the embedded text layer of a PDF without sending the document to a server. */
public final class PdfTextReader {
    public enum BlockType {
        CHAPTER,
        PARAGRAPH,
        SCENE_BREAK
    }

    /** A semantic piece of a book, reconstructed only from the PDF text layer. */
    public static final class Block {
        public final BlockType type;
        public final String text;

        Block(BlockType type, String text) {
            this.type = type;
            this.text = text;
        }
    }

    public interface ProgressListener {
        void onPageProcessed(int completedPages, int totalPages);
    }

    public interface CancellationSignal {
        boolean isCancelled();
    }

    public static final class Result {
        public final String text;
        public final List<Block> blocks;
        public final int documentPageCount;
        public final int processedPageCount;
        public final boolean truncated;

        Result(String text, List<Block> blocks, int documentPageCount,
               int processedPageCount, boolean truncated) {
            this.text = text;
            this.blocks = Collections.unmodifiableList(new ArrayList<>(blocks));
            this.documentPageCount = documentPageCount;
            this.processedPageCount = processedPageCount;
            this.truncated = truncated;
        }

        /** Paragraph-sized segments keep the offline narrator from stopping between PDF pages. */
        public List<String> getSpeechSegments() {
            List<String> segments = new ArrayList<>();
            StringBuilder pendingParagraphs = new StringBuilder();
            for (Block block : blocks) {
                if (block.type == BlockType.SCENE_BREAK || block.text.isEmpty()) continue;
                if (block.type == BlockType.CHAPTER) {
                    flushSpeechBuffer(segments, pendingParagraphs);
                    appendSpeechSegments(segments, block.text);
                    continue;
                }
                if (pendingParagraphs.length() > 0
                        && pendingParagraphs.length() + 1 + block.text.length()
                        > MAX_SPEECH_SEGMENT_CHARACTERS) {
                    flushSpeechBuffer(segments, pendingParagraphs);
                }
                if (block.text.length() > MAX_SPEECH_SEGMENT_CHARACTERS) {
                    appendSpeechSegments(segments, block.text);
                } else {
                    if (pendingParagraphs.length() > 0) pendingParagraphs.append(' ');
                    pendingParagraphs.append(block.text);
                }
            }
            flushSpeechBuffer(segments, pendingParagraphs);
            return segments;
        }
    }

    public static final class CancelledException extends IOException {
        CancelledException() {
            super("Leitura do PDF cancelada");
        }
    }

    public static final class NoTextException extends IOException {
        NoTextException() {
            super("O PDF não possui texto selecionável");
        }
    }

    public static final class PasswordProtectedException extends IOException {
        PasswordProtectedException() {
            super("O PDF é protegido por senha");
        }
    }

    private static final int MAX_PAGES = 120;
    private static final int MAX_TEXT_CHARACTERS = 320_000;
    private static final int MAX_SPEECH_SEGMENT_CHARACTERS = 1800;
    private static final int MAX_MARGIN_LINE_CHARACTERS = 90;
    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "(?i)^(?:p[áa]gina\\s*)?(?:\\d{1,4}|[ivxlcdm]{1,10})\\.?$");
    private static final Pattern EXPLICIT_PAGE_NUMBER = Pattern.compile(
            "(?i)^p[áa]gina\\s*(?:\\d{1,4}|[ivxlcdm]{1,10})\\.?$");
    private static final Pattern SCENE_BREAK = Pattern.compile(
            "^(?:[★*#~_=•·—–\\-]\\s*){3,}$");
    private static final Pattern SECTION_HEADING = Pattern.compile(
            "(?i)^(?:cap[ií]tulo|chapter|parte|part|livro|book|pr[oó]logo|ep[ií]logo|pref[aá]cio|introdu[cç][aã]o)\\b.*$");
    private static final Pattern STANDALONE_SECTION_NUMBER = Pattern.compile(
            "^(?:\\d{1,3}|[IVXLCDM]{1,10})(?:[.:-])?$");

    private PdfTextReader() {
    }

    public static Result read(Context context, Uri uri, ProgressListener progress,
                              CancellationSignal cancellation) throws IOException {
        if (context == null || uri == null) throw new IOException("PDF indisponível");
        PDFBoxResourceLoader.init(context.getApplicationContext());
        ContentResolver resolver = context.getContentResolver();
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("Não foi possível abrir este PDF");
            try (PDDocument document = PDDocument.load(input)) {
                if (document.isEncrypted()) throw new PasswordProtectedException();
                int documentPages = document.getNumberOfPages();
                if (documentPages <= 0) throw new IOException("O PDF não possui páginas");
                int pageLimit = Math.min(documentPages, MAX_PAGES);
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                stripper.setSuppressDuplicateOverlappingText(true);
                List<String> pages = new ArrayList<>();
                int extractedCharacters = 0;
                int processedPages = 0;
                boolean truncated = documentPages > pageLimit;
                for (int page = 1; page <= pageLimit; page++) {
                    checkCancelled(cancellation);
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    String pageText = normalizePageText(stripper.getText(document));
                    if (!pageText.isEmpty()) {
                        int remaining = MAX_TEXT_CHARACTERS - extractedCharacters;
                        if (remaining <= 0) {
                            truncated = true;
                            break;
                        }
                        if (pageText.length() > remaining) {
                            pages.add(pageText.substring(0, remaining));
                            truncated = true;
                            processedPages = page;
                            notifyProgress(progress, processedPages, pageLimit);
                            break;
                        }
                        pages.add(pageText);
                        extractedCharacters += pageText.length();
                    }
                    processedPages = page;
                    notifyProgress(progress, processedPages, pageLimit);
                }
                checkCancelled(cancellation);
                List<Block> formattedBlocks = formatPages(pages);
                if (blockTextLength(formattedBlocks) > MAX_TEXT_CHARACTERS) truncated = true;
                List<Block> blocks = limitBlocks(formattedBlocks, MAX_TEXT_CHARACTERS);
                String text = flattenBlocks(blocks, MAX_TEXT_CHARACTERS);
                if (text.isEmpty()) throw new NoTextException();
                return new Result(text, blocks, documentPages, processedPages, truncated);
            }
        }
    }

    private static void checkCancelled(CancellationSignal cancellation) throws CancelledException {
        if (cancellation != null && cancellation.isCancelled()) throw new CancelledException();
    }

    private static void notifyProgress(ProgressListener progress, int completed, int total) {
        if (progress != null) progress.onPageProcessed(completed, total);
    }

    private static List<Block> formatPages(List<String> pages) {
        Set<String> recurringMargins = findRecurringMargins(pages);
        BookAssembler assembler = new BookAssembler();
        for (String page : pages) {
            if (page == null || page.isEmpty()) continue;
            String[] lines = page.split("\\n", -1);
            Set<Integer> marginLineIndexes = findMarginLineIndexes(lines, recurringMargins);
            Set<Integer> footerPageNumberIndexes = findFooterPageNumberIndexes(lines);
            for (int index = 0; index < lines.length; index++) {
                assembler.addLine(lines[index], marginLineIndexes.contains(index),
                        footerPageNumberIndexes.contains(index));
            }
        }
        return assembler.finish();
    }

    private static Set<Integer> findMarginLineIndexes(String[] lines, Set<String> recurringMargins) {
        List<Integer> nonEmptyIndexes = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            if (!cleanLine(lines[index]).isEmpty()) nonEmptyIndexes.add(index);
        }
        Set<Integer> marginIndexes = new HashSet<>();
        for (int position = 0; position < nonEmptyIndexes.size(); position++) {
            boolean isAtPageEdge = position < 2 || position >= nonEmptyIndexes.size() - 2;
            if (!isAtPageEdge) continue;
            int lineIndex = nonEmptyIndexes.get(position);
            if (isRecurringMargin(lines[lineIndex], recurringMargins)) marginIndexes.add(lineIndex);
        }
        return marginIndexes;
    }

    private static Set<Integer> findFooterPageNumberIndexes(String[] lines) {
        List<Integer> nonEmptyIndexes = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            if (!cleanLine(lines[index]).isEmpty()) nonEmptyIndexes.add(index);
        }
        Set<Integer> pageNumbers = new HashSet<>();
        int firstFooterPosition = Math.max(0, nonEmptyIndexes.size() - 2);
        for (int position = firstFooterPosition; position < nonEmptyIndexes.size(); position++) {
            int lineIndex = nonEmptyIndexes.get(position);
            if (PAGE_NUMBER.matcher(cleanLine(lines[lineIndex])).matches()) {
                pageNumbers.add(lineIndex);
            }
        }
        return pageNumbers;
    }

    /** Finds only repeated lines at page edges, where running headers and footers live. */
    private static Set<String> findRecurringMargins(List<String> pages) {
        Map<String, Integer> occurrences = new HashMap<>();
        int nonEmptyPages = 0;
        for (String page : pages) {
            if (page == null || page.isEmpty()) continue;
            List<String> lines = nonEmptyLines(page);
            if (lines.isEmpty()) continue;
            nonEmptyPages++;
            Set<String> pageCandidates = new HashSet<>();
            addMarginCandidate(pageCandidates, lines, 0);
            addMarginCandidate(pageCandidates, lines, 1);
            addMarginCandidate(pageCandidates, lines, lines.size() - 2);
            addMarginCandidate(pageCandidates, lines, lines.size() - 1);
            for (String candidate : pageCandidates) {
                occurrences.put(candidate, occurrences.containsKey(candidate)
                        ? occurrences.get(candidate) + 1 : 1);
            }
        }
        int threshold = nonEmptyPages <= 2 ? 2 : Math.max(3,
                (int) Math.ceil(nonEmptyPages * 0.45d));
        Set<String> margins = new HashSet<>();
        for (Map.Entry<String, Integer> entry : occurrences.entrySet()) {
            if (entry.getValue() >= threshold) margins.add(entry.getKey());
        }
        return margins;
    }

    private static void addMarginCandidate(Set<String> candidates, List<String> lines, int index) {
        if (index < 0 || index >= lines.size()) return;
        String key = marginKey(lines.get(index));
        if (!key.isEmpty()) candidates.add(key);
    }

    private static List<String> nonEmptyLines(String page) {
        List<String> lines = new ArrayList<>();
        for (String line : page.split("\\n", -1)) {
            String cleaned = cleanLine(line);
            if (!cleaned.isEmpty()) lines.add(cleaned);
        }
        return lines;
    }

    private static String marginKey(String line) {
        String cleaned = cleanLine(line);
        if (cleaned.length() < 2 || cleaned.length() > MAX_MARGIN_LINE_CHARACTERS) return "";
        if (SECTION_HEADING.matcher(cleaned).matches()
                || STANDALONE_SECTION_NUMBER.matcher(cleaned).matches()) return "";
        return cleaned.toLowerCase(Locale.ROOT).replaceAll("\\d+", "#");
    }

    private static boolean isRecurringMargin(String line, Set<String> recurringMargins) {
        String key = marginKey(line);
        return !key.isEmpty() && recurringMargins.contains(key);
    }

    private static boolean isHeading(String line) {
        if (line.length() < 2 || line.length() > 100 || isSceneBreak(line)) return false;
        if (SECTION_HEADING.matcher(line).matches()
                || STANDALONE_SECTION_NUMBER.matcher(line).matches()) return true;
        if (line.endsWith(".") || line.endsWith("!") || line.endsWith("?")) return false;
        int letters = 0;
        int lowercase = 0;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (Character.isLetter(character)) {
                letters++;
                if (Character.isLowerCase(character)) lowercase++;
            }
        }
        return letters >= 4 && lowercase == 0 && line.split("\\s+").length <= 10;
    }

    private static boolean isSceneBreak(String line) {
        return SCENE_BREAK.matcher(line).matches();
    }

    private static boolean isDialogueOrListItem(String line) {
        return line.startsWith("-") || line.startsWith("–") || line.startsWith("—")
                || line.matches("^(?:[•*]|\\d{1,3}[.)]|[A-Za-z][.)])\\s+.+$");
    }

    private static String cleanLine(String line) {
        if (line == null) return "";
        return line.replace('\u0000', ' ')
                .replace('\u00a0', ' ')
                .replace('\u00ad', '-')
                .replace("\uFB00", "ff")
                .replace("\uFB01", "fi")
                .replace("\uFB02", "fl")
                .replace("\uFB03", "ffi")
                .replace("\uFB04", "ffl")
                .replaceAll("[\\t\\f ]+", " ")
                .trim();
    }

    private static String normalizePageText(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.replace('\u0000', ' ')
                .replace('\u00a0', ' ')
                .replace('\u00ad', '-')
                .replace("\uFB00", "ff")
                .replace("\uFB01", "fi")
                .replace("\uFB02", "fl")
                .replace("\uFB03", "ffi")
                .replace("\uFB04", "ffl")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\f ]+", " ")
                .replaceAll("\\n{4,}", "\n\n\n")
                .trim();
    }

    private static final class BookAssembler {
        private final List<Block> blocks = new ArrayList<>();
        private final StringBuilder paragraph = new StringBuilder();

        void addLine(String rawLine, boolean skipAsMargin, boolean skipAsFooterPageNumber) {
            String line = cleanLine(rawLine);
            if (line.isEmpty()) {
                finishParagraph();
                return;
            }
            if (skipAsMargin || skipAsFooterPageNumber
                    || EXPLICIT_PAGE_NUMBER.matcher(line).matches()) {
                return;
            }
            if (isSceneBreak(line)) {
                finishParagraph();
                addSceneBreak();
                return;
            }
            if (isHeading(line)) {
                finishParagraph();
                blocks.add(new Block(BlockType.CHAPTER, line));
                return;
            }
            if (isDialogueOrListItem(line)) {
                finishParagraph();
                blocks.add(new Block(BlockType.PARAGRAPH, line));
                return;
            }
            appendPhysicalLine(line);
        }

        List<Block> finish() {
            finishParagraph();
            return blocks;
        }

        private void addSceneBreak() {
            if (!blocks.isEmpty() && blocks.get(blocks.size() - 1).type == BlockType.SCENE_BREAK) {
                return;
            }
            blocks.add(new Block(BlockType.SCENE_BREAK, "* * *"));
        }

        private void appendPhysicalLine(String line) {
            if (paragraph.length() == 0) {
                paragraph.append(line);
                return;
            }
            int lastIndex = paragraph.length() - 1;
            char lastCharacter = paragraph.charAt(lastIndex);
            if ((lastCharacter == '-' || lastCharacter == '\u2010') && startsWithLowerCase(line)
                    && lastIndex > 0 && Character.isLetter(paragraph.charAt(lastIndex - 1))) {
                paragraph.deleteCharAt(lastIndex);
                paragraph.append(line);
                return;
            }
            if (needsLeadingSpace(line)) paragraph.append(' ');
            paragraph.append(line);
        }

        private void finishParagraph() {
            String text = paragraph.toString().replaceAll("\\s+", " ").trim();
            paragraph.setLength(0);
            if (!text.isEmpty()) blocks.add(new Block(BlockType.PARAGRAPH, text));
        }
    }

    private static boolean startsWithLowerCase(String text) {
        return !text.isEmpty() && Character.isLowerCase(text.charAt(0));
    }

    private static boolean needsLeadingSpace(String text) {
        if (text.isEmpty()) return false;
        char first = text.charAt(0);
        return first != ',' && first != '.' && first != ';' && first != ':' && first != '!'
                && first != '?' && first != ')' && first != ']' && first != '}' && first != '”';
    }

    private static List<Block> limitBlocks(List<Block> blocks, int maximumCharacters) {
        List<Block> limited = new ArrayList<>();
        int length = 0;
        for (Block block : blocks) {
            if (block == null || block.text == null || block.text.isEmpty()) continue;
            int separator = limited.isEmpty() ? 0 : 2;
            int remaining = maximumCharacters - length - separator;
            if (remaining <= 0) break;
            String text = block.text;
            if (text.length() > remaining) {
                limited.add(new Block(block.type, trimAtWordBoundary(text, remaining)));
                break;
            }
            limited.add(block);
            length += separator + text.length();
        }
        return limited;
    }

    private static int blockTextLength(List<Block> blocks) {
        int length = 0;
        for (Block block : blocks) {
            if (block == null || block.text == null || block.text.isEmpty()) continue;
            if (length > 0) length += 2;
            length += block.text.length();
        }
        return length;
    }

    private static String flattenBlocks(List<Block> blocks, int maximumCharacters) {
        StringBuilder output = new StringBuilder();
        for (Block block : blocks) {
            if (block == null || block.text == null || block.text.isEmpty()) continue;
            int separator = output.length() == 0 ? 0 : 2;
            int remaining = maximumCharacters - output.length() - separator;
            if (remaining <= 0) break;
            if (separator > 0) output.append("\n\n");
            if (block.text.length() > remaining) {
                output.append(trimAtWordBoundary(block.text, remaining));
                break;
            }
            output.append(block.text);
        }
        return output.toString().trim();
    }

    private static String trimAtWordBoundary(String text, int maximumCharacters) {
        if (text.length() <= maximumCharacters) return text;
        int cut = text.lastIndexOf(' ', Math.max(0, maximumCharacters - 1));
        if (cut < maximumCharacters / 2) cut = maximumCharacters;
        return text.substring(0, cut).trim();
    }

    private static void flushSpeechBuffer(List<String> segments, StringBuilder buffer) {
        if (buffer.length() == 0) return;
        appendSpeechSegments(segments, buffer.toString());
        buffer.setLength(0);
    }

    private static void appendSpeechSegments(List<String> segments, String text) {
        String remaining = text == null ? "" : text.trim();
        while (!remaining.isEmpty()) {
            if (remaining.length() <= MAX_SPEECH_SEGMENT_CHARACTERS) {
                segments.add(remaining);
                return;
            }
            int cut = findSpeechBoundary(remaining, MAX_SPEECH_SEGMENT_CHARACTERS);
            String segment = remaining.substring(0, cut).trim();
            if (!segment.isEmpty()) segments.add(segment);
            remaining = remaining.substring(cut).trim();
        }
    }

    private static int findSpeechBoundary(String text, int maximumCharacters) {
        int start = Math.max(maximumCharacters / 2, 1);
        for (int index = maximumCharacters - 1; index >= start; index--) {
            char character = text.charAt(index);
            if (character == '.' || character == '!' || character == '?' || character == ';'
                    || character == ':' || character == '\n') return index + 1;
        }
        int space = text.lastIndexOf(' ', maximumCharacters - 1);
        return space >= start ? space + 1 : maximumCharacters;
    }
}

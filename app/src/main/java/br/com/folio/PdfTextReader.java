package br.com.folio;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.InputStream;

/** Extracts the embedded text layer of a PDF without sending the document to a server. */
public final class PdfTextReader {
    public interface ProgressListener {
        void onPageProcessed(int completedPages, int totalPages);
    }

    public interface CancellationSignal {
        boolean isCancelled();
    }

    public static final class Result {
        public final String text;
        public final int documentPageCount;
        public final int processedPageCount;
        public final boolean truncated;

        Result(String text, int documentPageCount, int processedPageCount, boolean truncated) {
            this.text = text;
            this.documentPageCount = documentPageCount;
            this.processedPageCount = processedPageCount;
            this.truncated = truncated;
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
                StringBuilder extracted = new StringBuilder();
                int processedPages = 0;
                boolean truncated = documentPages > pageLimit;
                for (int page = 1; page <= pageLimit; page++) {
                    checkCancelled(cancellation);
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    String pageText = normalize(stripper.getText(document));
                    if (!pageText.isEmpty()) {
                        int separatorLength = extracted.length() == 0 ? 0 : 2;
                        int remaining = MAX_TEXT_CHARACTERS - extracted.length() - separatorLength;
                        if (remaining <= 0) {
                            truncated = true;
                            break;
                        }
                        if (extracted.length() > 0) extracted.append("\n\n");
                        if (pageText.length() > remaining) {
                            extracted.append(pageText, 0, remaining);
                            truncated = true;
                            processedPages = page;
                            notifyProgress(progress, processedPages, pageLimit);
                            break;
                        }
                        extracted.append(pageText);
                    }
                    processedPages = page;
                    notifyProgress(progress, processedPages, pageLimit);
                }
                checkCancelled(cancellation);
                String text = extracted.toString().trim();
                if (text.isEmpty()) throw new NoTextException();
                return new Result(text, documentPages, processedPages, truncated);
            }
        }
    }

    private static void checkCancelled(CancellationSignal cancellation) throws CancelledException {
        if (cancellation != null && cancellation.isCancelled()) throw new CancelledException();
    }

    private static void notifyProgress(ProgressListener progress, int completed, int total) {
        if (progress != null) progress.onPageProcessed(completed, total);
    }

    private static String normalize(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.replace('\u0000', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\f ]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}

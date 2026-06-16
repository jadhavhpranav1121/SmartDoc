package org.example.service;

import org.example.model.DocumentChunk;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class PdfIngestionService {

    private static final int DEFAULT_CHUNK_SIZE = 500;
    private static final int DEFAULT_OVERLAP    = 50;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Full pipeline: upload → text → chunks → DocumentChunk list.
     */
    public List<DocumentChunk> ingestPdf(MultipartFile file,
                                         int chunkSize,
                                         int overlap) throws IOException {
        log.info("Starting PDF ingestion for file: {}, chunkSize={}, overlap={}",
                file.getOriginalFilename(), chunkSize, overlap);

        String rawText = extractText(file);
        log.info("Extracted {} characters of text from '{}'", rawText.length(), file.getOriginalFilename());

        List<String> windows = chunkText(rawText, chunkSize, overlap);
        log.info("Created {} word-window chunks from '{}'", windows.size(), file.getOriginalFilename());

        String fileName = file.getOriginalFilename() != null
                ? file.getOriginalFilename()
                : "unknown.pdf";

        List<DocumentChunk> chunks = buildChunks(windows, fileName);
        log.info("Built {} DocumentChunk objects for '{}'", chunks.size(), fileName);
        return chunks;
    }

    /**
     * Convenience overload with default chunk parameters.
     */
    public List<DocumentChunk> ingestPdf(MultipartFile file) throws IOException {
        return ingestPdf(file, DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
    }

    // -----------------------------------------------------------------------
    // Text extraction
    // -----------------------------------------------------------------------

    /**
     * Extracts and normalises plain text from a PDF MultipartFile.
     *
     * @throws IllegalArgumentException if the document is encrypted and cannot be read
     * @throws IOException              on I/O or PDF parse errors
     */
    public String extractText(MultipartFile file) throws IOException {
        log.debug("Loading PDDocument from multipart file '{}'", file.getOriginalFilename());

        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(file.getInputStream()))) {

            AccessPermission ap = document.getCurrentAccessPermission();
            if (!ap.canExtractContent()) {
                log.error("PDF '{}' is encrypted — extraction denied.", file.getOriginalFilename());
                throw new IllegalArgumentException(
                        "The PDF file is encrypted and cannot be read: " + file.getOriginalFilename());
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String raw = stripper.getText(document);

            // Normalise: collapse all whitespace sequences to a single space, trim
            String normalised = raw.replaceAll("\\s+", " ").trim();
            log.debug("Normalised text length: {} chars", normalised.length());
            return normalised;
        }
    }

    // -----------------------------------------------------------------------
    // Chunking
    // -----------------------------------------------------------------------

    /**
     * Splits {@code text} into overlapping word-based windows.
     *
     * @param text      normalised text (whitespace-separated words)
     * @param chunkSize number of words per chunk
     * @param overlap   number of words to repeat at the start of the next chunk
     * @return list of chunk strings (never empty for non-empty input)
     */
    public List<String> chunkText(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) {
            log.warn("chunkText received empty text — returning empty list.");
            return List.of();
        }

        String[] words = text.split("\\s+");
        int      total = words.length;
        int      step  = Math.max(1, chunkSize - overlap);

        log.debug("chunkText: totalWords={}, chunkSize={}, overlap={}, step={}",
                total, chunkSize, overlap, step);

        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < total) {
            int end = Math.min(start + chunkSize, total);
            String chunk = String.join(" ", Arrays.copyOfRange(words, start, end));
            chunks.add(chunk);

            if (end == total) {
                break;   // last chunk — don't advance past the end
            }
            start += step;
        }

        log.debug("chunkText produced {} chunks.", chunks.size());
        return chunks;
    }

    // -----------------------------------------------------------------------
    // Chunk building
    // -----------------------------------------------------------------------

    /**
     * Converts raw string windows into {@link DocumentChunk} objects.
     * ID format: {@code <baseFileName>_<index>_<8-char UUID>}.
     */
    private List<DocumentChunk> buildChunks(List<String> windows, String fileName) {
        // Strip path separators and spaces for a clean base name in the ID
        String baseName = fileName.replaceAll("[/\\\\\\s]", "_");

        List<DocumentChunk> chunks = new ArrayList<>(windows.size());
        for (int i = 0; i < windows.size(); i++) {
            String content   = windows.get(i);
            String shortUuid = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            String id        = baseName + "_" + i + "_" + shortUuid;
            int    wordCount = content.split("\\s+").length;

            chunks.add(DocumentChunk.builder()
                    .id(id)
                    .content(content)
                    .chunkIndex(i)
                    .sourceFileName(fileName)
                    .wordCount(wordCount)
                    .build());
        }
        return chunks;
    }
}
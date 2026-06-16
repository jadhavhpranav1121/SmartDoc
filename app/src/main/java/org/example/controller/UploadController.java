package org.example.controller;

import org.example.model.DocumentChunk;
import org.example.service.ElasticsearchIngestionService;
import org.example.service.PdfIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@CrossOrigin("*")
@RequiredArgsConstructor
public class UploadController {

    private final PdfIngestionService           pdfIngestionService;
    private final ElasticsearchIngestionService elasticsearchIngestionService;

    // -----------------------------------------------------------------------
    // Endpoints
    // -----------------------------------------------------------------------

    /**
     * POST /api/upload
     * Accepts a multipart PDF file, chunks it, embeds it, and stores it in Elasticsearch.
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadPdf(
            @RequestPart("file")                         MultipartFile file,
            @RequestParam(value = "chunkSize",  defaultValue = "500") int chunkSize,
            @RequestParam(value = "overlap",    defaultValue = "50")  int overlap) {

        log.info("Received upload request: file='{}', size={} bytes, chunkSize={}, overlap={}",
                file.getOriginalFilename(), file.getSize(), chunkSize, overlap);

        // ---- Validation ----
        if (file.isEmpty()) {
            log.warn("Upload rejected — file is empty.");
            return ResponseEntity
                    .badRequest()
                    .body(Map.of("error", "Uploaded file is empty."));
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.equalsIgnoreCase("application/pdf")) {
            log.warn("Upload rejected — invalid content-type: '{}'", contentType);
            return ResponseEntity
                    .badRequest()
                    .body(Map.of("error", "Only PDF files are accepted. Received: " + contentType));
        }

        if (chunkSize < 1) {
            return ResponseEntity.badRequest().body(Map.of("error", "chunkSize must be >= 1."));
        }
        if (overlap < 0 || overlap >= chunkSize) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "overlap must be >= 0 and < chunkSize."));
        }

        // ---- Ingestion pipeline ----
        try {
            List<DocumentChunk> chunks =
                    pdfIngestionService.ingestPdf(file, chunkSize, overlap);

            int savedCount = elasticsearchIngestionService.saveChunks(chunks);

            log.info("Upload complete: file='{}', totalChunks={}, savedChunks={}",
                    file.getOriginalFilename(), chunks.size(), savedCount);

            return ResponseEntity.ok(Map.of(
                    "status",      "SUCCESS",
                    "fileName",    file.getOriginalFilename(),
                    "totalChunks", chunks.size(),
                    "savedChunks", savedCount
            ));

        } catch (IllegalArgumentException e) {
            log.warn("PDF ingestion rejected for '{}': {}", file.getOriginalFilename(), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));

        } catch (Exception e) {
            log.error("PDF ingestion failed for '{}': {}", file.getOriginalFilename(), e.getMessage(), e);
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Ingestion failed: " + e.getMessage()));
        }
    }

    /**
     * GET /api/health
     * Simple liveness check — does not require authentication.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        log.debug("Health check requested.");
        return ResponseEntity.ok(Map.of(
                "status",  "UP",
                "service", "SmartDoc AI"
        ));
    }
}

# SmartDoc — Complete Technical Documentation

> A RAG-based (Retrieval-Augmented Generation) Document Q&A system built with **Spring Boot 3.2**, **Elasticsearch**, **OpenAI APIs**, and **Java 21**.

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Architecture & Data Flow](#2-architecture--data-flow)
3. [Project Structure](#3-project-structure)
4. [Build System — Gradle](#4-build-system--gradle)
5. [Dependencies Deep-Dive](#5-dependencies-deep-dive)
6. [Configuration — Application.properties](#6-configuration--applicationproperties)
7. [Entry Point — App.java](#7-entry-point--appjava)
8. [Config Layer](#8-config-layer)
   - [ElasticsearchConfig.java](#81-elasticsearchconfigjava)
   - [SecurityConfig.java](#82-securityconfigjava)
9. [Security Layer](#9-security-layer)
   - [JwtUtil.java](#91-jwtutiljava)
   - [JwtFilter.java](#92-jwtfilterjava)
   - [UserDetailsServiceImpl.java](#93-userdetailsserviceimpljava)
10. [Model Layer](#10-model-layer)
    - [DocumentChunk.java](#101-documentchunkjava)
    - [DocumentChunkDocument.java](#102-documentchunkdocumentjava)
    - [AskRequest.java](#103-askrequestjava)
    - [AskResponse.java](#104-askresponsejava)
    - [ConversationMessage.java](#105-conversationmessagejava)
11. [Repository Layer](#11-repository-layer)
12. [Service Layer](#12-service-layer)
    - [PdfIngestionService.java](#121-pdfingestionservicejava)
    - [EmbeddingService.java](#122-embeddingservicejava)
    - [ElasticsearchIngestionService.java](#123-elasticsearchingestionservicejava)
    - [SemanticSearchService.java](#124-semanticsearchservicejava)
    - [ChatService.java](#125-chatservicejava)
    - [ConversationStore.java](#126-conversationstorjava)
13. [Controller Layer](#13-controller-layer)
    - [UploadController.java](#131-uploadcontrollerjava)
    - [AskController.java](#132-askcontrollerjava)
    - [AuthController.java](#133-authcontrollerjava)
14. [End-to-End Request Flows](#14-end-to-end-request-flows)
15. [API Reference](#15-api-reference)
16. [How to Run Locally](#16-how-to-run-locally)
17. [Design Decisions & Caveats](#17-design-decisions--caveats)

---

## 1. Project Overview

SmartDoc lets a user:
1. **Upload a PDF** → it is parsed, split into overlapping word-based chunks, each chunk is embedded via OpenAI's `text-embedding-ada-002`, and stored in Elasticsearch with a 1 536-dimension `dense_vector` field.
2. **Ask a question** → the question is embedded, a KNN vector search finds the most relevant chunks, those chunks plus optional conversation history are sent to OpenAI's Chat API (`gpt-4o-mini`), and the grounded answer is returned.
3. **Hold multi-turn conversations** → an in-memory session store tracks Q&A history per session, letting users ask follow-up questions that reference earlier turns.

All endpoints except `/api/auth/login` and `/api/health` require a **JWT Bearer token**.

---

## 2. Architecture & Data Flow

```
                        ┌─────────────────────────────────────┐
                        │           HTTP Client (any)          │
                        └────────────────┬────────────────────┘
                                         │
                         ┌───────────────▼───────────────┐
                         │       Spring Security          │
                         │    JwtFilter (OncePerRequest)  │
                         └───────────────┬───────────────┘
                                         │
              ┌──────────────────────────┼──────────────────────────┐
              │                          │                           │
   ┌──────────▼──────┐       ┌──────────▼──────┐      ┌────────────▼──────┐
   │  AuthController  │       │ UploadController │      │   AskController   │
   │  POST /auth/login│       │  POST /upload    │      │   POST /ask       │
   └──────────┬───────┘       └──────────┬───────┘      └────────────┬──────┘
              │                          │                             │
         JwtUtil                  PdfIngestionService        SemanticSearchService
         (token gen)              │                             │
                            EmbeddingService◄──────────────────┘
                                   │                             │
                            ElasticsearchIngestionService    ChatService
                                   │                             │
                            DocumentChunkRepository         ConversationStore
                                   │                             │
                         ┌─────────▼────────┐     ┌────────────▼──────┐
                         │  Elasticsearch    │     │  OpenAI Chat API  │
                         │  (dense_vector    │     │  (gpt-4o-mini)    │
                         │   KNN index)      │     └───────────────────┘
                         └───────────────────┘
```

**Upload pipeline (one-time per document):**
```
MultipartFile PDF
  → PdfIngestionService.extractText()       [PDFBox 3.x]
  → PdfIngestionService.chunkText()         [word-window sliding]
  → EmbeddingService.getEmbeddingAsArray()  [OpenAI Embeddings API per chunk]
  → ElasticsearchIngestionService.saveChunks() [ES save via repository]
```

**Query pipeline (per question):**
```
AskRequest{question, topK, sessionId}
  → EmbeddingService.getEmbedding(question)        [1536-dim vector]
  → SemanticSearchService.findSimilarChunks()      [KNN search in ES]
  → ConversationStore.getHistory(sessionId)        [optional prior turns]
  → ChatService.askQuestion(question, chunks, history) [OpenAI Chat API]
  → ConversationStore.addTurn()                    [persist this turn]
  → AskResponse{answer, sourcesUsed, sourceFile, timeTakenMs, sessionId}
```

---

## 3. Project Structure

```
smartdoc/
├── gradle/
│   ├── libs.versions.toml          # Version catalog (guava, junit pins)
│   └── wrapper/
│       ├── gradle-wrapper.jar      # Gradle wrapper binary
│       └── gradle-wrapper.properties  # Gradle 9.5.1, zip distribution
├── gradle.properties               # JVM args for the Gradle daemon
├── gradlew / gradlew.bat           # Unix/Windows wrapper scripts
├── settings.gradle                 # Root project name = "smartdoc"; includes "app"
├── .gitignore / .gitattributes
└── app/
    ├── build.gradle                # All dependencies, Spring Boot plugin 3.2.5, Java 21
    └── src/
        ├── main/
        │   ├── java/org/example/
        │   │   ├── App.java                            # @SpringBootApplication entry
        │   │   ├── config/
        │   │   │   ├── ElasticsearchConfig.java        # ES client + index bootstrap
        │   │   │   └── SecurityConfig.java             # JWT, CSRF-off, stateless
        │   │   ├── controller/
        │   │   │   ├── AskController.java              # POST /api/ask, DELETE /api/conversation/{id}
        │   │   │   ├── AuthController.java             # POST /api/auth/login
        │   │   │   └── UploadController.java           # POST /api/upload, GET /api/health
        │   │   ├── model/
        │   │   │   ├── AskRequest.java
        │   │   │   ├── AskResponse.java
        │   │   │   ├── ConversationMessage.java
        │   │   │   ├── DocumentChunk.java              # In-memory chunk (pre-ES)
        │   │   │   └── DocumentChunkDocument.java      # ES @Document entity
        │   │   ├── repository/
        │   │   │   └── DocumentChunkRepository.java    # ElasticsearchRepository<>
        │   │   ├── security/
        │   │   │   ├── JwtFilter.java                  # OncePerRequestFilter
        │   │   │   ├── JwtUtil.java                    # HMAC256 token gen/validate
        │   │   │   └── UserDetailsServiceImpl.java     # Hardcoded admin user
        │   │   └── service/
        │   │       ├── ChatService.java                # OpenAI Chat completions
        │   │       ├── ConversationStore.java          # In-memory session history
        │   │       ├── ElasticsearchIngestionService.java # Embed + save chunks
        │   │       ├── EmbeddingService.java           # OpenAI Embeddings API
        │   │       ├── PdfIngestionService.java        # PDFBox parse + chunk
        │   │       └── SemanticSearchService.java      # KNN search via ES client
        │   └── resources/
        │       └── Application.properties
        └── test/
            └── java/org/example/
                └── AppTest.java                        # Placeholder test
```

---

## 4. Build System — Gradle

### `settings.gradle`
```groovy
plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'
}
rootProject.name = 'smartdoc'
include('app')
```
- The **Foojay resolver** plugin allows Gradle to automatically download the correct JDK if Java 21 isn't found on the machine — it queries the Foojay Disco API.
- The project is a **single-module** build: root = `smartdoc`, only subproject = `app`.

### `app/build.gradle`
```groovy
plugins {
    id 'org.springframework.boot' version '3.2.5'
    id 'io.spring.dependency-management' version '1.1.5'
    id 'java'
}

group = 'org.example'
version = '0.0.1-SNAPSHOT'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly { extendsFrom annotationProcessor }
}
```

**Key points:**
- `spring-boot` plugin adds `bootJar`, `bootRun`, `bootBuildImage` tasks. `bootJar` produces a fat executable JAR.
- `dependency-management` plugin imports the Spring Boot BOM, so you don't have to specify versions for any Spring dependency.
- `JavaLanguageVersion.of(21)` — uses Java 21 (LTS). The toolchain API means Gradle will auto-provision JDK 21 if needed.
- `compileOnly.extendsFrom annotationProcessor` — makes Lombok available during annotation processing but not on the runtime classpath.

### `gradle/libs.versions.toml`
```toml
[versions]
guava = "33.5.0-jre"
junit = "4.13.2"

[libraries]
guava = { module = "com.google.guava:guava", version.ref = "guava" }
junit = { module = "junit:junit", version.ref = "junit" }
```
This is the **Gradle Version Catalog** — a centralized dependency declaration. Note that `guava` and `junit4` are declared here but **not actually used** in `build.gradle` (which uses Spring Boot's transitive deps). These are Gradle init task remnants.

### `gradle/wrapper/gradle-wrapper.properties`
Pins the Gradle version to **9.5.1**, distribution type `bin` (not `all`). The wrapper ensures every developer and CI runner uses the exact same Gradle version without a system install.

---

## 5. Dependencies Deep-Dive

| Dependency | Scope | Purpose |
|---|---|---|
| `spring-boot-starter-web` | implementation | Embedded Tomcat, Spring MVC, Jackson JSON |
| `spring-boot-starter-data-elasticsearch` | implementation | Spring Data ES, `ElasticsearchRepository`, `ElasticsearchOperations` |
| `spring-boot-starter-security` | implementation | Spring Security filter chain, `AuthenticationManager` |
| `pdfbox:3.0.2` | implementation | PDF text extraction (Apache PDFBox 3.x API, uses `Loader.loadPDF` not `PDDocument.load`) |
| `java-jwt:4.4.0` | implementation | Auth0 JWT library for HMAC256 token sign/verify |
| `lombok` | compileOnly + annotationProcessor | Reduces boilerplate: `@Data`, `@Builder`, `@Slf4j`, `@RequiredArgsConstructor` |
| `spring-boot-starter-test` | testImplementation | JUnit 5, Mockito, AssertJ, Spring test context |

**Why no Spring AI?** Spring AI's vector store abstraction was not used — the project calls OpenAI REST APIs directly via `java.net.http.HttpClient`, giving full control over request/response handling and avoiding additional BOM complexity.

---

## 6. Configuration — `Application.properties`

```properties
server.port=8080

spring.servlet.multipart.max-file-size=50MB
spring.servlet.multipart.max-request-size=50MB

logging.level.org.example=DEBUG
logging.level.org.springframework.web=INFO

openai.api.key=YOUR_OPENAI_API_KEY_HERE
openai.embedding.model=text-embedding-ada-002
openai.chat.model=gpt-4o-mini

# spring.elasticsearch.uris=http://localhost:9200   (commented out — ES auto-config excluded)
conversation.max-turns=10
```

**Every property explained:**

- `server.port=8080` — Tomcat listens on 8080.
- `spring.servlet.multipart.max-file-size=50MB` — Spring's multipart resolver rejects files larger than 50 MB before they even reach the controller.
- `spring.servlet.multipart.max-request-size=50MB` — total HTTP request body limit (includes multipart boundary overhead).
- `logging.level.org.example=DEBUG` — all classes in the `org.example` package emit DEBUG logs. Since every service logs chunk counts, embedding dims, timings, etc., this makes ingestion fully observable.
- `openai.api.key` — injected via `@Value` into `EmbeddingService` and `ChatService`. Must be a real OpenAI key for the app to function.
- `openai.embedding.model=text-embedding-ada-002` — 1 536-dimension model. All ES index mappings are hardcoded to `dims: 1536` to match.
- `openai.chat.model=gpt-4o-mini` — cheapest capable OpenAI chat model.
- `spring.elasticsearch.uris` — commented out because `ElasticsearchClientAutoConfiguration` is **excluded** at the `@SpringBootApplication` level. The URI is instead read by `ElasticsearchConfig` via `@Value("${spring.elasticsearch.uris:http://localhost:9200}")`.
- `conversation.max-turns=10` — injected into `ConversationStore`. Each "turn" = 1 user message + 1 assistant message = 2 entries in the deque. So max 20 messages in history per session.

---

## 7. Entry Point — `App.java`

```java
@SpringBootApplication(exclude = {
        ElasticsearchDataAutoConfiguration.class,
        ElasticsearchRepositoriesAutoConfiguration.class,
        ElasticsearchClientAutoConfiguration.class
})
public class App {
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
}
```

**Why exclude all three ES auto-configurations?**

Spring Boot's ES auto-config tries to create a `ReactiveElasticsearchClient` and a `RestHighLevelClient` using properties it expects in `spring.elasticsearch.*`. Since we build our own `ElasticsearchClient` bean manually in `ElasticsearchConfig` (using the new Java low-level client from `co.elastic.clients`), letting auto-config run would either:
1. Fail on startup if ES is not running (connection refused), or
2. Create a conflicting duplicate bean.

Excluding all three prevents any Spring-initiated ES connection attempt at startup. The manual client in `ElasticsearchConfig` is created lazily-but-eagerly enough (it's a `@Bean`) and calls `ensureIndex()` at startup, but this is under our control.

---

## 8. Config Layer

### 8.1 `ElasticsearchConfig.java`

**Responsibilities:** Build the `ElasticsearchClient` bean; ensure the `document_chunks` index exists with the correct `dense_vector` mapping at application startup.

**Index mapping (hardcoded JSON):**
```json
{
  "mappings": {
    "properties": {
      "content":        { "type": "text"    },
      "sourceFileName": { "type": "keyword" },
      "chunkIndex":     { "type": "integer" },
      "wordCount":      { "type": "integer" },
      "embedding": {
        "type":       "dense_vector",
        "dims":       1536,
        "index":      true,
        "similarity": "cosine"
      }
    }
  }
}
```

**Why manual mapping?** Spring Data Elasticsearch 5.x (bundled with Boot 3.2) does not support `@Field(type = FieldType.DenseVector)` annotations. There is no `FieldType.DENSE_VECTOR` enum value. The only way to create an indexed `dense_vector` field (required for KNN search) is to pass the mapping JSON directly via the `CreateIndexRequest.withJson()` method. This is why `ElasticsearchConfig` exists at all.

**URI parsing logic:**
```java
boolean secure  = elasticsearchUri.startsWith("https");
String  scheme  = secure ? "https" : "http";
String  noProto = elasticsearchUri.replaceFirst("https?://", "");
String[] parts  = noProto.split(":");
String   host   = parts[0];
int      port   = parts.length > 1 ? Integer.parseInt(parts[1]) : 9200;
```
This is a manual URI parser to extract host/port/scheme from the `spring.elasticsearch.uris` property and feed them into `RestClient.builder(new HttpHost(...))`. It handles both `http://localhost:9200` and `https://my-host:443` formats.

**`ensureIndex` idempotency:** Before creating the index, it calls `client.indices().exists(...)`. If the index already exists, it logs and returns — no error, no re-creation. This means the app can be restarted repeatedly without corrupting an existing index.

**Transport stack used:**
```
RestClient (Apache HttpClient-based, low-level)
  └── RestClientTransport (wraps RestClient with JacksonJsonpMapper)
        └── ElasticsearchClient (typed, lambda-based fluent API)
```
This is the **new ES Java client** (co.elastic.clients, ES 8.x compatible), NOT the deprecated `RestHighLevelClient`.

---

### 8.2 `SecurityConfig.java`

```java
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final JwtFilter jwtFilter;
    ...
}
```

**What it configures:**

| Setting | Value | Reason |
|---|---|---|
| CSRF | Disabled | Stateless REST API — no browser sessions, no CSRF attack vector |
| Session creation | `STATELESS` | No `HttpSession` created or used |
| `/api/auth/login` | `permitAll()` | Must be reachable without a token to get a token |
| `/api/health` | `permitAll()` | Liveness probes don't need auth |
| `/api/**` | `authenticated()` | All other API endpoints require a valid JWT |
| `anyRequest` | `permitAll()` | Non-API paths (e.g., actuator, static resources) are open |
| Password encoder | `BCryptPasswordEncoder` | Spring Security's recommended encoder; work factor defaults to 10 |
| `JwtFilter` position | Before `UsernamePasswordAuthenticationFilter` | JWT validation must happen before Spring's default auth filter runs |

**`PasswordEncoder` bean:** Declared here so it can be injected into `UserDetailsServiceImpl` and used by `AuthenticationManager` to compare submitted passwords against the BCrypt-encoded stored password.

**`AuthenticationManager` bean:** Exposed via `config.getAuthenticationManager()` from `AuthenticationConfiguration`. Needed in `AuthController` to trigger Spring Security's authentication flow (which internally calls `UserDetailsServiceImpl.loadUserByUsername`).

---

## 9. Security Layer

### 9.1 `JwtUtil.java`

Uses **Auth0 java-jwt 4.4.0** library. HMAC-SHA256 algorithm.

```java
@Value("${jwt.secret:SmartDocAI_SuperSecret_JWT_Key_2024_Change_This}")
private String secret;

@Value("${jwt.expiration.ms:86400000}")
private long expirationMs;
```

- `jwt.secret` — not present in `Application.properties`, so the default string `SmartDocAI_SuperSecret_JWT_Key_2024_Change_This` is used. **This must be overridden in production.**
- `jwt.expiration.ms=86400000` — default 24 hours (86 400 000 ms). Again a default, not set in properties.

**`generateToken(String username)`:**
```java
JWT.create()
    .withSubject(username)
    .withIssuedAt(now)
    .withExpiresAt(expires)
    .sign(Algorithm.HMAC256(secret));
```
Produces a signed JWT with subject = username, `iat` = now, `exp` = now + 24h.

**`validateToken(String token)`:**
```java
JWT.require(Algorithm.HMAC256(secret)).build().verify(token);
```
Verifies signature AND expiry in one call. Returns `false` on any `JWTVerificationException` (includes expired, tampered, wrong key).

**`extractUsername(String token)`:**
```java
JWT.decode(token).getSubject();
```
Does NOT verify — always call `validateToken` first.

---

### 9.2 `JwtFilter.java`

Extends `OncePerRequestFilter` — guaranteed to run exactly once per request even if the request is forwarded internally.

**Flow:**
```
Request arrives
  → Read "Authorization" header
  → Does it start with "Bearer "?
     NO  → trace log, continue filter chain (unauthenticated)
     YES → extract token substring
         → jwtUtil.validateToken(token)
              FAIL → warn log, continue filter chain (unauthenticated)
              PASS → extract username
                   → loadUserByUsername(username)
                   → build UsernamePasswordAuthenticationToken
                   → set into SecurityContextHolder
                   → debug log
  → chain.doFilter(request, response)  ← always called
```

The filter **never blocks** the request — it either populates the `SecurityContext` or doesn't. The actual authorization decision (return 401/403 or allow) is made by Spring Security's `FilterSecurityInterceptor` downstream based on the route rules in `SecurityConfig`.

---

### 9.3 `UserDetailsServiceImpl.java`

**Hardcoded single user:** `admin` / `password123`

```java
private static final String ENCODED_PASSWORD =
        new BCryptPasswordEncoder().encode("password123");
```

The BCrypt hash is computed **once at class load time** (static initializer). It is deterministic in the sense that Spring will always accept `password123` for user `admin` — but each JVM start generates a fresh hash (BCrypt is salted). This is fine because the hash is only used for Spring's internal `matches(rawPassword, encodedPassword)` check.

Any username other than `"admin"` throws `UsernameNotFoundException`, which Spring Security converts to HTTP 401.

---

## 10. Model Layer

### 10.1 `DocumentChunk.java`

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class DocumentChunk {
    private String id;           // "<sanitized_filename>_<index>_<8charUUID>"
    private String content;      // raw text of this chunk window
    private int    chunkIndex;   // position in the chunk sequence (0-based)
    private String sourceFileName; // original upload filename
    private int    wordCount;    // words in this chunk
}
```

This is a **pure Java POJO** — not an ES entity. It lives only in memory during the ingestion pipeline (PDF → chunks → embed → save). It gets converted to `DocumentChunkDocument` before being stored.

**ID format:** `report_pdf_0_a3f9b2c1` — filename (spaces/slashes replaced with `_`) + chunk index + 8-char UUID fragment. The UUID suffix prevents collisions when the same file is re-uploaded.

---

### 10.2 `DocumentChunkDocument.java`

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(indexName = "document_chunks")
public class DocumentChunkDocument {
    @Id
    private String id;

    @Field(type = FieldType.Text)
    private String content;

    @Field(type = FieldType.Keyword)
    private String sourceFileName;

    @Field(type = FieldType.Integer)
    private int chunkIndex;

    @Field(type = FieldType.Integer)
    private int wordCount;

    // No @Field annotation — mapping is applied via ElasticsearchConfig JSON
    private float[] embedding;
}
```

**`@Document(indexName = "document_chunks")`** — tells Spring Data ES which index this entity maps to. Used by `DocumentChunkRepository` for CRUD operations.

**`@Field(type = FieldType.Text)` on `content`** — analyzed field (tokenized, lowercase, etc.) — allows BM25 full-text queries if needed in the future.

**`@Field(type = FieldType.Keyword)` on `sourceFileName`** — not analyzed, exact match only. Good for filtering by file.

**`embedding` field — no annotation intentionally.** Spring Data ES would try to map this to an ES type it doesn't understand (dense_vector). Leaving it unannotated means Spring Data ES ignores it during index creation (which we skip anyway), but Jackson still serializes/deserializes it as a JSON array when reading from ES.

---

### 10.3 `AskRequest.java`

```java
@Data @NoArgsConstructor @AllArgsConstructor
public class AskRequest {
    private String question;
    private int    topK = 3;      // default: return top 3 chunks
    private String sessionId;     // null = stateless; "new" or unknown = auto-create
}
```

Jackson deserializes this from the POST body. The `topK` default of `3` only applies if the field is omitted in JSON (Jackson respects field initializers for `@Data`).

---

### 10.4 `AskResponse.java`

```java
@Data @Builder
public class AskResponse {
    private String answer;
    private int    sourcesUsed;   // number of chunks fed to LLM
    private String sourceFile;    // filename of the top-ranked chunk
    private long   timeTakenMs;   // wall-clock from request start to response
    private String sessionId;     // echoed back for client to use in next request
}
```

`@Builder` — uses the builder pattern for construction in `AskController` (`AskResponse.builder().answer(...).build()`).

---

### 10.5 `ConversationMessage.java`

```java
@Data @NoArgsConstructor @AllArgsConstructor
public class ConversationMessage {
    private String role;     // "system", "user", or "assistant"
    private String content;
}
```

Directly maps to the OpenAI Chat API message format. Instances stored in `ConversationStore` always have role `"user"` or `"assistant"` — the `"system"` role is constructed fresh in `ChatService.buildRequestBody()` and never stored.

---

## 11. Repository Layer

### `DocumentChunkRepository.java`

```java
@Repository
public interface DocumentChunkRepository
        extends ElasticsearchRepository<DocumentChunkDocument, String> {
}
```

Extends `ElasticsearchRepository<T, ID>` — a Spring Data ES interface that provides inherited CRUD methods:
- `save(entity)` — index one document
- `saveAll(iterable)` — bulk index
- `findById(id)` — get by `@Id`
- `deleteById(id)` — delete
- `findAll()` — all documents (don't use on large indexes)

**No custom query methods** are declared because KNN vector search is not expressible via Spring Data's method-name DSL. KNN is handled directly in `SemanticSearchService` using the typed `ElasticsearchClient`.

---

## 12. Service Layer

### 12.1 `PdfIngestionService.java`

**Three public methods:**

#### `ingestPdf(MultipartFile, int chunkSize, int overlap)`
Orchestrates the full pipeline: extract → chunk → build. Returns `List<DocumentChunk>`.

#### `extractText(MultipartFile file)`
Uses **Apache PDFBox 3.x** API:
```java
PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(file.getInputStream()));
```
Note: PDFBox 3.x replaced `PDDocument.load(InputStream)` with `Loader.loadPDF(RandomAccessRead)` — the old API is gone. `RandomAccessReadBuffer` wraps the input stream bytes into an in-memory random-access buffer.

Checks `AccessPermission.canExtractContent()` — if the PDF has copy-protection flags set, it throws `IllegalArgumentException` rather than returning garbage text.

`PDFTextStripper.setSortByPosition(true)` — sorts text fragments by their X/Y coordinates on the page, producing more natural reading order for multi-column PDFs.

Normalisation:
```java
raw.replaceAll("\\s+", " ").trim()
```
Collapses all whitespace (newlines, tabs, multiple spaces) into single spaces. This is crucial for word-based chunking — without it, the word count per chunk would be polluted by blank lines.

#### `chunkText(String text, int chunkSize, int overlap)`
**Sliding word window algorithm:**
```
words = text.split("\\s+")
step  = chunkSize - overlap
start = 0

while start < words.length:
    end   = min(start + chunkSize, words.length)
    chunk = join(words[start:end])
    chunks.add(chunk)
    if end == words.length: break
    start += step
```

Example with `chunkSize=5, overlap=2`:
```
words: [A, B, C, D, E, F, G, H]
step = 3

Chunk 0: A B C D E   (start=0, end=5)
Chunk 1: D E F G H   (start=3, end=8)
```
Words D and E appear in both chunks — this is the **overlap** that preserves context across chunk boundaries, preventing answers from being cut off at a chunk edge.

**Edge case:** `if end == words.length: break` — prevents an infinite loop on the last chunk. Without this, `start` would advance but `end` would cap at `words.length` repeatedly.

Default values: `chunkSize=500 words`, `overlap=50 words`, giving `step=450`.

#### `buildChunks(List<String> windows, String fileName)`
Constructs `DocumentChunk` objects. ID format:
```java
String baseName  = fileName.replaceAll("[/\\\\\\s]", "_");
String shortUuid = UUID.randomUUID().toString().replace("-","").substring(0, 8);
String id        = baseName + "_" + i + "_" + shortUuid;
```
The UUID suffix makes IDs unique across multiple uploads of the same file, preventing ES document collisions.

---

### 12.2 `EmbeddingService.java`

Calls **OpenAI Embeddings API** (`/v1/embeddings`) via `java.net.http.HttpClient` (Java 11+ built-in, no extra dependency).

```java
HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build();
```

**Request body:**
```json
{ "model": "text-embedding-ada-002", "input": "<chunk text>" }
```

**Response parsing:**
```java
JsonNode embeddingArray = root.path("data").get(0).path("embedding");
for (JsonNode dim : embeddingArray) {
    embedding.add((float) dim.asDouble());
}
```
The API returns 1 536 doubles. They are cast to `float` to match ES's `float[]` expectation for `dense_vector`.

**`getEmbeddingAsArray(String text)`** — converts `List<Float>` to primitive `float[]` (Elasticsearch's Java client requires `List<Float>` for the KNN query vector but `float[]` is used in the stored document).

**Timeout:** `Duration.ofSeconds(60)` per request — generous because large chunks can take a moment to embed.

**Error handling:** Any non-200 HTTP status throws `RuntimeException` with the response body included, making OpenAI error messages (rate limits, quota exceeded, invalid key) visible in logs.

---

### 12.3 `ElasticsearchIngestionService.java`

Iterates over chunks, embeds each one, wraps it in a `DocumentChunkDocument`, and saves via the repository. **Chunk-level error isolation:**

```java
for (DocumentChunk chunk : chunks) {
    try {
        float[] embedding = embeddingService.getEmbeddingAsArray(chunk.getContent());
        DocumentChunkDocument doc = DocumentChunkDocument.builder()...build();
        documentChunkRepository.save(doc);
        savedCount++;
    } catch (Exception e) {
        log.error("Failed to embed/save chunk ...");
        // Continue — do not abort the batch
    }
}
```

If one chunk fails (e.g., transient OpenAI error, ES timeout), processing continues for remaining chunks. The return value `savedCount` (vs `chunks.size()`) tells the caller how many actually made it into ES.

**Why not `saveAll` (bulk)?** Bulk indexing would be more efficient, but chunked-one-at-a-time gives per-chunk error isolation and simpler retry logic. For a portfolio project, individual saves are perfectly acceptable.

---

### 12.4 `SemanticSearchService.java`

Performs **KNN (K-Nearest Neighbor) vector search** using Elasticsearch's HNSW (Hierarchical Navigable Small World) graph algorithm.

```java
SearchResponse<DocumentChunkDocument> response = elasticsearchClient.search(
    s -> s
        .index("document_chunks")
        .knn(k -> k
                .field("embedding")
                .queryVector(queryVector)          // List<Float> — 1536 dims
                .numCandidates((long) topK * 5)   // HNSW exploration breadth
                .k((long) topK)                   // final results to return
        )
        .size(topK),
    DocumentChunkDocument.class
);
```

**`numCandidates`:** During HNSW graph traversal, ES considers `numCandidates` nodes before selecting the final `k`. Higher values = better recall at the cost of latency. `topK * 5` is a standard heuristic (ES docs recommend `numCandidates >= k` and suggest 100+ for good recall).

**`similarity: cosine`** (set in the index mapping) — cosine similarity is appropriate for OpenAI embeddings (which are L2-normalized, making cosine and dot-product equivalent, but cosine is the explicit setting here).

**Why use the low-level client instead of Spring Data?** Spring Data ES's `NativeQuery` with `.withKnnQuery()` had unstable builder APIs across 5.x patch versions (method signatures changed between 5.1.x and 5.2.x). Using `ElasticsearchClient.search()` directly with the lambda builder is stable and maps 1:1 to the ES REST API.

---

### 12.5 `ChatService.java`

Calls **OpenAI Chat Completions API** (`/v1/chat/completions`) with the grounded context and optional conversation history.

**System prompt:**
```
You are a helpful assistant. Answer the user's question using ONLY the context
provided below. If the answer is not in the context, say
'I could not find an answer in the uploaded document.'
Do not make up information.
You have access to the conversation history — use it to answer follow-up questions
and resolve pronouns or references to earlier turns.
```
This is a strict grounding prompt — it forbids hallucination explicitly.

**Message array order sent to OpenAI:**
```
[
  { "role": "system",    "content": "<SYSTEM_PROMPT>\n\nContext:\n<chunk1>\n\n<chunk2>..." },
  { "role": "user",      "content": "<history turn 1 user msg>" },
  { "role": "assistant", "content": "<history turn 1 assistant reply>" },
  ...
  { "role": "user",      "content": "<current question>" }
]
```

The context chunks are injected into the **system message**, not the user message. This is the recommended pattern — system messages set the assistant's behavior and context, keeping the user message clean.

**Chat model parameters:**
```json
{
  "model": "gpt-4o-mini",
  "max_tokens": 1000,
  "temperature": 0.2
}
```
`temperature: 0.2` — low temperature for factual, grounded responses. Near-deterministic but not fully zero (to allow slight variation in phrasing).

**Single-turn backward-compatible method:**
```java
public String askQuestion(String question, List<DocumentChunkDocument> chunks) {
    return askQuestion(question, chunks, List.of());
}
```
Passes empty history — stateless mode.

**Timeout:** 120 seconds on the HTTP request. LLM responses can be slow, especially if the model needs to reason through long context.

---

### 12.6 `ConversationStore.java`

In-memory session store using `ConcurrentHashMap<String, Deque<ConversationMessage>>`.

**Session lifecycle:**
```java
// Create
String sessionId = UUID.randomUUID().toString();
sessions.put(sessionId, new ArrayDeque<>());

// Add turn
Deque<ConversationMessage> deque = sessions.computeIfAbsent(sessionId, ...);
// Evict oldest turn if over cap
while (deque.size() >= maxTurns * 2) {
    deque.pollFirst(); // remove oldest user msg
    deque.pollFirst(); // remove oldest assistant msg
}
deque.addLast(new ConversationMessage("user",      userMessage));
deque.addLast(new ConversationMessage("assistant", assistantReply));
```

**Why `ArrayDeque`?** `ArrayDeque` is the recommended general-purpose `Deque` implementation in Java — amortized O(1) for all head/tail operations. `LinkedList` would work but has higher per-node memory overhead.

**Why `ConcurrentHashMap`?** Multiple HTTP request threads may concurrently create sessions, read history, or add turns. `HashMap` would not be thread-safe. `ConcurrentHashMap.computeIfAbsent` is atomic.

**Sliding window cap:**
```
maxTurns = 10  →  maxMessages = 20
```
When 20 messages exist and a new turn arrives, the oldest 2 (1 user + 1 assistant) are evicted before adding the new pair. The window always stays at exactly `maxTurns` turns. This bounds the token count sent to OpenAI (each additional history turn costs tokens).

**`clearSession` vs destroying the session:** `clearSession` empties the deque but keeps the session key in the map. The session ID remains valid; subsequent asks get a fresh empty history. This supports a "start new topic" UX without changing the session ID.

**Limitation:** All sessions are lost on application restart (in-memory only). For production, this would need Redis or a database.

---

## 13. Controller Layer

### 13.1 `UploadController.java`

**`POST /api/upload`**

Parameters:
- `file` — `@RequestPart` multipart PDF file
- `chunkSize` — `@RequestParam` default `500` (words per chunk)
- `overlap` — `@RequestParam` default `50` (overlapping words)

**Validation checks (in order):**
1. File is not empty → 400
2. Content-Type is `application/pdf` → 400 (rejects text/plain, image/*, etc.)
3. `chunkSize >= 1` → 400
4. `0 <= overlap < chunkSize` → 400

**Success response (HTTP 200):**
```json
{
  "status":      "SUCCESS",
  "fileName":    "report.pdf",
  "totalChunks": 42,
  "savedChunks": 42
}
```
`savedChunks` can be less than `totalChunks` if some embedding/ES-save attempts failed.

**`GET /api/health`**
```json
{ "status": "UP", "service": "SmartDoc AI" }
```
Declared `permitAll()` in `SecurityConfig` — no JWT needed.

---

### 13.2 `AskController.java`

**`POST /api/ask`**

**Session resolution logic (`resolveSession`):**
```
requestedSessionId == null/blank  →  return null  (stateless)
sessions.contains(requestedId)    →  return requestedId  (existing session)
else                              →  conversationStore.createSession()  (new)
```
The string `"new"` is treated as an unknown ID, triggering auto-creation. The new session ID (a UUID) is returned in the response body. The client must capture and re-send it in subsequent requests.

**RAG pipeline steps (inside the try block):**
1. Validate question is non-blank
2. Resolve `topK` (default 3 if `<= 0`)
3. Resolve session
4. `semanticSearchService.findSimilarChunks(question, topK)` → List of chunks
5. If chunks empty → return "I could not find an answer..." immediately (still logs the turn if session exists)
6. `conversationStore.getHistory(sessionId)` → prior messages (empty list for stateless)
7. `chatService.askQuestion(question, chunks, history)` → LLM answer
8. `conversationStore.addTurn(sessionId, question, answer)` → persist
9. Build and return `AskResponse`

**`sourceFile` in response** = `chunks.get(0).getSourceFileName()` — the file that produced the top-ranked chunk.

**`DELETE /api/conversation/{sessionId}`**
Clears session history. Returns 404 if session not found, 200 with `{"status":"CLEARED","sessionId":"..."}` on success.

---

### 13.3 `AuthController.java`

**`POST /api/auth/login`**

Request body:
```json
{ "username": "admin", "password": "password123" }
```

Flow:
```java
Authentication auth = authenticationManager.authenticate(
        new UsernamePasswordAuthenticationToken(username, password));
String token = jwtUtil.generateToken(auth.getName());
return ResponseEntity.ok(Map.of("token", token));
```

`authenticationManager.authenticate()` internally:
1. Calls `UserDetailsServiceImpl.loadUserByUsername("admin")`
2. Calls `BCryptPasswordEncoder.matches("password123", storedHash)`
3. If match → returns authenticated `Authentication` object
4. If mismatch → throws `BadCredentialsException` → HTTP 401

Success response:
```json
{ "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." }
```

---

## 14. End-to-End Request Flows

### Flow A: Login
```
POST /api/auth/login
  → SecurityConfig: permitAll() → skip JWT filter check
  → AuthController.login()
  → AuthenticationManager.authenticate()
  → UserDetailsServiceImpl.loadUserByUsername("admin")
  → BCrypt.matches("password123", storedHash) → true
  → JwtUtil.generateToken("admin") → JWT string
  → 200 OK { "token": "..." }
```

### Flow B: Upload PDF
```
POST /api/upload  (with Authorization: Bearer <jwt>)
  → JwtFilter: validates JWT → sets SecurityContext
  → SecurityConfig: /api/** requires authenticated → passes
  → UploadController.uploadPdf(file, 500, 50)
    → PdfIngestionService.ingestPdf(file, 500, 50)
      → PDFBox: extract text → normalize whitespace
      → chunkText: sliding window → List<String> (N windows)
      → buildChunks: wrap in DocumentChunk with IDs
    → ElasticsearchIngestionService.saveChunks(chunks)
      [for each chunk:]
        → EmbeddingService.getEmbeddingAsArray(content)
          → POST https://api.openai.com/v1/embeddings
          → parse 1536 floats
        → DocumentChunkRepository.save(doc)
          → ES REST PUT /document_chunks/_doc/<id>
  → 200 OK { status, fileName, totalChunks, savedChunks }
```

### Flow C: Ask Question (Multi-Turn)
```
POST /api/ask { question: "What is the policy?", topK: 3, sessionId: "new" }
  → JwtFilter: validates JWT
  → AskController.ask()
    → resolveSession("new") → creates new session "abc-123"
    → SemanticSearchService.findSimilarChunks("What is the policy?", 3)
      → EmbeddingService.getEmbedding(question) → 1536-dim vector
      → ElasticsearchClient.search() with .knn(embedding, k=3, candidates=15)
      → returns top 3 DocumentChunkDocument
    → ConversationStore.getHistory("abc-123") → [] (new session)
    → ChatService.askQuestion(question, 3 chunks, [])
      → buildRequestBody: system(context) + user(question)
      → POST https://api.openai.com/v1/chat/completions
      → parse choices[0].message.content → answer string
    → ConversationStore.addTurn("abc-123", question, answer)
    → 200 OK AskResponse { answer, sourcesUsed:3, sourceFile, timeTakenMs, sessionId:"abc-123" }

Next request: POST /api/ask { question: "Tell me more about that", sessionId: "abc-123" }
  → ConversationStore.getHistory("abc-123") → [user: "What is the policy?", assistant: "<answer>"]
  → ChatService sends: system + prior user/assistant pair + new question
  → GPT resolves "that" using conversation history
```

---

## 15. API Reference

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/auth/login` | None | Get JWT token |
| `GET` | `/api/health` | None | Liveness check |
| `POST` | `/api/upload` | JWT | Upload and ingest a PDF |
| `POST` | `/api/ask` | JWT | Ask a question (RAG + optional multi-turn) |
| `DELETE` | `/api/conversation/{sessionId}` | JWT | Clear session history |

**Upload request:**
```
POST /api/upload
Authorization: Bearer <token>
Content-Type: multipart/form-data

file=<pdf_binary>
chunkSize=500    (optional)
overlap=50       (optional)
```

**Ask request:**
```json
POST /api/ask
Authorization: Bearer <token>

{
  "question":  "What are the refund terms?",
  "topK":      3,
  "sessionId": "new"
}
```

---

## 16. How to Run Locally

### Prerequisites
- Java 21 (or let Gradle auto-provision via Foojay)
- Elasticsearch 8.x running at `http://localhost:9200`
- OpenAI API key

### Steps

```bash
# 1. Clone / unzip the project
cd smartdoc

# 2. Set your OpenAI key (edit Application.properties or pass as env var)
# app/src/main/resources/Application.properties:
# openai.api.key=sk-...

# 3. Uncomment the ES URI in Application.properties:
# spring.elasticsearch.uris=http://localhost:9200

# 4. Start Elasticsearch (Docker example)
docker run -d --name es -p 9200:9200 \
  -e "discovery.type=single-node" \
  -e "xpack.security.enabled=false" \
  docker.elastic.co/elasticsearch/elasticsearch:8.13.0

# 5. Build and run
./gradlew :app:bootRun

# 6. Get a token
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"password123"}'

# 7. Upload a PDF
curl -X POST http://localhost:8080/api/upload \
  -H "Authorization: Bearer <token>" \
  -F "file=@/path/to/document.pdf"

# 8. Ask a question
curl -X POST http://localhost:8080/api/ask \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"question":"What is the main topic?","topK":3}'
```

---

## 17. Design Decisions & Caveats

| Area | Decision | Tradeoff / Caveat |
|---|---|---|
| **Auth** | Hardcoded single `admin` user | Easy demo setup; production needs a user database |
| **JWT secret** | Defaults to a hardcoded string | Must be set via env var in production |
| **Embedding calls** | One HTTP call per chunk (no batching) | Simple but slow for large PDFs; OpenAI Embeddings API supports batching up to 2048 inputs |
| **Conversation store** | In-memory `ConcurrentHashMap` | Lost on restart; needs Redis/DB for production |
| **ES client** | Low-level typed Java client (not Spring Data) | Avoids unstable KNN builder API changes; requires more boilerplate |
| **ES auto-config** | Fully excluded | Must manually manage connection and index lifecycle |
| **`dense_vector` mapping** | Defined in JSON string, not annotation | Spring Data ES 5.x does not support `dense_vector` annotations |
| **Chunking** | Word-based sliding window | Ignores sentence/paragraph boundaries; semantic chunking would give better retrieval quality |
| **Temperature** | 0.2 | Good for factual grounding; can be too rigid for creative or open-ended questions |
| **Security** | No HTTPS, no rate limiting | Acceptable for local dev; production needs both |
| **Tests** | Only `AppTest.java` (placeholder) | No unit or integration tests exist yet |
| **No Angular frontend** | Backend-only (API) | A frontend (Angular) was planned but not included in this zip |

# SmartDoc

A Spring Boot RAG (Retrieval-Augmented Generation) application that lets you upload PDF documents and ask questions about their content using semantic search and OpenAI.

## How it works

1. **Upload** a PDF via `POST /api/upload` — it is chunked, embedded via OpenAI, and stored in Elasticsearch.
2. **Ask** a question via `POST /api/ask` — the question is embedded, similar chunks are retrieved via KNN search, and OpenAI generates an answer grounded in the document.

## Prerequisites

- Java 17+
- Elasticsearch 8.x running on `localhost:9200`
- OpenAI API key

## Configuration

Set your API key as an environment variable before running:

```bash
export OPENAI_API_KEY=sk-...
```

Or edit `app/src/main/resources/application.properties` directly.

## Running

```bash
./gradlew bootRun
```

The server starts on `http://localhost:8080`.

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/auth/login` | Get a JWT token |
| `POST` | `/api/upload` | Upload a PDF (multipart) |
| `POST` | `/api/ask` | Ask a question about the uploaded document |
| `GET`  | `/api/health` | Liveness check |

## Project Structure

```
app/src/main/java/org/example/
├── controller/       # REST endpoints (Auth, Upload, Ask)
├── service/          # Business logic (PDF ingestion, embeddings, search, chat)
├── model/            # Request/response and Elasticsearch document models
├── repository/       # Spring Data Elasticsearch repository
├── security/         # JWT filter, util, and UserDetailsService
└── config/           # Elasticsearch and Security configuration
```

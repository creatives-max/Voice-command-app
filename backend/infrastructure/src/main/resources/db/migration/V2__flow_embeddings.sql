-- pgvector screen embeddings: match a live screen to the closest saved flow even when labels change slightly.
CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE flow_versions ADD COLUMN embedding vector(256);
ALTER TABLE flow_versions ADD COLUMN embedding_model TEXT;

CREATE INDEX flow_versions_embedding_hnsw ON flow_versions USING hnsw (embedding vector_cosine_ops);

package com.codescope.client.llm;

// Ollama /api/embeddings 요청 바디
public record OllamaEmbeddingRequest(String model, String prompt) {
}

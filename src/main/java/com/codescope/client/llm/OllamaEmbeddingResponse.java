package com.codescope.client.llm;

import java.util.List;

// Ollama /api/embeddings 응답 바디 - {"embedding": [0.1, 0.2, ...]}
public record OllamaEmbeddingResponse(List<Double> embedding) {
}

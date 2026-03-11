package com.baincu.medireader.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.nio.file.Paths;

@Configuration
public class VectorStoreConfig {

    @Value("${medireader.vectorstore-path}")
    private String vectorStorePath;

    @Bean
    public SimpleVectorStore vectorStore(EmbeddingModel embeddingModel) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        File file = Paths.get(System.getProperty("user.dir")).resolve(vectorStorePath).normalize().toFile();
        if (file.exists()) {
            store.load(file);
        }
        return store;
    }
}

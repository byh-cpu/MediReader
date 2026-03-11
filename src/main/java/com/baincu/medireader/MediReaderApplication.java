package com.baincu.medireader;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.File;

@SpringBootApplication
public class MediReaderApplication {

    @Value("${medireader.data-dir:./data}")
    private String dataDir;

    @Value("${medireader.knowledge-dir:./data/knowledge}")
    private String knowledgeDir;

    public static void main(String[] args) {
        SpringApplication.run(MediReaderApplication.class, args);
    }

    @PostConstruct
    public void initDirectories() {
        new File(dataDir).mkdirs();
        new File(knowledgeDir).mkdirs();
    }
}

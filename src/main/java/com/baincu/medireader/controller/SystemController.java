package com.baincu.medireader.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final ChatClient.Builder chatClientBuilder;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean ollamaConnected = false;
        String errorMessage = null;

        try {
            ChatClient client = chatClientBuilder.build();
            String response = client.prompt().user("hi").call().content();
            ollamaConnected = response != null && !response.isEmpty();
        } catch (Exception e) {
            errorMessage = e.getMessage();
        }

        return ResponseEntity.ok(Map.of(
                "status", ollamaConnected ? "ok" : "degraded",
                "ollama", ollamaConnected,
                "message", ollamaConnected
                        ? "系统正常运行"
                        : "Ollama 连接失败: " + (errorMessage != null ? errorMessage : "未知错误")
        ));
    }
}

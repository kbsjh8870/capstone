package com.example.Backend.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/config")
public class ApiKeyController {

    @Value("${tmap.api.key}")
    private String tmapApiKey;

    @GetMapping("/tmap-key")
    public ResponseEntity<String> getTmapApiKey() {
        return ResponseEntity.ok(tmapApiKey);
    }
}
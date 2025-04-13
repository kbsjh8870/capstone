package com.example.Backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.Backend.service.TmapApiService;

@RestController
@RequestMapping("/api/route")
public class RouteController {

    private final TmapApiService tmapApiService;

    public RouteController(TmapApiService tmapApiService) {
        this.tmapApiService = tmapApiService;
    }

    @GetMapping("/walking")
    public ResponseEntity<String> getWalkingRoute(
            @RequestParam double startLat,
            @RequestParam double startLng,
            @RequestParam double endLat,
            @RequestParam double endLng) {

        try {
            String routeData = tmapApiService.getWalkingRoute(startLat, startLng, endLat, endLng);
            return ResponseEntity.ok(routeData);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("{\"error\": \"" + e.getMessage() + "\"}");
        }
    }
}
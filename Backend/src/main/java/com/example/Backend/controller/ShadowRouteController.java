package com.example.Backend.controller;

import com.example.Backend.model.Route;
import com.example.Backend.model.SunPosition;
import com.example.Backend.service.ShadowRouteService;
import com.example.Backend.service.ShadowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/routes")
public class ShadowRouteController {

    @Autowired
    private ShadowRouteService shadowRouteService;

    @Autowired
    private ShadowService shadowService;

    /**
     * 태양 위치 정보 제공 API
     */
    @GetMapping("/sun-position")
    public ResponseEntity<SunPosition> getSunPosition(
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTime) {

        if (dateTime == null) {
            dateTime = LocalDateTime.now();
        }

        SunPosition sunPosition = shadowService.calculateSunPosition(latitude, longitude, dateTime);
        return ResponseEntity.ok(sunPosition);
    }

    /**
     * 그림자를 고려한 경로 API
     */
    @GetMapping("/shadow")
    public ResponseEntity<List<Route>> getShadowRoutes(
            @RequestParam double startLat,
            @RequestParam double startLng,
            @RequestParam double endLat,
            @RequestParam double endLng,
            @RequestParam(required = false, defaultValue = "true") boolean avoidShadow,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTime) {

        if (dateTime == null) {
            dateTime = LocalDateTime.now();
        }

        List<Route> routes = shadowRouteService.calculateShadowRoutes(
                startLat, startLng, endLat, endLng, avoidShadow, dateTime);

        return ResponseEntity.ok(routes);
    }
}
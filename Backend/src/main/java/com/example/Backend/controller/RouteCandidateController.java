package com.example.Backend.controller;

import com.example.Backend.model.RouteCandidate;
import com.example.Backend.service.RouteCandidateService;
import com.example.Backend.service.WeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/routes")
public class RouteCandidateController {

    private static final Logger logger = LoggerFactory.getLogger(RouteCandidateController.class);

    @Autowired
    private RouteCandidateService routeCandidateService;

    @Autowired
    private WeatherService weatherService;

    /**
     * 3개 후보 경로 제공 API
     */
    @GetMapping("/candidate-routes")
    public ResponseEntity<Map<String, Object>> getCandidateRoutes(
            @RequestParam double startLat,
            @RequestParam double startLng,
            @RequestParam double endLat,
            @RequestParam double endLng,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTime) {

        try {
            if (dateTime == null) {
                dateTime = LocalDateTime.now();
            }

            logger.info("=== 후보 경로 요청 ===");
            logger.info("출발: ({}, {}), 도착: ({}, {}), 시간: {}",
                    startLat, startLng, endLat, endLng, dateTime);

            // 경로 후보 생성
            List<RouteCandidate> candidates = routeCandidateService.generateCandidateRoutes(
                    startLat, startLng, endLat, endLng, dateTime);

            // 응답 데이터 구성
            Map<String, Object> response = new HashMap<>();
            response.put("candidates", candidates);
            response.put("totalCount", candidates.size());
            response.put("requestTime", dateTime);
            response.put("weatherMessage", weatherService.getRouteRecommendationMessage(startLat, startLng));

            // 추가 메타데이터
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("isNightTime", isNightTime(dateTime));
            metadata.put("weatherDescription", weatherService.getWeatherDescription(startLat, startLng));
            response.put("metadata", metadata);

            logger.info("후보 경로 응답 완료: {}개 경로", candidates.size());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("후보 경로 요청 처리 오류: " + e.getMessage(), e);

            // 오류 응답
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "경로 생성 중 오류가 발생했습니다");
            errorResponse.put("message", e.getMessage());
            errorResponse.put("candidates", List.of()); // 빈 리스트

            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * 특정 후보 경로의 상세 정보 조회
     */
    @GetMapping("/candidate-routes/{type}")
    public ResponseEntity<Map<String, Object>> getCandidateRouteDetail(
            @PathVariable String type,
            @RequestParam double startLat,
            @RequestParam double startLng,
            @RequestParam double endLat,
            @RequestParam double endLng,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTime) {

        try {
            if (dateTime == null) {
                dateTime = LocalDateTime.now();
            }

            logger.info("상세 경로 요청: 타입={}, 출발=({}, {}), 도착=({}, {})",
                    type, startLat, startLng, endLat, endLng);

            // 모든 후보 생성 후 해당 타입만 필터링
            List<RouteCandidate> allCandidates = routeCandidateService.generateCandidateRoutes(
                    startLat, startLng, endLat, endLng, dateTime);

            RouteCandidate selectedCandidate = allCandidates.stream()
                    .filter(candidate -> type.equals(candidate.getType()))
                    .findFirst()
                    .orElse(null);

            if (selectedCandidate == null) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("error", "해당 타입의 경로를 찾을 수 없습니다");
                errorResponse.put("requestedType", type);
                return ResponseEntity.notFound().build();
            }

            Map<String, Object> response = new HashMap<>();
            response.put("candidate", selectedCandidate);
            response.put("requestTime", dateTime);

            logger.info("상세 경로 응답 완료: 타입={}", type);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("상세 경로 요청 처리 오류: " + e.getMessage(), e);

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "상세 경로 조회 중 오류가 발생했습니다");
            errorResponse.put("message", e.getMessage());

            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * 경로 타입 목록 조회
     */
    @GetMapping("/candidate-types")
    public ResponseEntity<Map<String, Object>> getCandidateTypes() {
        Map<String, Object> response = new HashMap<>();

        Map<String, String> types = new HashMap<>();
        types.put("shortest", "최단경로");
        types.put("shade", "그늘이 많은경로");
        types.put("balanced", "균형경로");

        response.put("types", types);
        response.put("defaultType", "shortest");

        return ResponseEntity.ok(response);
    }

    /**
     * 시스템 상태 확인 (헬스체크)
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", LocalDateTime.now());
        health.put("service", "route-candidate");

        return ResponseEntity.ok(health);
    }

    /**
     * 밤 시간 판별 (유틸리티 메서드)
     */
    private boolean isNightTime(LocalDateTime dateTime) {
        int hour = dateTime.getHour();
        return hour < 6 || hour >= 18;
    }
}
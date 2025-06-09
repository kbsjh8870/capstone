package com.example.Backend.controller;

import com.example.Backend.model.Route;
import com.example.Backend.model.RouteAnalysis;
import com.example.Backend.model.RoutePoint;
import com.example.Backend.model.SunPosition;
import com.example.Backend.service.ShadowRouteService;
import com.example.Backend.service.ShadowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/routes")
public class ShadowRouteController {

    private static final Logger logger = LoggerFactory.getLogger(ShadowRouteController.class);

    @Autowired
    private ShadowRouteService shadowRouteService;

    @Autowired
    private ShadowService shadowService;

    private final Map<String, List<Route>> routeCache = new ConcurrentHashMap<>();

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

        logger.debug("=== 그림자 경로 API 요청 ===");
        logger.debug("시작: ({}, {}), 끝: ({}, {})", startLat, startLng, endLat, endLng);
        logger.debug("그림자 회피: {}, 시간: {}", avoidShadow, dateTime);

        List<Route> routes = shadowRouteService.calculateShadowRoutes(
                startLat, startLng, endLat, endLng, avoidShadow, dateTime);

        // *** 응답 전 최종 확인 디버깅 로그 ***
        logger.debug("=== 응답 전 최종 확인 ===");
        for (int i = 0; i < routes.size(); i++) {
            Route route = routes.get(i);
            logger.debug("경로 {}: 기본경로={}, 그림자회피={}, 그림자비율={}%, 포인트수={}",
                    i, route.isBasicRoute(), route.isAvoidShadow(), route.getShadowPercentage(), route.getPoints().size());

            if (!route.isBasicRoute()) {
                // 그림자 경로의 처음 10개 포인트 그림자 정보 확인
                List<RoutePoint> points = route.getPoints();
                logger.debug("  그림자 경로 상세 확인:");

                for (int j = 0; j < Math.min(10, points.size()); j++) {
                    RoutePoint point = points.get(j);
                    logger.debug("    응답 포인트 {}: 위치=({}, {}), 그림자={}",
                            j, point.getLat(), point.getLng(), point.isInShadow());
                }

                // 실제 그림자 포인트 개수 확인
                int shadowCount = 0;
                for (RoutePoint point : points) {
                    if (point.isInShadow()) shadowCount++;
                }
                logger.debug("  실제 그림자 포인트 개수: {}/{} (계산된 비율: {}%)",
                        shadowCount, points.size(), shadowCount * 100 / points.size());

                // 그림자 영역 정보
                if (route.getShadowAreas() != null) {
                    logger.debug("  그림자 영역 개수: {}", route.getShadowAreas().size());
                }
            }
        }

        logger.debug("총 {}개 경로 응답 준비 완료", routes.size());
        return ResponseEntity.ok(routes);
    }

    // (디버깅용)
    @GetMapping("/test-shadow")
    public ResponseEntity<String> testShadowCalculation(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTime) {

        if (dateTime == null) {
            dateTime = LocalDateTime.now();
        }

        shadowRouteService.testShadowCalculationAtPoint(lat, lng, dateTime);
        return ResponseEntity.ok("그림자 계산 테스트 완료. 로그를 확인하세요.");
    }

    /**
     * 다중 경로 옵션 제공 API
     */
    @GetMapping("/shadow/multiple")
    public ResponseEntity<List<Route>> getMultipleShadowRoutes(
            @RequestParam double startLat,
            @RequestParam double startLng,
            @RequestParam double endLat,
            @RequestParam double endLng,
            @RequestParam(required = false, defaultValue = "true") boolean preferShadow,
            @RequestParam(required = false, defaultValue = "5") int maxRoutes,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTime) {

        if (dateTime == null) {
            dateTime = LocalDateTime.now();
        }

        logger.info("=== 다중 경로 API 요청 ===");
        logger.info("시작: ({}, {}), 끝: ({}, {})", startLat, startLng, endLat, endLng);
        logger.info("그늘 선호: {}, 최대 경로 수: {}, 시간: {}", preferShadow, maxRoutes, dateTime);

        try {
            // 캐시 확인
            String cacheKey = String.format("%.6f,%.6f,%.6f,%.6f,%b,%s",
                    startLat, startLng, endLat, endLng, preferShadow, dateTime.toLocalDate());

            List<Route> cachedRoutes = routeCache.get(cacheKey);
            if (cachedRoutes != null) {
                logger.info("캐시된 경로 반환: {}개", cachedRoutes.size());
                return ResponseEntity.ok(cachedRoutes.stream()
                        .limit(maxRoutes)
                        .collect(Collectors.toList()));
            }

            // 새로운 경로 계산
            List<Route> routes = shadowRouteService.generateMultipleRouteOptions(
                    startLat, startLng, endLat, endLng, preferShadow, dateTime
            );

            // 캐시 저장 (10분간 유지)
            routeCache.put(cacheKey, routes);

            // 요청된 개수만큼 반환
            List<Route> limitedRoutes = routes.stream()
                    .limit(maxRoutes)
                    .collect(Collectors.toList());

            logger.info("다중 경로 생성 완료: {}개 경로 반환", limitedRoutes.size());

            return ResponseEntity.ok(limitedRoutes);

        } catch (Exception e) {
            logger.error("다중 경로 생성 오류", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.emptyList());
        }
    }

    /**
     * 경로 분석 정보 제공 API
     */
    @PostMapping("/analyze")
    public ResponseEntity<RouteAnalysis> analyzeRoute(@RequestBody Route route) {
        try {
            RouteAnalysis analysis = shadowRouteService.analyzeRouteDetails(route);
            return ResponseEntity.ok(analysis);
        } catch (Exception e) {
            logger.error("경로 분석 오류", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
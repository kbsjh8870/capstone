package com.example.Backend.controller;

import com.example.Backend.model.Route;
import com.example.Backend.model.RoutePoint;
import com.example.Backend.model.SunPosition;
import com.example.Backend.service.ShadowRouteService;
import com.example.Backend.service.ShadowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/routes")
public class ShadowRouteController {

    private static final Logger logger = LoggerFactory.getLogger(ShadowRouteController.class);

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
}
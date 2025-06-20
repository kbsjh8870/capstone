package com.example.Backend.service;

import com.example.Backend.model.Route;
import com.example.Backend.model.RoutePoint;
import com.example.Backend.model.ShadowArea;
import com.example.Backend.model.SunPosition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ShadowRouteService {

    @Autowired
    private TmapApiService tmapApiService;

    @Autowired
    private ShadowService shadowService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Logger logger = LoggerFactory.getLogger(ShadowRouteService.class);

    /**
     * 그림자를 고려한 대체 경로 계산
     */
    public List<Route> calculateShadowRoutes(
            double startLat, double startLng,
            double endLat, double endLng,
            boolean avoidShadow, LocalDateTime dateTime) {

        List<Route> routes = new ArrayList<>();

        try {
            // 1. 기본 경로 획득 (T맵 API)
            String tmapRouteJson = tmapApiService.getWalkingRoute(startLat, startLng, endLat, endLng);
            Route basicRoute = parseBasicRoute(tmapRouteJson);
            basicRoute.setBasicRoute(true);
            basicRoute.setDateTime(dateTime);
            routes.add(basicRoute);

            // 2. 태양 위치 계산
            SunPosition sunPos = shadowService.calculateSunPosition(startLat, startLng, dateTime);
            logger.debug("태양 위치: 고도={}도, 방위각={}도", sunPos.getAltitude(), sunPos.getAzimuth());

            // 3. 실제 DB 건물 데이터로 그림자 계산
            List<ShadowArea> shadowAreas = calculateBuildingShadows(startLat, startLng, endLat, endLng, sunPos);
            logger.debug("DB에서 가져온 건물 그림자 영역: {}개", shadowAreas.size());

            // 4. 그림자 경로 생성 (대폭 우회 전략 적용)
            Route shadowRoute = createEnhancedShadowRoute(startLat, startLng, endLat, endLng,
                    shadowAreas, sunPos, avoidShadow, dateTime);

            // 5. 실제 그림자 정보 계산 및 적용
            applyShadowInfoFromDB(shadowRoute, shadowAreas);

            shadowRoute.setShadowAreas(shadowAreas);
            shadowRoute.setAvoidShadow(avoidShadow);
            shadowRoute.setDateTime(dateTime);
            shadowRoute.setBasicRoute(false);

            routes.add(shadowRoute);

            logger.info("실제 DB 기반 그림자 경로 생성 완료: {}% 그림자", shadowRoute.getShadowPercentage());

            return routes;

        } catch (Exception e) {
            logger.error("그림자 경로 계산 오류: " + e.getMessage(), e);
            return routes;
        }
    }

    /**
     *  수정된 그림자 경로 생성 메서드
     */
    private Route createEnhancedShadowRoute(double startLat, double startLng, double endLat, double endLng,
                                            List<ShadowArea> shadowAreas, SunPosition sunPos,
                                            boolean avoidShadow, LocalDateTime dateTime) {
        try {
            logger.debug("=== 개선된 그림자 경로 생성 시작 ===");

            // 기본 경로 먼저 획득
            String baseRouteJson = tmapApiService.getWalkingRoute(startLat, startLng, endLat, endLng);
            Route baseRoute = parseBasicRoute(baseRouteJson);

            if (shadowAreas.isEmpty()) {
                logger.debug("그림자 영역이 없음. 기본 경로 사용");
                baseRoute.setAvoidShadow(avoidShadow);
                return baseRoute;
            }

            // 경유지 생성
            RoutePoint strategicWaypoint = createStrategicWaypoint(
                    baseRoute.getPoints(), sunPos, avoidShadow, shadowAreas);

            if (strategicWaypoint != null) {
                logger.debug("전략적 경유지 생성: ({}, {})", strategicWaypoint.getLat(), strategicWaypoint.getLng());

                // 경유지를 통한 새 경로 생성
                String waypointRouteJson = tmapApiService.getWalkingRouteWithWaypoint(
                        startLat, startLng,
                        strategicWaypoint.getLat(), strategicWaypoint.getLng(),
                        endLat, endLng);

                Route enhancedRoute = parseBasicRoute(waypointRouteJson);
                enhancedRoute.setAvoidShadow(avoidShadow);

                // 경로 품질 확인
                if (isRouteQualityAcceptable(baseRoute, enhancedRoute)) {
                    logger.debug("개선된 경로 생성 성공: {}개 포인트", enhancedRoute.getPoints().size());

                    //  단일 호출로 모든 그림자 정보 처리
                    applyShadowInfoWithWaypointCorrection(enhancedRoute, shadowAreas, strategicWaypoint);

                    return enhancedRoute;
                }
            }

            // 적절한 경로를 만들지 못한 경우 기본 경로 사용
            logger.debug("개선된 경로 생성 실패. 기본 경로 사용");
            baseRoute.setAvoidShadow(avoidShadow);
            return baseRoute;

        } catch (Exception e) {
            logger.error("개선된 그림자 경로 생성 오류: " + e.getMessage(), e);

            // 실패 시 기본 경로 반환
            try {
                String fallbackRouteJson = tmapApiService.getWalkingRoute(startLat, startLng, endLat, endLng);
                Route fallbackRoute = parseBasicRoute(fallbackRouteJson);
                fallbackRoute.setAvoidShadow(avoidShadow);
                return fallbackRoute;
            } catch (Exception ex) {
                return createSimplePath(startLat, startLng, endLat, endLng);
            }
        }
    }

    /**
     *  통합된 그림자 정보 적용 메서드 (중복 호출 방지)
     */
    private void applyShadowInfoWithWaypointCorrection(Route route, List<ShadowArea> shadowAreas, RoutePoint waypoint) {
        try {
            logger.debug("=== 통합 그림자 정보 적용 시작 ===");

            if (shadowAreas.isEmpty()) {
                for (RoutePoint point : route.getPoints()) {
                    point.setInShadow(false);
                }
                route.setShadowPercentage(0);
                logger.debug("그림자 영역이 없음. 모든 포인트를 햇빛으로 설정");
                return;
            }

            List<RoutePoint> points = route.getPoints();

            // 1차: 배치 처리로 기본 그림자 검사
            Map<Integer, Boolean> basicShadowResults = batchCheckBasicShadows(points, shadowAreas);

            // 2차: 배치 처리로 상세 분석
            Map<Integer, Boolean> detailedShadowResults = batchCheckDetailedShadows(points, shadowAreas);

            // 3차: 경유지 근처 특별 보정
            Map<Integer, Boolean> waypointShadowResults = batchCheckWaypointShadows(points, shadowAreas, waypoint);

            //  모든 결과 통합 적용
            int shadowCount = 0;
            for (int i = 0; i < points.size(); i++) {
                RoutePoint point = points.get(i);

                boolean isInShadow = basicShadowResults.getOrDefault(i, false) ||
                        detailedShadowResults.getOrDefault(i, false) ||
                        waypointShadowResults.getOrDefault(i, false);

                point.setInShadow(isInShadow);
                if (isInShadow) {
                    shadowCount++;
                    logger.debug("최종 그림자 포인트 {}: ({}, {}) - inShadow={}",
                            i, point.getLat(), point.getLng(), point.isInShadow());
                }
            }

            int shadowPercentage = points.size() > 0 ? (shadowCount * 100 / points.size()) : 0;
            route.setShadowPercentage(shadowPercentage);

            logger.info("통합 그림자 정보 적용 완료: {}% ({}/{}개 포인트)",
                    shadowPercentage, shadowCount, points.size());

            //  최종 검증 로깅
            logger.debug("=== 최종 그림자 포인트 검증 ===");
            for (int i = 0; i < Math.min(points.size(), 20); i++) {
                RoutePoint point = points.get(i);
                logger.debug("포인트 {}: 위치=({}, {}), inShadow={}",
                        i, point.getLat(), point.getLng(), point.isInShadow());
            }

        } catch (Exception e) {
            logger.error("통합 그림자 정보 적용 오류: " + e.getMessage(), e);
            for (RoutePoint point : route.getPoints()) {
                point.setInShadow(false);
            }
            route.setShadowPercentage(0);
        }
    }

    /**
     * 경유지 근처 배치 그림자 검사
     */
    private Map<Integer, Boolean> batchCheckWaypointShadows(List<RoutePoint> points,
                                                            List<ShadowArea> shadowAreas,
                                                            RoutePoint waypoint) {
        Map<Integer, Boolean> results = new HashMap<>();

        try {
            // 경유지 근처 20개 포인트 범위 찾기
            int waypointIndex = findClosestPointIndex(points, waypoint);
            int startIdx = Math.max(0, waypointIndex - 10);
            int endIdx = Math.min(points.size() - 1, waypointIndex + 10);

            logger.debug("경유지 근처 배치 검사: 포인트 {} ~ {} (경유지: {})", startIdx, endIdx, waypointIndex);

            // 경유지 근처 포인트들만 MULTIPOINT로 변환
            StringBuilder waypointPointsWkt = new StringBuilder("MULTIPOINT(");
            List<Integer> waypointIndices = new ArrayList<>();

            for (int i = startIdx; i <= endIdx; i++) {
                RoutePoint point = points.get(i);
                if (waypointIndices.size() > 0) waypointPointsWkt.append(",");
                waypointPointsWkt.append(String.format("(%f %f)", point.getLng(), point.getLat()));
                waypointIndices.add(i);
            }
            waypointPointsWkt.append(")");

            // 각 그림자 영역에 대해 경유지 근처 포인트들을 관대하게 검사
            for (ShadowArea shadowArea : shadowAreas) {
                String shadowGeom = shadowArea.getShadowGeometry();
                if (shadowGeom == null || shadowGeom.isEmpty()) continue;

                String waypointSql = """
                WITH shadow_geom AS (
                    SELECT ST_GeomFromGeoJSON(?) as geom
                ),
                waypoint_points AS (
                    SELECT 
                        (ST_Dump(ST_GeomFromText(?, 4326))).geom as point_geom,
                        generate_series(1, ST_NumGeometries(ST_GeomFromText(?, 4326))) as point_index
                )
                SELECT 
                    wp.point_index as local_index,
                    ST_DWithin(sg.geom, wp.point_geom, 0.0015) as is_near_shadow  -- 165m
                FROM waypoint_points wp, shadow_geom sg
                WHERE ST_DWithin(sg.geom, wp.point_geom, 0.0015)
                ORDER BY wp.point_index
                """;

                try {
                    List<Map<String, Object>> waypointResults = jdbcTemplate.queryForList(waypointSql,
                            shadowGeom, waypointPointsWkt.toString(), waypointPointsWkt.toString());

                    // 로컬 인덱스를 전체 경로 인덱스로 매핑
                    for (Map<String, Object> row : waypointResults) {
                        int localIndex = ((Number) row.get("local_index")).intValue() - 1;
                        boolean isNearShadow = (Boolean) row.get("is_near_shadow");

                        if (isNearShadow && localIndex < waypointIndices.size()) {
                            int globalIndex = waypointIndices.get(localIndex);
                            results.put(globalIndex, true);
                            logger.debug("경유지 근처 그림자 감지: 포인트 {} (로컬 {})", globalIndex, localIndex);
                        }
                    }

                } catch (Exception e) {
                    logger.warn("경유지 그림자 영역 {}에 대한 검사 실패: {}", shadowArea.getId(), e.getMessage());
                }
            }

            logger.debug("경유지 근처 배치 그림자 검사 완료: {}개 포인트 감지", results.size());

        } catch (Exception e) {
            logger.error("경유지 근처 배치 그림자 검사 오류: " + e.getMessage(), e);
        }

        return results;
    }

    /**
     * 전략적 경유지 생성
     */
    private RoutePoint createStrategicWaypoint(List<RoutePoint> basePoints, SunPosition sunPos,
                                               boolean avoidShadow, List<ShadowArea> shadowAreas) {
        if (basePoints.size() < 5) return null;

        try {
            RoutePoint startPoint = basePoints.get(0);
            RoutePoint endPoint = basePoints.get(basePoints.size() - 1);
            RoutePoint middlePoint = basePoints.get(basePoints.size() / 2);

            // 목적지 방향 계산 (북쪽 기준 0-360도)
            double destinationDirection = calculateBearing(startPoint, endPoint);

            // 원하는 경유지 방향 계산 (태양/그림자 방향)
            double preferredDirection;
            if (avoidShadow) {
                preferredDirection = sunPos.getAzimuth(); // 태양 방향
            } else {
                preferredDirection = (sunPos.getAzimuth() + 180) % 360; // 그림자 방향
            }

            // 핵심: 목적지 방향 제약 적용
            double constrainedDirection = constrainDirectionToDestination(
                    preferredDirection, destinationDirection);

            // 경유지 거리 (짧게 유지)
            double detourMeters = 40.0;

            // 지리적 좌표로 변환
            double directionRad = Math.toRadians(constrainedDirection);
            double latDegreeInMeters = 111000.0;
            double lngDegreeInMeters = 111000.0 * Math.cos(Math.toRadians(middlePoint.getLat()));

            double latOffset = detourMeters * Math.cos(directionRad) / latDegreeInMeters;
            double lngOffset = detourMeters * Math.sin(directionRad) / lngDegreeInMeters;

            RoutePoint waypoint = new RoutePoint();
            waypoint.setLat(middlePoint.getLat() + latOffset);
            waypoint.setLng(middlePoint.getLng() + lngOffset);

            // 경유지가 목적지 방향으로 진행하는지 검증
            if (!isWaypointProgressive(startPoint, waypoint, endPoint)) {
                logger.debug("경유지가 목적지 방향으로 진행하지 않음 - 거부");
                return null;
            }

            logger.debug("제약된 경유지 생성: 원하는방향={}도, 목적지방향={}도, 최종방향={}도",
                    preferredDirection, destinationDirection, constrainedDirection);

            return waypoint;

        } catch (Exception e) {
            logger.error("제약된 경유지 계산 오류: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 목적지 방향을 고려하여 경유지 방향 제약
     */
    private double constrainDirectionToDestination(double preferredDirection, double destinationDirection) {
        try {
            // 목적지 방향 ±120도 범위 내에서만 경유지 설정 허용
            double maxAngleDiff = 120.0;

            // 두 방향 간의 각도 차이 계산 (0-180도)
            double angleDiff = Math.abs(preferredDirection - destinationDirection);
            if (angleDiff > 180) {
                angleDiff = 360 - angleDiff;
            }

            // 각도 차이가 허용 범위 내면 그대로 사용
            if (angleDiff <= maxAngleDiff) {
                logger.debug("방향 제약 통과: 차이={}도", angleDiff);
                return preferredDirection;
            }

            // 허용 범위를 벗어나면 가장 가까운 허용 방향으로 조정
            double constrainedDirection;
            if (preferredDirection > destinationDirection) {
                if (preferredDirection - destinationDirection <= 180) {
                    // 시계방향으로 가까움
                    constrainedDirection = (destinationDirection + maxAngleDiff) % 360;
                } else {
                    // 반시계방향으로 가까움
                    constrainedDirection = (destinationDirection - maxAngleDiff + 360) % 360;
                }
            } else {
                if (destinationDirection - preferredDirection <= 180) {
                    // 반시계방향으로 가까움
                    constrainedDirection = (destinationDirection - maxAngleDiff + 360) % 360;
                } else {
                    // 시계방향으로 가까움
                    constrainedDirection = (destinationDirection + maxAngleDiff) % 360;
                }
            }

            logger.debug("방향 제약 적용: 원래={}도 → 제약={}도 (목적지={}도)",
                    preferredDirection, constrainedDirection, destinationDirection);

            return constrainedDirection;

        } catch (Exception e) {
            logger.error("방향 제약 계산 오류: " + e.getMessage(), e);
            return destinationDirection; // 오류 시 목적지 방향 반환
        }
    }

    /**
     * 방위각 계산 (북쪽 기준 0-360도)
     */
    private double calculateBearing(RoutePoint from, RoutePoint to) {
        double deltaLng = Math.toRadians(to.getLng() - from.getLng());
        double fromLat = Math.toRadians(from.getLat());
        double toLat = Math.toRadians(to.getLat());

        double y = Math.sin(deltaLng) * Math.cos(toLat);
        double x = Math.cos(fromLat) * Math.sin(toLat) -
                Math.sin(fromLat) * Math.cos(toLat) * Math.cos(deltaLng);

        double bearing = Math.toDegrees(Math.atan2(y, x));
        return (bearing + 360) % 360; // 0-360도 범위로 정규화
    }


    /**
     * 경유지가 목적지 방향으로 진행하는지 검증
     */
    private boolean isWaypointProgressive(RoutePoint start, RoutePoint waypoint, RoutePoint end) {
        try {
            // 출발지에서 경유지까지의 거리
            double distanceToWaypoint = calculateDistance(
                    start.getLat(), start.getLng(),
                    waypoint.getLat(), waypoint.getLng());

            // 출발지에서 목적지까지의 거리
            double directDistance = calculateDistance(
                    start.getLat(), start.getLng(),
                    end.getLat(), end.getLng());

            // 경유지에서 목적지까지의 거리
            double waypointToEnd = calculateDistance(
                    waypoint.getLat(), waypoint.getLng(),
                    end.getLat(), end.getLng());

            // 경유지를 거친 총 거리
            double totalViaWaypoint = distanceToWaypoint + waypointToEnd;

            // 경유지를 거친 거리가 직선 거리의 150% 이하여야 함
            double detourRatio = totalViaWaypoint / directDistance;
            if (detourRatio > 1.5) {
                logger.debug("경유지 우회 비율 과다: {}% > 150%", (int)(detourRatio * 100));
                return false;
            }

            // 경유지가 출발지보다 목적지에 더 가까워야 함
            if (waypointToEnd >= directDistance) {
                logger.debug("경유지가 목적지에서 더 멀어짐: 경유지→목적지={}m, 직선거리={}m",
                        (int)waypointToEnd, (int)directDistance);
                return false;
            }

            logger.debug("경유지 진행성 검증 통과: 우회비율={}%, 거리단축={}m",
                    (int)(detourRatio * 100), (int)(directDistance - waypointToEnd));

            return true;

        } catch (Exception e) {
            logger.error("경유지 진행성 검증 오류: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 경로 품질 검증
     */
    private boolean isRouteQualityAcceptable(Route baseRoute, Route shadowRoute) {
        try {
            logger.debug("=== 경로 품질 검증 시작 ===");

            // 1. 거리 비율 검증 (150% 이하로 제한 강화)
            double distanceRatio = shadowRoute.getDistance() / baseRoute.getDistance();
            if (distanceRatio > 1.5) {
                logger.debug("❌ 거리 초과: 기본={}m, 생성={}m ({}% 증가, 허용: 150%)",
                        (int)baseRoute.getDistance(), (int)shadowRoute.getDistance(),
                        (int)((distanceRatio - 1) * 100));
                return false;
            }

            // 2. 포인트 수 합리성 검증
            if (shadowRoute.getPoints().size() < baseRoute.getPoints().size() * 0.5) {
                logger.debug("X 경로 포인트 수 부족: 기본={}개, 생성={}개",
                        baseRoute.getPoints().size(), shadowRoute.getPoints().size());
                return false;
            }

            // 3. 경로 연속성 검증
            if (!isRouteContinuous(shadowRoute)) {
                logger.debug("X 경로 연속성 검증 실패");
                return false;
            }

            // 4. 목적지 근접성 검증
            if (!isDestinationReachable(shadowRoute)) {
                logger.debug("X 목적지 근접성 검증 실패");
                return false;
            }

            // 5. 역방향 이동 검증
            if (!isProgressiveRoute(shadowRoute)) {
                logger.debug("X 역방향 이동 감지");
                return false;
            }

            logger.debug("✅ 경로 품질 검증 통과: 거리 비율 {}%", (int)((distanceRatio - 1) * 100));
            return true;

        } catch (Exception e) {
            logger.error("경로 품질 검증 오류: " + e.getMessage(), e);
            return false;
        }
    }


    /**
     * 경로 연속성 검증 (포인트 간 거리가 비정상적으로 크지 않은지 확인)
     */
    private boolean isRouteContinuous(Route route) {
        try {
            List<RoutePoint> points = route.getPoints();
            if (points.size() < 2) return true;

            int discontinuousCount = 0;
            final double MAX_SEGMENT_DISTANCE = 500.0; // 500m 이상 점프는 비정상

            for (int i = 0; i < points.size() - 1; i++) {
                RoutePoint current = points.get(i);
                RoutePoint next = points.get(i + 1);

                double segmentDistance = calculateDistance(
                        current.getLat(), current.getLng(),
                        next.getLat(), next.getLng()
                );

                if (segmentDistance > MAX_SEGMENT_DISTANCE) {
                    discontinuousCount++;
                    logger.debug("긴 구간 발견: {}m (포인트 {} → {})",
                            (int)segmentDistance, i, i + 1);
                }
            }

            // 전체 구간의 5% 이상이 비정상적으로 길면 실패
            double discontinuousRatio = (double) discontinuousCount / (points.size() - 1);
            boolean isContinuous = discontinuousRatio <= 0.05;

            logger.debug("경로 연속성: {}% 비정상 구간 (허용: 5%)", (int)(discontinuousRatio * 100));
            return isContinuous;

        } catch (Exception e) {
            logger.error("경로 연속성 검증 오류: " + e.getMessage(), e);
            return true; // 오류 시 허용
        }
    }

    /**
     * 목적지 근접성 검증 (마지막 포인트가 목적지 근처에 있는지 확인)
     */
    private boolean isDestinationReachable(Route route) {
        try {
            List<RoutePoint> points = route.getPoints();
            if (points.isEmpty()) return false;

            // 경로의 마지막 포인트가 있는지만 확인
            RoutePoint lastPoint = points.get(points.size() - 1);

            // 좌표가 유효한 범위 내에 있는지 확인
            if (Math.abs(lastPoint.getLat()) > 90 || Math.abs(lastPoint.getLng()) > 180) {
                logger.debug("X 마지막 포인트 좌표 무효: ({}, {})",
                        lastPoint.getLat(), lastPoint.getLng());
                return false;
            }

            return true;

        } catch (Exception e) {
            logger.error("목적지 근접성 검증 오류: " + e.getMessage(), e);
            return true;
        }
    }

    /**
     * 전진성 검증 강화 (역방향 이동이나 과도한 우회 방지)
     */
    private boolean isProgressiveRoute(Route route) {
        try {
            List<RoutePoint> points = route.getPoints();
            if (points.size() < 5) return true;

            RoutePoint start = points.get(0);
            RoutePoint end = points.get(points.size() - 1);

            // 전체적인 목적지 방향 계산
            double overallDirection = Math.atan2(
                    end.getLng() - start.getLng(),
                    end.getLat() - start.getLat()
            );

            // 경로 구간들의 방향성 분석
            int forwardSegments = 0;
            int backwardSegments = 0;
            int totalSegments = 0;

            // 10개 포인트마다 샘플링하여 분석
            int stepSize = Math.max(1, points.size() / 20); // 최대 20개 샘플
            for (int i = 0; i < points.size() - stepSize; i += stepSize) {
                RoutePoint current = points.get(i);
                RoutePoint next = points.get(Math.min(i + stepSize, points.size() - 1));

                double segmentDirection = Math.atan2(
                        next.getLng() - current.getLng(),
                        next.getLat() - current.getLat()
                );

                // 전체 방향과의 각도 차이 계산
                double angleDiff = Math.abs(segmentDirection - overallDirection);
                if (angleDiff > Math.PI) {
                    angleDiff = 2 * Math.PI - angleDiff;
                }

                if (angleDiff <= Math.PI / 2) { // 90도 이내
                    forwardSegments++;
                } else {
                    backwardSegments++;
                    logger.debug("--- 역방향 구간 감지: 포인트 {} → {}, 각도차이: {}도",
                            i, i + stepSize, Math.toDegrees(angleDiff));
                }
                totalSegments++;
            }

            // 75% 이상이 전진 방향이어야 함
            double forwardRatio = totalSegments > 0 ? (double) forwardSegments / totalSegments : 1.0;
            boolean isProgressive = forwardRatio >= 0.75;

            logger.debug("경로 전진성: {}% 전진 구간 (기준: 75%)", (int)(forwardRatio * 100));

            return isProgressive;

        } catch (Exception e) {
            logger.error("전진성 검증 오류: " + e.getMessage(), e);
            return true;
        }
    }

    /**
     * 실제 DB 그림자 정보를 경로에 적용
     */
    public void applyShadowInfoFromDB(Route route, List<ShadowArea> shadowAreas) {
        if (shadowAreas.isEmpty()) {
            for (RoutePoint point : route.getPoints()) {
                point.setInShadow(false);
            }
            route.setShadowPercentage(0);
            logger.debug("그림자 영역이 없음. 모든 포인트를 햇빛으로 설정");
            return;
        }

        try {
            List<RoutePoint> points = route.getPoints();

            // 배치 처리로 기본 그림자 검사
            Map<Integer, Boolean> basicShadowResults = batchCheckBasicShadows(points, shadowAreas);

            // 1차 결과 적용
            int basicShadowCount = 0;
            for (int i = 0; i < points.size(); i++) {
                RoutePoint point = points.get(i);
                boolean isInShadow = basicShadowResults.getOrDefault(i, false);
                point.setInShadow(isInShadow);
                if (isInShadow) basicShadowCount++;
            }

            logger.debug("1차 배치 그림자 검사 완료: {}개 포인트 감지", basicShadowCount);

            // 배치 처리로 상세 분석
            analyzeRouteDetailedShadows(route, shadowAreas);

            // 최종 통계 (analyzeRouteDetailedShadows에서 이미 계산됨)
            int finalShadowCount = 0;
            for (RoutePoint point : points) {
                if (point.isInShadow()) finalShadowCount++;
            }

            logger.info("최종 배치 처리 완료: {}% ({}/{}개 포인트)",
                    route.getShadowPercentage(), finalShadowCount, points.size());

        } catch (Exception e) {
            logger.error("배치 처리 그림자 정보 적용 오류: " + e.getMessage(), e);
            for (RoutePoint point : route.getPoints()) {
                point.setInShadow(false);
            }
            route.setShadowPercentage(0);
        }
    }

    /**
     *  배치 처리로 기본 그림자 검사
     */
    private Map<Integer, Boolean> batchCheckBasicShadows(List<RoutePoint> points, List<ShadowArea> shadowAreas) {
        Map<Integer, Boolean> results = new HashMap<>();

        try {
            String mergedShadows = createShadowUnion(shadowAreas);

            // 모든 포인트를 MULTIPOINT로 변환
            StringBuilder pointsWkt = new StringBuilder("MULTIPOINT(");
            for (int i = 0; i < points.size(); i++) {
                RoutePoint point = points.get(i);
                if (i > 0) pointsWkt.append(",");
                pointsWkt.append(String.format("(%f %f)", point.getLng(), point.getLat()));
            }
            pointsWkt.append(")");

            // 더 관대한 기준으로 그림자 검사 (특히 경유지 근처)
            String batchSql = """
            WITH shadow_geom AS (
                SELECT ST_GeomFromGeoJSON(?) as geom
            ),
            route_points AS (
                SELECT 
                    (ST_Dump(ST_GeomFromText(?, 4326))).geom as point_geom,
                    generate_series(1, ST_NumGeometries(ST_GeomFromText(?, 4326))) as point_index
            )
            SELECT 
                rp.point_index - 1 as index,
                CASE 
                    WHEN ST_Contains(sg.geom, rp.point_geom) THEN true
                    WHEN ST_DWithin(sg.geom, rp.point_geom, 0.0003) THEN true  -- 약 33m
                    WHEN ST_DWithin(sg.geom, rp.point_geom, 0.0005) THEN true  -- 약 55m  
                    WHEN ST_DWithin(sg.geom, rp.point_geom, 0.0008) THEN true  -- 약 88m (경유지용)
                    ELSE false
                END as in_shadow,
                ST_Distance(sg.geom, rp.point_geom) as distance_to_shadow
            FROM route_points rp, shadow_geom sg
            ORDER BY rp.point_index
            """;

            List<Map<String, Object>> batchResults = jdbcTemplate.queryForList(batchSql,
                    mergedShadows, pointsWkt.toString(), pointsWkt.toString());

            // 결과 매핑
            for (Map<String, Object> row : batchResults) {
                int index = ((Number) row.get("index")).intValue();
                boolean inShadow = (Boolean) row.get("in_shadow");
                double distance = ((Number) row.get("distance_to_shadow")).doubleValue();

                results.put(index, inShadow);

                //  경유지 근처 포인트 디버깅
                if (inShadow && distance > 0.0005) {
                    logger.debug("확장 범위에서 그림자 감지: 포인트={}, 거리={}m", index, distance * 111000);
                }
            }

            logger.debug("확장 범위 그림자 검사 완료: {}개 포인트 처리", results.size());

        } catch (Exception e) {
            logger.error("확장 범위 그림자 검사 오류: " + e.getMessage(), e);
        }

        return results;
    }


    /**
     *  경유지와 가장 가까운 경로 포인트 찾기
     */
    private int findClosestPointIndex(List<RoutePoint> points, RoutePoint waypoint) {
        int closestIndex = 0;
        double minDistance = Double.MAX_VALUE;

        for (int i = 0; i < points.size(); i++) {
            RoutePoint point = points.get(i);
            double distance = Math.pow(point.getLat() - waypoint.getLat(), 2) +
                    Math.pow(point.getLng() - waypoint.getLng(), 2);

            if (distance < minDistance) {
                minDistance = distance;
                closestIndex = i;
            }
        }

        return closestIndex;
    }


    private void analyzeRouteDetailedShadows(Route route, List<ShadowArea> shadowAreas) {
        try {
            logger.debug("=== 배치 처리 상세 그림자 분석 시작 ===");

            List<RoutePoint> points = route.getPoints();
            if (points.isEmpty()) return;

            // 배치 처리로 모든 포인트를 한 번에 검사
            Map<Integer, Boolean> detailedShadowResults = batchCheckDetailedShadows(points, shadowAreas);

            // 결과 적용 (기존 그림자 정보 + 새로 발견한 그림자)
            int newShadowCount = 0;
            for (int i = 0; i < points.size(); i++) {
                RoutePoint point = points.get(i);

                Boolean isDetailedShadow = detailedShadowResults.get(i);
                if (isDetailedShadow != null && isDetailedShadow && !point.isInShadow()) {
                    point.setInShadow(true);
                    newShadowCount++;
                    logger.debug("포인트 {}에서 배치 분석으로 그림자 감지: ({}, {})",
                            i, point.getLat(), point.getLng());
                }
            }

            // 그림자 비율 재계산
            int totalShadowCount = 0;
            for (RoutePoint point : points) {
                if (point.isInShadow()) totalShadowCount++;
            }

            int newShadowPercentage = points.size() > 0 ? (totalShadowCount * 100 / points.size()) : 0;
            route.setShadowPercentage(newShadowPercentage);

            logger.info("배치 처리 상세 분석 완료: {}% 그림자 ({}/{}개 포인트) - 새로 발견: {}개",
                    newShadowPercentage, totalShadowCount, points.size(), newShadowCount);

        } catch (Exception e) {
            logger.error("배치 처리 상세 그림자 분석 오류: " + e.getMessage(), e);
        }
    }

    /**
     *  배치 처리로 상세 그림자 검사 (기존 개별 검사를 배치로 변경)
     */
    private Map<Integer, Boolean> batchCheckDetailedShadows(List<RoutePoint> points, List<ShadowArea> shadowAreas) {
        Map<Integer, Boolean> results = new HashMap<>();

        try {
            // 모든 포인트를 MULTIPOINT로 변환
            StringBuilder pointsWkt = new StringBuilder("MULTIPOINT(");
            for (int i = 0; i < points.size(); i++) {
                RoutePoint point = points.get(i);
                if (i > 0) pointsWkt.append(",");
                pointsWkt.append(String.format("(%f %f)", point.getLng(), point.getLat()));
            }
            pointsWkt.append(")");

            // 각 그림자 영역에 대해 배치로 모든 포인트 검사
            for (ShadowArea shadowArea : shadowAreas) {
                String shadowGeom = shadowArea.getShadowGeometry();
                if (shadowGeom == null || shadowGeom.isEmpty()) continue;

                // 한 번의 쿼리로 이 그림자 영역과 모든 포인트의 관계 확인
                String batchSql = """
                WITH shadow_geom AS (
                    SELECT ST_GeomFromGeoJSON(?) as geom
                ),
                route_points AS (
                    SELECT 
                        (ST_Dump(ST_GeomFromText(?, 4326))).geom as point_geom,
                        generate_series(1, ST_NumGeometries(ST_GeomFromText(?, 4326))) as point_index
                )
                SELECT 
                    rp.point_index - 1 as index,
                    ST_DWithin(sg.geom, rp.point_geom, 0.0007) as is_near_shadow
                FROM route_points rp, shadow_geom sg
                WHERE ST_DWithin(sg.geom, rp.point_geom, 0.0007)
                ORDER BY rp.point_index
                """;

                try {
                    List<Map<String, Object>> batchResults = jdbcTemplate.queryForList(batchSql,
                            shadowGeom, pointsWkt.toString(), pointsWkt.toString());

                    // 이 그림자 영역에서 감지된 포인트들 기록
                    for (Map<String, Object> row : batchResults) {
                        int index = ((Number) row.get("index")).intValue();
                        boolean isNearShadow = (Boolean) row.get("is_near_shadow");

                        if (isNearShadow) {
                            results.put(index, true);
                        }
                    }

                } catch (Exception e) {
                    logger.warn("그림자 영역 {}에 대한 배치 검사 실패: {}", shadowArea.getId(), e.getMessage());
                }
            }

            logger.debug("배치 상세 그림자 검사 완료: {}개 포인트가 그림자로 감지", results.size());

        } catch (Exception e) {
            logger.error("배치 상세 그림자 검사 오류: " + e.getMessage(), e);
        }

        return results;
    }


    /**
     *  (디버깅용) 그림자 계산 테스트 메서드
     */
    public void testShadowCalculationAtPoint(double lat, double lng, LocalDateTime dateTime) {
        try {
            SunPosition sunPos = shadowService.calculateSunPosition(lat, lng, dateTime);

            logger.info("=== 그림자 계산 테스트 ===");
            logger.info("위치: ({}, {})", lat, lng);
            logger.info("시간: {}", dateTime);
            logger.info("태양 위치: 고도={}도, 방위각={}도", sunPos.getAltitude(), sunPos.getAzimuth());

            // 해당 지점 주변 건물 조회
            String buildingQuery = """
            SELECT id, "A16" as height, ST_AsText(geom) as geom_wkt
            FROM public."AL_D010_26_20250304" 
            WHERE ST_DWithin(geom, ST_SetSRID(ST_MakePoint(?, ?), 4326), 0.001)
            AND "A16" > 3
            ORDER BY "A16" DESC
            LIMIT 10
            """;

            List<Map<String, Object>> buildings = jdbcTemplate.queryForList(buildingQuery, lng, lat);
            logger.info("주변 건물 {}개 발견:", buildings.size());

            for (Map<String, Object> building : buildings) {
                logger.info("  건물 ID: {}, 높이: {}m",
                        building.get("id"), building.get("height"));
            }

            // 그림자 계산
            List<ShadowArea> shadows = calculateBuildingShadows(lat, lng, lat, lng, sunPos);
            logger.info("계산된 그림자 영역: {}개", shadows.size());

        } catch (Exception e) {
            logger.error("그림자 테스트 오류: " + e.getMessage(), e);
        }
    }


    /**
     * 건물 그림자 계산
     */
    public List<ShadowArea> calculateBuildingShadows(
            double startLat, double startLng, double endLat, double endLng, SunPosition sunPos) {

        try {
            logger.info("선택된 시간의 태양 위치 분석:");
            logger.info("  - 태양 고도: {:.2f}도", sunPos.getAltitude());
            logger.info("  - 태양 방위각: {:.2f}도", sunPos.getAzimuth());

            // 태양 위치에 따른 그림자 가능성 판단
            if (sunPos.getAltitude() < -10) {
                logger.info("  - 태양이 지평선 아래 → 그림자 계산 제외");
                return new ArrayList<>();
            } else if (sunPos.getAltitude() < 10) {
                logger.info("  - 낮은 태양 고도 → 매우 긴 그림자 생성");
            } else if (sunPos.getAltitude() > 60) {
                logger.info("  - 높은 태양 고도 → 짧은 그림자 생성");
            } else {
                logger.info("  - 중간 태양 고도 → 적당한 그림자 생성");
            }

            double shadowDirection = (sunPos.getAzimuth() + 180) % 360;

            // 그림자 길이 계산 - 더 현실적으로
            double shadowLength;
            if (sunPos.getAltitude() <= 5) {
                shadowLength = 1000; // 저녁 시간에는 매우 긴 그림자
                logger.info("  - 그림자 길이: {}m (매우 긴 그림자)", shadowLength);
            } else {
                double tanValue = Math.tan(Math.toRadians(sunPos.getAltitude()));
                shadowLength = Math.min(2000, Math.max(50, 100 / tanValue));
                logger.info("  - 그림자 길이: {:.1f}m (계산된 길이)", shadowLength);
            }

            logger.info("  - 그림자 방향: {:.1f}도", shadowDirection);
            logger.info("건물 그림자 계산 시작...");

            // 기존 SQL 쿼리 실행
            String sql = """
        WITH route_area AS (
            SELECT ST_Buffer(
                ST_MakeLine(
                    ST_SetSRID(ST_MakePoint(?, ?), 4326),
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)
                ), 0.008
            ) as geom
        ),
        enhanced_building_shadows AS (
            SELECT 
                b.id,
                b."A16" as height,
                ST_AsGeoJSON(b.geom) as building_geom,
                ST_AsGeoJSON(
                    ST_Union(ARRAY[
                        b.geom,
                        ST_Translate(
                            b.geom,
                            (? * 0.5) * cos(radians(?)) / (111320.0 * cos(radians(ST_Y(ST_Centroid(b.geom))))),
                            (? * 0.5) * sin(radians(?)) / 110540.0
                        ),
                        ST_Translate(
                            b.geom,
                            ? * cos(radians(?)) / (111320.0 * cos(radians(ST_Y(ST_Centroid(b.geom))))),
                            ? * sin(radians(?)) / 110540.0
                        ),
                        ST_Translate(
                            b.geom,
                            (? * (b."A16" / 50.0)) * cos(radians(?)) / (111320.0 * cos(radians(ST_Y(ST_Centroid(b.geom))))),
                            (? * (b."A16" / 50.0)) * sin(radians(?)) / 110540.0
                        )
                    ])
                ) as shadow_geom
            FROM public."AL_D010_26_20250304" b, route_area r
            WHERE ST_Intersects(b.geom, r.geom)
              AND b."A16" > 2
            ORDER BY 
                ST_Distance(b.geom, r.geom) ASC,
                b."A16" DESC
            LIMIT 100
        )
        SELECT id, height, building_geom, shadow_geom
        FROM enhanced_building_shadows
        """;

            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql,
                    startLng, startLat, endLng, endLat,  // route_area
                    shadowLength, shadowDirection,        // 50% 그림자
                    shadowLength, shadowDirection,
                    shadowLength, shadowDirection,        // 100% 그림자
                    shadowLength, shadowDirection,
                    shadowLength, shadowDirection,        // 높이 비례 그림자
                    shadowLength, shadowDirection);

            List<ShadowArea> shadowAreas = new ArrayList<>();
            for (Map<String, Object> row : results) {
                ShadowArea area = new ShadowArea();
                area.setId(((Number) row.get("id")).longValue());
                area.setHeight(((Number) row.get("height")).doubleValue());
                area.setBuildingGeometry((String) row.get("building_geom"));
                area.setShadowGeometry((String) row.get("shadow_geom"));
                shadowAreas.add(area);
            }

            logger.info("건물 그림자 계산 완료:");
            logger.info("  - 분석된 건물 수: {}개", shadowAreas.size());
            logger.info("  - 적용된 그림자 길이: {:.1f}m", shadowLength);
            logger.info("  - 그림자 방향: {:.1f}도", shadowDirection);

            return shadowAreas;

        } catch (Exception e) {
            logger.error("태양 위치 기반 그림자 계산 오류: " + e.getMessage(), e);
            return new ArrayList<>();
        }
    }


    /**
     * 그림자 영역들을 하나의 GeoJSON으로 병합
     */
    private String createShadowUnion(List<ShadowArea> shadowAreas) {
        if (shadowAreas == null || shadowAreas.isEmpty()) {
            return "{\"type\":\"GeometryCollection\",\"geometries\":[]}";
        }

        List<ShadowArea> limitedAreas = shadowAreas.size() > 50 ?
                shadowAreas.subList(0, 50) : shadowAreas;

        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"GeometryCollection\",\"geometries\":[");

        boolean hasValidGeometry = false;
        for (int i = 0; i < limitedAreas.size(); i++) {
            ShadowArea area = limitedAreas.get(i);
            String shadowGeom = area.getShadowGeometry();

            if (shadowGeom != null && !shadowGeom.isEmpty() && !shadowGeom.equals("null")) {
                if (hasValidGeometry) {
                    sb.append(",");
                }
                sb.append(shadowGeom);
                hasValidGeometry = true;
            }
        }

        sb.append("]}");

        if (!hasValidGeometry) {
            return "{\"type\":\"GeometryCollection\",\"geometries\":[]}";
        }

        logger.debug("그림자 영역 병합 완료: {}개 영역 사용", limitedAreas.size());
        return sb.toString();
    }

    /**
     * 기본 T맵 경로 파싱
     */
    public Route parseBasicRoute(String tmapRouteJson) {
        Route route = new Route();
        List<RoutePoint> points = new ArrayList<>();

        try {
            JsonNode rootNode = objectMapper.readTree(tmapRouteJson);
            JsonNode features = rootNode.path("features");

            if (features.isEmpty()) {
                logger.warn("T맵 응답에 경로 데이터가 없음");
                return createFallbackRoute();
            }

            double totalDistance = 0;
            int totalDuration = 0;
            int validSegments = 0;

            for (JsonNode feature : features) {
                JsonNode properties = feature.path("properties");

                if (properties.has("distance")) {
                    double segmentDistance = properties.path("distance").asDouble();
                    // 비정상적으로 긴 구간 필터링
                    if (segmentDistance > 0 && segmentDistance < 10000) { // 10km 이하만 허용
                        totalDistance += segmentDistance;
                        validSegments++;
                    }
                }
                if (properties.has("time")) {
                    int segmentTime = properties.path("time").asInt();
                    if (segmentTime > 0 && segmentTime < 3600) { // 1시간 이하만 허용
                        totalDuration += segmentTime;
                    }
                }

                JsonNode geometry = feature.path("geometry");
                if (geometry.path("type").asText().equals("LineString")) {
                    JsonNode coordinates = geometry.path("coordinates");

                    for (JsonNode coord : coordinates) {
                        double lng = coord.get(0).asDouble();
                        double lat = coord.get(1).asDouble();

                        // 좌표 유효성 검증
                        if (isValidCoordinate(lat, lng)) {
                            RoutePoint point = new RoutePoint();
                            point.setLat(lat);
                            point.setLng(lng);
                            point.setInShadow(false);
                            points.add(point);
                        } else {
                            logger.warn("️무효한 좌표 제외: ({}, {})", lat, lng);
                        }
                    }
                }
            }

            // 최종 검증
            if (points.size() < 2) {
                logger.error("X 경로 포인트 부족: {}개", points.size());
                return createFallbackRoute();
            }

            if (validSegments == 0) {
                logger.warn("유효한 경로 구간이 없음");
                totalDistance = calculateTotalDistanceFromPoints(points);
            }

            route.setPoints(points);
            route.setDistance(totalDistance);
            route.setDuration(totalDuration / 60); // 초를 분으로 변환

            logger.debug("T맵 경로 파싱 성공: {}개 포인트, 거리={}m, 시간={}분",
                    points.size(), (int)totalDistance, totalDuration / 60);

            return route;

        } catch (Exception e) {
            logger.error("X T맵 경로 파싱 오류: " + e.getMessage(), e);
            return createFallbackRoute();
        }
    }

    /**
     * 좌표 유효성 검증
     */
    private boolean isValidCoordinate(double lat, double lng) {
        // 한국 영역 대략적 범위로 제한 (+ 여유분)
        return lat >= 33.0 && lat <= 39.0 && lng >= 124.0 && lng <= 132.0;
    }

    /**
     * 포인트 기반 총 거리 계산
     */
    private double calculateTotalDistanceFromPoints(List<RoutePoint> points) {
        double totalDistance = 0;
        for (int i = 0; i < points.size() - 1; i++) {
            RoutePoint p1 = points.get(i);
            RoutePoint p2 = points.get(i + 1);
            totalDistance += calculateDistance(p1.getLat(), p1.getLng(), p2.getLat(), p2.getLng());
        }
        return totalDistance;
    }

    /**
     * 대체 경로 생성 (API 실패 시)
     */
    private Route createFallbackRoute() {
        Route fallbackRoute = new Route();
        fallbackRoute.setPoints(new ArrayList<>());
        fallbackRoute.setDistance(0);
        fallbackRoute.setDuration(0);
        fallbackRoute.setRouteType("fallback");

        logger.info("대체 경로 생성됨");
        return fallbackRoute;
    }

    /**
     * 두 지점 간 거리 계산 (Haversine 공식)
     */
    private double calculateDistance(double lat1, double lng1, double lat2, double lng2) {
        final int R = 6371000;
        double latDistance = Math.toRadians(lat2 - lat1);
        double lngDistance = Math.toRadians(lng2 - lng1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lngDistance / 2) * Math.sin(lngDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    /**
     * 단순 경로 생성 (오류 시 대체 경로)
     */
    private Route createSimplePath(double startLat, double startLng, double endLat, double endLng) {
        Route route = new Route();
        List<RoutePoint> points = new ArrayList<>();

        RoutePoint startPoint = new RoutePoint();
        startPoint.setLat(startLat);
        startPoint.setLng(startLng);
        points.add(startPoint);

        RoutePoint endPoint = new RoutePoint();
        endPoint.setLat(endLat);
        endPoint.setLng(endLng);
        points.add(endPoint);

        route.setPoints(points);

        double distance = calculateDistance(startLat, startLng, endLat, endLng);
        route.setDistance(distance);
        route.setDuration((int) (distance / 67));

        return route;
    }
}
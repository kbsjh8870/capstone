package com.example.Backend.service;

import com.example.Backend.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

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
     *  수정된 그림자 경로 생성 메서드 (경유지 보정 추가)
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

            // 대폭 우회 경유지 생성
            RoutePoint strategicWaypoint = createStrategicWaypoint(
                    baseRoute.getPoints(), sunPos, avoidShadow, shadowAreas);

            if (strategicWaypoint != null) {
                logger.debug("전략적 경유지 생성: ({}, {})",
                        strategicWaypoint.getLat(), strategicWaypoint.getLng());

                // 경유지를 통한 새 경로 생성
                String waypointRouteJson = tmapApiService.AsgetWalkingRouteWithWaypoint(
                        startLat, startLng,
                        strategicWaypoint.getLat(), strategicWaypoint.getLng(),
                        endLat, endLng);

                Route enhancedRoute = parseBasicRoute(waypointRouteJson);
                enhancedRoute.setAvoidShadow(avoidShadow);

                // 경로 품질 확인
                if (isRouteQualityAcceptable(baseRoute, enhancedRoute)) {
                    logger.debug("개선된 경로 생성 성공: {}개 포인트", enhancedRoute.getPoints().size());

                    // 🔧 단일 호출로 모든 그림자 정보 처리
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

            // 🔧 모든 결과 통합 적용
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

            // 🔧 최종 검증 로깅
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

            // 🔧 각 그림자 영역에 대해 경유지 근처 포인트들을 관대하게 검사
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

            // 목적지 방향 계산
            double destinationDirection = calculateDirection(startPoint, endPoint);

            // avoidShadow 여부에 따라 명확히 다른 전략 적용
            double targetDirection;
            double detourMeters;

            if (avoidShadow) {
                // 그림자 회피: 태양 방향으로 우회
                targetDirection = determineAvoidShadowDirection(sunPos, destinationDirection, shadowAreas, middlePoint);
                detourMeters = 150.0; // 더 큰 우회
                logger.debug("그림자 회피 모드: 태양방향 기준 우회={}도", targetDirection);
            } else {
                // 그림자 선호: 태양 반대 방향으로 우회
                targetDirection = determineFollowShadowDirection(sunPos, destinationDirection, shadowAreas, middlePoint);
                detourMeters = 150.0; // 더욱 큰 우회로 그림자 찾기
                logger.debug("그림자 선호 모드: 그림자 밀집 지역 우회={}도", targetDirection);
            }

            // 지리적 좌표로 변환
            double directionRad = Math.toRadians(targetDirection);
            double latDegreeInMeters = 111000.0;
            double lngDegreeInMeters = 111000.0 * Math.cos(Math.toRadians(middlePoint.getLat()));

            double latOffset = detourMeters * Math.cos(directionRad) / latDegreeInMeters;
            double lngOffset = detourMeters * Math.sin(directionRad) / lngDegreeInMeters;

            RoutePoint waypoint = new RoutePoint();
            waypoint.setLat(middlePoint.getLat() + latOffset);
            waypoint.setLng(middlePoint.getLng() + lngOffset);

            // 좌표 유효성 검사
            if (Math.abs(waypoint.getLat()) > 90 || Math.abs(waypoint.getLng()) > 180) {
                logger.error("잘못된 경유지 좌표: ({}, {})", waypoint.getLat(), waypoint.getLng());
                return null;
            }

            logger.debug("차별화된 경유지 생성: avoidShadow={}, 방향={}도, 거리={}m",
                    avoidShadow, targetDirection, detourMeters);

            return waypoint;

        } catch (Exception e) {
            logger.error("차별화된 경유지 계산 오류: " + e.getMessage(), e);
            return null;
        }
    }

    private double determineAvoidShadowDirection(SunPosition sunPos, double destinationDirection,
                                                 List<ShadowArea> shadowAreas, RoutePoint centerPoint) {
        // 1순위: 태양 방향 (햇빛이 있는 곳)
        double sunDirection = sunPos.getAzimuth();

        // 2순위: 태양 방향 ±45도
        double[] avoidCandidates = {
                sunDirection,
                (sunDirection + 45) % 360,
                (sunDirection - 45 + 360) % 360,
                (sunDirection + 90) % 360,
                (sunDirection - 90 + 360) % 360
        };

        // 목적지 방향과 너무 반대되지 않는 방향 선택 (±135도 내에서)
        for (double candidate : avoidCandidates) {
            double angleDiff = Math.min(Math.abs(candidate - destinationDirection),
                    360 - Math.abs(candidate - destinationDirection));

            if (angleDiff <= 135) { // 목적지 방향 ±135도 범위
                logger.debug("그림자 회피 방향 선택: {}도 (태양={}도, 목적지={}도)",
                        candidate, sunDirection, destinationDirection);
                return candidate;
            }
        }

        // 모든 후보가 불가능하면 태양 방향 사용
        return sunDirection;
    }

    private double determineFollowShadowDirection(SunPosition sunPos, double destinationDirection,
                                                  List<ShadowArea> shadowAreas, RoutePoint centerPoint) {
        // 1순위: 태양 반대 방향 (그림자가 있는 곳)
        double shadowDirection = (sunPos.getAzimuth() + 180) % 360;

        // 2순위: 실제 그림자 밀집 지역 찾기
        double optimalShadowDirection = findDensestShadowDirection(centerPoint, shadowAreas, shadowDirection);

        // 3순위: 태양 반대 방향 ±45도
        double[] shadowCandidates = {
                optimalShadowDirection,
                shadowDirection,
                (shadowDirection + 45) % 360,
                (shadowDirection - 45 + 360) % 360,
                (shadowDirection + 90) % 360,
                (shadowDirection - 90 + 360) % 360
        };

        // 목적지 방향과 너무 반대되지 않는 방향 선택 (±135도 내에서)
        for (double candidate : shadowCandidates) {
            double angleDiff = Math.min(Math.abs(candidate - destinationDirection),
                    360 - Math.abs(candidate - destinationDirection));

            if (angleDiff <= 135) { // 목적지 방향 ±135도 범위
                logger.debug("그림자 선호 방향 선택: {}도 (그림자={}도, 목적지={}도)",
                        candidate, shadowDirection, destinationDirection);
                return candidate;
            }
        }

        // 모든 후보가 불가능하면 그림자 방향 사용
        return shadowDirection;
    }

    /**
     *  가장 그림자가 밀집된 방향 찾기
     */
    private double findDensestShadowDirection(RoutePoint centerPoint, List<ShadowArea> shadowAreas, double baseShadowDirection) {
        if (shadowAreas.isEmpty()) {
            return baseShadowDirection;
        }

        try {
            // 8방향에서 그림자 밀도 분석
            double[] directions = {0, 45, 90, 135, 180, 225, 270, 315};
            double[] shadowDensities = new double[8];
            double maxDensity = 0;
            int maxIndex = 4; // 기본값: 남쪽

            for (int i = 0; i < directions.length; i++) {
                double checkRadius = 300.0;
                double dirRad = Math.toRadians(directions[i]);
                double checkLat = centerPoint.getLat() + (checkRadius * Math.cos(dirRad)) / 111000.0;
                double checkLng = centerPoint.getLng() + (checkRadius * Math.sin(dirRad)) /
                        (111000.0 * Math.cos(Math.toRadians(centerPoint.getLat())));

                shadowDensities[i] = calculateShadowDensityAtPoint(checkLat, checkLng, shadowAreas);

                if (shadowDensities[i] > maxDensity) {
                    maxDensity = shadowDensities[i];
                    maxIndex = i;
                }
            }

            double densestDirection = directions[maxIndex];
            logger.debug("가장 그림자 밀집된 방향: {}도 (밀도={}%)", densestDirection, maxDensity);

            return densestDirection;

        } catch (Exception e) {
            logger.error("그림자 밀집 방향 찾기 오류: " + e.getMessage(), e);
            return baseShadowDirection;
        }
    }


    /**
     *  두 지점 간의 방향 계산 (도 단위)
     */
    private double calculateDirection(RoutePoint from, RoutePoint to) {
        double deltaLng = to.getLng() - from.getLng();
        double deltaLat = to.getLat() - from.getLat();

        double directionRad = Math.atan2(deltaLng, deltaLat);
        double directionDeg = Math.toDegrees(directionRad);

        // 0-360도 범위로 정규화
        return (directionDeg + 360) % 360;
    }

    /**
     *  목적지 고려한 우회 방향 결정
     */
    private double determineSmartDetourDirection(RoutePoint centerPoint, List<ShadowArea> shadowAreas,
                                                 SunPosition sunPos, boolean avoidShadow,
                                                 double destinationDirection, double solarDirection,
                                                 double minAllowed, double maxAllowed) {
        try {
            // 목적지 방향 기준 4방향으로 분석 (±45도, ±135도)
            double[] candidateDirections = {
                    (destinationDirection - 45 + 360) % 360,  // 목적지 방향 기준 왼쪽 45도
                    (destinationDirection + 45) % 360,        // 목적지 방향 기준 오른쪽 45도
                    (destinationDirection - 135 + 360) % 360, // 목적지 방향 기준 왼쪽 135도
                    (destinationDirection + 135) % 360        // 목적지 방향 기준 오른쪽 135도
            };

            double[] shadowDensity = new double[candidateDirections.length];
            double checkRadius = 200.0;

            // 각 방향의 그림자 밀도 계산
            for (int i = 0; i < candidateDirections.length; i++) {
                double direction = candidateDirections[i];

                // 허용 범위 내에 있는지 확인
                if (!isDirectionInRange(direction, minAllowed, maxAllowed)) {
                    shadowDensity[i] = avoidShadow ? 100.0 : 0.0; // 허용 범위 밖은 불리하게
                    continue;
                }

                double dirRad = Math.toRadians(direction);
                double checkLat = centerPoint.getLat() +
                        (checkRadius * Math.cos(dirRad)) / 111000.0;
                double checkLng = centerPoint.getLng() +
                        (checkRadius * Math.sin(dirRad)) / (111000.0 * Math.cos(Math.toRadians(centerPoint.getLat())));

                shadowDensity[i] = calculateShadowDensityAtPoint(checkLat, checkLng, shadowAreas);

                logger.debug("방향 {}도: 그림자밀도={}%", direction, shadowDensity[i]);
            }

            // 최적 방향 선택
            int bestIndex = 0;
            for (int i = 1; i < candidateDirections.length; i++) {
                if (avoidShadow) {
                    // 그림자 회피: 그림자 밀도가 낮고, 목적지 방향에 가까운 것 우선
                    if (shadowDensity[i] < shadowDensity[bestIndex] ||
                            (Math.abs(shadowDensity[i] - shadowDensity[bestIndex]) < 10 &&
                                    isCloserToDestination(candidateDirections[i], candidateDirections[bestIndex], destinationDirection))) {
                        bestIndex = i;
                    }
                } else {
                    // 그림자 선호: 그림자 밀도가 높고, 목적지 방향에 가까운 것 우선
                    if (shadowDensity[i] > shadowDensity[bestIndex] ||
                            (Math.abs(shadowDensity[i] - shadowDensity[bestIndex]) < 10 &&
                                    isCloserToDestination(candidateDirections[i], candidateDirections[bestIndex], destinationDirection))) {
                        bestIndex = i;
                    }
                }
            }

            double optimalDirection = candidateDirections[bestIndex];

            logger.debug("스마트 방향 선택: 목적지방향={}도, 최적방향={}도, 그림자밀도={}%",
                    destinationDirection, optimalDirection, shadowDensity[bestIndex]);

            return optimalDirection;

        } catch (Exception e) {
            logger.error("스마트 우회 방향 결정 오류: " + e.getMessage(), e);
            // 실패 시 목적지 방향의 수직 방향으로 소폭 우회
            return (destinationDirection + 90) % 360;
        }
    }

    /**
     *  방향이 허용 범위 내에 있는지 확인
     */
    private boolean isDirectionInRange(double direction, double minAllowed, double maxAllowed) {
        if (minAllowed <= maxAllowed) {
            return direction >= minAllowed && direction <= maxAllowed;
        } else {
            // 범위가 0도를 넘나드는 경우 (예: 315도 ~ 45도)
            return direction >= minAllowed || direction <= maxAllowed;
        }
    }

    /**
     *  목적지 방향에 더 가까운 방향 판단
     */
    private boolean isCloserToDestination(double direction1, double direction2, double destinationDirection) {
        double diff1 = Math.min(Math.abs(direction1 - destinationDirection),
                360 - Math.abs(direction1 - destinationDirection));
        double diff2 = Math.min(Math.abs(direction2 - destinationDirection),
                360 - Math.abs(direction2 - destinationDirection));
        return diff1 < diff2;
    }

    /**
     *  보수적 우회 거리 계산 (목적지 방향 고려)
     */
    private double calculateConservativeDetourDistance(SunPosition sunPos, double destinationDirection, double detourDirection) {
        double altitude = sunPos.getAltitude();

        // 목적지 방향과 우회 방향의 차이
        double directionDiff = Math.min(Math.abs(detourDirection - destinationDirection),
                360 - Math.abs(detourDirection - destinationDirection));

        // 기본 우회 거리
        double baseDetour;
        if (altitude < 15) {
            baseDetour = 150.0; // 저녁/새벽: 보수적
        } else if (altitude < 45) {
            baseDetour = 100.0; // 오전/오후: 보통
        } else {
            baseDetour = 80.0;  // 정오: 최소
        }

        //  목적지 방향과 많이 다를수록 우회 거리 감소
        double directionFactor = 1.0 - (directionDiff / 180.0) * 0.5; // 최대 50% 감소

        return baseDetour * directionFactor;
    }

    /**
     * 실제 그림자 영역을 분석하여 우회 방향 조정
     */
    private double adjustDirectionBasedOnShadowAreas(RoutePoint centerPoint,
                                                     List<ShadowArea> shadowAreas,
                                                     SunPosition sunPos,
                                                     boolean avoidShadow,
                                                     double initialDirection) {
        try {
            // 중심점 주변의 그림자 밀도를 8방향으로 분석
            double[] directions = {0, 45, 90, 135, 180, 225, 270, 315};
            double[] shadowDensity = new double[8];

            double checkRadius = 200.0; // 200m 반경에서 체크

            for (int i = 0; i < directions.length; i++) {
                double dirRad = Math.toRadians(directions[i]);
                double checkLat = centerPoint.getLat() +
                        (checkRadius * Math.cos(dirRad)) / 111000.0;
                double checkLng = centerPoint.getLng() +
                        (checkRadius * Math.sin(dirRad)) / (111000.0 * Math.cos(Math.toRadians(centerPoint.getLat())));

                // 해당 방향의 그림자 밀도 계산
                shadowDensity[i] = calculateShadowDensityAtPoint(checkLat, checkLng, shadowAreas);
            }

            // 그림자 회피 vs 선호에 따라 최적 방향 선택
            int bestDirectionIndex = 0;
            for (int i = 1; i < directions.length; i++) {
                if (avoidShadow) {
                    // 그림자 회피: 그림자 밀도가 가장 낮은 방향
                    if (shadowDensity[i] < shadowDensity[bestDirectionIndex]) {
                        bestDirectionIndex = i;
                    }
                } else {
                    // 그림자 선호: 그림자 밀도가 가장 높은 방향
                    if (shadowDensity[i] > shadowDensity[bestDirectionIndex]) {
                        bestDirectionIndex = i;
                    }
                }
            }

            double optimalDirection = directions[bestDirectionIndex];

            logger.debug("실제 그림자 분석 결과: 초기방향={}도, 최적방향={}도, avoidShadow={}",
                    initialDirection, optimalDirection, avoidShadow);

            return optimalDirection;

        } catch (Exception e) {
            logger.error("그림자 영역 기반 방향 조정 오류: " + e.getMessage(), e);
            return initialDirection; // 오류 시 초기 방향 반환
        }
    }

    /**
     * 특정 지점의 그림자 밀도 계산
     */
    private double calculateShadowDensityAtPoint(double lat, double lng, List<ShadowArea> shadowAreas) {
        try {
            // PostGIS를 사용하여 해당 지점 주변 100m 내의 그림자 영역 비율 계산
            String sql = """
            WITH point_buffer AS (
                SELECT ST_Buffer(ST_SetSRID(ST_MakePoint(?, ?), 4326), 0.001) as geom
            ),
            shadow_union AS (
                SELECT ST_Union(ST_GeomFromGeoJSON(?)) as shadow_geom
            )
            SELECT 
                COALESCE(
                    ST_Area(ST_Intersection(pb.geom, su.shadow_geom)) / ST_Area(pb.geom) * 100,
                    0
                ) as shadow_percentage
            FROM point_buffer pb, shadow_union su
            """;

            String shadowUnion = createShadowUnion(shadowAreas);

            Double shadowPercentage = jdbcTemplate.queryForObject(sql, Double.class,
                    lng, lat, shadowUnion);

            return shadowPercentage != null ? shadowPercentage : 0.0;

        } catch (Exception e) {
            logger.warn("그림자 밀도 계산 오류: " + e.getMessage());
            return 0.0;
        }
    }

    /**
     * 태양 고도에 따른 최적 우회 거리 계산
     */
    private double calculateOptimalDetourDistance(SunPosition sunPos) {
        double altitude = sunPos.getAltitude();

        if (altitude < 15) {
            // 저녁/새벽: 그림자가 길어서 더 큰 우회 필요
            return 200.0;
        } else if (altitude < 45) {
            // 오전/오후: 중간 우회
            return 150.0;
        } else {
            // 정오: 그림자가 짧아서 소형 우회
            return 100.0;
        }
    }

    /**
     * 경로 품질 검증
     */
    private boolean isRouteQualityAcceptable(Route baseRoute, Route shadowRoute) {
        // 거리 차이가 기본 경로의 25% 이내인지 확인
        double distanceRatio = shadowRoute.getDistance() / baseRoute.getDistance();

        if (distanceRatio > 1.18) {
            logger.debug("경로가 너무 멀어짐: 기본={}m, 그림자={}m ({}% 증가)",
                    (int)baseRoute.getDistance(), (int)shadowRoute.getDistance(),
                    (int)((distanceRatio - 1) * 100));
            return false;
        }

        // 포인트 수가 합리적인지 확인 (조건 완화)
        if (shadowRoute.getPoints().size() < baseRoute.getPoints().size() * 0.3) {
            logger.debug("경로 포인트가 너무 적음");
            return false;
        }

        logger.debug("경로 품질 검증 통과: 거리 차이 {}%", (int)((distanceRatio - 1) * 100));
        return true;
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

            //  배치 처리로 기본 그림자 검사
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

            //배치 처리로 상세 분석
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

                // 🔧 경유지 근처 포인트 디버깅
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
     *  경유지 근처 그림자 보정 메서드
     */
    private void adjustWaypointShadows(Route route, RoutePoint waypoint, List<ShadowArea> shadowAreas) {
        try {
            logger.debug("=== 경유지 근처 그림자 보정 시작 ===");
            logger.debug("경유지 위치: ({}, {})", waypoint.getLat(), waypoint.getLng());

            List<RoutePoint> points = route.getPoints();

            // 경유지 근처 20개 포인트 범위 찾기
            int waypointIndex = findClosestPointIndex(points, waypoint);
            int startIdx = Math.max(0, waypointIndex - 10);
            int endIdx = Math.min(points.size() - 1, waypointIndex + 10);

            logger.debug("경유지 근처 포인트 범위: {} ~ {} (총 {}개)", startIdx, endIdx, endIdx - startIdx + 1);

            // 🔧 경유지 근처 포인트들에 대해 더 관대한 그림자 검사
            for (int i = startIdx; i <= endIdx; i++) {
                RoutePoint point = points.get(i);

                if (!point.isInShadow()) {  // 이미 그림자로 감지되지 않은 포인트만
                    boolean isNearWaypointShadow = checkWaypointNearShadow(point, shadowAreas);

                    if (isNearWaypointShadow) {
                        point.setInShadow(true);
                        logger.debug("경유지 근처 그림자 보정: 포인트 {} ({}, {})",
                                i, point.getLat(), point.getLng());
                    }
                }
            }

            // 그림자 비율 재계산
            int shadowCount = 0;
            for (RoutePoint point : points) {
                if (point.isInShadow()) shadowCount++;
            }

            int newPercentage = points.size() > 0 ? (shadowCount * 100 / points.size()) : 0;
            route.setShadowPercentage(newPercentage);

            logger.info("경유지 근처 그림자 보정 완료: {}%", newPercentage);

        } catch (Exception e) {
            logger.error("경유지 근처 그림자 보정 오류: " + e.getMessage(), e);
        }
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

    /**
     *  경유지 근처 특별 그림자 검사 (더 관대한 기준)
     */
    private boolean checkWaypointNearShadow(RoutePoint point, List<ShadowArea> shadowAreas) {
        try {
            for (ShadowArea shadowArea : shadowAreas) {
                String shadowGeom = shadowArea.getShadowGeometry();
                if (shadowGeom == null || shadowGeom.isEmpty()) continue;

                // 경유지 근처는 150m 이내까지 관대하게 검사
                String sql = """
                SELECT ST_DWithin(
                    ST_GeomFromGeoJSON(?), 
                    ST_SetSRID(ST_MakePoint(?, ?), 4326), 
                    0.0013  -- 약 150m
                )
                """;

                Boolean isNear = jdbcTemplate.queryForObject(sql, Boolean.class,
                        shadowGeom, point.getLng(), point.getLat());

                if (isNear != null && isNear) {
                    return true;
                }
            }

            return false;

        } catch (Exception e) {
            logger.warn("경유지 근처 그림자 검사 실패: " + e.getMessage());
            return false;
        }
    }

    /**
     * 경로와 그림자 교차 여부 확인
     */
    private boolean checkPointInShadowRelaxed(RoutePoint point, String mergedShadows) {
        try {
            // 🔧 1. 더 정밀한 포함 확인
            String containsSql = "SELECT ST_Contains(ST_GeomFromGeoJSON(?), ST_SetSRID(ST_MakePoint(?, ?), 4326))";
            Boolean exactContains = jdbcTemplate.queryForObject(containsSql, Boolean.class,
                    mergedShadows, point.getLng(), point.getLat());

            if (exactContains != null && exactContains) {
                return true;
            }

            // 🔧 2. 다중 거리 기준 확인 (10m, 25m, 50m)
            String[] distances = {"0.0001", "0.0002", "0.0005"}; // 약 11m, 22m, 55m

            for (String distance : distances) {
                String distanceSql = "SELECT ST_DWithin(ST_GeomFromGeoJSON(?), ST_SetSRID(ST_MakePoint(?, ?), 4326), ?)";
                Boolean nearShadow = jdbcTemplate.queryForObject(distanceSql, Boolean.class,
                        mergedShadows, point.getLng(), point.getLat(), Double.parseDouble(distance));

                if (nearShadow != null && nearShadow) {
                    return true;
                }
            }

            // 🔧 3. 교차 확인 (포인트에서 작은 버퍼 생성해서 교차 검사)
            String intersectsSql = """
            SELECT ST_Intersects(
                ST_GeomFromGeoJSON(?), 
                ST_Buffer(ST_SetSRID(ST_MakePoint(?, ?), 4326), 0.0001)
            )
            """;
            Boolean intersects = jdbcTemplate.queryForObject(intersectsSql, Boolean.class,
                    mergedShadows, point.getLng(), point.getLat());

            return intersects != null && intersects;

        } catch (Exception e) {
            logger.warn("개선된 그림자 확인 실패: " + e.getMessage());
            return false;
        }
    }

    private void analyzeRouteDetailedShadows(Route route, List<ShadowArea> shadowAreas) {
        try {
            logger.debug("=== 배치 처리 상세 그림자 분석 시작 ===");

            List<RoutePoint> points = route.getPoints();
            if (points.isEmpty()) return;

            // 🚀 배치 처리로 모든 포인트를 한 번에 검사
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

            // 🚀 각 그림자 영역에 대해 배치로 모든 포인트 검사
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
     * 개별 포인트의 상세 그림자 검사
     */
    private boolean checkPointDetailedShadow(RoutePoint point, List<ShadowArea> shadowAreas) {
        try {
            // 🔧 각 그림자 영역별로 개별 검사
            for (ShadowArea shadowArea : shadowAreas) {
                String shadowGeom = shadowArea.getShadowGeometry();
                if (shadowGeom == null || shadowGeom.isEmpty()) continue;

                // 더 관대한 기준으로 검사 (75m 이내)
                String sql = """
                SELECT ST_DWithin(
                    ST_GeomFromGeoJSON(?), 
                    ST_SetSRID(ST_MakePoint(?, ?), 4326), 
                    0.0007
                )
                """;

                Boolean isNear = jdbcTemplate.queryForObject(sql, Boolean.class,
                        shadowGeom, point.getLng(), point.getLat());

                if (isNear != null && isNear) {
                    return true;
                }
            }

            return false;

        } catch (Exception e) {
            logger.warn("개별 포인트 그림자 검사 실패: " + e.getMessage());
            return false;
        }
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
            if (sunPos.getAltitude() < -10) {
                logger.debug("태양 고도가 너무 낮음 ({}도). 그림자 계산 제외", sunPos.getAltitude());
                return new ArrayList<>();
            }

            double shadowDirection = (sunPos.getAzimuth() + 180) % 360;

            // 🔧 그림자 길이 계산 - 더 현실적으로
            double shadowLength;
            if (sunPos.getAltitude() <= 5) {
                shadowLength = 1000; // 저녁 시간에는 매우 긴 그림자
            } else {
                // tan 값이 매우 작을 때 보정
                double tanValue = Math.tan(Math.toRadians(sunPos.getAltitude()));
                shadowLength = Math.min(2000, Math.max(50, 100 / tanValue));
            }

            logger.debug("개선된 그림자 계산: 태양고도={}도, 방위각={}도, 그림자방향={}도, 그림자길이={}m",
                    sunPos.getAltitude(), sunPos.getAzimuth(), shadowDirection, shadowLength);

            // 🔧 완전히 개선된 PostGIS 쿼리
            String sql = """
            WITH route_area AS (
                SELECT ST_Buffer(
                    ST_MakeLine(
                        ST_SetSRID(ST_MakePoint(?, ?), 4326),
                        ST_SetSRID(ST_MakePoint(?, ?), 4326)
                    ), 0.008  -- 더 넓은 버퍼 (약 900m)
                ) as geom
            ),
            enhanced_building_shadows AS (
                SELECT 
                    b.id,
                    b."A16" as height,
                    ST_AsGeoJSON(b.geom) as building_geom,
                    -- 🔧 다중 그림자 영역 생성 (건물 높이에 따라)
                    ST_AsGeoJSON(
                        ST_Union(ARRAY[
                            -- 기본 건물 영역
                            b.geom,
                            -- 50% 그림자
                            ST_Translate(
                                b.geom,
                                (? * 0.5) * cos(radians(?)) / (111320.0 * cos(radians(ST_Y(ST_Centroid(b.geom))))),
                                (? * 0.5) * sin(radians(?)) / 110540.0
                            ),
                            -- 100% 그림자  
                            ST_Translate(
                                b.geom,
                                ? * cos(radians(?)) / (111320.0 * cos(radians(ST_Y(ST_Centroid(b.geom))))),
                                ? * sin(radians(?)) / 110540.0
                            ),
                            -- 🆕 건물 높이 고려한 추가 그림자 (높은 건물은 더 긴 그림자)
                            ST_Translate(
                                b.geom,
                                (? * (b."A16" / 50.0)) * cos(radians(?)) / (111320.0 * cos(radians(ST_Y(ST_Centroid(b.geom))))),
                                (? * (b."A16" / 50.0)) * sin(radians(?)) / 110540.0
                            )
                        ])
                    ) as shadow_geom
                FROM public."AL_D010_26_20250304" b, route_area r
                WHERE ST_Intersects(b.geom, r.geom)
                  AND b."A16" > 2  -- 2m 이상 모든 건물
                ORDER BY 
                    -- 🔧 경로와 가까운 건물 우선, 높은 건물 우선
                    ST_Distance(b.geom, r.geom) ASC,
                    b."A16" DESC
                LIMIT 100  -- 더 많은 건물 포함
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

            logger.info("개선된 그림자 계산 완료: {}개 건물, 그림자길이={}m",
                    shadowAreas.size(), shadowLength);

            return shadowAreas;

        } catch (Exception e) {
            logger.error("개선된 그림자 계산 오류: " + e.getMessage(), e);
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

            double totalDistance = 0;
            int totalDuration = 0;

            for (JsonNode feature : features) {
                JsonNode properties = feature.path("properties");

                if (properties.has("distance")) {
                    totalDistance += properties.path("distance").asDouble();
                }
                if (properties.has("time")) {
                    totalDuration += properties.path("time").asInt();
                }

                JsonNode geometry = feature.path("geometry");
                if (geometry.path("type").asText().equals("LineString")) {
                    JsonNode coordinates = geometry.path("coordinates");

                    for (JsonNode coord : coordinates) {
                        double lng = coord.get(0).asDouble();
                        double lat = coord.get(1).asDouble();

                        RoutePoint point = new RoutePoint();
                        point.setLat(lat);
                        point.setLng(lng);
                        point.setInShadow(false); // 초기값
                        points.add(point);
                    }
                }
            }

            route.setPoints(points);
            route.setDistance(totalDistance);
            route.setDuration(totalDuration / 60);

            logger.debug("T맵 경로 파싱 완료: {}개 포인트, 거리={}m", points.size(), totalDistance);

        } catch (Exception e) {
            logger.error("경로 파싱 오류: " + e.getMessage(), e);
            route.setPoints(new ArrayList<>());
            route.setDistance(0);
            route.setDuration(0);
        }

        return route;
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



    //--------------------------------------------------------------------------------------------------------------

    /**
     * 다양한 경로 옵션을 생성하여 그늘과 시간 효율성의 최적 조합 찾기
     */
    public List<Route> generateMultipleRouteOptions(
            double startLat, double startLng,
            double endLat, double endLng,
            boolean preferShadow, LocalDateTime dateTime) {

        List<Route> allRoutes = new ArrayList<>();
        List<CompletableFuture<Route>> futureRoutes = new ArrayList<>();

        try {
            // 1. 기본 경로
            CompletableFuture<Route> basicRouteFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    String tmapJson = tmapApiService.getWalkingRoute(startLat, startLng, endLat, endLng);
                    Route route = parseBasicRoute(tmapJson);
                    route.setBasicRoute(true);
                    route.setDateTime(dateTime);
                    return route;
                } catch (Exception e) {
                    logger.error("기본 경로 생성 실패", e);
                    return null;
                }
            });
            futureRoutes.add(basicRouteFuture);

            // 2. 태양 위치 계산
            SunPosition sunPos = shadowService.calculateSunPosition(startLat, startLng, dateTime);
            // 3. 그늘 영역 계산
            List<ShadowArea> shadowAreas = calculateBuildingShadows(startLat, startLng, endLat, endLng, sunPos);

            // 4. 그늘 밀집 지역 분석
            List<RoutePoint> shadowHotspots = findShadowHotspots(startLat, startLng, endLat, endLng, shadowAreas);

            // 5. 다양한 경유지 전략으로 경로 생성
            List<List<RoutePoint>> waypointStrategies = generateWaypointStrategies(
                    startLat, startLng, endLat, endLng, shadowHotspots, sunPos, preferShadow);

            // 6. 각 전략에 대해 비동기로 경로 계산
            for (List<RoutePoint> waypoints : waypointStrategies) {
                CompletableFuture<Route> routeFuture = CompletableFuture.supplyAsync(() ->
                        calculateRouteWithWaypoints(startLat, startLng, endLat, endLng,
                                waypoints, shadowAreas, preferShadow, dateTime)
                );
                futureRoutes.add(routeFuture);
            }

            // 7. 모든 경로 계산 완료 대기
            CompletableFuture.allOf(futureRoutes.toArray(new CompletableFuture[0])).join();

            // 8. 결과 수집 및 필터링
            for (CompletableFuture<Route> future : futureRoutes) {
                Route route = future.get();
                if (route != null && isRouteValid(route, allRoutes)) {
                    allRoutes.add(route);
                }
            }

            // 9. 경로 효율성 점수 계산 및 정렬
            calculateEfficiencyScores(allRoutes, preferShadow);
            allRoutes.sort((r1, r2) -> Double.compare(r2.getEfficiencyScore(), r1.getEfficiencyScore()));

            // 10. 상위 5개 경로만 반환
            return allRoutes.stream().limit(5).collect(Collectors.toList());

        } catch (Exception e) {
            logger.error("다중 경로 생성 오류", e);
            return allRoutes;
        }
    }

    /**
     * 그늘 밀집 지역 찾기
     */
    private List<RoutePoint> findShadowHotspots(double startLat, double startLng,
                                                double endLat, double endLng,
                                                List<ShadowArea> shadowAreas) {
        List<RoutePoint> hotspots = new ArrayList<>();

        // 경로 영역을 격자로 나누기
        int gridSize = 10; // 10x10 격자
        double latStep = (endLat - startLat) / gridSize;
        double lngStep = (endLng - startLng) / gridSize;

        Map<RoutePoint, Double> shadowDensityMap = new HashMap<>();

        // 각 격자점의 그늘 밀도 계산
        for (int i = 1; i < gridSize; i++) {
            for (int j = 1; j < gridSize; j++) {
                double lat = startLat + (latStep * i);
                double lng = startLng + (lngStep * j);

                double density = calculateShadowDensityAtPoint(lat, lng, shadowAreas);

                if (density > 30) { // 30% 이상 그늘인 지점
                    RoutePoint hotspot = new RoutePoint(lat, lng);
                    shadowDensityMap.put(hotspot, density);
                }
            }
        }

        // 밀도 순으로 정렬하여 상위 지점 선택
        shadowDensityMap.entrySet().stream()
                .sorted(Map.Entry.<RoutePoint, Double>comparingByValue().reversed())
                .limit(10)
                .forEach(entry -> hotspots.add(entry.getKey()));

        logger.debug("그늘 밀집 지역 {}개 발견", hotspots.size());
        return hotspots;
    }

    /**
     * 다양한 경유지 전략 생성
     */
    private List<List<RoutePoint>> generateWaypointStrategies(
            double startLat, double startLng, double endLat, double endLng,
            List<RoutePoint> shadowHotspots, SunPosition sunPos, boolean preferShadow) {

        List<List<RoutePoint>> strategies = new ArrayList<>();

        // 전략 1: 단일 중간 경유지 (8방향)
        double midLat = (startLat + endLat) / 2;
        double midLng = (startLng + endLng) / 2;

        double[] directions = {0, 45, 90, 135, 180, 225, 270, 315};
        double[] distances = {100, 200, 300}; // 미터

        for (double direction : directions) {
            for (double distance : distances) {
                RoutePoint waypoint = calculateWaypointByDirection(midLat, midLng, direction, distance);
                strategies.add(Arrays.asList(waypoint));
            }
        }

        // 전략 2: 그늘 밀집 지역 경유
        for (RoutePoint hotspot : shadowHotspots) {
            strategies.add(Arrays.asList(hotspot));

            // 두 개의 그늘 지역 조합
            if (shadowHotspots.size() > 1) {
                for (RoutePoint hotspot2 : shadowHotspots) {
                    if (!hotspot.equals(hotspot2)) {
                        // 순서를 고려한 경유
                        if (isOrderedPath(startLat, startLng, hotspot, hotspot2, endLat, endLng)) {
                            strategies.add(Arrays.asList(hotspot, hotspot2));
                        }
                    }
                }
            }
        }

        // 전략 3: 구간별 경유지
        int segments = 3;
        for (int i = 1; i < segments; i++) {
            double segmentLat = startLat + ((endLat - startLat) * i / segments);
            double segmentLng = startLng + ((endLng - startLng) * i / segments);

            // 각 구간에서 좌우로 경유지 생성
            double perpDirection = calculatePerpendicularDirection(startLat, startLng, endLat, endLng);

            RoutePoint leftWaypoint = calculateWaypointByDirection(segmentLat, segmentLng, perpDirection, 150);
            RoutePoint rightWaypoint = calculateWaypointByDirection(segmentLat, segmentLng, (perpDirection + 180) % 360, 150);

            strategies.add(Arrays.asList(leftWaypoint));
            strategies.add(Arrays.asList(rightWaypoint));
        }

        logger.debug("총 {}개의 경유지 전략 생성", strategies.size());
        return strategies;
    }

    /**
     * 경유지를 포함한 경로 계산
     */
    private Route calculateRouteWithWaypoints(double startLat, double startLng,
                                              double endLat, double endLng,
                                              List<RoutePoint> waypoints,
                                              List<ShadowArea> shadowAreas,
                                              boolean preferShadow,
                                              LocalDateTime dateTime) {
        try {
            String routeJson;

            if (waypoints.isEmpty()) {
                routeJson = tmapApiService.getWalkingRoute(startLat, startLng, endLat, endLng);
            } else if (waypoints.size() == 1) {
                RoutePoint wp = waypoints.get(0);
                routeJson = tmapApiService.AsgetWalkingRouteWithWaypoint(
                        startLat, startLng, wp.getLat(), wp.getLng(), endLat, endLng);
            } else {
                routeJson = tmapApiService.getWalkingRouteWithMultiWaypoints(
                        startLat, startLng, waypoints, endLat, endLng);
            }

            Route route = parseBasicRoute(routeJson);
            route.setBasicRoute(false);
            route.setAvoidShadow(preferShadow);
            route.setDateTime(dateTime);

            // 그림자 정보 적용
            applyShadowInfoFromDB(route, shadowAreas);

            return route;

        } catch (Exception e) {
            logger.error("경유지 경로 계산 실패", e);
            return null;
        }
    }

    /**
     * 경로 효율성 점수 계산
     */
    private void calculateEfficiencyScores(List<Route> routes, boolean preferShadow) {
        if (routes.isEmpty()) return;

        // 기본 경로 찾기
        Route baseRoute = routes.stream()
                .filter(Route::isBasicRoute)
                .findFirst()
                .orElse(routes.get(0));

        double baseDistance = baseRoute.getDistance();

        for (Route route : routes) {
            double distanceRatio = route.getDistance() / baseDistance;
            double shadowRatio = route.getShadowPercentage() / 100.0;

            double score;
            if (preferShadow) {
                // 그늘 선호: 그늘 비율을 높게, 거리 증가를 낮게 평가
                score = (shadowRatio * 0.7) - ((distanceRatio - 1) * 0.3);
            } else {
                // 햇빛 선호: 그늘 비율을 낮게, 거리 증가를 낮게 평가
                score = ((1 - shadowRatio) * 0.7) - ((distanceRatio - 1) * 0.3);
            }

            // 시간당 그늘/햇빛 효율성 고려
            double timeEfficiency = route.getDuration() > 0 ?
                    (preferShadow ? shadowRatio : (1 - shadowRatio)) / (route.getDuration() / 60.0) : 0;

            score += timeEfficiency * 0.2;

            route.setEfficiencyScore(score);

            logger.debug("경로 점수: 거리비율={}, 그늘비율={}, 점수={}",
                    distanceRatio, shadowRatio, score);
        }
    }

    /**
     * 경로 유효성 검사
     */
    private boolean isRouteValid(Route route, List<Route> allRoutes) {
        if (route == null || route.getPoints().isEmpty()) {
            return false;
        }

        // 기본 경로는 항상 포함
        if (route.isBasicRoute()) {
            return true;
        }

        // 거리가 기본 경로의 1.5배를 초과하면 제외
        Route baseRoute = allRoutes.stream()
                .filter(Route::isBasicRoute)
                .findFirst()
                .orElse(null);

        if (baseRoute != null && route.getDistance() > baseRoute.getDistance() * 1.5) {
            logger.debug("경로 제외: 거리 초과 ({}m > {}m)",
                    route.getDistance(), baseRoute.getDistance() * 1.5);
            return false;
        }

        // 경로 포인트가 너무 적으면 제외
        if (route.getPoints().size() < 10) {
            logger.debug("경로 제외: 포인트 수 부족 ({}개)", route.getPoints().size());
            return false;
        }

        return true;
    }

    /**
     * 방향과 거리로 경유지 계산
     */
    private RoutePoint calculateWaypointByDirection(double lat, double lng,
                                                    double direction, double distanceMeters) {
        double dirRad = Math.toRadians(direction);
        double latOffset = (distanceMeters * Math.cos(dirRad)) / 111000.0;
        double lngOffset = (distanceMeters * Math.sin(dirRad)) /
                (111000.0 * Math.cos(Math.toRadians(lat)));

        return new RoutePoint(lat + latOffset, lng + lngOffset);
    }

    /**
     * 두 점을 잇는 선에 수직인 방향 계산
     */
    private double calculatePerpendicularDirection(double lat1, double lng1,
                                                   double lat2, double lng2) {
        double direction = calculateDirection(new RoutePoint(lat1, lng1),
                new RoutePoint(lat2, lng2));
        return (direction + 90) % 360;
    }

    /**
     * 경유지들이 순서대로 배치되어 있는지 확인
     */
    private boolean isOrderedPath(double startLat, double startLng,
                                  RoutePoint wp1, RoutePoint wp2,
                                  double endLat, double endLng) {
        // 각 구간의 거리 계산
        double dist1 = calculateDistance(startLat, startLng, wp1.getLat(), wp1.getLng());
        double dist2 = calculateDistance(wp1.getLat(), wp1.getLng(), wp2.getLat(), wp2.getLng());
        double dist3 = calculateDistance(wp2.getLat(), wp2.getLng(), endLat, endLng);

        double totalWithWaypoints = dist1 + dist2 + dist3;
        double directDistance = calculateDistance(startLat, startLng, endLat, endLng);

        // 전체 거리가 직선 거리의 2배를 초과하면 비효율적
        return totalWithWaypoints < directDistance * 2;
    }

    /**
     * 경로 상세 분석
     */
    public RouteAnalysis analyzeRouteDetails(Route route) {
        RouteAnalysis analysis = new RouteAnalysis();

        analysis.setRouteId(route.getId());
        analysis.setTotalDistance(route.getDistance());
        analysis.setTotalDuration(route.getDuration());
        analysis.setShadowPercentage(route.getShadowPercentage());
        analysis.setEfficiencyScore(route.getEfficiencyScore());

        // 구간별 분석
        List<RouteAnalysis.SegmentAnalysis> segments = analyzeSegments(route);
        analysis.setSegments(segments);

        // 시간대별 그림자 변화 분석
        Map<Integer, Integer> hourlyShadow = analyzeHourlyShadowChanges(route);
        analysis.setHourlyShhadowPercentage(hourlyShadow);

        // 경로 특성 분석
        RouteAnalysis.RouteCharacteristics characteristics = analyzeCharacteristics(route);
        analysis.setCharacteristics(characteristics);

        return analysis;
    }

    /**
     * 구간별 분석
     */
    private List<RouteAnalysis.SegmentAnalysis> analyzeSegments(Route route) {
        List<RouteAnalysis.SegmentAnalysis> segments = new ArrayList<>();
        List<RoutePoint> points = route.getPoints();

        int segmentSize = Math.max(10, points.size() / 10); // 10개 구간으로 나누기

        for (int i = 0; i < points.size(); i += segmentSize) {
            int endIndex = Math.min(i + segmentSize, points.size() - 1);

            RouteAnalysis.SegmentAnalysis segment = new RouteAnalysis.SegmentAnalysis();
            segment.setStartIndex(i);
            segment.setEndIndex(endIndex);

            // 구간 거리 계산
            double segmentDistance = 0;
            int shadowCount = 0;

            for (int j = i; j < endIndex; j++) {
                RoutePoint p1 = points.get(j);
                RoutePoint p2 = points.get(j + 1);

                segmentDistance += calculateDistance(
                        p1.getLat(), p1.getLng(),
                        p2.getLat(), p2.getLng()
                );

                if (p1.isInShadow()) {
                    shadowCount++;
                }
            }

            segment.setDistance(segmentDistance);
            segment.setDuration((int)(segmentDistance / 67)); // 평균 보행 속도
            segment.setInShadow(shadowCount > (endIndex - i) / 2); // 50% 이상이면 그림자 구간
            segment.setShadowDensity((double)shadowCount / (endIndex - i) * 100);

            segments.add(segment);
        }

        return segments;
    }

    /**
     * 시간대별 그림자 변화 분석
     */
    private Map<Integer, Integer> analyzeHourlyShadowChanges(Route route) {
        Map<Integer, Integer> hourlyShadow = new HashMap<>();
        LocalDateTime baseTime = route.getDateTime();

        // 현재 시간 기준 전후 3시간 분석
        for (int hourOffset = -3; hourOffset <= 3; hourOffset++) {
            LocalDateTime checkTime = baseTime.plusHours(hourOffset);
            int hour = checkTime.getHour();

            // 태양 위치 재계산
            SunPosition sunPos = shadowService.calculateSunPosition(
                    route.getPoints().get(0).getLat(),
                    route.getPoints().get(0).getLng(),
                    checkTime
            );

            // 간단한 추정 (실제로는 더 정확한 계산 필요)
            int estimatedShadow = route.getShadowPercentage();

            if (sunPos.getAltitude() < 20) {
                estimatedShadow = Math.min(100, estimatedShadow + 20);
            } else if (sunPos.getAltitude() > 60) {
                estimatedShadow = Math.max(0, estimatedShadow - 20);
            }

            hourlyShadow.put(hour, estimatedShadow);
        }

        return hourlyShadow;
    }

    /**
     * 경로 특성 분석
     */
    private RouteAnalysis.RouteCharacteristics analyzeCharacteristics(Route route) {
        RouteAnalysis.RouteCharacteristics characteristics =
                new RouteAnalysis.RouteCharacteristics();

        // 회전 지점 계산
        int turningPoints = 0;
        List<RoutePoint> points = route.getPoints();

        for (int i = 1; i < points.size() - 1; i++) {
            double angle = calculateTurningAngle(
                    points.get(i-1), points.get(i), points.get(i+1)
            );

            if (Math.abs(angle) > 30) { // 30도 이상 회전
                turningPoints++;
            }
        }

        characteristics.setTurningPoints(turningPoints);

        // 평균 그림자 밀도
        double avgShadowDensity = route.getShadowPercentage();
        characteristics.setAverageShadowDensity(avgShadowDensity);

        // 추천 시간대
        if (route.isAvoidShadow()) {
            characteristics.setRecommendedTimeOfDay("morning"); // 아침에 그림자가 적음
        } else {
            characteristics.setRecommendedTimeOfDay("afternoon"); // 오후에 그림자가 많음
        }

        return characteristics;
    }

    /**
     * 회전 각도 계산
     */
    private double calculateTurningAngle(RoutePoint p1, RoutePoint p2, RoutePoint p3) {
        double angle1 = Math.atan2(p2.getLat() - p1.getLat(), p2.getLng() - p1.getLng());
        double angle2 = Math.atan2(p3.getLat() - p2.getLat(), p3.getLng() - p2.getLng());

        double angle = Math.toDegrees(angle2 - angle1);

        // -180 ~ 180 범위로 정규화
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;

        return angle;
    }

    @Autowired
    private CacheManager cacheManager;

    /**
     * 캐시를 활용한 다중 경로 생성
     */
    @Cacheable(value = "routes", key = "#startLat + ',' + #startLng + ',' + #endLat + ',' + #endLng + ',' + #preferShadow + ',' + #dateTime.toLocalDate()")
    public List<Route> generateMultipleRouteOptionsWithCache(
            double startLat, double startLng,
            double endLat, double endLng,
            boolean preferShadow, LocalDateTime dateTime) {

        return generateMultipleRouteOptions(startLat, startLng, endLat, endLng, preferShadow, dateTime);
    }

    /**
     * 캐시 무효화
     */
    @CacheEvict(value = "routes", allEntries = true)
    public void clearRouteCache() {
        logger.info("경로 캐시 초기화");
    }
}
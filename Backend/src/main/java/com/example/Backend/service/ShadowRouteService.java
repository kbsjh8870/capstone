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
import org.springframework.dao.DataAccessException;
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
     * 그림자 경로 생성 메서드
     */
    private Route createEnhancedShadowRoute(double startLat, double startLng, double endLat, double endLng,
                                            List<ShadowArea> shadowAreas, SunPosition sunPos,
                                            boolean avoidShadow, LocalDateTime dateTime) {
        try {
            logger.info("=== 태양 위치 기반 방향성 우회 시작: avoidShadow={} ===", avoidShadow);

            // 기본 경로 생성
            String basicRouteJson = tmapApiService.getWalkingRoute(startLat, startLng, endLat, endLng);
            Route basicRoute = parseBasicRoute(basicRouteJson);

            // 경로가 너무 짧으면 우회하지 않음
            if (basicRoute.getDistance() < 200) {
                logger.info("경로가 너무 짧음 ({}m). 기본 경로 반환", basicRoute.getDistance());
                basicRoute.setAvoidShadow(avoidShadow);
                return basicRoute;
            }

            // 태양 위치 정보
            double sunAzimuth = sunPos.getAzimuth();           // 태양 방위각
            double shadowDirection = (sunAzimuth + 180) % 360; // 그림자 방향

            logger.info("태양 정보: 방위각={}도, 그림자방향={}도", sunAzimuth, shadowDirection);

            // 📍 avoidShadow 값에 따라 명확히 다른 방향으로 우회
            double detourDirection;
            String detourReason;

            if (avoidShadow) {
                // 그림자 회피: 태양 방향(햇빛)으로 우회
                detourDirection = sunAzimuth;
                detourReason = "햇빛 방향";
            } else {
                // 그림자 선호: 그림자 방향으로 우회
                detourDirection = shadowDirection;
                detourReason = "그림자 방향";
            }

            logger.info("우회 방향: {}도 ({})", detourDirection, detourReason);

            // 경로 중점에서 우회지점 계산
            double midLat = (startLat + endLat) / 2;
            double midLng = (startLng + endLng) / 2;
            double detourDistance = Math.min(basicRoute.getDistance() * 0.15, 200.0); // 최대 200m

            // 지리적 좌표 변환
            double dirRad = Math.toRadians(detourDirection);
            double latOffset = detourDistance * Math.cos(dirRad) / 111000.0;
            double lngOffset = detourDistance * Math.sin(dirRad) / (111000.0 * Math.cos(Math.toRadians(midLat)));

            double waypointLat = midLat + latOffset;
            double waypointLng = midLng + lngOffset;

            logger.info("우회지점: ({}, {}), 우회거리={}m", waypointLat, waypointLng, detourDistance);

            // 경유지를 거치는 우회 경로 생성
            String waypointRouteJson = tmapApiService.getWalkingRouteWithWaypoint(
                    startLat, startLng, endLat, endLng, waypointLat, waypointLng);
            Route detourRoute = parseBasicRoute(waypointRouteJson);

            // 우회 경로가 너무 길어지면 기본 경로 사용
            if (detourRoute.getDistance() > basicRoute.getDistance() * 1.4) {
                logger.warn("우회 경로가 너무 길어짐 ({}m > {}m). 기본 경로 사용",
                        detourRoute.getDistance(), basicRoute.getDistance() * 1.4);
                basicRoute.setAvoidShadow(avoidShadow);
                return basicRoute;
            }

            // 우회 경로 설정
            detourRoute.setBasicRoute(false);
            detourRoute.setAvoidShadow(avoidShadow);
            detourRoute.setDateTime(dateTime);

            logger.info("=== 방향성 우회 경로 생성 완료: {}방향, 거리={}m ===",
                    detourReason, detourRoute.getDistance());

            return detourRoute;

        } catch (Exception e) {
            logger.error("방향성 우회 경로 생성 실패: " + e.getMessage(), e);
            // 기본 경로라도 반환
            try {
                String fallbackJson = tmapApiService.getWalkingRoute(startLat, startLng, endLat, endLng);
                Route fallbackRoute = parseBasicRoute(fallbackJson);
                fallbackRoute.setAvoidShadow(avoidShadow);
                return fallbackRoute;
            } catch (Exception fallbackEx) {
                logger.error("기본 경로도 생성 실패: " + fallbackEx.getMessage(), fallbackEx);
                return createSimplePath(startLat, startLng, endLat, endLng);
            }
        }
    }











    /**
     * 실제 DB 그림자 정보를 경로에 적용
     */
    private void applyShadowInfoFromDB(Route route, List<ShadowArea> shadowAreas) {
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

            // 🚀 1차: 배치 처리로 기본 그림자 검사
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

            // 🚀 2차: 배치 처리로 상세 분석
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
     * 배치 처리로 기본 그림자 검사
     */
    private Map<Integer, Boolean> batchCheckBasicShadows(List<RoutePoint> points, List<ShadowArea> shadowAreas) {
        Map<Integer, Boolean> results = new HashMap<>();

        try {
            // 최적화된 Union 사용
            String mergedShadows = createOptimizedShadowUnion(shadowAreas);

            // 모든 포인트를 MULTIPOINT로 변환
            StringBuilder pointsWkt = new StringBuilder("MULTIPOINT(");
            for (int i = 0; i < points.size(); i++) {
                RoutePoint point = points.get(i);
                if (i > 0) pointsWkt.append(",");
                pointsWkt.append(String.format("(%f %f)", point.getLng(), point.getLat()));
            }
            pointsWkt.append(")");

            // 방향성 그림자에 맞는 관대한 검사
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
                            WHEN ST_DWithin(sg.geom, rp.point_geom, 0.0002) THEN true  -- 22m (기본)
                            WHEN ST_DWithin(sg.geom, rp.point_geom, 0.0004) THEN true  -- 44m (중간)
                            WHEN ST_DWithin(sg.geom, rp.point_geom, 0.0006) THEN true  -- 66m (관대)
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

                // 방향성 그림자 확장 범위 디버깅
                if (inShadow && distance > 0.0004) {
                    logger.debug("방향성 그림자 확장 범위에서 감지: 포인트={}, 거리={}m",
                            index, distance * 111000);
                }
            }

            logger.debug("방향성 그림자 배치 검사 완료: {}개 포인트 처리", results.size());

        } catch (Exception e) {
            logger.error("방향성 그림자 배치 검사 오류: " + e.getMessage(), e);
        }

        return results;
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
     * 배치 처리로 상세 그림자 검사 (기존 개별 검사를 배치로 변경)
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
     * (디버깅용) 그림자 계산 테스트 메서드
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
    private List<ShadowArea> calculateBuildingShadows(
            double startLat, double startLng, double endLat, double endLng, SunPosition sunPos) {

        try {
            if (sunPos.getAltitude() < -10) {
                logger.debug("태양 고도가 너무 낮음 ({}도). 그림자 계산 제외", sunPos.getAltitude());
                return new ArrayList<>();
            }

            // 태양 반대 방향 계산 (그림자가 뻗어나가는 방향)
            double shadowDirection = (sunPos.getAzimuth() + 180) % 360;

            // 태양 고도에 따른 그림자 길이 계산
            double shadowLength = calculateAdvancedShadowLength(sunPos.getAltitude());

            // 4단계 그림자 파라미터 계산
            double baseRadius = Math.min(shadowLength / 8, 30.0);           // 기본 반지름 (최대 30m)
            double ellipseRadius = Math.min(shadowLength / 6, 40.0);        // 타원 기본 반지름 (최대 40m)
            double extensionRatio = Math.min(shadowLength / 50.0, 4.0);     // 확장 비율 (최대 4배)
            double moveDistance = shadowLength * 0.5;                       // 이동 거리 (그림자 길이의 절반)
            double tallBuildingExtra = shadowLength * 0.8;                  // 높은 건물 추가 그림자

            logger.debug("4단계 그림자 계산: 태양고도={}도, 방위각={}도, 그림자방향={}도, 길이={}m",
                    sunPos.getAltitude(), sunPos.getAzimuth(), shadowDirection, shadowLength);
            logger.debug("파라미터: 기본반지름={}m, 타원반지름={}m, 확장비율={}배, 이동거리={}m",
                    baseRadius, ellipseRadius, extensionRatio, moveDistance);

            String sql = """
                    WITH route_area AS (
                        SELECT ST_Buffer(
                            ST_MakeLine(
                                ST_SetSRID(ST_MakePoint(?, ?), 4326),
                                ST_SetSRID(ST_MakePoint(?, ?), 4326)
                            ), 0.006
                        ) as geom
                    ),
                    directional_shadows AS (
                        SELECT 
                            b.id,
                            b."A16" as height,
                            ST_AsGeoJSON(b.geom) as building_geom,
                            ST_AsGeoJSON(
                                ST_Union(ARRAY[
                                    -- 1단계: 건물 자체 (완전 그림자)
                                    b.geom,
                    
                                    -- 2단계: 건물 주변 기본 그림자 (작은 원형)
                                    ST_Buffer(
                                        ST_Centroid(b.geom),
                                        ? / 111320.0
                                    ),
                    
                                    -- 3단계: 방향성 있는 그림자 (타원형 확장)
                                    ST_Translate(
                                        ST_Scale(
                                            ST_Buffer(
                                                ST_Centroid(b.geom), 
                                                ? / 111320.0
                                            ),
                                            1.0,        -- X축 비율 (폭 유지)
                                            ?           -- Y축 비율 (그림자 방향으로 확장)
                                        ),
                                        -- 그림자 방향으로 중심 이동
                                        ? * cos(radians(?)) / (111320.0 * cos(radians(ST_Y(ST_Centroid(b.geom))))),
                                        ? * sin(radians(?)) / 110540.0
                                    ),
                    
                                    -- 4단계: 높은 건물 추가 그림자 (20m 이상만)
                                    CASE 
                                        WHEN b."A16" > 20 THEN
                                            ST_Translate(
                                                ST_Buffer(
                                                    ST_Centroid(b.geom),
                                                    (? * LEAST(b."A16" / 30.0, 2.0)) / 111320.0
                                                ),
                                                ? * cos(radians(?)) / (111320.0 * cos(radians(ST_Y(ST_Centroid(b.geom))))),
                                                ? * sin(radians(?)) / 110540.0
                                            )
                                        ELSE
                                            ST_GeomFromText('POLYGON EMPTY', 4326)
                                    END
                                ])
                            ) as shadow_geom
                        FROM public."AL_D010_26_20250304" b, route_area r
                        WHERE ST_Intersects(b.geom, r.geom)
                          AND b."A16" > 2
                        ORDER BY 
                            ST_Distance(b.geom, 
                                ST_SetSRID(ST_MakePoint(?, ?), 4326)
                            ) ASC,
                            b."A16" DESC
                        LIMIT 35
                    )
                    SELECT id, height, building_geom, shadow_geom
                    FROM directional_shadows
                    """;

            // 정확히 17개 파라미터 전달
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql,
                    startLng, startLat, endLng, endLat,     // 1-4: route_area
                    baseRadius,                              // 5: 2단계 기본 그림자 반지름
                    ellipseRadius,                           // 6: 3단계 타원 기본 반지름
                    extensionRatio,                          // 7: 3단계 Y축 확장 비율
                    moveDistance, shadowDirection,           // 8-9: 3단계 X축 이동
                    moveDistance, shadowDirection,           // 10-11: 3단계 Y축 이동
                    tallBuildingExtra,                       // 12: 4단계 높은 건물 반지름
                    tallBuildingExtra, shadowDirection,      // 13-14: 4단계 X축 이동
                    tallBuildingExtra, shadowDirection,      // 15-16: 4단계 Y축 이동
                    startLng, startLat);                     // 17: 거리 계산용

            List<ShadowArea> shadowAreas = new ArrayList<>();
            for (Map<String, Object> row : results) {
                ShadowArea area = new ShadowArea();
                area.setId(((Number) row.get("id")).longValue());
                area.setHeight(((Number) row.get("height")).doubleValue());
                area.setBuildingGeometry((String) row.get("building_geom"));
                area.setShadowGeometry((String) row.get("shadow_geom"));
                shadowAreas.add(area);
            }

            logger.info("4단계 그림자 계산 완료: {}개 건물, 방향={}도, 총길이={}m",
                    shadowAreas.size(), shadowDirection, shadowLength);

            // 🔧 계산 결과 검증
            if (shadowAreas.isEmpty()) {
                logger.warn("경로 주변에 그림자를 생성할 건물이 없습니다. 건물 조건을 확인하세요.");

                // 건물 존재 여부 확인
                return verifyBuildingsInArea(startLat, startLng, endLat, endLng);
            }

            return shadowAreas;

        } catch (Exception e) {
            logger.error("4단계 그림자 계산 오류: " + e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * 태양 고도에 따른 그림자 길이 계산
     */
    private double calculateAdvancedShadowLength(double solarElevation) {
        if (solarElevation <= 0) {
            return 300; // 야간/일출전: 매우 긴 그림자
        } else if (solarElevation <= 5) {
            return 250; // 일출/일몰: 긴 그림자
        } else if (solarElevation <= 15) {
            return 180; // 이른 오전/늦은 오후: 중간 긴 그림자
        } else if (solarElevation <= 30) {
            return 120; // 오전/오후: 보통 그림자
        } else if (solarElevation <= 45) {
            return 80;  // 중간 높이: 짧은 그림자
        } else if (solarElevation <= 60) {
            return 50;  // 높은 태양: 더 짧은 그림자
        } else {
            return 30;  // 정오 근처: 매우 짧은 그림자
        }
    }

    /**
     * 건물 존재 여부 확인 및 디버깅
     */
    private List<ShadowArea> verifyBuildingsInArea(double startLat, double startLng, double endLat, double endLng) {
        try {
            String verifySql = """
                    WITH route_area AS (
                        SELECT ST_Buffer(
                            ST_MakeLine(
                                ST_SetSRID(ST_MakePoint(?, ?), 4326),
                                ST_SetSRID(ST_MakePoint(?, ?), 4326)
                            ), 0.006
                        ) as geom
                    )
                    SELECT 
                        COUNT(*) as total_buildings,
                        COUNT(CASE WHEN b."A16" > 2 THEN 1 END) as valid_buildings,
                        AVG(b."A16") as avg_height,
                        MAX(b."A16") as max_height
                    FROM public."AL_D010_26_20250304" b, route_area r
                    WHERE ST_Intersects(b.geom, r.geom)
                    """;

            Map<String, Object> stats = jdbcTemplate.queryForMap(verifySql,
                    startLng, startLat, endLng, endLat);

            logger.info("건물 통계: 전체={}개, 유효={}개, 평균높이={}m, 최고높이={}m",
                    stats.get("total_buildings"), stats.get("valid_buildings"),
                    stats.get("avg_height"), stats.get("max_height"));

            return new ArrayList<>(); // 빈 리스트 반환

        } catch (Exception e) {
            logger.error("건물 검증 오류: " + e.getMessage(), e);
            return new ArrayList<>();
        }
    }


    /**
     * 그림자 영역들을 하나의 GeoJSON으로 병합 (최적화)
     */
    private String createOptimizedShadowUnion(List<ShadowArea> shadowAreas) {
        if (shadowAreas == null || shadowAreas.isEmpty()) {
            return "{\"type\":\"GeometryCollection\",\"geometries\":[]}";
        }

        // 최대 25개로 제한하여 성능 최적화
        List<ShadowArea> limitedAreas = shadowAreas.size() > 25 ?
                shadowAreas.subList(0, 25) : shadowAreas;

        try {
            // PostGIS에서 직접 Union 연산 수행 (더 효율적)
            StringBuilder geomList = new StringBuilder();
            boolean hasValidGeometry = false;

            for (int i = 0; i < limitedAreas.size(); i++) {
                ShadowArea area = limitedAreas.get(i);
                String shadowGeom = area.getShadowGeometry();

                if (shadowGeom != null && !shadowGeom.isEmpty() && !shadowGeom.equals("null")) {
                    if (hasValidGeometry) {
                        geomList.append(",");
                    }
                    geomList.append("ST_GeomFromGeoJSON('").append(shadowGeom).append("')");
                    hasValidGeometry = true;
                }
            }

            if (!hasValidGeometry) {
                return "{\"type\":\"GeometryCollection\",\"geometries\":[]}";
            }

            // PostGIS에서 효율적인 Union 연산
            String unionSql = "SELECT ST_AsGeoJSON(ST_Union(ARRAY[" + geomList.toString() + "]))";

            String result = jdbcTemplate.queryForObject(unionSql, String.class);

            logger.debug("최적화된 그림자 Union 완료: {}개 영역", limitedAreas.size());

            return result != null ? result : "{\"type\":\"GeometryCollection\",\"geometries\":[]}";

        } catch (Exception e) {
            logger.error("그림자 Union 최적화 오류: " + e.getMessage(), e);

            // 실패 시 기존 방식으로 fallback
            StringBuilder sb = new StringBuilder();
            sb.append("{\"type\":\"GeometryCollection\",\"geometries\":[");

            boolean hasValidGeometry = false;
            for (ShadowArea area : limitedAreas) {
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
            return sb.toString();
        }
    }

    /**
     * 기본 T맵 경로 파싱
     */
    private Route parseBasicRoute(String tmapRouteJson) {
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
}
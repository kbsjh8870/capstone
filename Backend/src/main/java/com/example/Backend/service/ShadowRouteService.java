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
    public List<Route> calculateShadowRoutes(double startLat, double startLng,
                                             double endLat, double endLng,
                                             boolean avoidShadow, LocalDateTime dateTime) {
        List<Route> routes = new ArrayList<>();

        try {
            // 기본 경로 생성
            String tmapRouteJson = tmapApiService.getWalkingRoute(startLat, startLng, endLat, endLng);
            Route basicRoute = parseBasicRoute(tmapRouteJson);
            basicRoute.setBasicRoute(true);
            basicRoute.setDateTime(dateTime);

            // 그림자 정보 적용
            SunPosition sunPos = shadowService.calculateSunPosition(startLat, startLng, dateTime);
            List<ShadowArea> shadowAreas = calculateBuildingShadows(startLat, startLng, endLat, endLng, sunPos);

            applyShadowInfoFromDB(basicRoute, shadowAreas);
            basicRoute.setShadowAreas(shadowAreas);

            routes.add(basicRoute);

            // 그림자 경로 생성
            Route shadowRoute = parseBasicRoute(tmapRouteJson);
            applyShadowInfoFromDB(shadowRoute, shadowAreas);
            shadowRoute.setShadowAreas(shadowAreas);
            shadowRoute.setAvoidShadow(avoidShadow);
            shadowRoute.setDateTime(dateTime);
            shadowRoute.setBasicRoute(false);

            routes.add(shadowRoute);

            logger.info("그림자 경로 생성 완료: {}% 그림자", shadowRoute.getShadowPercentage());
            return routes;

        } catch (Exception e) {
            logger.error("그림자 경로 계산 오류: " + e.getMessage(), e);
            return routes;
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

            // 그림자 길이 계산
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

}
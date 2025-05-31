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
     * 개선된 그림자 경로 생성 - 대폭 우회 전략
     */
    private Route createEnhancedShadowRoute(double startLat, double startLng, double endLat, double endLng,
                                            List<ShadowArea> shadowAreas, SunPosition sunPos,
                                            boolean avoidShadow, LocalDateTime dateTime) {
        try {
            logger.debug("=== 개선된 그림자 경로 생성 시작 ===");
            logger.debug("그림자 {}: 태양 위치 고도={}도, 방위각={}도",
                    avoidShadow ? "회피" : "선호", sunPos.getAltitude(), sunPos.getAzimuth());

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
                String waypointRouteJson = tmapApiService.getWalkingRouteWithWaypoint(
                        startLat, startLng,
                        strategicWaypoint.getLat(), strategicWaypoint.getLng(),
                        endLat, endLng);

                Route enhancedRoute = parseBasicRoute(waypointRouteJson);
                enhancedRoute.setAvoidShadow(avoidShadow);

                // 경로 품질 확인
                if (isRouteQualityAcceptable(baseRoute, enhancedRoute)) {
                    logger.debug("개선된 경로 생성 성공: {}개 포인트", enhancedRoute.getPoints().size());
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
     * 전략적 경유지 생성
     */
    private RoutePoint createStrategicWaypoint(List<RoutePoint> basePoints, SunPosition sunPos,
                                               boolean avoidShadow, List<ShadowArea> shadowAreas) {
        if (basePoints.size() < 5) return null;

        try {
            // 경로 중간점 선택
            int middleIdx = basePoints.size() / 2;
            RoutePoint middlePoint = basePoints.get(middleIdx);

            // 🔧 수정된 부분: 태양 위치 기반 우회 방향 결정
            double targetDirection;
            if (avoidShadow) {
                // 그림자 회피: 태양 반대 방향으로 우회 (그림자가 적은 곳으로)
                // 건물 그림자는 태양 반대편에 생기므로, 그림자를 피하려면 태양쪽으로 가야 함
                targetDirection = sunPos.getAzimuth();
            } else {
                // 그림자 선호: 태양 반대 방향으로 우회 (그림자가 많은 곳으로)
                // 건물 그림자가 있는 태양 반대편으로 우회
                targetDirection = (sunPos.getAzimuth() + 180) % 360;
            }

            // 🔧 추가 개선: 실제 그림자 영역 분석 기반 우회 방향 조정
            if (!shadowAreas.isEmpty()) {
                targetDirection = adjustDirectionBasedOnShadowAreas(
                        middlePoint, shadowAreas, sunPos, avoidShadow, targetDirection);
            }

            // 태양 고도에 따른 우회 거리 조정
            double detourMeters = calculateOptimalDetourDistance(sunPos);

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

            logger.debug("전략적 경유지 생성: 태양방위={}도, 목표방위={}도, 우회거리={}m, avoidShadow={}",
                    sunPos.getAzimuth(), targetDirection, detourMeters, avoidShadow);

            return waypoint;

        } catch (Exception e) {
            logger.error("전략적 경유지 계산 오류: " + e.getMessage(), e);
            return null;
        }
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
        // 거리 차이가 기본 경로의 15% 이내인지 확인
        double distanceRatio = shadowRoute.getDistance() / baseRoute.getDistance();

        if (distanceRatio > 1.15) {
            logger.debug("경로가 너무 멀어짐: 기본={}m, 그림자={}m ({}% 증가)",
                    (int)baseRoute.getDistance(), (int)shadowRoute.getDistance(),
                    (int)((distanceRatio - 1) * 100));
            return false;
        }

        // 포인트 수가 합리적인지 확인
        if (shadowRoute.getPoints().size() < baseRoute.getPoints().size() * 0.5) {
            logger.debug("경로 포인트가 너무 적음");
            return false;
        }

        logger.debug("경로 품질 검증 통과: 거리 차이 {}%", (int)((distanceRatio - 1) * 100));
        return true;
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
            String mergedShadows = createShadowUnion(shadowAreas);
            List<RoutePoint> points = route.getPoints();
            int shadowCount = 0;

            // 각 경로 포인트가 실제 그림자 영역에 있는지 확인
            for (RoutePoint point : points) {
                boolean isInShadow = checkPointInShadowRelaxed(point, mergedShadows);
                point.setInShadow(isInShadow);

                if (isInShadow) {
                    shadowCount++;
                }
            }

            // 그림자 비율 계산
            int shadowPercentage = points.size() > 0 ? (shadowCount * 100 / points.size()) : 0;
            route.setShadowPercentage(shadowPercentage);

            logger.info("실제 DB 그림자 정보 적용 완료: {}% ({}/{}개 포인트)",
                    shadowPercentage, shadowCount, points.size());

        } catch (Exception e) {
            logger.error("DB 그림자 정보 적용 오류: " + e.getMessage(), e);
            for (RoutePoint point : route.getPoints()) {
                point.setInShadow(false);
            }
            route.setShadowPercentage(0);
        }
    }

    /**
     * 경로와 그림자 교차 여부를 관대하게 확인
     */
    private boolean checkPointInShadowRelaxed(RoutePoint point, String mergedShadows) {
        try {
            // 1. 정확한 포함 확인
            String containsSql = "SELECT ST_Contains(ST_GeomFromGeoJSON(?), ST_SetSRID(ST_MakePoint(?, ?), 4326))";
            Boolean exactContains = jdbcTemplate.queryForObject(containsSql, Boolean.class,
                    mergedShadows, point.getLng(), point.getLat());

            if (exactContains != null && exactContains) {
                return true;
            }

            // 2. 관대한 거리 기반 확인 (100m 이내)
            String distanceSql = "SELECT ST_DWithin(ST_GeomFromGeoJSON(?), ST_SetSRID(ST_MakePoint(?, ?), 4326), 0.001)";
            Boolean nearShadow = jdbcTemplate.queryForObject(distanceSql, Boolean.class,
                    mergedShadows, point.getLng(), point.getLat());

            return nearShadow != null && nearShadow;

        } catch (Exception e) {
            logger.warn("관대한 그림자 확인 실패: " + e.getMessage());
            return false;
        }
    }

    /**
     * 건물 그림자 계산
     */
    private List<ShadowArea> calculateBuildingShadows(
            double startLat, double startLng, double endLat, double endLng, SunPosition sunPos) {

        try {
            double shadowDirection = (sunPos.getAzimuth() + 180) % 360;

            String sql = """
            WITH route_area AS (
                SELECT ST_Buffer(
                    ST_MakeLine(
                        ST_SetSRID(ST_MakePoint(?, ?), 4326),
                        ST_SetSRID(ST_MakePoint(?, ?), 4326)
                    ), 0.003
                ) as geom
            ),
            building_shadows AS (
                SELECT 
                    b.id,
                    b."A16" as height,
                    ST_AsGeoJSON(b.geom) as building_geom,
                    ST_AsGeoJSON(
                        ST_Union(
                            b.geom,
                            ST_Translate(
                                b.geom,
                                (b."A16" / tan(radians(?))) * cos(radians(?)) / (111000.0 * cos(radians(ST_Y(ST_Centroid(b.geom))))),
                                (b."A16" / tan(radians(?))) * sin(radians(?)) / 111000.0
                            )
                        )
                    ) as shadow_geom
                FROM public."AL_D010_26_20250304" b, route_area r
                WHERE ST_Intersects(b.geom, r.geom)
                  AND b."A16" > 5
                  AND ? > 5
                LIMIT 30
            )
            SELECT id, height, building_geom, shadow_geom
            FROM building_shadows
            """;

            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql,
                    startLng, startLat, endLng, endLat,
                    sunPos.getAltitude(), shadowDirection,
                    sunPos.getAltitude(), shadowDirection,
                    sunPos.getAltitude());

            List<ShadowArea> shadowAreas = new ArrayList<>();
            for (Map<String, Object> row : results) {
                ShadowArea area = new ShadowArea();
                area.setId(((Number) row.get("id")).longValue());
                area.setHeight(((Number) row.get("height")).doubleValue());
                area.setBuildingGeometry((String) row.get("building_geom"));
                area.setShadowGeometry((String) row.get("shadow_geom"));
                shadowAreas.add(area);
            }

            logger.debug("PostGIS 기반 그림자 계산 완료: {}개 영역", shadowAreas.size());
            return shadowAreas;

        } catch (Exception e) {
            logger.error("PostGIS 그림자 계산 오류: " + e.getMessage(), e);
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
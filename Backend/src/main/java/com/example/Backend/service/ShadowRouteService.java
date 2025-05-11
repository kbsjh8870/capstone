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

            // 3. 도로 네트워크에 맞는 그림자 경로 생성
            Route shadowRoute = createRoadAlignedShadowRoute(basicRoute, sunPos, avoidShadow);

            // 그림자 정보 추가
            shadowRoute.setAvoidShadow(avoidShadow);
            shadowRoute.setDateTime(dateTime);
            shadowRoute.setBasicRoute(false);
            shadowRoute.setShadowPercentage(avoidShadow ? 15 : 75);

            routes.add(shadowRoute);

            return routes;
        } catch (Exception e) {
            logger.error("그림자 경로 계산 오류: " + e.getMessage(), e);

            // 오류 발생 시 기본 경로만 반환
            if (routes.isEmpty()) {
                Route basicRoute = createSimplePath(startLat, startLng, endLat, endLng);
                basicRoute.setBasicRoute(true);
                basicRoute.setDateTime(dateTime);
                routes.add(basicRoute);
            }

            return routes;
        }
    }

    /**
     * 도로 네트워크에 정렬된 그림자 경로 생성
     */
    private Route createRoadAlignedShadowRoute(Route basicRoute, SunPosition sunPos, boolean avoidShadow) {
        List<RoutePoint> originalPoints = basicRoute.getPoints();
        if (originalPoints.size() <= 2) {
            return basicRoute; // 충분한 포인트가 없으면 기본 경로 반환
        }

        // 기본 경로의 시작점과 끝점
        RoutePoint startPoint = originalPoints.get(0);
        RoutePoint endPoint = originalPoints.get(originalPoints.size() - 1);

        // 중간 지점 선택 (전체 경로의 약 1/3 지점)
        int midIndex = originalPoints.size() / 3;
        RoutePoint midPoint = originalPoints.get(midIndex);

        // 태양 위치에 따라 중간 지점 약간 이동 (도로 상에서 가장 가까운 지점을 찾도록 이동 크기를 작게)
        double angle = Math.toRadians(avoidShadow ? sunPos.getAzimuth() + 90 : sunPos.getAzimuth());
        double offset = 0.0001 * (avoidShadow ? 1 : -1); // 약 10m 이내로 제한

        double newLat = midPoint.getLat() + offset * Math.sin(angle);
        double newLng = midPoint.getLng() + offset * Math.cos(angle);

        // T맵 API로 경유지를 포함한 경로 요청
        try {
            String wayPointRouteJson = tmapApiService.getWalkingRouteWithWaypoint(
                    startPoint.getLat(), startPoint.getLng(),
                    newLat, newLng,
                    endPoint.getLat(), endPoint.getLng());

            // 새 경로 파싱
            Route shadowRoute = parseBasicRoute(wayPointRouteJson);
            shadowRoute.setAvoidShadow(avoidShadow);

            return shadowRoute;
        } catch (Exception e) {
            logger.error("경유지 경로 요청 실패: " + e.getMessage(), e);
            return createMultiPointShadowRoute(basicRoute, sunPos, avoidShadow); // 실패 시 다른 방법 시도
        }
    }

    /**
     * 다중 경유지를 사용한 그림자 경로 생성
     */
    private Route createMultiWaypointShadowRoute(Route basicRoute, SunPosition sunPos, boolean avoidShadow) {
        List<RoutePoint> originalPoints = basicRoute.getPoints();
        if (originalPoints.size() <= 4) {
            return createMultiPointShadowRoute(basicRoute, sunPos, avoidShadow);
        }

        // 시작점과 끝점
        RoutePoint startPoint = originalPoints.get(0);
        RoutePoint endPoint = originalPoints.get(originalPoints.size() - 1);

        // 2-3개의 중간 경유지 선택
        int numWaypoints = 2;
        double step = originalPoints.size() / (numWaypoints + 1.0);

        List<RoutePoint> waypoints = new ArrayList<>();
        for (int i = 1; i <= numWaypoints; i++) {
            int index = (int) (i * step);
            if (index >= originalPoints.size() - 1) continue;

            RoutePoint origPoint = originalPoints.get(index);

            // 태양 위치에 따른 이동
            double angle = Math.toRadians(avoidShadow ? sunPos.getAzimuth() + 90 : sunPos.getAzimuth());
            double offset = 0.0001 * (avoidShadow ? 1 : -1) * Math.sin(Math.PI * i / (numWaypoints + 1));

            double newLat = origPoint.getLat() + offset * Math.sin(angle);
            double newLng = origPoint.getLng() + offset * Math.cos(angle);

            RoutePoint waypoint = new RoutePoint();
            waypoint.setLat(newLat);
            waypoint.setLng(newLng);
            waypoints.add(waypoint);
        }

        // T맵 API로 다중 경유지 경로 요청
        try {
            String multiWaypointRouteJson = tmapApiService.getWalkingRouteWithMultiWaypoints(
                    startPoint.getLat(), startPoint.getLng(),
                    waypoints,
                    endPoint.getLat(), endPoint.getLng());

            Route shadowRoute = parseBasicRoute(multiWaypointRouteJson);
            shadowRoute.setAvoidShadow(avoidShadow);

            return shadowRoute;
        } catch (Exception e) {
            logger.error("다중 경유지 경로 요청 실패: " + e.getMessage(), e);
            return createMultiPointShadowRoute(basicRoute, sunPos, avoidShadow);
        }
    }

    /**
     * 포인트 추가 방식의 그림자 경로 생성 (API 요청 실패 시 백업 메서드)
     */
    private Route createMultiPointShadowRoute(Route basicRoute, SunPosition sunPos, boolean avoidShadow) {
        Route shadowRoute = new Route();
        List<RoutePoint> modifiedPoints = new ArrayList<>();

        List<RoutePoint> originalPoints = basicRoute.getPoints();
        if (originalPoints.size() <= 1) {
            return basicRoute; // 충분한 포인트가 없으면 기본 경로 반환
        }

        // 기본 경로의 시작점과 끝점
        RoutePoint startPoint = originalPoints.get(0);
        RoutePoint endPoint = originalPoints.get(originalPoints.size() - 1);

        // 시작점 추가
        RoutePoint startPointCopy = new RoutePoint();
        startPointCopy.setLat(startPoint.getLat());
        startPointCopy.setLng(startPoint.getLng());
        modifiedPoints.add(startPointCopy);

        // 기존 경로 포인트 복사 및 소폭 조정
        for (int i = 1; i < originalPoints.size() - 1; i++) {
            RoutePoint orig = originalPoints.get(i);

            // 태양 위치에 따라 포인트 조금씩 이동
            double angle = Math.toRadians(avoidShadow ? sunPos.getAzimuth() + 90 : sunPos.getAzimuth());

            // 경로 중간 부분에서 가장 큰 편차(그림자 효과) 적용
            double factor = Math.sin(Math.PI * i / originalPoints.size());
            double offset = 0.00005 * (avoidShadow ? 1 : -1) * factor; // 매우 작은 오프셋 (~5m)

            double newLat = orig.getLat() + offset * Math.sin(angle);
            double newLng = orig.getLng() + offset * Math.cos(angle);

            RoutePoint modified = new RoutePoint();
            modified.setLat(newLat);
            modified.setLng(newLng);
            modified.setInShadow(!avoidShadow);

            modifiedPoints.add(modified);
        }

        // 끝점 추가
        RoutePoint endPointCopy = new RoutePoint();
        endPointCopy.setLat(endPoint.getLat());
        endPointCopy.setLng(endPoint.getLng());
        modifiedPoints.add(endPointCopy);

        shadowRoute.setPoints(modifiedPoints);

        // 거리 및 소요 시간 계산
        double distance = calculateRouteDistance(modifiedPoints);
        shadowRoute.setDistance(distance);
        shadowRoute.setDuration((int) (distance / 67)); // 평균 보행 속도 (약 4km/h)

        return shadowRoute;
    }

    /**
     * 데이터베이스에서 건물 및 그림자 정보 조회
     */
    private List<ShadowArea> getShadowAreasFromDatabase(List<RoutePoint> routePoints, SunPosition sunPos) {
        // 경로를 LineString으로 변환
        String routeWkt = convertRoutePointsToWkt(routePoints);

        try {
            // 테이블 존재 여부 확인
            String checkTableSql = "SELECT EXISTS (SELECT FROM information_schema.tables " +
                    "WHERE table_schema = 'public' AND table_name = 'al_d010_26_20250304')";
            boolean tableExists = jdbcTemplate.queryForObject(checkTableSql, Boolean.class);

            if (!tableExists) {
                logger.warn("AL_D010_26_20250304 테이블이 존재하지 않습니다.");
                return new ArrayList<>();
            }

            // SQL 쿼리로 경로 주변 건물의 그림자 영역 계산
            String sql = "WITH route AS (" +
                    "  SELECT ST_GeomFromText(?, 4326) as geom" +
                    ")" +
                    "SELECT " +
                    "  b.id, " +
                    "  b.\"A16\" as height, " +
                    "  ST_AsGeoJSON(b.geom) as building_geom, " +
                    "  ST_AsGeoJSON(calculate_building_shadow(b.geom, b.\"A16\", ?, ?)) as shadow_geom " +
                    "FROM public.\"AL_D010_26_20250304\" b, route r " +
                    "WHERE ST_DWithin(b.geom, r.geom, 100) " +
                    "  AND b.\"A16\" > 0";

            List<Map<String, Object>> results = jdbcTemplate.queryForList(
                    sql, routeWkt, sunPos.getAzimuth(), sunPos.getAltitude());

            // 결과 파싱 및 그림자 영역 생성
            List<ShadowArea> shadowAreas = new ArrayList<>();

            for (Map<String, Object> row : results) {
                ShadowArea area = new ShadowArea();
                area.setId(((Number) row.get("id")).longValue());
                area.setHeight(((Number) row.get("height")).doubleValue());
                area.setBuildingGeometry((String) row.get("building_geom"));
                area.setShadowGeometry((String) row.get("shadow_geom"));
                shadowAreas.add(area);
            }

            return shadowAreas;
        } catch (DataAccessException e) {
            logger.error("데이터베이스 접근 오류: " + e.getMessage(), e);
            return new ArrayList<>();
        } catch (Exception e) {
            logger.error("그림자 영역 처리 오류: " + e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * 경로 포인트들을 WKT LineString으로 변환
     */
    private String convertRoutePointsToWkt(List<RoutePoint> points) {
        StringBuilder sb = new StringBuilder("LINESTRING(");
        for (int i = 0; i < points.size(); i++) {
            RoutePoint point = points.get(i);
            sb.append(point.getLng()).append(" ").append(point.getLat());
            if (i < points.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append(")");
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

            // 각 경로 세그먼트 처리
            for (JsonNode feature : features) {
                JsonNode properties = feature.path("properties");

                // 거리 및 시간 정보 추출
                if (properties.has("distance")) {
                    totalDistance += properties.path("distance").asDouble();
                }
                if (properties.has("time")) {
                    totalDuration += properties.path("time").asInt();
                }

                // 좌표 정보 추출
                JsonNode geometry = feature.path("geometry");
                if (geometry.path("type").asText().equals("LineString")) {
                    JsonNode coordinates = geometry.path("coordinates");

                    for (JsonNode coord : coordinates) {
                        double lng = coord.get(0).asDouble();
                        double lat = coord.get(1).asDouble();

                        RoutePoint point = new RoutePoint();
                        point.setLat(lat);
                        point.setLng(lng);
                        points.add(point);
                    }
                }
            }

            route.setPoints(points);
            route.setDistance(totalDistance);
            route.setDuration(totalDuration / 60); // 초 -> 분 변환

        } catch (Exception e) {
            logger.error("경로 파싱 오류: " + e.getMessage(), e);
            // 오류 발생시 빈 경로 반환
            route.setPoints(new ArrayList<>());
            route.setDistance(0);
            route.setDuration(0);
        }

        return route;
    }

    /**
     * 경로 거리 계산
     */
    private double calculateRouteDistance(List<RoutePoint> points) {
        double distance = 0;
        for (int i = 0; i < points.size() - 1; i++) {
            RoutePoint p1 = points.get(i);
            RoutePoint p2 = points.get(i + 1);
            distance += calculateDistance(p1.getLat(), p1.getLng(), p2.getLat(), p2.getLng());
        }
        return distance;
    }

    /**
     * 두 지점 간 거리 계산 (Haversine 공식)
     */
    private double calculateDistance(double lat1, double lng1, double lat2, double lng2) {
        final int R = 6371000; // 지구 반경 (미터)
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

        // 시작점
        RoutePoint startPoint = new RoutePoint();
        startPoint.setLat(startLat);
        startPoint.setLng(startLng);
        points.add(startPoint);

        // 도착점
        RoutePoint endPoint = new RoutePoint();
        endPoint.setLat(endLat);
        endPoint.setLng(endLng);
        points.add(endPoint);

        route.setPoints(points);

        // 거리 계산
        double distance = calculateDistance(startLat, startLng, endLat, endLng);
        route.setDistance(distance);
        route.setDuration((int) (distance / 67)); // 평균 보행 속도 4km/h (약 67m/분)

        return route;
    }
}
package com.example.Backend.service;

import com.example.Backend.model.Route;
import com.example.Backend.model.RoutePoint;
import com.example.Backend.model.ShadowArea;
import com.example.Backend.model.SunPosition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class ShadowRouteService {

    @Autowired
    private TmapApiService tmapApiService;

    @Autowired
    private ShadowService shadowService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 그림자를 고려한 대체 경로 계산
     */
    public List<Route> calculateShadowRoutes(
            double startLat, double startLng,
            double endLat, double endLng,
            boolean avoidShadow, LocalDateTime dateTime) {

        List<Route> routes = new ArrayList<>();

        // 1. 기본 경로 획득 (T맵 API)
        String tmapRouteJson = tmapApiService.getWalkingRoute(startLat, startLng, endLat, endLng);
        Route basicRoute = parseBasicRoute(tmapRouteJson);
        basicRoute.setBasicRoute(true);
        routes.add(basicRoute);

        // 2. 태양 위치 계산
        SunPosition sunPos = shadowService.calculateSunPosition(startLat, startLng, dateTime);

        // 3. 경로 주변 건물들의 그림자 계산
        List<RoutePoint> routePoints = basicRoute.getPoints();
        List<ShadowArea> shadowAreas = shadowService.getShadowsAlongRoute(routePoints, sunPos);

        // 4. 그림자 정보를 기반으로 대체 경로 계산
        Route shadowRoute = calculateAlternateRoute(
                startLat, startLng, endLat, endLng, shadowAreas, avoidShadow);

        // 그림자 정보 추가
        shadowRoute.setShadowAreas(shadowAreas);
        shadowRoute.setAvoidShadow(avoidShadow);
        shadowRoute.setDateTime(dateTime);
        shadowRoute.setBasicRoute(false);
        shadowRoute.calculateShadowPercentage(shadowAreas);

        routes.add(shadowRoute);

        return routes;
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
            e.printStackTrace();
            // 오류 발생시 빈 경로 반환
            route.setPoints(new ArrayList<>());
            route.setDistance(0);
            route.setDuration(0);
        }

        return route;
    }

    /**
     * 그림자 영역을 고려한 대체 경로 계산
     */
    private Route calculateAlternateRoute(
            double startLat, double startLng,
            double endLat, double endLng,
            List<ShadowArea> shadowAreas,
            boolean avoidShadow) {

        try {
            // 출발점과 도착점 생성
            String startPoint = String.format("POINT(%f %f)", startLng, startLat);
            String endPoint = String.format("POINT(%f %f)", endLng, endLat);

            // 그림자 영역들을 하나의 Geometry로 병합
            String shadowUnion = createShadowUnion(shadowAreas);

            // 빈 그림자 영역 처리
            if (shadowUnion.equals("{}") || shadowAreas.isEmpty()) {
                // 그림자 영역이 없으면 기본 경로 반환
                return createSimplePath(startLat, startLng, endLat, endLng);
            }

            // 로그 추가
            log.debug("GeoJSON 변환: " + shadowUnion);

            // SQL을 사용하여 대체 경로 계산
            try {
                String sql = "SELECT ST_AsGeoJSON(calculate_shadow_aware_route(" +
                        "ST_GeomFromText(?, 4326), " +  // 시작점
                        "ST_GeomFromText(?, 4326), " +  // 도착점
                        "ST_GeomFromGeoJSON(?), " +    // 병합된 그림자 영역
                        "?)) AS route_geom";           // 그림자 회피 여부

                String routeGeoJson = jdbcTemplate.queryForObject(
                        sql, String.class, startPoint, endPoint, shadowUnion, avoidShadow);

                // GeoJSON을 경로 객체로 변환
                return parseRouteFromGeoJson(routeGeoJson, avoidShadow);
            } catch (Exception e) {
                log.error("대체 경로 계산 실패: " + e.getMessage(), e);
                // 오류 발생 시 기본 경로 생성
                return createSimplePath(startLat, startLng, endLat, endLng);
            }
        } catch (Exception e) {
            log.error("대체 경로 처리 오류: " + e.getMessage(), e);
            return createSimplePath(startLat, startLng, endLat, endLng);
        }
    }

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
     * 그림자 영역들을 하나의 GeoJSON으로 병합
     */
    private String createShadowUnion(List<ShadowArea> shadowAreas) {
        if (shadowAreas == null || shadowAreas.isEmpty()) {
            // 유효한 빈 GeoJSON
            return "{\"type\":\"GeometryCollection\",\"geometries\":[]}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"GeometryCollection\",\"geometries\":[");

        boolean hasValidGeometry = false;
        for (int i = 0; i < shadowAreas.size(); i++) {
            ShadowArea area = shadowAreas.get(i);
            String shadowGeom = area.getShadowGeometry();

            // null이 아니고 유효한 GeoJSON인지 확인
            if (shadowGeom != null && !shadowGeom.isEmpty() && !shadowGeom.equals("null")) {
                if (hasValidGeometry) {
                    sb.append(",");
                }
                sb.append(shadowGeom);
                hasValidGeometry = true;
            }
        }

        sb.append("]}");

        // 유효한 지오메트리가 없으면 빈 컬렉션 반환
        if (!hasValidGeometry) {
            return "{\"type\":\"GeometryCollection\",\"geometries\":[]}";
        }

        return sb.toString();
    }

    /**
     * GeoJSON에서 경로 객체 생성
     */
    private Route parseRouteFromGeoJson(String geoJson, boolean avoidShadow) {
        Route route = new Route();
        List<RoutePoint> points = new ArrayList<>();

        try {
            JsonNode rootNode = objectMapper.readTree(geoJson);

            if (rootNode.has("type") && rootNode.path("type").asText().equals("LineString")) {
                JsonNode coordinates = rootNode.path("coordinates");

                for (JsonNode coord : coordinates) {
                    double lng = coord.get(0).asDouble();
                    double lat = coord.get(1).asDouble();

                    RoutePoint point = new RoutePoint();
                    point.setLat(lat);
                    point.setLng(lng);
                    points.add(point);
                }
            }

            route.setPoints(points);

            // 경로 거리 계산
            double distance = 0;
            for (int i = 0; i < points.size() - 1; i++) {
                RoutePoint p1 = points.get(i);
                RoutePoint p2 = points.get(i + 1);

                double latDistance = Math.toRadians(p2.getLat() - p1.getLat());
                double lngDistance = Math.toRadians(p2.getLng() - p1.getLng());

                double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                        + Math.cos(Math.toRadians(p1.getLat())) * Math.cos(Math.toRadians(p2.getLat()))
                        * Math.sin(lngDistance / 2) * Math.sin(lngDistance / 2);
                double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

                distance += 6371000 * c; // 지구 반경(m) * 중심각(라디안)
            }

            route.setDistance(distance);
            route.setDuration((int) (distance / 67)); // 평균 보행 속도 4km/h (약 67m/분)
            route.setAvoidShadow(avoidShadow);

        } catch (Exception e) {
            e.printStackTrace();
            route.setPoints(new ArrayList<>());
            route.setDistance(0);
            route.setDuration(0);
        }

        return route;
    }
}
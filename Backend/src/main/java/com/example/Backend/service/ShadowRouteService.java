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

            // 3. 경로 주변 건물들의 그림자 계산
            List<RoutePoint> routePoints = basicRoute.getPoints();
            List<ShadowArea> shadowAreas = new ArrayList<>();

            try {
                // 데이터베이스에서 건물 정보 조회 - 오류 발생 시 우회
                shadowAreas = getShadowAreasFromDatabase(routePoints, sunPos);
            } catch (Exception e) {
                logger.error("건물 데이터 조회 오류: " + e.getMessage(), e);
                // 오류 발생 시 빈 그림자 영역 목록 사용
            }

            // 4. 그림자 정보를 기반으로 대체 경로 계산
            Route shadowRoute;
            if (!shadowAreas.isEmpty()) {
                // 건물 데이터가 있으면 실제 그림자 정보 활용
                shadowRoute = calculateAlternateRoute(startLat, startLng, endLat, endLng, shadowAreas, avoidShadow);
            } else {
                // 건물 데이터가 없으면 시뮬레이션 경로 생성
                shadowRoute = createEnhancedShadowRoute(basicRoute, sunPos, avoidShadow);
            }

            // 그림자 정보 추가
            shadowRoute.setShadowAreas(shadowAreas);
            shadowRoute.setAvoidShadow(avoidShadow);
            shadowRoute.setDateTime(dateTime);
            shadowRoute.setBasicRoute(false);

            // 그림자 비율 계산 또는 설정
            if (!shadowAreas.isEmpty()) {
                shadowRoute.calculateShadowPercentage(shadowAreas);
            } else {
                shadowRoute.setShadowPercentage(avoidShadow ? 15 : 75); // 임의의 그림자 비율
            }

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
                    "  b.\"A16\" as height, " +  // 큰따옴표로 컬럼 이름 감싸기
                    "  ST_AsGeoJSON(b.geom) as building_geom, " +
                    "  ST_AsGeoJSON(calculate_building_shadow(b.geom, b.\"A16\", ?, ?)) as shadow_geom " +  // 큰따옴표 사용
                    "FROM public.\"AL_D010_26_20250304\" b, route r " +  // 테이블 이름 큰따옴표로 감싸기
                    "WHERE ST_DWithin(b.geom, r.geom, 100) " +  // 경로 100m 이내 건물
                    "  AND b.\"A16\" > 0";  // 높이가 있는 건물만

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
            if (shadowUnion.equals("{\"type\":\"GeometryCollection\",\"geometries\":[]}") || shadowAreas.isEmpty()) {
                // 그림자 영역이 없으면 기본 경로 반환
                return createSimplePath(startLat, startLng, endLat, endLng);
            }

            try {
                // 함수 존재 여부 확인
                String checkFunctionSql = "SELECT EXISTS (SELECT FROM pg_proc WHERE proname = 'calculate_shadow_aware_route')";
                boolean functionExists = jdbcTemplate.queryForObject(checkFunctionSql, Boolean.class);

                if (!functionExists) {
                    logger.warn("calculate_shadow_aware_route 함수가 존재하지 않습니다.");
                    return createSimplePath(startLat, startLng, endLat, endLng);
                }

                // SQL을 사용하여 대체 경로 계산
                String sql = "SELECT ST_AsGeoJSON(calculate_shadow_aware_route(" +
                        "ST_GeomFromText(?, 4326), " +  // 시작점
                        "ST_GeomFromText(?, 4326), " +  // 도착점
                        "ST_GeomFromGeoJSON(?), " +    // 병합된 그림자 영역
                        "?)) AS route_geom";           // 그림자 회피 여부

                String routeGeoJson = jdbcTemplate.queryForObject(
                        sql, String.class, startPoint, endPoint, shadowUnion, avoidShadow);

                // GeoJSON을 경로 객체로 변환
                Route route = parseRouteFromGeoJson(routeGeoJson, avoidShadow);
                return route;
            } catch (Exception e) {
                logger.error("대체 경로 계산 실패: " + e.getMessage(), e);
                // 오류 발생 시 시뮬레이션 경로 생성
                Route basicRoute = createSimplePath(startLat, startLng, endLat, endLng);
                List<RoutePoint> basicPoints = basicRoute.getPoints();

                if (basicPoints.size() >= 2) {
                    SunPosition sunPos = shadowService.calculateSunPosition(startLat, startLng, LocalDateTime.now());
                    Route enhancedRoute = createEnhancedShadowRoute(basicRoute, sunPos, avoidShadow);
                    return enhancedRoute;
                } else {
                    return basicRoute;
                }
            }
        } catch (Exception e) {
            logger.error("대체 경로 처리 오류: " + e.getMessage(), e);
            return createSimplePath(startLat, startLng, endLat, endLng);
        }
    }

    /**
     * 개선된 그림자 경로 생성 메서드 - 데이터베이스 대신 시뮬레이션 사용
     */
    private Route createEnhancedShadowRoute(Route basicRoute, SunPosition sunPos, boolean avoidShadow) {
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

        // 중간 지점 생성
        if (originalPoints.size() >= 3) {
            // 기존 경로에서 중간 지점들을 활용 - 일부 포인트만 활용하여 경로 단순화
            int step = Math.max(1, originalPoints.size() / 5); // 포인트 수 제한

            for (int i = step; i < originalPoints.size() - 1; i += step) {
                RoutePoint orig = originalPoints.get(i);

                // 태양 위치에 따라 포인트 이동
                double angle = Math.toRadians(avoidShadow ? sunPos.getAzimuth() + 90 : sunPos.getAzimuth());
                double offset = 0.0002 * (avoidShadow ? 1 : -1); // 약 20-30m

                // 경로의 중간 부분에서 더 큰 편차 적용
                double factor = Math.sin(Math.PI * i / originalPoints.size());
                offset *= factor;

                double newLat = orig.getLat() + offset * Math.sin(angle);
                double newLng = orig.getLng() + offset * Math.cos(angle);

                RoutePoint modified = new RoutePoint();
                modified.setLat(newLat);
                modified.setLng(newLng);
                modified.setInShadow(!avoidShadow);

                modifiedPoints.add(modified);
            }
        } else {
            // 중간 지점이 없는 경우, 새로운 중간 지점 생성
            double midLat = (startPoint.getLat() + endPoint.getLat()) / 2;
            double midLng = (startPoint.getLng() + endPoint.getLng()) / 2;

            // 태양 위치에 따라 중간 지점 이동
            double angle = Math.toRadians(avoidShadow ? sunPos.getAzimuth() + 90 : sunPos.getAzimuth());
            double offset = 0.0003 * (avoidShadow ? 1 : -1); // 약 30-50m

            midLat += offset * Math.sin(angle);
            midLng += offset * Math.cos(angle);

            RoutePoint midPoint = new RoutePoint();
            midPoint.setLat(midLat);
            midPoint.setLng(midLng);
            midPoint.setInShadow(!avoidShadow);

            modifiedPoints.add(midPoint);
        }

        // 끝점 추가
        RoutePoint endPointCopy = new RoutePoint();
        endPointCopy.setLat(endPoint.getLat());
        endPointCopy.setLng(endPoint.getLng());
        modifiedPoints.add(endPointCopy);

        shadowRoute.setPoints(modifiedPoints);

        // 거리 및 소요 시간 계산
        double distance = 0;
        for (int i = 0; i < modifiedPoints.size() - 1; i++) {
            RoutePoint p1 = modifiedPoints.get(i);
            RoutePoint p2 = modifiedPoints.get(i + 1);
            distance += calculateDistance(p1.getLat(), p1.getLng(), p2.getLat(), p2.getLng());
        }

        shadowRoute.setDistance(distance);
        shadowRoute.setDuration((int) (distance / 67)); // 평균 보행 속도

        return shadowRoute;
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
                distance += calculateDistance(p1.getLat(), p1.getLng(), p2.getLat(), p2.getLng());
            }

            route.setDistance(distance);
            route.setDuration((int) (distance / 67)); // 평균 보행 속도 4km/h (약 67m/분)
            route.setAvoidShadow(avoidShadow);

        } catch (Exception e) {
            logger.error("GeoJSON 파싱 오류: " + e.getMessage(), e);
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
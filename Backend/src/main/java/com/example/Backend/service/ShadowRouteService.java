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
            List<ShadowArea> shadowAreas = calculateBuildingShadows(startLat, startLng, endLat, endLng, sunPos);

            // 4. 그림자 정보를 기반으로 대체 경로 계산
            Route shadowRoute;
            if (!shadowAreas.isEmpty()) {
                // 건물 데이터가 있으면 그림자 정보 활용
                shadowRoute = calculateShadowAwareRoute(startLat, startLng, endLat, endLng, shadowAreas, avoidShadow);
            } else {
                // 건물 데이터가 없으면 태양 위치 기반 경로 생성
                shadowRoute = createRoadAlignedShadowRoute(basicRoute, sunPos, avoidShadow);
            }

            // 그림자 정보 추가
            shadowRoute.setShadowAreas(shadowAreas);
            shadowRoute.setAvoidShadow(avoidShadow);
            shadowRoute.setDateTime(dateTime);
            shadowRoute.setBasicRoute(false);

            // 그림자 비율 계산
            if (!shadowAreas.isEmpty()) {
                calculateShadowPercentage(shadowRoute, shadowAreas);
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
     * 건물 그림자 계산
     */
    private List<ShadowArea> calculateBuildingShadows(double startLat, double startLng,
                                                      double endLat, double endLng,
                                                      SunPosition sunPos) {
        try {
            // 경로 주변 영역 정의 (출발-도착 경로 박스 + 버퍼)
            String boundingBoxSql = "SELECT ST_Buffer(ST_Envelope(ST_MakeLine(" +
                    "ST_SetSRID(ST_MakePoint(?, ?), 4326), " +
                    "ST_SetSRID(ST_MakePoint(?, ?), 4326))), 0.003) as search_area";

            // 테이블 존재 여부 확인
            String checkTableSql = "SELECT EXISTS (SELECT FROM information_schema.tables " +
                    "WHERE table_schema = 'public' AND table_name = 'AL_D010_26_20250304')";
            boolean tableExists = jdbcTemplate.queryForObject(checkTableSql, Boolean.class);

            if (!tableExists) {
                logger.warn("건물 데이터 테이블이 존재하지 않습니다.");
                return new ArrayList<>();
            }

            // 해당 영역 내 건물 검색 및 그림자 계산
            String shadowSql = "WITH search_area AS (" + boundingBoxSql + ") " +
                    "SELECT b.id, b.\"A16\" as height, " +
                    "ST_AsGeoJSON(b.geom) as building_geom, " +
                    "ST_AsGeoJSON(calculate_shadow_geometry(b.geom, b.\"A16\", ?, ?, ?)) as shadow_geom " +  // 3개의 파라미터
                    "FROM public.\"AL_D010_26_20250304\" b, search_area sa " +
                    "WHERE ST_Intersects(b.geom, sa.search_area) " +
                    "AND b.\"A16\" > 5";

            // calculate_shadow_geometry 함수 존재 확인
            String checkFunctionSql = "SELECT EXISTS (SELECT FROM pg_proc WHERE proname = 'calculate_shadow_geometry')";
            boolean functionExists = jdbcTemplate.queryForObject(checkFunctionSql, Boolean.class);

           /* if (!functionExists) {
                // 함수가 없으면 간단한 그림자 계산 로직 적용
                shadowSql = "WITH search_area AS (" + boundingBoxSql + ") " +
                        "SELECT b.id, b.\"A16\" as height, " +
                        "ST_AsGeoJSON(b.geom) as building_geom, " +
                        "ST_AsGeoJSON(ST_Translate(b.geom, " +
                        "   ? * b.\"A16\" / GREATEST(TAN(RADIANS(?)), 0.1), " +  // 그림자 길이와 방향 계산
                        "   ? * b.\"A16\" / GREATEST(TAN(RADIANS(?)), 0.1) " +
                        ")) as shadow_geom " +
                        "FROM public.\"AL_D010_26_20250304\" b, search_area sa " +
                        "WHERE ST_Intersects(b.geom, sa.search_area) " +
                        "AND b.\"A16\" > 5";  // 의미 있는 높이의 건물만
            }*/

            // 태양 고도가 낮을 때 방위각에 따른 그림자 방향 계산
            double shadowDirX = -Math.cos(Math.toRadians(sunPos.getAzimuth()));
            double shadowDirY = -Math.sin(Math.toRadians(sunPos.getAzimuth()));

            List<Map<String, Object>> results = jdbcTemplate.queryForList(
                    shadowSql,
                    startLng, startLat, endLng, endLat,
                    shadowDirX, shadowDirY, sunPos.getAltitude());

            // 결과 파싱
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
        } catch (Exception e) {
            logger.error("건물 그림자 계산 오류: " + e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * 그림자 영역을 고려한 경로 계산
     */
    private Route calculateShadowAwareRoute(
            double startLat, double startLng,
            double endLat, double endLng,
            List<ShadowArea> shadowAreas,
            boolean avoidShadow) {

        try {
            // 그림자 영역들을 하나의 다각형으로 합치기
            String mergedShadows = createShadowUnion(shadowAreas);

            if (mergedShadows.equals("{\"type\":\"GeometryCollection\",\"geometries\":[]}") || shadowAreas.isEmpty()) {
                // 그림자 영역이 없으면 기본 경로 요청
                return createSimplePath(startLat, startLng, endLat, endLng);
            }

            // 출발점과 도착점 포인트
            String startPoint = String.format("POINT(%f %f)", startLng, startLat);
            String endPoint = String.format("POINT(%f %f)", endLng, endLat);

            // 함수 존재 여부 확인
            String checkFunctionSql = "SELECT EXISTS (SELECT FROM pg_proc WHERE proname = 'calculate_shadow_aware_route')";
            boolean functionExists = false;

            try {
                functionExists = jdbcTemplate.queryForObject(checkFunctionSql, Boolean.class);
            } catch (Exception e) {
                logger.warn("함수 존재 여부 확인 실패: " + e.getMessage());
            }

            Route route;

            if (functionExists) {
                // DB 함수를 사용하여 그림자 경로 계산
                String sql = "SELECT ST_AsGeoJSON(calculate_shadow_aware_route(" +
                        "ST_GeomFromText(?, 4326), " +  // 시작점
                        "ST_GeomFromText(?, 4326), " +  // 도착점
                        "ST_GeomFromGeoJSON(?), " +    // 병합된 그림자 영역
                        "?)) AS route_geom";           // 그림자 회피 여부

                String routeGeoJson = jdbcTemplate.queryForObject(
                        sql, String.class, startPoint, endPoint, mergedShadows, avoidShadow);

                route = parseRouteFromGeoJson(routeGeoJson, avoidShadow);
            } else {
                // DB 함수가 없으면 경유지 방식으로 대체
                route = createWaypointsBasedOnShadows(startLat, startLng, endLat, endLng, mergedShadows, avoidShadow);
            }

            return route;
        } catch (Exception e) {
            logger.error("그림자 인식 경로 계산 오류: " + e.getMessage(), e);
            // 오류 발생 시 태양 위치 기반 방식으로 폴백
            String tmapRouteJson = tmapApiService.getWalkingRoute(startLat, startLng, endLat, endLng);
            Route basicRoute = parseBasicRoute(tmapRouteJson);
            SunPosition sunPos = shadowService.calculateSunPosition(startLat, startLng, LocalDateTime.now());
            return createRoadAlignedShadowRoute(basicRoute, sunPos, avoidShadow);
        }
    }

    /**
     * 그림자 정보에 기반한 경유지 방식 경로 생성
     */
    private Route createWaypointsBasedOnShadows(
            double startLat, double startLng,
            double endLat, double endLng,
            String mergedShadows, boolean avoidShadow) {

        try {
            // 1. 기본 경로 획득
            String baseRouteJson = tmapApiService.getWalkingRoute(startLat, startLng, endLat, endLng);
            Route baseRoute = parseBasicRoute(baseRouteJson);

            // 기본 경로가 너무 짧으면 그대로 반환
            if (baseRoute.getPoints().size() <= 3) {
                return baseRoute;
            }

            // 2. 경로 중간 지점 샘플링 (3-5개 포인트)
            List<RoutePoint> originalPoints = baseRoute.getPoints();
            int numSamples = Math.min(5, originalPoints.size() / 4);
            numSamples = Math.max(numSamples, 1); // 최소 1개

            List<RoutePoint> samplePoints = new ArrayList<>();
            double step = originalPoints.size() / (double)(numSamples + 1);

            for (int i = 1; i <= numSamples; i++) {
                int index = (int)(i * step);
                if (index >= originalPoints.size()) continue;
                samplePoints.add(originalPoints.get(index));
            }

            // 3. 샘플 포인트별 그림자 영역 포함 여부 확인
            List<RoutePoint> waypoints = new ArrayList<>();
            String pointInShadowSql = "SELECT ST_Contains(ST_GeomFromGeoJSON(?), ST_SetSRID(ST_MakePoint(?, ?), 4326))";

            for (RoutePoint point : samplePoints) {
                boolean isInShadow = false;

                try {
                    isInShadow = jdbcTemplate.queryForObject(
                            pointInShadowSql, Boolean.class,
                            mergedShadows, point.getLng(), point.getLat());
                } catch (Exception e) {
                    logger.warn("포인트 그림자 확인 실패: " + e.getMessage());
                }

                // 그림자 회피/따라가기 조건에 맞는 포인트만 선택
                if ((avoidShadow && !isInShadow) || (!avoidShadow && isInShadow)) {
                    // 조건에 맞는 포인트 주변으로 적절한 경유지 추가
                    RoutePoint waypoint = new RoutePoint();

                    // 기존 점에서 조금 이동 (랜덤성 부여 - 다양한 경로를 위해)
                    double jitter = 0.0001 * (Math.random() - 0.5); // ~10m 이내
                    waypoint.setLat(point.getLat() + jitter);
                    waypoint.setLng(point.getLng() + jitter);

                    waypoints.add(waypoint);

                    // 최대 2개 경유지만 사용
                    if (waypoints.size() >= 2) break;
                }
            }

            // 경유지가 없으면 직접 생성
            if (waypoints.isEmpty()) {
                // 중간 지점 계산
                double midLat = (startLat + endLat) / 2;
                double midLng = (startLng + endLng) / 2;

                // 직각 방향으로 약간 이동
                double dx = endLng - startLng;
                double dy = endLat - startLat;
                double dist = Math.sqrt(dx*dx + dy*dy);

                // 왼쪽 또는 오른쪽으로 이동 (그림자 회피/따라가기에 따라)
                double offsetFactor = 0.0005 * (avoidShadow ? 1 : -1); // ~50m
                double offsetLat = offsetFactor * (-dy / dist);
                double offsetLng = offsetFactor * (dx / dist);

                RoutePoint waypoint = new RoutePoint();
                waypoint.setLat(midLat + offsetLat);
                waypoint.setLng(midLng + offsetLng);
                waypoints.add(waypoint);
            }

            // 4. 경유지 기반 경로 요청
            try {
                if (waypoints.size() == 1) {
                    String waypointRouteJson = tmapApiService.getWalkingRouteWithWaypoint(
                            startLat, startLng,
                            waypoints.get(0).getLat(), waypoints.get(0).getLng(),
                            endLat, endLng);

                    return parseBasicRoute(waypointRouteJson);
                } else {
                    String multiWaypointRouteJson = tmapApiService.getWalkingRouteWithMultiWaypoints(
                            startLat, startLng, waypoints, endLat, endLng);

                    return parseBasicRoute(multiWaypointRouteJson);
                }
            } catch (Exception e) {
                logger.error("경유지 경로 요청 실패: " + e.getMessage(), e);
                return baseRoute;
            }

        } catch (Exception e) {
            logger.error("경유지 기반 경로 생성 오류: " + e.getMessage(), e);
            return createSimplePath(startLat, startLng, endLat, endLng);
        }
    }

    /**
     * 그림자 비율 계산
     */
    private void calculateShadowPercentage(Route route, List<ShadowArea> shadowAreas) {
        if (shadowAreas.isEmpty()) {
            route.setShadowPercentage(50); // 기본값
            return;
        }

        try {
            // 그림자 영역들을 하나의 다각형으로 합치기
            String mergedShadows = createShadowUnion(shadowAreas);

            if (mergedShadows.equals("{\"type\":\"GeometryCollection\",\"geometries\":[]}")) {
                route.setShadowPercentage(50);
                return;
            }

            // 경로 포인트별 그림자 포함 여부 확인
            List<RoutePoint> points = route.getPoints();
            double totalDistance = 0;
            double shadowDistance = 0;

            String pointInShadowSql = "SELECT ST_Contains(ST_GeomFromGeoJSON(?), ST_SetSRID(ST_MakePoint(?, ?), 4326))";

            for (int i = 0; i < points.size() - 1; i++) {
                RoutePoint p1 = points.get(i);
                RoutePoint p2 = points.get(i + 1);

                // 해당 세그먼트 거리 계산
                double segmentDistance = calculateDistance(
                        p1.getLat(), p1.getLng(), p2.getLat(), p2.getLng());
                totalDistance += segmentDistance;

                // 두 포인트가 그림자 안에 있는지 확인
                boolean p1InShadow = false;
                boolean p2InShadow = false;

                try {
                    p1InShadow = jdbcTemplate.queryForObject(
                            pointInShadowSql, Boolean.class, mergedShadows, p1.getLng(), p1.getLat());
                    p2InShadow = jdbcTemplate.queryForObject(
                            pointInShadowSql, Boolean.class, mergedShadows, p2.getLng(), p2.getLat());
                } catch (Exception e) {
                    logger.warn("그림자 포함 여부 확인 실패: " + e.getMessage());
                }

                // 두 포인트 모두 그림자 안에 있으면 전체 세그먼트가 그림자
                if (p1InShadow && p2InShadow) {
                    shadowDistance += segmentDistance;
                }
                // 한 포인트만 그림자에 있으면 절반만 그림자
                else if (p1InShadow || p2InShadow) {
                    shadowDistance += segmentDistance / 2;
                }

                // 그림자 여부 설정 (시각화용)
                p1.setInShadow(p1InShadow);
                p2.setInShadow(p2InShadow);
            }

            // 그림자 비율 계산 (%)
            int shadowPercentage = totalDistance > 0 ?
                    (int) ((shadowDistance / totalDistance) * 100) : 0;

            route.setShadowPercentage(shadowPercentage);

        } catch (Exception e) {
            logger.error("그림자 비율 계산 오류: " + e.getMessage(), e);
            // 오류 시 기본 추정치 사용
            route.setShadowPercentage(route.isAvoidShadow() ? 15 : 75);
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
     * 포인트 추가 방식의 그림자 경로 생성
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
            double distance = calculateRouteDistance(points);
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
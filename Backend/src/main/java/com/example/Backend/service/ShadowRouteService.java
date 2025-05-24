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

            // 3. 경로 주변 건물들의 그림자 계산 (실제 DB 데이터 사용)
            List<ShadowArea> shadowAreas = calculateBuildingShadows(startLat, startLng, endLat, endLng, sunPos);

            // 4. 그림자 정보를 기반으로 대체 경로 계산
            Route shadowRoute;
            if (!shadowAreas.isEmpty()) {
                // 건물 데이터가 있으면 그림자 정보 활용
                shadowRoute = calculateShadowAwareRoute(startLat, startLng, endLat, endLng, shadowAreas, avoidShadow);
            } else {
                // 건물 데이터가 없으면 기본 경로만 사용 (그림자 정보 없음)
                logger.debug("건물 데이터가 없음. 기본 경로만 사용");
                shadowRoute = createShadowRouteFromBasicRoute(basicRoute, sunPos, avoidShadow);
                // 실제 그림자 데이터가 없으므로 그림자 비율은 0
                shadowRoute.setShadowPercentage(0);
            }

            // 그림자 정보 추가
            shadowRoute.setShadowAreas(shadowAreas);
            shadowRoute.setAvoidShadow(avoidShadow);
            shadowRoute.setDateTime(dateTime);
            shadowRoute.setBasicRoute(false);

            // 그림자 비율 계산 - 실제 shadowAreas 데이터가 있을 때만
            if (!shadowAreas.isEmpty()) {
                calculateShadowPercentage(shadowRoute, shadowAreas);
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
                    "ST_AsGeoJSON(b.geom) as building_geom, " +  // ST_Transform 제거
                    "ST_AsGeoJSON(calculate_shadow_geometry(b.geom, b.\"A16\", ?, ?)) as shadow_geom " +
                    "FROM public.\"AL_D010_26_20250304\" b, search_area sa " +
                    "WHERE ST_Intersects(b.geom, sa.search_area) " +  // ST_Transform 제거
                    "AND b.\"A16\" > 5";

            // calculate_shadow_geometry 함수 존재 확인
            String checkFunctionSql = "SELECT EXISTS (SELECT FROM pg_proc WHERE proname = 'calculate_shadow_geometry')";
            boolean functionExists = jdbcTemplate.queryForObject(checkFunctionSql, Boolean.class);

            // 태양 고도가 낮을 때 방위각에 따른 그림자 방향 계산
            double shadowDirX = -Math.cos(Math.toRadians(sunPos.getAzimuth()));
            double shadowDirY = -Math.sin(Math.toRadians(sunPos.getAzimuth()));

            // 파라미터 바인딩
            List<Map<String, Object>> results = jdbcTemplate.queryForList(
                    shadowSql,
                    startLng, startLat, endLng, endLat,
                    sunPos.getAzimuth(), sunPos.getAltitude());

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

            logger.debug("건물 검색 결과: " + results.size() + "개 건물");

            // calculateBuildingShadows 메서드의 테이블 존재 확인 후에 추가
            logger.debug("검색 좌표: startLat={}, startLng={}, endLat={}, endLng={}", startLat, startLng, endLat, endLng);

            // 먼저 전체 건물 개수 확인
            String countSql = "SELECT COUNT(*) FROM public.\"AL_D010_26_20250304\" WHERE \"A16\" > 5";
            int totalBuildings = jdbcTemplate.queryForObject(countSql, Integer.class);
            logger.debug("전체 건물 개수 (높이 > 5m): {}", totalBuildings);

            // 검색 영역 내 모든 건물 개수 (높이 조건 없이)
            String areaCountSql = "WITH search_area AS (" + boundingBoxSql + ") " +
                    "SELECT COUNT(*) FROM public.\"AL_D010_26_20250304\" b, search_area sa " +
                    "WHERE ST_Intersects(b.geom, sa.search_area)";
            int areaBuildings = jdbcTemplate.queryForObject(areaCountSql, Integer.class, startLng, startLat, endLng, endLat);
            logger.debug("검색 영역 내 건물 개수: {}", areaBuildings);

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
            logger.debug("=== calculateShadowAwareRoute 시작 ===");
            logger.debug("시작점: ({}, {}), 끝점: ({}, {})", startLat, startLng, endLat, endLng);
            logger.debug("그림자 회피: {}", avoidShadow);

            // 그림자 영역들을 하나의 다각형으로 합치기
            String mergedShadows = createShadowUnion(shadowAreas);

            if (mergedShadows.equals("{\"type\":\"GeometryCollection\",\"geometries\":[]}") || shadowAreas.isEmpty()) {
                logger.debug("그림자 영역이 없음. 기본 경로 사용");
                String basicRouteJson = tmapApiService.getWalkingRoute(startLat, startLng, endLat, endLng);
                Route basicRoute = parseBasicRoute(basicRouteJson);
                basicRoute.setAvoidShadow(avoidShadow);
                return basicRoute;
            }

            // DB 함수 사용하지 않고 직접 T맵 API 경유지 기능 사용
            logger.debug("T맵 API 경유지 기능을 사용하여 실제 경로 생성");

            Route route = createRealWaypointRoute(startLat, startLng, endLat, endLng, mergedShadows, avoidShadow);

            // 생성된 경로에 실제 그림자 정보 추가
            addRealShadowInfoToRoute(route, mergedShadows);

            return route;

        } catch (Exception e) {
            logger.error("그림자 인식 경로 계산 오류: " + e.getMessage(), e);
            // 오류 발생 시 기본 T맵 경로로 폴백
            try {
                String fallbackRouteJson = tmapApiService.getWalkingRoute(startLat, startLng, endLat, endLng);
                Route fallbackRoute = parseBasicRoute(fallbackRouteJson);
                fallbackRoute.setAvoidShadow(avoidShadow);
                return fallbackRoute;
            } catch (Exception fallbackException) {
                return createSimplePath(startLat, startLng, endLat, endLng);
            }
        }
    }
    /**
     * T맵 API 경유지 기능으로 실제 도로 기반 경로 생성
     */
    private Route createRealWaypointRoute(double startLat, double startLng, double endLat, double endLng,
                                          String mergedShadows, boolean avoidShadow) {
        try {
            // 1. 먼저 기본 경로 획득
            String baseRouteJson = tmapApiService.getWalkingRoute(startLat, startLng, endLat, endLng);
            Route baseRoute = parseBasicRoute(baseRouteJson);

            if (baseRoute.getPoints().size() < 3) {
                logger.debug("기본 경로가 너무 짧음. 그대로 사용");
                baseRoute.setAvoidShadow(avoidShadow);
                return baseRoute;
            }

            // 2. 기본 경로에서 적절한 경유지 찾기
            RoutePoint waypoint = findBestWaypoint(baseRoute.getPoints(), mergedShadows, avoidShadow);

            if (waypoint == null) {
                logger.debug("적절한 경유지를 찾지 못함. 기본 경로 사용");
                baseRoute.setAvoidShadow(avoidShadow);
                return baseRoute;
            }

            // 3. T맵 API로 경유지 포함 실제 경로 요청
            logger.debug("경유지 위치: ({}, {})", waypoint.getLat(), waypoint.getLng());

            String waypointRouteJson = tmapApiService.getWalkingRouteWithWaypoint(
                    startLat, startLng,
                    waypoint.getLat(), waypoint.getLng(),
                    endLat, endLng
            );

            Route waypointRoute = parseBasicRoute(waypointRouteJson);
            waypointRoute.setAvoidShadow(avoidShadow);

            // 4. 생성된 경로가 유효한지 확인
            if (waypointRoute.getPoints().size() >= baseRoute.getPoints().size()) {
                logger.debug("경유지 경로 생성 성공: 포인트 수=" + waypointRoute.getPoints().size());
                return waypointRoute;
            } else {
                logger.debug("경유지 경로가 기본 경로보다 단순함. 기본 경로 사용");
                baseRoute.setAvoidShadow(avoidShadow);
                return baseRoute;
            }

        } catch (Exception e) {
            logger.error("실제 경유지 경로 생성 실패: " + e.getMessage(), e);
            // 실패 시 기본 경로 반환
            try {
                String fallbackRouteJson = tmapApiService.getWalkingRoute(startLat, startLng, endLat, endLng);
                Route fallbackRoute = parseBasicRoute(fallbackRouteJson);
                fallbackRoute.setAvoidShadow(avoidShadow);
                return fallbackRoute;
            } catch (Exception fallbackException) {
                return createSimplePath(startLat, startLng, endLat, endLng);
            }
        }
    }

    /**
     * 기본 경로에서 최적의 경유지 찾기
     */
    private RoutePoint findBestWaypoint(List<RoutePoint> basePoints, String mergedShadows, boolean avoidShadow) {
        try {
            // 경로의 중간 부분에서 경유지 후보들 찾기
            int startIndex = basePoints.size() / 4;  // 25% 지점부터
            int endIndex = basePoints.size() * 3 / 4; // 75% 지점까지

            List<RoutePoint> candidates = new ArrayList<>();

            // 후보 지점들 수집
            for (int i = startIndex; i <= endIndex; i += Math.max(1, (endIndex - startIndex) / 5)) {
                if (i >= basePoints.size()) break;
                candidates.add(basePoints.get(i));
            }

            // 각 후보에 대해 그림자 여부 확인하고 적절한 우회지점 생성
            for (RoutePoint candidate : candidates) {
                boolean isInShadow = isPointInShadowArea(candidate, mergedShadows);

                // 그림자 회피/따라가기 전략에 따라 판단
                if ((avoidShadow && isInShadow) || (!avoidShadow && !isInShadow)) {
                    // 조건에 맞지 않는 지점이면 우회 경유지 생성
                    RoutePoint detourWaypoint = generateNearbyWaypoint(candidate, basePoints, avoidShadow);
                    if (detourWaypoint != null) {
                        logger.debug("경유지 생성: 원본({}, {}) -> 경유지({}, {})",
                                candidate.getLat(), candidate.getLng(),
                                detourWaypoint.getLat(), detourWaypoint.getLng());
                        return detourWaypoint;
                    }
                }
            }

            // 적절한 경유지를 찾지 못하면 중간지점 기준으로 생성
            RoutePoint midPoint = basePoints.get(basePoints.size() / 2);
            return generateNearbyWaypoint(midPoint, basePoints, avoidShadow);

        } catch (Exception e) {
            logger.error("경유지 찾기 오류: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 주변의 실제 도로 지점으로 경유지 생성
     */
    private RoutePoint generateNearbyWaypoint(RoutePoint originalPoint, List<RoutePoint> basePoints, boolean avoidShadow) {
        try {
            // 기본 경로의 방향 벡터 계산
            int originalIndex = findPointIndex(originalPoint, basePoints);
            if (originalIndex < 1 || originalIndex >= basePoints.size() - 1) {
                return null;
            }

            RoutePoint prevPoint = basePoints.get(originalIndex - 3 < 0 ? 0 : originalIndex - 3);
            RoutePoint nextPoint = basePoints.get(originalIndex + 3 >= basePoints.size() ? basePoints.size() - 1 : originalIndex + 3);

            // 경로의 진행 방향
            double dx = nextPoint.getLng() - prevPoint.getLng();
            double dy = nextPoint.getLat() - prevPoint.getLat();

            // 수직 방향으로 우회 (좌측 또는 우측)
            double perpX = -dy;
            double perpY = dx;

            // 정규화
            double length = Math.sqrt(perpX * perpX + perpY * perpY);
            if (length == 0) return null;

            perpX /= length;
            perpY /= length;

            // 우회 거리 (약 100-300미터 정도)
            double detourDistance = 0.002; // 위도/경도 단위로 약 200미터

            // 그림자 회피면 한쪽으로, 따라가기면 반대쪽으로
            int direction = avoidShadow ? 1 : -1;

            RoutePoint waypoint = new RoutePoint();
            waypoint.setLat(originalPoint.getLat() + direction * perpY * detourDistance);
            waypoint.setLng(originalPoint.getLng() + direction * perpX * detourDistance);

            return waypoint;

        } catch (Exception e) {
            logger.error("주변 경유지 생성 오류: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 리스트에서 특정 포인트의 인덱스 찾기
     */
    private int findPointIndex(RoutePoint target, List<RoutePoint> points) {
        for (int i = 0; i < points.size(); i++) {
            RoutePoint point = points.get(i);
            if (Math.abs(point.getLat() - target.getLat()) < 0.0001 &&
                    Math.abs(point.getLng() - target.getLng()) < 0.0001) {
                return i;
            }
        }
        return points.size() / 2; // 못 찾으면 중간점 반환
    }

    /**
     * 점이 그림자 영역에 있는지 확인
     */
    private boolean isPointInShadowArea(RoutePoint point, String mergedShadows) {
        try {
            String sql = "SELECT ST_Contains(ST_GeomFromGeoJSON(?), ST_SetSRID(ST_MakePoint(?, ?), 4326))";
            return jdbcTemplate.queryForObject(sql, Boolean.class,
                    mergedShadows, point.getLng(), point.getLat());
        } catch (Exception e) {
            logger.warn("그림자 영역 확인 실패: " + e.getMessage());
            return false;
        }
    }
    /**
     * 기본 경로를 복사해서 그림자 경로 생성 (실제 DB 데이터만 사용)
     */
    private Route createShadowRouteFromBasicRoute(Route basicRoute, SunPosition sunPos, boolean avoidShadow) {
        try {
            // 기본 경로를 복사
            Route shadowRoute = new Route();
            shadowRoute.setPoints(new ArrayList<>());

            // 기본 경로의 모든 포인트를 복사
            for (RoutePoint originalPoint : basicRoute.getPoints()) {
                RoutePoint newPoint = new RoutePoint();
                newPoint.setLat(originalPoint.getLat());
                newPoint.setLng(originalPoint.getLng());
                newPoint.setInShadow(false); // 초기값, 실제 계산으로 업데이트됨
                shadowRoute.getPoints().add(newPoint);
            }

            // 기본 정보 복사
            shadowRoute.setDistance(basicRoute.getDistance());
            shadowRoute.setDuration(basicRoute.getDuration());
            shadowRoute.setAvoidShadow(avoidShadow);

            logger.debug("기본 경로 기반 그림자 경로 생성 완료: 포인트 수=" + shadowRoute.getPoints().size());

            return shadowRoute;

        } catch (Exception e) {
            logger.error("기본 경로 복사 오류: " + e.getMessage(), e);
            return basicRoute; // 실패 시 기본 경로 반환
        }
    }

    /**
     * 실제 그림자 정보 추가 (DB 데이터만 사용)
     */
    private void addRealShadowInfoToRoute(Route route, String mergedShadows) {
        if (mergedShadows == null || mergedShadows.equals("{\"type\":\"GeometryCollection\",\"geometries\":[]}")) {
            logger.debug("그림자 영역 데이터가 없음. 그림자 정보 추가 생략");
            route.setShadowPercentage(0);
            return;
        }

        try {
            String pointInShadowSql = "SELECT ST_Contains(ST_GeomFromGeoJSON(?), ST_SetSRID(ST_MakePoint(?, ?), 4326))";

            int shadowCount = 0;

            for (RoutePoint point : route.getPoints()) {
                boolean isInShadow = false;

                try {
                    isInShadow = jdbcTemplate.queryForObject(
                            pointInShadowSql, Boolean.class,
                            mergedShadows, point.getLng(), point.getLat());
                } catch (Exception e) {
                    logger.warn("그림자 포함 여부 확인 실패: " + e.getMessage());
                    // DB 오류 시 그림자가 아닌 것으로 처리
                    isInShadow = false;
                }

                point.setInShadow(isInShadow);
                if (isInShadow) shadowCount++;
            }

            // 실제 계산된 그림자 비율
            int shadowPercentage = route.getPoints().size() > 0 ?
                    (shadowCount * 100 / route.getPoints().size()) : 0;
            route.setShadowPercentage(shadowPercentage);

            logger.debug("실제 그림자 정보 추가 완료: " + shadowCount + "/" + route.getPoints().size() +
                    " (" + shadowPercentage + "%)");

        } catch (Exception e) {
            logger.error("그림자 정보 추가 오류: " + e.getMessage(), e);
            route.setShadowPercentage(0);
        }
    }

    /**
     * 그림자 비율 계산
     */
    private void calculateShadowPercentage(Route route, List<ShadowArea> shadowAreas) {

        logger.debug("=== 그림자 비율 계산 디버깅 ===");
        logger.debug("shadowAreas 개수: " + shadowAreas.size());

        List<RoutePoint> points = route.getPoints();

        if (shadowAreas.isEmpty()) {
            logger.debug("그림자 영역이 없음");
            route.setShadowPercentage(0);
            return;
        }

        try {
            // 그림자 영역들을 하나의 다각형으로 합치기
            String mergedShadows = createShadowUnion(shadowAreas);
            logger.debug("병합된 그림자 GeoJSON 길이: " + mergedShadows.length());

            if (mergedShadows.equals("{\"type\":\"GeometryCollection\",\"geometries\":[]}")) {
                route.setShadowPercentage(0);
                return;
            }

            // 경로 포인트별 그림자 포함 여부 확인
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

            // 마지막 포인트 처리
            if (points.size() > 0) {
                RoutePoint lastPoint = points.get(points.size() - 1);
                try {
                    boolean lastInShadow = jdbcTemplate.queryForObject(
                            pointInShadowSql, Boolean.class, mergedShadows, lastPoint.getLng(), lastPoint.getLat());
                    lastPoint.setInShadow(lastInShadow);
                } catch (Exception e) {
                    logger.warn("마지막 포인트 그림자 확인 실패: " + e.getMessage());
                }
            }

            // 그림자 비율 계산 (%)
            int shadowPercentage = totalDistance > 0 ?
                    (int) ((shadowDistance / totalDistance) * 100) : 0;

            route.setShadowPercentage(shadowPercentage);

        } catch (Exception e) {
            logger.error("그림자 비율 계산 오류: " + e.getMessage(), e);
            route.setShadowPercentage(0);
        }
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
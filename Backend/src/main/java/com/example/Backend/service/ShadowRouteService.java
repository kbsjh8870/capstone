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

            // *** 핵심 수정: 태양 고도각이 낮으면 그림자 경로도 기본 경로와 동일하게 ***
            if (sunPos.getAltitude() < 5.0) {
                // 새벽/밤 시간대: 그림자가 없으므로 기본 경로를 복사해서 반환
                logger.debug("태양 고도각이 낮음 ({}도). 그림자 경로도 기본 경로와 동일하게 설정",
                        sunPos.getAltitude());

                // 그림자 X와 그림자 O 모두 기본 T맵 경로와 동일하게 설정
                Route shadowRoute = copyBasicRoute(basicRoute);
                shadowRoute.setBasicRoute(false);
                shadowRoute.setAvoidShadow(avoidShadow);
                shadowRoute.setDateTime(dateTime);
                shadowRoute.setShadowPercentage(0); // 그림자 없음
                shadowRoute.setShadowAreas(new ArrayList<>()); // 빈 그림자 영역

                routes.add(shadowRoute);

                logger.info("새벽 시간대 처리 완료: 기본 경로와 그림자 경로가 동일함");
                return routes;
            }

            // 3. 경로 주변 건물들의 그림자 계산 (태양이 있을 때만)
            List<ShadowArea> shadowAreas = calculateBuildingShadows(startLat, startLng, endLat, endLng, sunPos);

            // 4. 그림자 정보를 기반으로 대체 경로 계산
            Route shadowRoute;
            if (!shadowAreas.isEmpty()) {
                // 건물 데이터가 있으면 그림자 정보 활용
                shadowRoute = calculateShadowAwareRoute(startLat, startLng, endLat, endLng, shadowAreas, avoidShadow);
            } else {
                // 건물 데이터가 없으면 기본 경로만 사용 (그림자 정보 없음)
                logger.debug("건물 데이터가 없음. 기본 경로만 사용");
                shadowRoute = copyBasicRoute(basicRoute);
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

    private Route copyBasicRoute(Route originalRoute) {
        Route copiedRoute = new Route();

        // 포인트 복사
        List<RoutePoint> copiedPoints = new ArrayList<>();
        for (RoutePoint originalPoint : originalRoute.getPoints()) {
            RoutePoint newPoint = new RoutePoint();
            newPoint.setLat(originalPoint.getLat());
            newPoint.setLng(originalPoint.getLng());
            newPoint.setInShadow(false); // 그림자 없음으로 설정
            copiedPoints.add(newPoint);
        }

        copiedRoute.setPoints(copiedPoints);
        copiedRoute.setDistance(originalRoute.getDistance());
        copiedRoute.setDuration(originalRoute.getDuration());

        logger.debug("기본 경로 복사 완료: 포인트 수={}, 거리={}m",
                copiedPoints.size(), originalRoute.getDistance());

        return copiedRoute;
    }

    /**
     * 건물 그림자 계산
     */
    private List<ShadowArea> calculateBuildingShadows(double startLat, double startLng,
                                                      double endLat, double endLng,
                                                      SunPosition sunPos) {

        try {
            // 1. 태양 위치 로깅
            logger.debug("=== 건물 그림자 계산 시작 ===");
            logger.debug("태양 위치: 고도={}도, 방위각={}도", sunPos.getAltitude(), sunPos.getAzimuth());
            logger.debug("경로 좌표: 시작({}, {}), 끝({}, {})", startLat, startLng, endLat, endLng);

            // 2. 테이블 존재 여부 확인
            String checkTableSql = "SELECT EXISTS (SELECT FROM information_schema.tables " +
                    "WHERE table_schema = 'public' AND table_name = 'AL_D010_26_20250304')";
            boolean tableExists = jdbcTemplate.queryForObject(checkTableSql, Boolean.class);
            logger.debug("건물 테이블 존재 여부: {}", tableExists);

            if (!tableExists) {
                logger.warn("건물 데이터 테이블이 존재하지 않습니다.");
                return new ArrayList<>();
            }

            // 3. 해당 지역의 건물 개수 먼저 확인
            String countSql = "SELECT COUNT(*) FROM public.\"AL_D010_26_20250304\" b " +
                    "WHERE ST_DWithin(b.geom, ST_MakeLine(" +
                    "ST_SetSRID(ST_MakePoint(?, ?), 4326), " +
                    "ST_SetSRID(ST_MakePoint(?, ?), 4326)), 0.003)";

            Integer buildingCount = jdbcTemplate.queryForObject(countSql, Integer.class,
                    startLng, startLat, endLng, endLat);
            logger.debug("검색 범위 내 건물 개수: {}개", buildingCount);

            if (buildingCount == null || buildingCount == 0) {
                logger.warn("해당 지역에 건물 데이터가 없습니다.");
                return new ArrayList<>();
            }

            // 4. 높이 정보가 있는 건물 개수 확인
            String heightCountSql = "SELECT COUNT(*) FROM public.\"AL_D010_26_20250304\" b " +
                    "WHERE ST_DWithin(b.geom, ST_MakeLine(" +
                    "ST_SetSRID(ST_MakePoint(?, ?), 4326), " +
                    "ST_SetSRID(ST_MakePoint(?, ?), 4326)), 0.003) " +
                    "AND b.\"A16\" IS NOT NULL AND b.\"A16\" > 5";

            Integer heightBuildingCount = jdbcTemplate.queryForObject(heightCountSql, Integer.class,
                    startLng, startLat, endLng, endLat);
            logger.debug("높이 정보가 있는 건물 개수: {}개 (5m 이상)", heightBuildingCount);

            if (heightBuildingCount == null || heightBuildingCount == 0) {
                logger.warn("해당 지역에 유효한 높이 정보를 가진 건물이 없습니다.");
                return new ArrayList<>();
            }

            // 5. calculate_shadow_geometry 함수 존재 여부 확인
            String checkFunctionSql = "SELECT EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'calculate_shadow_geometry')";
            boolean functionExists = jdbcTemplate.queryForObject(checkFunctionSql, Boolean.class);
            logger.debug("그림자 계산 함수 존재 여부: {}", functionExists);

            if (!functionExists) {
                logger.error("calculate_shadow_geometry 함수가 존재하지 않습니다.");
                return new ArrayList<>();
            }

            // 6. 실제 그림자 계산 쿼리 실행
            String shadowSql = "SELECT b.id, b.\"A16\" as height, " +
                    "ST_AsGeoJSON(b.geom) as building_geom, " +
                    "ST_AsGeoJSON(calculate_shadow_geometry(b.geom, b.\"A16\", ?, ?)) as shadow_geom " +
                    "FROM public.\"AL_D010_26_20250304\" b " +
                    "WHERE ST_DWithin(b.geom, ST_MakeLine(" +
                    "ST_SetSRID(ST_MakePoint(?, ?), 4326), " +
                    "ST_SetSRID(ST_MakePoint(?, ?), 4326)), 0.003) " +
                    "AND b.\"A16\" IS NOT NULL AND b.\"A16\" > 5 " +
                    "LIMIT 10"; // 최대 10개만 조회해서 성능 확인

            logger.debug("그림자 계산 쿼리 실행 중...");
            long startTime = System.currentTimeMillis();

            List<Map<String, Object>> results = jdbcTemplate.queryForList(
                    shadowSql,
                    sunPos.getAzimuth(), sunPos.getAltitude(),
                    startLng, startLat, endLng, endLat);

            long endTime = System.currentTimeMillis();
            logger.debug("쿼리 실행 시간: {}ms, 결과 개수: {}개", (endTime - startTime), results.size());

            // 7. 결과 파싱
            List<ShadowArea> shadowAreas = new ArrayList<>();
            for (Map<String, Object> row : results) {
                try {
                    ShadowArea area = new ShadowArea();
                    area.setId(((Number) row.get("id")).longValue());
                    area.setHeight(((Number) row.get("height")).doubleValue());
                    area.setBuildingGeometry((String) row.get("building_geom"));
                    area.setShadowGeometry((String) row.get("shadow_geom"));
                    shadowAreas.add(area);

                    logger.debug("건물 {}번: 높이={}m", area.getId(), area.getHeight());
                } catch (Exception e) {
                    logger.warn("그림자 영역 파싱 오류: " + e.getMessage());
                }
            }

            logger.debug("건물 그림자 계산 완료: {}개 건물의 그림자 영역 생성", shadowAreas.size());
            return shadowAreas;

        } catch (Exception e) {
            logger.error("건물 그림자 계산 오류: " + e.getMessage(), e);
            e.printStackTrace(); // 상세한 스택 트레이스 출력
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

            if (baseRoute.getPoints().size() < 5) {
                logger.debug("기본 경로가 너무 짧음 ({}개 포인트). 그대로 사용", baseRoute.getPoints().size());
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

            // 4. 생성된 경로가 유효하고 기본 경로와 충분히 다른지 확인
            if (waypointRoute.getPoints().size() >= baseRoute.getPoints().size() * 0.8 &&
                    waypointRoute.getPoints().size() <= baseRoute.getPoints().size() * 3) {

                // 경로 거리 차이 확인
                double distanceDiff = Math.abs(waypointRoute.getDistance() - baseRoute.getDistance());
                double distanceRatio = distanceDiff / baseRoute.getDistance();

                logger.debug("경로 비교: 기본={}포인트/{}m, 경유지={}포인트/{}m, 거리차이={}%",
                        baseRoute.getPoints().size(), (int)baseRoute.getDistance(),
                        waypointRoute.getPoints().size(), (int)waypointRoute.getDistance(),
                        (int)(distanceRatio * 100));

                // 거리 차이가 너무 크지 않으면 경유지 경로 사용
                if (distanceRatio < 0.5) { // 기본 경로 대비 50% 이내 차이
                    logger.debug("경유지 경로 사용");
                    return waypointRoute;
                } else {
                    logger.debug("경유지 경로가 너무 멀어서 기본 경로 사용");
                    baseRoute.setAvoidShadow(avoidShadow);
                    return baseRoute;
                }
            } else {
                logger.debug("경유지 경로가 부적절함. 기본 경로 사용");
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
            // 경로의 중간 부분에서만 경유지 후보 찾기 (시작/끝 20% 제외)
            int startIndex = basePoints.size() / 5;  // 20% 지점부터
            int endIndex = basePoints.size() * 4 / 5; // 80% 지점까지

            // 인덱스 범위 검증
            startIndex = Math.max(1, startIndex);
            endIndex = Math.min(basePoints.size() - 2, endIndex);

            if (startIndex >= endIndex) {
                logger.debug("경로가 너무 짧아 적절한 경유지 범위를 설정할 수 없음");
                return null;
            }

            logger.debug("경유지 후보 범위: {}~{} (전체 {}개 포인트)", startIndex, endIndex, basePoints.size());

            List<RoutePoint> candidates = new ArrayList<>();

            // 후보 지점들 수집 (중간 부분에서만)
            int step = Math.max(1, (endIndex - startIndex) / 8); // 최대 8개 후보
            for (int i = startIndex; i <= endIndex; i += step) {
                if (i >= basePoints.size()) break;
                candidates.add(basePoints.get(i));
            }

            logger.debug("경유지 후보 개수: {}", candidates.size());

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

            // 적절한 경유지를 찾지 못하면 안전한 중간지점 사용
            int safeMiddleIndex = (startIndex + endIndex) / 2;
            RoutePoint safeMiddlePoint = basePoints.get(safeMiddleIndex);
            RoutePoint safeWaypoint = generateNearbyWaypoint(safeMiddlePoint, basePoints, avoidShadow);

            if (safeWaypoint != null) {
                logger.debug("안전한 중간 경유지 생성: ({}, {})", safeWaypoint.getLat(), safeWaypoint.getLng());
            }

            return safeWaypoint;

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

            // 안전한 인덱스 범위 확인
            int prevIndex = Math.max(0, originalIndex - 5);
            int nextIndex = Math.min(basePoints.size() - 1, originalIndex + 5);

            if (prevIndex >= nextIndex) {
                logger.debug("경로가 너무 짧아 방향 벡터를 계산할 수 없음");
                return null;
            }

            RoutePoint prevPoint = basePoints.get(prevIndex);
            RoutePoint nextPoint = basePoints.get(nextIndex);

            // 경로의 진행 방향 계산
            double dx = nextPoint.getLng() - prevPoint.getLng();
            double dy = nextPoint.getLat() - prevPoint.getLat();

            // 벡터 길이 확인
            double vectorLength = Math.sqrt(dx * dx + dy * dy);
            if (vectorLength == 0) {
                logger.debug("방향 벡터의 길이가 0");
                return null;
            }

            // 수직 방향으로 우회 (좌측 또는 우측)
            double perpX = -dy / vectorLength;
            double perpY = dx / vectorLength;

            // 우회 거리 조정 - 전체 경로 길이에 비례
            double totalDistance = calculateDistance(
                    basePoints.get(0).getLat(), basePoints.get(0).getLng(),
                    basePoints.get(basePoints.size()-1).getLat(), basePoints.get(basePoints.size()-1).getLng()
            );

            // 우회 거리를 전체 경로 길이의 10-20% 정도로 설정
            double detourDistance = Math.min(0.003, Math.max(0.001, totalDistance * 0.15));

            logger.debug("전체 거리: {}, 우회 거리: {}", totalDistance, detourDistance);

            // 그림자 회피면 한쪽으로, 따라가기면 반대쪽으로
            int direction = avoidShadow ? 1 : -1;

            RoutePoint waypoint = new RoutePoint();
            waypoint.setLat(originalPoint.getLat() + direction * perpY * detourDistance);
            waypoint.setLng(originalPoint.getLng() + direction * perpX * detourDistance);

            // 생성된 경유지가 시작점이나 끝점과 너무 가깝지 않은지 확인
            RoutePoint startPoint = basePoints.get(0);
            RoutePoint endPoint = basePoints.get(basePoints.size() - 1);

            double distToStart = calculateDistance(waypoint.getLat(), waypoint.getLng(), startPoint.getLat(), startPoint.getLng());
            double distToEnd = calculateDistance(waypoint.getLat(), waypoint.getLng(), endPoint.getLat(), endPoint.getLng());

            // 시작점이나 끝점과 너무 가까우면 null 반환
            if (distToStart < totalDistance * 0.2 || distToEnd < totalDistance * 0.2) {
                logger.debug("경유지가 시작점 또는 끝점과 너무 가까움 (시작점 거리: {}, 끝점 거리: {})", distToStart, distToEnd);
                return null;
            }

            logger.debug("경유지 생성 성공: ({}, {}), 시작점 거리: {}, 끝점 거리: {}",
                    waypoint.getLat(), waypoint.getLng(), distToStart, distToEnd);

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
     * 실제 그림자 정보 추가 최적화
     */
    private void addRealShadowInfoToRoute(Route route, String mergedShadows) {
        List<RoutePoint> points = route.getPoints();
        logger.debug("=== 실제 그림자 정보 추가 시작 ===");
        logger.debug("전체 포인트 수: {}", points.size());

        if (mergedShadows == null || mergedShadows.equals("{\"type\":\"GeometryCollection\",\"geometries\":[]}")) {
            logger.debug("그림자 영역 데이터가 없음. 모든 포인트를 햇빛 구간으로 설정");
            for (RoutePoint point : points) {
                point.setInShadow(false);
            }
            route.setShadowPercentage(0);
            return;
        }

        int totalPoints = points.size();
        int shadowCount = 0;
        int batchSize = 50; // 배치 처리로 성능 최적화

        try {
            // 배치 단위로 그림자 포함 여부 확인
            for (int i = 0; i < totalPoints; i += batchSize) {
                int endIndex = Math.min(i + batchSize, totalPoints);
                List<RoutePoint> batch = points.subList(i, endIndex);

                // 배치의 각 포인트에 대해 그림자 포함 여부 확인
                for (int j = 0; j < batch.size(); j++) {
                    RoutePoint point = batch.get(j);
                    boolean isInShadow = false;

                    try {
                        // PostGIS 함수로 실제 그림자 영역 포함 여부 확인
                        String sql = "SELECT ST_Contains(ST_GeomFromGeoJSON(?), ST_SetSRID(ST_MakePoint(?, ?), 4326))";
                        isInShadow = jdbcTemplate.queryForObject(sql, Boolean.class,
                                mergedShadows, point.getLng(), point.getLat());
                    } catch (Exception e) {
                        logger.warn("포인트 {}의 그림자 확인 실패: {}", (i + j), e.getMessage());
                        isInShadow = false; // 오류 시 햇빛 구간으로 처리
                    }

                    point.setInShadow(isInShadow);
                    if (isInShadow) {
                        shadowCount++;
                        if (shadowCount <= 10) { // 처음 10개만 로깅
                            logger.debug("그림자 포인트 발견: idx={}, lat={}, lng={}",
                                    (i + j), point.getLat(), point.getLng());
                        }
                    }
                }

                // 중간 진행상황 로깅
                if (i + batchSize < totalPoints) {
                    logger.debug("배치 처리 진행: {}/{} 완료", i + batchSize, totalPoints);
                }
            }

            // 그림자 비율 계산
            int shadowPercentage = totalPoints > 0 ? (shadowCount * 100 / totalPoints) : 0;
            route.setShadowPercentage(shadowPercentage);

            logger.debug("실제 그림자 정보 추가 완료: {}/{} 포인트가 그림자 영역 ({}%)",
                    shadowCount, totalPoints, shadowPercentage);

        } catch (Exception e) {
            logger.error("실제 그림자 정보 추가 실패: " + e.getMessage(), e);

            // 실패 시 모든 포인트를 햇빛 구간으로 설정
            for (RoutePoint point : points) {
                point.setInShadow(false);
            }
            route.setShadowPercentage(0);
        }
    }

    /**
     * 시간별로 다른 그림자 패턴을 보장하는 경로 검증
     */
    private boolean validateTimeBasedShadowDifference(Route route1, Route route2,
                                                      SunPosition sunPos1, SunPosition sunPos2) {

        // 태양 위치 차이가 충분한지 확인
        double altitudeDiff = Math.abs(sunPos1.getAltitude() - sunPos2.getAltitude());
        double azimuthDiff = Math.abs(sunPos1.getAzimuth() - sunPos2.getAzimuth());

        logger.debug("태양 위치 차이: 고도차이={}도, 방위각차이={}도", altitudeDiff, azimuthDiff);

        // 태양 위치가 크게 다르면 경로도 달라야 함
        if (altitudeDiff > 10.0 || azimuthDiff > 30.0) {

            // 두 경로의 그림자 비율 비교
            int shadowDiff = Math.abs(route1.getShadowPercentage() - route2.getShadowPercentage());

            if (shadowDiff < 5) { // 그림자 비율 차이가 5% 미만이면 경고
                logger.warn("태양 위치가 크게 다른데 그림자 비율이 유사함: {}% vs {}%",
                        route1.getShadowPercentage(), route2.getShadowPercentage());
                return false;
            }
        }

        return true;
    }


    /**
     * 태양 위치에 따른 동적 경로 생성 강화
     */
    private Route createTimeSpecificShadowRoute(double startLat, double startLng,
                                                double endLat, double endLng,
                                                boolean avoidShadow, SunPosition sunPos) {

        logger.debug("시간별 특화 그림자 경로 생성: 태양고도={}도, 방위각={}도",
                sunPos.getAltitude(), sunPos.getAzimuth());

        try {
            // 1. 태양 방위각에 따른 우회 방향 결정
            double sunAzimuthRad = Math.toRadians(sunPos.getAzimuth());

            // 2. 태양 고도에 따른 우회 거리 조정
            double baseDetour = 0.002; // 기본 200m
            double altitudeMultiplier = Math.max(0.5, (90 - sunPos.getAltitude()) / 90.0);
            double detourDistance = baseDetour * altitudeMultiplier;

            logger.debug("우회 거리 계산: 기본={}m, 고도배수={}, 최종={}m",
                    baseDetour * 111000, altitudeMultiplier, detourDistance * 111000);

            // 3. 기본 경로 획득
            String baseRouteJson = tmapApiService.getWalkingRoute(startLat, startLng, endLat, endLng);
            Route baseRoute = parseBasicRoute(baseRouteJson);

            // 4. 태양 위치 기반 경유지 계산
            RoutePoint smartWaypoint = calculateSunBasedWaypoint(
                    baseRoute.getPoints(), sunPos, avoidShadow, detourDistance);

            if (smartWaypoint != null) {
                // 5. 태양 위치 고려 경유지 경로 생성
                String waypointRouteJson = tmapApiService.getWalkingRouteWithWaypoint(
                        startLat, startLng, smartWaypoint.getLat(), smartWaypoint.getLng(), endLat, endLng);

                Route timeSpecificRoute = parseBasicRoute(waypointRouteJson);
                timeSpecificRoute.setAvoidShadow(avoidShadow);

                logger.debug("시간별 특화 경로 생성 완료: {}포인트", timeSpecificRoute.getPoints().size());
                return timeSpecificRoute;
            } else {
                logger.debug("적절한 경유지를 찾지 못함. 기본 경로 사용");
                baseRoute.setAvoidShadow(avoidShadow);
                return baseRoute;
            }

        } catch (Exception e) {
            logger.error("시간별 특화 경로 생성 실패: " + e.getMessage(), e);

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
     * 태양 위치 기반 스마트 경유지 계산
     */
    private RoutePoint calculateSunBasedWaypoint(List<RoutePoint> basePoints,
                                                 SunPosition sunPos, boolean avoidShadow,
                                                 double detourDistance) {

        if (basePoints.size() < 5) return null;

        int middleIndex = basePoints.size() / 2;
        RoutePoint middlePoint = basePoints.get(middleIndex);

        // 태양 방위각 기반 우회 방향 계산
        double sunAzimuthRad = Math.toRadians(sunPos.getAzimuth());

        // 그림자 회피: 태양 반대 방향으로 우회
        // 그림자 따라가기: 태양 방향으로 우회
        double waypointDirection = avoidShadow ?
                (sunAzimuthRad + Math.PI) : sunAzimuthRad; // 180도 차이

        double waypointLat = middlePoint.getLat() +
                detourDistance * Math.cos(waypointDirection);
        double waypointLng = middlePoint.getLng() +
                detourDistance * Math.sin(waypointDirection);

        RoutePoint waypoint = new RoutePoint();
        waypoint.setLat(waypointLat);
        waypoint.setLng(waypointLng);

        logger.debug("태양 기반 경유지: 원점({}, {}) -> 경유지({}, {}), 태양방위={}도",
                middlePoint.getLat(), middlePoint.getLng(),
                waypointLat, waypointLng, sunPos.getAzimuth());

        return waypoint;
    }


    /**
     * 그림자 비율 계산
     */
    private void calculateShadowPercentage(Route route, List<ShadowArea> shadowAreas) {
        logger.debug("=== 그림자 비율 계산 시작 ===");
        logger.debug("shadowAreas 개수: " + shadowAreas.size());

        List<RoutePoint> points = route.getPoints();

        if (shadowAreas.isEmpty()) {
            logger.debug("그림자 영역이 없음");
            route.setShadowPercentage(0);
            return;
        }

        // 성능 최적화: 그림자 영역이 너무 많으면 샘플링
        if (shadowAreas.size() > 100) {
            logger.debug("그림자 영역이 너무 많음 ({}개). 샘플링 적용", shadowAreas.size());
            shadowAreas = sampleShadowAreas(shadowAreas, 50); // 최대 50개만 사용
        }

        try {
            // 그림자 영역들을 하나의 다각형으로 합치기
            String mergedShadows = createShadowUnion(shadowAreas);
            logger.debug("병합된 그림자 영역 사용");

            if (mergedShadows.equals("{\"type\":\"GeometryCollection\",\"geometries\":[]}")) {
                route.setShadowPercentage(0);
                return;
            }

            // 성능 최적화: 포인트 샘플링 (모든 포인트 확인하지 않고 샘플링)
            List<RoutePoint> samplePoints = sampleRoutePoints(points, 20); // 최대 20개 포인트만 확인

            int shadowCount = 0;
            String pointInShadowSql = "SELECT ST_Contains(ST_GeomFromGeoJSON(?), ST_SetSRID(ST_MakePoint(?, ?), 4326))";

            for (RoutePoint point : samplePoints) {
                boolean isInShadow = false;

                try {
                    isInShadow = jdbcTemplate.queryForObject(
                            pointInShadowSql, Boolean.class, mergedShadows, point.getLng(), point.getLat());
                } catch (Exception e) {
                    logger.warn("그림자 포함 여부 확인 실패: " + e.getMessage());
                    // 타임아웃이나 오류 발생 시 즉시 중단
                    break;
                }

                point.setInShadow(isInShadow);
                if (isInShadow) shadowCount++;
            }

            // 샘플 기반 그림자 비율 계산
            int shadowPercentage = samplePoints.size() > 0 ?
                    (shadowCount * 100 / samplePoints.size()) : 0;

            route.setShadowPercentage(shadowPercentage);

            // 샘플링 결과를 전체 포인트에 적용 (근사치)
            applyShadowInfoToAllPoints(points, samplePoints, shadowPercentage);

            logger.debug("그림자 비율 계산 완료: {}% (샘플 기반)", shadowPercentage);

        } catch (Exception e) {
            logger.error("그림자 비율 계산 오류: " + e.getMessage(), e);
            route.setShadowPercentage(0);

            // 오류 발생 시 기본값으로 설정
            for (RoutePoint point : points) {
                point.setInShadow(false);
            }
        }
    }

    private List<ShadowArea> sampleShadowAreas(List<ShadowArea> shadowAreas, int maxCount) {
        if (shadowAreas.size() <= maxCount) {
            return shadowAreas;
        }

        List<ShadowArea> sampled = new ArrayList<>();
        int step = shadowAreas.size() / maxCount;

        for (int i = 0; i < shadowAreas.size(); i += step) {
            sampled.add(shadowAreas.get(i));
            if (sampled.size() >= maxCount) break;
        }

        logger.debug("그림자 영역 샘플링: {}개 -> {}개", shadowAreas.size(), sampled.size());
        return sampled;
    }

    /**
     * 경로 포인트 샘플링 (성능 최적화)
     */
    private List<RoutePoint> sampleRoutePoints(List<RoutePoint> points, int maxCount) {
        if (points.size() <= maxCount) {
            return new ArrayList<>(points);
        }

        List<RoutePoint> sampled = new ArrayList<>();
        int step = points.size() / maxCount;

        for (int i = 0; i < points.size(); i += step) {
            sampled.add(points.get(i));
            if (sampled.size() >= maxCount) break;
        }

        // 시작점과 끝점은 반드시 포함
        if (!sampled.contains(points.get(0))) {
            sampled.add(0, points.get(0));
        }
        if (!sampled.contains(points.get(points.size() - 1))) {
            sampled.add(points.get(points.size() - 1));
        }

        logger.debug("경로 포인트 샘플링: {}개 -> {}개", points.size(), sampled.size());
        return sampled;
    }

    /**
     * 샘플링 결과를 전체 포인트에 적용
     */
    private void applyShadowInfoToAllPoints(List<RoutePoint> allPoints, List<RoutePoint> samplePoints, int shadowPercentage) {
        // 간단한 보간법으로 그림자 정보 적용
        for (int i = 0; i < allPoints.size(); i++) {
            RoutePoint point = allPoints.get(i);

            // 전체 경로에서의 위치 비율
            double ratio = (double) i / (allPoints.size() - 1);

            // 그림자 비율을 기반으로 확률적 할당
            boolean inShadow = (Math.random() * 100) < shadowPercentage;
            point.setInShadow(inShadow);
        }
    }


    /**
     * 그림자 영역들을 하나의 GeoJSON으로 병합
     */
    private String createShadowUnion(List<ShadowArea> shadowAreas) {
        if (shadowAreas == null || shadowAreas.isEmpty()) {
            return "{\"type\":\"GeometryCollection\",\"geometries\":[]}";
        }

        // 성능 최적화: 너무 많은 영역은 제한
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
package com.example.Backend.service;

import com.example.Backend.model.Route;
import com.example.Backend.model.RoutePoint;
import com.example.Backend.model.RouteCandidate;
import com.example.Backend.model.SunPosition;
import com.example.Backend.model.ShadowArea;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Service
public class RouteCandidateService {

    private static final Logger logger = LoggerFactory.getLogger(RouteCandidateService.class);

    @Autowired
    private TmapApiService tmapApiService;

    @Autowired
    private ShadowService shadowService;

    @Autowired
    private WeatherService weatherService;

    @Autowired
    private ShadowRouteService shadowRouteService;

    private boolean isNightTime(LocalDateTime dateTime) {
        int hour = dateTime.getHour();
        return hour < 6 || hour >= 22;
    }

    /**
     * 3개의 후보 경로 생성
     */
    public List<RouteCandidate> generateCandidateRoutes(
            double startLat, double startLng, double endLat, double endLng, LocalDateTime dateTime) {

        try {
            logger.info("=== 다중 경유지 기반 경로 후보 생성 시작 ===");
            logger.info("출발: ({}, {}), 도착: ({}, {}), 시간: {}",
                    startLat, startLng, endLat, endLng, dateTime);

            boolean isNight = isNightTime(dateTime);
            boolean isBadWeather = weatherService.isBadWeather(startLat, startLng);

            logger.info("시간 분석 - 선택시간: {}, 밤시간: {}, 나쁜날씨: {}",
                    dateTime.getHour() + "시", isNight, isBadWeather);

            // 밤이거나 날씨가 나쁘면 최단경로만
            if (isNight || isBadWeather) {
                String reason = isNight ? "밤 시간 (22시~6시)" : "나쁜 날씨";
                logger.info("{}로 인해 안전한 최단경로만 생성", reason);
                return generateShortestRouteOnly(startLat, startLng, endLat, endLng, dateTime);
            }

            logger.info("낮 시간 + 좋은 날씨 → 다중 경유지 기반 경로 생성");

            // 다중 경유지 로직
            return generateOptimizedRouteCandidates(startLat, startLng, endLat, endLng, dateTime);

        } catch (Exception e) {
            logger.error("경로 후보 생성 오류: " + e.getMessage(), e);
            return generateShortestRouteOnly(startLat, startLng, endLat, endLng, dateTime);
        }
    }

    /**
     *  다중 경유지 기반 경로 생성
     */
    private List<RouteCandidate> generateOptimizedRouteCandidates(double startLat, double startLng,
                                                                  double endLat, double endLng,
                                                                  LocalDateTime dateTime) {
        List<RouteCandidate> candidates = new ArrayList<>();

        try {
            logger.info("=== 다중 경유지 최적화 경로 생성 시작 ===");
            long startTime = System.currentTimeMillis();

            // 최단경로 (기준점)
            Route shortestRoute = generateShortestRoute(startLat, startLng, endLat, endLng, dateTime);
            if (shortestRoute == null) {
                logger.error("기준 최단경로 생성 실패");
                return generateShortestRouteOnly(startLat, startLng, endLat, endLng, dateTime);
            }

            RouteCandidate shortestCandidate = new RouteCandidate("shortest", "최단경로", shortestRoute);
            candidates.add(shortestCandidate);
            logger.info("최단경로: {}m, {}분", (int)shortestRoute.getDistance(), shortestRoute.getDuration());

            // 병렬 처리로 그림자/균형 경로 생성
            CompletableFuture<Route> shadeFuture = CompletableFuture.supplyAsync(() ->
                    generateOptimizedShadeRoute(startLat, startLng, endLat, endLng, dateTime));

            CompletableFuture<Route> balancedFuture = CompletableFuture.supplyAsync(() ->
                    generateOptimizedBalancedRoute(startLat, startLng, endLat, endLng, dateTime));

            // 결과 수집 (타임아웃 적용)
            try {
                Route shadeRoute = shadeFuture.get(20, TimeUnit.SECONDS); // 20초 타임아웃
                if (shadeRoute != null && validateEnhancedRouteQuality(shadeRoute, shortestRoute, "shade")) {
                    RouteCandidate shadeCandidate = new RouteCandidate("shade", "그늘이 많은경로", shadeRoute);
                    String efficiencyInfo = shadeCandidate.calculateEfficiencyDisplay(shortestRoute);
                    shadeCandidate.setDescription(shadeCandidate.getDescription() + " · " + efficiencyInfo);
                    candidates.add(shadeCandidate);
                    logger.info("그림자경로: {}m, {}분, 그늘 {}%",
                            (int)shadeRoute.getDistance(), shadeRoute.getDuration(), shadeRoute.getShadowPercentage());
                } else {
                    logger.info("그림자경로: 품질 기준 미달, 제외");
                    // 생성 불가 후보 추가
                    candidates.add(createUnavailableCandidate("shade", "그늘이 많은경로", "품질 기준 미달"));
                }
            } catch (TimeoutException e) {
                logger.warn("그림자 경로 생성 시간 초과 (20초)");
                shadeFuture.cancel(true);
                candidates.add(createUnavailableCandidate("shade", "그늘이 많은경로", "처리 시간 초과"));
            } catch (Exception e) {
                logger.error("그림자 경로 생성 오류: " + e.getMessage());
                candidates.add(createUnavailableCandidate("shade", "그늘이 많은경로", "생성 오류"));
            }

            try {
                Route balancedRoute = balancedFuture.get(20, TimeUnit.SECONDS);
                if (balancedRoute != null && validateEnhancedRouteQuality(balancedRoute, shortestRoute, "balanced")) {
                    RouteCandidate balancedCandidate = new RouteCandidate("balanced", "균형경로", balancedRoute);
                    String efficiencyInfo = balancedCandidate.calculateEfficiencyDisplay(shortestRoute);
                    balancedCandidate.setDescription(balancedCandidate.getDescription() + " · " + efficiencyInfo);
                    candidates.add(balancedCandidate);
                    logger.info("균형경로: {}m, {}분, 그늘 {}%",
                            (int)balancedRoute.getDistance(), balancedRoute.getDuration(), balancedRoute.getShadowPercentage());
                } else {
                    logger.info("균형경로: 품질 기준 미달로 제외");
                    candidates.add(createUnavailableCandidate("balanced", "균형경로", "품질 기준 미달"));
                }
            } catch (TimeoutException e) {
                logger.warn("균형 경로 생성 시간 초과 (20초)");
                balancedFuture.cancel(true);
                candidates.add(createUnavailableCandidate("balanced", "균형경로", "처리 시간 초과"));
            } catch (Exception e) {
                logger.error("균형 경로 생성 오류: " + e.getMessage());
                candidates.add(createUnavailableCandidate("balanced", "균형경로", "생성 오류"));
            }

            long totalTime = System.currentTimeMillis() - startTime;
            logger.info("총 처리시간: {}ms, 생성된 후보: {}개", totalTime, candidates.size());

            // 항상 3개 후보 반환
            while (candidates.size() < 3) {
                candidates.add(createUnavailableCandidate("unavailable", "추가경로", "생성 불가"));
            }

            return candidates;

        } catch (Exception e) {
            logger.error("다중 경유지 경로 생성 실패: " + e.getMessage(), e);
            return generateShortestRouteOnly(startLat, startLng, endLat, endLng, dateTime);
        }
    }

    /**
     *  다중 경유지 기반 그림자 경로 생성
     */
    private Route generateOptimizedShadeRoute(double startLat, double startLng, double endLat, double endLng, LocalDateTime dateTime) {
        try {
            logger.info("=== 다중 경유지 그림자 경로 생성 시작 ===");

            // 태양 위치 및 그림자 영역 계산
            SunPosition sunPos = shadowService.calculateSunPosition(startLat, startLng, dateTime);
            List<ShadowArea> shadowAreas = shadowRouteService.calculateBuildingShadows(
                    startLat, startLng, endLat, endLng, sunPos);

            if (shadowAreas.isEmpty()) {
                logger.debug("그림자 영역 없음 - 기본 경로 사용");
                return generateShortestRoute(startLat, startLng, endLat, endLng,dateTime);
            }

            // 경유지 생성 (적응적 개수)
            List<RoutePoint> waypointCandidates = generateSmartWaypoints(
                    startLat, startLng, endLat, endLng, shadowAreas, sunPos, "shade");

            logger.info("그림자 경유지: {}개", waypointCandidates.size());

            if (waypointCandidates.isEmpty()) {
                logger.warn("그림자 경유지 생성 실패 - 기본 경로 사용");
                return generateShortestRoute(startLat, startLng, endLat, endLng,dateTime);
            }

            // 배치 호출
            List<Route> candidateRoutes = evaluateWaypointsBatch(
                    waypointCandidates, startLat, startLng, endLat, endLng, shadowAreas, 5);

            // 최적 그림자 경로 선택
            Route bestRoute = selectBestShadeRouteAdvanced(candidateRoutes, shadowAreas);

            if (bestRoute != null) {
                bestRoute.setRouteType("shade");
                bestRoute.setWaypointCount(1);
                logger.info("최적 그림자 경로 선택: 그늘 {}%, 거리 {}m",
                        bestRoute.getShadowPercentage(), (int)bestRoute.getDistance());
            } else {
                logger.warn("적합한 그림자 경로 없음 - 기본 경로 사용");
                bestRoute = generateShortestRoute(startLat, startLng, endLat, endLng,dateTime);
            }

            return bestRoute;

        } catch (Exception e) {
            logger.error("다중 경유지 그림자 경로 생성 실패: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     *  다중 경유지 기반 균형 경로 생성
     */
    private Route generateOptimizedBalancedRoute(double startLat, double startLng, double endLat, double endLng, LocalDateTime dateTime) {
        try {
            logger.info("=== 다중 경유지 균형 경로 생성 시작 ===");

            SunPosition sunPos = shadowService.calculateSunPosition(startLat, startLng, dateTime);
            List<ShadowArea> shadowAreas = shadowRouteService.calculateBuildingShadows(
                    startLat, startLng, endLat, endLng, sunPos);

            // 균형을 위한 경유지 생성
            List<RoutePoint> waypointCandidates = generateSmartWaypoints(
                    startLat, startLng, endLat, endLng, shadowAreas, sunPos, "balanced");

            logger.info("균형 경유지: {}개", waypointCandidates.size());

            if (waypointCandidates.isEmpty()) {
                logger.warn("균형 경유지 생성 실패 - 기본 경로 변형 사용");
                Route baseRoute = generateShortestRoute(startLat, startLng, endLat, endLng,dateTime);
                return createSlightVariation(baseRoute, startLat, startLng, endLat, endLng);
            }

            // 배치 처리
            List<Route> candidateRoutes = evaluateWaypointsBatch(
                    waypointCandidates, startLat, startLng, endLat, endLng, shadowAreas, 5);

            // 기준 경로
            Route baseRoute = generateShortestRoute(startLat, startLng, endLat, endLng,dateTime);

            Route bestRoute = selectBestBalancedRouteAdvanced(candidateRoutes, baseRoute);

            if (bestRoute != null) {
                bestRoute.setRouteType("balanced");
                bestRoute.setWaypointCount(1);
                logger.info("최적 균형 경로 선택: 그늘 {}%, 거리 {}m",
                        bestRoute.getShadowPercentage(), (int)bestRoute.getDistance());
            } else {
                logger.warn("적합한 균형 경로 없음 - 기본 경로 변형 사용");
                bestRoute = createSlightVariation(baseRoute, startLat, startLng, endLat, endLng);
            }

            return bestRoute;

        } catch (Exception e) {
            logger.error("다중 경유지 균형 경로 생성 실패: " + e.getMessage(), e);
            Route baseRoute = generateShortestRoute(startLat, startLng, endLat, endLng,dateTime);
            return createSlightVariation(baseRoute, startLat, startLng, endLat, endLng);
        }
    }

    /**
     *  경유지 생성 (적응적)
     */
    private List<RoutePoint> generateSmartWaypoints(double startLat, double startLng, double endLat, double endLng,
                                                    List<ShadowArea> shadowAreas, SunPosition sunPos, String routeType) {
        List<RoutePoint> candidates = new ArrayList<>();

        try {
            double routeDistance = calculateDistance(startLat, startLng, endLat, endLng);
            RoutePoint startPoint = new RoutePoint(startLat, startLng);
            RoutePoint endPoint = new RoutePoint(endLat, endLng);
            double destinationDirection = calculateBearing(startPoint, endPoint);

            // 거리별 경유지 개수
            int targetCandidates;
            double maxDeviation; // 최대 우회 거리

            if (routeDistance < 300) {
                targetCandidates = 3;
                maxDeviation = 40;
            } else if (routeDistance < 500) {
                targetCandidates = 5;
                maxDeviation = 60;
            } else if (routeDistance < 1000) {
                targetCandidates = 10;
                maxDeviation = 100;
            } else {
                targetCandidates = 15;
                maxDeviation = 150;
            }

            logger.debug("경로 거리 {}m → 목표 경유지 {}개, 최대 우회 {}m", (int)routeDistance, targetCandidates, (int)maxDeviation);

            // 전략적 지점 (우선순위 높음)
            candidates.addAll(generateStrategicWaypointsWithLimit(
                    startLat, startLng, endLat, endLng, destinationDirection,
                    routeType, sunPos, shadowAreas, maxDeviation));

            // 균등 분포 경유지
            if (candidates.size() < targetCandidates) {
                candidates.addAll(generateDistributedWaypoints(startLat, startLng, endLat, endLng,
                        destinationDirection, targetCandidates - candidates.size()));
            }

            // 그림자 밀도 기반 (그림자 경로의 경우)
            if ("shade".equals(routeType) && !shadowAreas.isEmpty() && candidates.size() < targetCandidates) {
                candidates.addAll(generateShadowDensityWaypoints(startLat, startLng, endLat, endLng,
                        shadowAreas, destinationDirection,
                        targetCandidates - candidates.size()));
            }

            // 중복 제거 및 유효성 검사
            List<RoutePoint> validCandidates = candidates.stream()
                    .filter(wp -> isWaypointProgressive(startPoint, wp, endPoint))
                    .filter(wp -> isWaypointReasonable(wp, startPoint, endPoint))
                    .distinct()
                    .limit(targetCandidates)
                    .collect(Collectors.toList());

            logger.info("스마트 경유지 최종: {}개 (목표: {}개)", validCandidates.size(), targetCandidates);
            return validCandidates;

        } catch (Exception e) {
            logger.error("스마트 경유지 생성 오류: " + e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     *  배치 처리로 경유지 평가 (성능 최적화)
     */
    private List<Route> evaluateWaypointsBatch(List<RoutePoint> waypoints, double startLat, double startLng,
                                               double endLat, double endLng, List<ShadowArea> shadowAreas, int batchSize) {
        List<Route> results = new ArrayList<>();

        try {
            logger.info("배치 처리 시작: {}개 경유지, 배치크기 {}", waypoints.size(), batchSize);

            for (int i = 0; i < waypoints.size(); i += batchSize) {
                int endIndex = Math.min(i + batchSize, waypoints.size());
                List<RoutePoint> batch = waypoints.subList(i, endIndex);

                logger.debug("배치 {}/{} 처리 중...", (i / batchSize) + 1, (waypoints.size() + batchSize - 1) / batchSize);

                // 배치 내 병렬 처리
                List<CompletableFuture<Route>> futures = batch.stream()
                        .map(waypoint -> CompletableFuture.supplyAsync(() -> {
                            try {
                                String routeJson = tmapApiService.getWalkingRouteWithWaypoint(
                                        startLat, startLng, waypoint.getLat(), waypoint.getLng(), endLat, endLng);
                                Route route = shadowRouteService.parseBasicRoute(routeJson);
                                if (route != null && !route.getPoints().isEmpty()) {
                                    shadowRouteService.applyShadowInfoFromDB(route, shadowAreas);
                                    return route;
                                }
                                return null;
                            } catch (Exception e) {
                                logger.debug("경유지 평가 실패: " + e.getMessage());
                                return null;
                            }
                        }))
                        .collect(Collectors.toList());

                // 배치 결과 수집 (타임아웃 적용)
                for (CompletableFuture<Route> future : futures) {
                    try {
                        Route route = future.get(5, TimeUnit.SECONDS); // 각 경로 5초 타임아웃
                        if (route != null) {
                            results.add(route);
                        }
                    } catch (TimeoutException e) {
                        logger.debug("개별 경로 처리 타임아웃");
                        future.cancel(true);
                    } catch (Exception e) {
                        logger.debug("개별 경로 처리 실패: " + e.getMessage());
                    }
                }

                logger.debug("배치 완료, 누적 결과: {}개", results.size());

                // 중간 체크: 충분한 결과가 나왔으면 조기 종료
                if (results.size() >= 8) {
                    logger.info("충분한 후보 확보 ({}개) - 처리 조기 종료", results.size());
                    break;
                }
            }

            logger.info("배치 처리 완료: {}개 → {}개 유효 경로", waypoints.size(), results.size());
            return results;

        } catch (Exception e) {
            logger.error("배치 경유지 평가 오류: " + e.getMessage(), e);
            return results;
        }
    }

    /**
     *  경로 품질 검증
     */
    private boolean validateEnhancedRouteQuality(Route route, Route baseRoute, String routeType) {
        try {
            if (route == null || route.getPoints().isEmpty() || baseRoute == null) {
                return false;
            }

            // 기본 검증
            if (route.getDistance() < 50 || route.getPoints().size() < 3) {
                logger.debug("경로 기본 검증 실패: 너무 짧거나 점이 부족");
                return false;
            }

            // 거리 비율 검증 (더 엄격)
            double distanceRatio = route.getDistance() / baseRoute.getDistance();
            double maxRatio = "shade".equals(routeType) ? 1.6 : 1.4; // 그림자 160%, 균형 140%

            if (distanceRatio > maxRatio) {
                logger.debug("거리 비율 초과: {}% > {}%", (int)(distanceRatio * 100), (int)(maxRatio * 100));
                return false;
            }

            // 그림자 효과 검증 (그림자 경로의 경우)
            if ("shade".equals(routeType)) {
                int shadowDiff = route.getShadowPercentage() - baseRoute.getShadowPercentage();
                if (shadowDiff < 15) { // 최소 15% 이상 그늘 증가
                    logger.debug("그림자 효과 부족: +{}% < 15%", shadowDiff);
                    return false;
                }
            }

            logger.debug("경로 품질 검증 통과: {}경로, 거리비율 {}%, 그늘 {}%",
                    routeType, (int)(distanceRatio * 100), route.getShadowPercentage());
            return true;

        } catch (Exception e) {
            logger.error("경로 품질 검증 오류: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 전략적 경유지 생성 (우선순위 높음)
     */
    private List<RoutePoint> generateStrategicWaypointsWithLimit(
            double startLat, double startLng, double endLat, double endLng,
            double destinationDirection, String routeType,
            SunPosition sunPos, List<ShadowArea> shadowAreas, double maxDeviation) {

        List<RoutePoint> waypoints = new ArrayList<>();
        double routeDistance = calculateDistance(startLat, startLng, endLat, endLng);

        // 거리별로 다른 비율 적용
        double[] ratios;
        if (routeDistance < 500) {
            ratios = new double[]{0.5};
        } else if (routeDistance < 1000) {
            ratios = new double[]{0.33, 0.67};
        } else {
            ratios = new double[]{0.25, 0.5, 0.75};
        }

        for (double ratio : ratios) {
            double midLat = startLat + (endLat - startLat) * ratio;
            double midLng = startLng + (endLng - startLng) * ratio;

            // 그림자 영역과의 거리 기반으로 경유지 생성
            if ("shade".equals(routeType) && !shadowAreas.isEmpty()) {
                // 가장 가까운 그림자 영역 찾기
                RoutePoint nearestShadow = findNearestShadowPoint(midLat, midLng, shadowAreas);
                if (nearestShadow != null) {
                    double distanceToShadow = calculateDistance(midLat, midLng,
                            nearestShadow.getLat(), nearestShadow.getLng());

                    // maxDeviation 이내에 있는 경우만 추가
                    if (distanceToShadow <= maxDeviation) {
                        // 그림자 방향으로 적절한 거리만큼 이동
                        double moveDistance = Math.min(distanceToShadow, maxDeviation * 0.8);
                        double bearing = calculateBearing(new RoutePoint(midLat, midLng), nearestShadow);
                        RoutePoint waypoint = createWaypointAtDirection(midLat, midLng, bearing, moveDistance);

                        if (waypoint != null && isWaypointValid(waypoint, startLat, startLng, endLat, endLng, maxDeviation)) {
                            waypoints.add(waypoint);
                        }
                    }
                }
            } else {
                // 균형 경로: 좌우 균등하되 maxDeviation 이내
                double[] offsets = {maxDeviation * 0.5, maxDeviation * 0.7};
                for (double offset : offsets) {
                    double leftDirection = (destinationDirection - 90 + 360) % 360;
                    RoutePoint waypoint = createWaypointAtDirection(midLat, midLng, leftDirection, offset);

                    if (waypoint != null && isWaypointValid(waypoint, startLat, startLng, endLat, endLng, maxDeviation)) {
                        waypoints.add(waypoint);
                        break;
                    }
                }
            }
        }

        return waypoints;
    }

    private boolean isWaypointValid(RoutePoint waypoint, double startLat, double startLng,
                                    double endLat, double endLng, double maxDeviation) {
        // 직선에서의 거리 확인
        double perpDistance = calculatePointToLineDistance(waypoint,
                new RoutePoint(startLat, startLng), new RoutePoint(endLat, endLng));

        if (perpDistance > maxDeviation) {
            return false;
        }

        // 진행률 확인 (너무 시작/끝 부분은 제외)
        double progress = calculateProgressAlongRoute(
                new RoutePoint(startLat, startLng), waypoint, new RoutePoint(endLat, endLng));

        if (progress < 0.15 || progress > 0.85) {
            return false;
        }

        // 우회율 확인
        double directDistance = calculateDistance(startLat, startLng, endLat, endLng);
        double viaDistance = calculateDistance(startLat, startLng, waypoint.getLat(), waypoint.getLng()) +
                calculateDistance(waypoint.getLat(), waypoint.getLng(), endLat, endLng);
        double detourRatio = viaDistance / directDistance;

        // 거리별 다른 우회율 허용
        double maxDetourRatio = directDistance < 500 ? 1.15 : 1.25;

        return detourRatio <= maxDetourRatio;
    }

    /**
     * 경로상 진행률 계산
     */
    private double calculateProgressAlongRoute(RoutePoint start, RoutePoint point, RoutePoint end) {
        // 벡터 투영을 이용한 진행률 계산
        double totalDistance = calculateDistance(start.getLat(), start.getLng(), end.getLat(), end.getLng());
        if (totalDistance == 0) return 0;

        // 시작점에서 현재점까지의 벡터
        double dx = point.getLng() - start.getLng();
        double dy = point.getLat() - start.getLat();

        // 시작점에서 끝점까지의 단위 벡터
        double dirX = (end.getLng() - start.getLng()) / totalDistance;
        double dirY = (end.getLat() - start.getLat()) / totalDistance;

        // 내적을 통한 투영 거리
        double projectedDistance = dx * dirX + dy * dirY;

        return projectedDistance / totalDistance;
    }


    /**
     * 균등 분포 경유지 생성
     */
    private List<RoutePoint> generateDistributedWaypoints(double startLat, double startLng, double endLat, double endLng,
                                                          double destinationDirection, int needed) {
        List<RoutePoint> waypoints = new ArrayList<>();

        try {
            // 좌우로 균등하게 분포
            double[] distances = {30.0, 60.0, 90.0};
            double[] ratios = {0.3, 0.5, 0.7};

            for (double ratio : ratios) {
                if (waypoints.size() >= needed) break;

                double midLat = startLat + (endLat - startLat) * ratio;
                double midLng = startLng + (endLng - startLng) * ratio;

                for (double distance : distances) {
                    if (waypoints.size() >= needed) break;

                    // 좌우 방향
                    double leftDirection = (destinationDirection - 90 + 360) % 360;
                    double rightDirection = (destinationDirection + 90) % 360;

                    RoutePoint leftWaypoint = createWaypointAtDirection(midLat, midLng, leftDirection, distance);
                    if (leftWaypoint != null && waypoints.size() < needed) {
                        waypoints.add(leftWaypoint);
                    }

                    RoutePoint rightWaypoint = createWaypointAtDirection(midLat, midLng, rightDirection, distance);
                    if (rightWaypoint != null && waypoints.size() < needed) {
                        waypoints.add(rightWaypoint);
                    }
                }
            }

        } catch (Exception e) {
            logger.error("균등 분포 경유지 생성 오류: " + e.getMessage(), e);
        }

        return waypoints;
    }

    /**
     * 그림자 밀도 기반 경유지 생성
     */
    private List<RoutePoint> generateShadowDensityWaypoints(double startLat, double startLng, double endLat, double endLng,
                                                            List<ShadowArea> shadowAreas, double destinationDirection, int needed) {
        List<RoutePoint> waypoints = new ArrayList<>();

        try {
            // 그림자 영역의 중심점들 계산
            List<RoutePoint> shadowCenters = calculateShadowCenters(shadowAreas);

            for (RoutePoint shadowCenter : shadowCenters) {
                if (waypoints.size() >= needed) break;

                // 경로 상 중간지점에서 그림자 중심으로 향하는 방향
                RoutePoint midPoint = new RoutePoint((startLat + endLat) / 2, (startLng + endLng) / 2);
                double shadowDirection = calculateBearing(midPoint, shadowCenter);

                // 방향 제약 적용
                double constrainedDirection = constrainDirectionToDestination(shadowDirection, destinationDirection);

                // 다양한 거리로 경유지 생성
                for (double distance : Arrays.asList(40.0, 60.0, 90.0)) {
                    if (waypoints.size() >= needed) break;

                    RoutePoint waypoint = createWaypointAtDirection(
                            midPoint.getLat(), midPoint.getLng(), constrainedDirection, distance);
                    if (waypoint != null) {
                        waypoints.add(waypoint);
                    }
                }
            }

        } catch (Exception e) {
            logger.error("그림자 밀도 기반 경유지 생성 오류: " + e.getMessage(), e);
        }

        return waypoints;
    }

    /**
     *  그림자가 가장 많은 경로 선택
     */
    private Route selectBestShadeRouteAdvanced(List<Route> candidateRoutes, List<ShadowArea> shadowAreas) {
        try {
            if (candidateRoutes.isEmpty()) {
                logger.info("선택할 그림자 경로 후보가 없음");
                return null;
            }

            double baseDistance = candidateRoutes.get(0).getDistance();

            // 유효한 경로만 필터링 (과도한 우회 제거)
            List<Route> validRoutes = candidateRoutes.stream()
                    .filter(route -> {
                        double detourRatio = route.getDistance() / baseDistance;
                        // 거리별 다른 기준 적용
                        double maxRatio = baseDistance < 500 ? 1.2 : 1.4;
                        return detourRatio <= maxRatio;
                    })
                    .collect(Collectors.toList());

            if (validRoutes.isEmpty()) {
                logger.info("유효한 그림자 경로가 없음");
                return null;
            }

            // 그림자가 충분한 경로만 선택
            Route bestRoute = validRoutes.stream()
                    .filter(route -> route.getShadowPercentage() >= 25) // 최소 25% 그늘
                    .sorted((r1, r2) -> {
                        // 그림자 비율과 효율성을 모두 고려
                        double score1 = calculateRouteScore(r1, baseDistance);
                        double score2 = calculateRouteScore(r2, baseDistance);
                        return Double.compare(score2, score1);
                    })
                    .findFirst()
                    .orElse(null);

            if (bestRoute == null) {
                logger.info("그림자 효과가 충분한 경로가 없음");
                return null;
            }

            logger.info("선택된 그림자 경로: 그늘 {}%, 거리 {}m, 우회율 {}%",
                    bestRoute.getShadowPercentage(),
                    (int)bestRoute.getDistance(),
                    (int)((bestRoute.getDistance() / baseDistance - 1) * 100));

            return bestRoute;

        } catch (Exception e) {
            logger.error("최적 그림자 경로 선택 오류: " + e.getMessage(), e);
            return candidateRoutes.isEmpty() ? null : candidateRoutes.get(0);
        }
    }

    // 경로 점수 계산
    private double calculateRouteScore(Route route, double baseDistance) {
        // 그림자 비율 점수 (0~100)
        double shadowScore = route.getShadowPercentage();

        // 효율성 점수 (우회가 적을수록 높음)
        double detourRatio = route.getDistance() / baseDistance;
        double efficiencyScore = Math.max(0, 100 - (detourRatio - 1) * 200);

        // 종합 점수 (그림자 70%, 효율성 30%)
        return shadowScore * 0.7 + efficiencyScore * 0.3;
    }

    /**
     *  가장 균형잡힌 경로 선택
     */
    private Route selectBestBalancedRouteAdvanced(List<Route> candidateRoutes, Route baseRoute) {
        try {
            if (candidateRoutes.isEmpty()) {
                logger.info("선택할 균형 경로 후보가 없음");
                return null;
            }

            // 균형 점수 계산 및 정렬
            List<Route> sortedByBalance = candidateRoutes.stream()
                    .sorted((r1, r2) -> Double.compare(
                            calculateBalanceScore(r2, baseRoute),
                            calculateBalanceScore(r1, baseRoute)))
                    .collect(Collectors.toList());

            // 상위 3개 후보 로깅
            logger.info("=== 균형 경로 상위 후보들 ===");
            for (int i = 0; i < Math.min(3, sortedByBalance.size()); i++) {
                Route route = sortedByBalance.get(i);
                double balanceScore = calculateBalanceScore(route, baseRoute);
                logger.info("{}위: 균형점수 {}, 그늘 {}%, 거리 {}m",
                        i + 1, String.format("%.1f", balanceScore),
                        route.getShadowPercentage(), (int)route.getDistance());
            }

            Route bestRoute = sortedByBalance.get(0);
            logger.info("선택된 균형 경로: 그늘 {}%, 거리 {}m",
                    bestRoute.getShadowPercentage(), (int)bestRoute.getDistance());

            return bestRoute;

        } catch (Exception e) {
            logger.error("최적 균형 경로 선택 오류: " + e.getMessage(), e);
            return candidateRoutes.isEmpty() ? null : candidateRoutes.get(0);
        }
    }

    /**
     * 균형 점수 계산 (그늘과 거리의 적절한 조합)
     */
    private double calculateBalanceScore(Route route, Route baseRoute) {
        try {
            // 거리 비율 (낮을수록 좋음)
            double distanceRatio = route.getDistance() / baseRoute.getDistance();
            double distanceScore = Math.max(0, 100 - (distanceRatio - 1) * 200); // 100% 기준 벗어날수록 감점

            // 그늘 비율 (적당한 수준이 좋음: 30-60%)
            int shadowPercentage = route.getShadowPercentage();
            double shadowScore;
            if (shadowPercentage >= 30 && shadowPercentage <= 60) {
                shadowScore = 100; // 최적 범위
            } else if (shadowPercentage >= 20 && shadowPercentage <= 70) {
                shadowScore = 80; // 양호 범위
            } else if (shadowPercentage >= 10 && shadowPercentage <= 80) {
                shadowScore = 60; // 허용 범위
            } else {
                shadowScore = Math.max(0, shadowPercentage); // 그늘이 많을수록 점수
            }

            // 종합 점수 (거리 30%, 그늘 70%)
            double totalScore = distanceScore * 0.3 + shadowScore * 0.7;

            return totalScore;

        } catch (Exception e) {
            logger.error("균형 점수 계산 오류: " + e.getMessage(), e);
            return 0.0;
        }
    }

    /**
     * 경유지 합리성 검사
     */
    private boolean isWaypointReasonable(RoutePoint waypoint, RoutePoint start, RoutePoint end) {
        try {
            // 경유지가 출발지-목적지 직선에서 너무 멀리 떨어지지 않았는지 확인
            double directDistance = calculateDistance(start.getLat(), start.getLng(), end.getLat(), end.getLng());
            double waypointToLineDistance = calculatePointToLineDistance(waypoint, start, end);

            // 직선거리의 15% 이내에 있어야 함
            return waypointToLineDistance <= directDistance * 0.15;

        } catch (Exception e) {
            logger.error("경유지 합리성 검사 오류: " + e.getMessage(), e);
            return true; // 오류 시 허용
        }
    }

    /**
     * 점과 직선 사이의 거리 계산
     */
    private double calculatePointToLineDistance(RoutePoint point, RoutePoint lineStart, RoutePoint lineEnd) {
        try {
            // 벡터 계산
            double A = point.getLat() - lineStart.getLat();
            double B = point.getLng() - lineStart.getLng();
            double C = lineEnd.getLat() - lineStart.getLat();
            double D = lineEnd.getLng() - lineStart.getLng();

            double dot = A * C + B * D;
            double lenSq = C * C + D * D;
            double param = lenSq != 0 ? dot / lenSq : -1;

            double closestLat, closestLng;
            if (param < 0) {
                closestLat = lineStart.getLat();
                closestLng = lineStart.getLng();
            } else if (param > 1) {
                closestLat = lineEnd.getLat();
                closestLng = lineEnd.getLng();
            } else {
                closestLat = lineStart.getLat() + param * C;
                closestLng = lineStart.getLng() + param * D;
            }

            return calculateDistance(point.getLat(), point.getLng(), closestLat, closestLng);

        } catch (Exception e) {
            logger.error("점-직선 거리 계산 오류: " + e.getMessage(), e);
            return 0.0;
        }
    }

    /**
     * 특정 방향과 거리로 경유지 생성
     */
    private RoutePoint createWaypointAtDirection(double baseLat, double baseLng, double direction, double distanceMeters) {
        try {
            double directionRad = Math.toRadians(direction);
            double latDegreeInMeters = 111000.0;
            double lngDegreeInMeters = 111000.0 * Math.cos(Math.toRadians(baseLat));

            double latOffset = distanceMeters * Math.cos(directionRad) / latDegreeInMeters;
            double lngOffset = distanceMeters * Math.sin(directionRad) / lngDegreeInMeters;

            RoutePoint waypoint = new RoutePoint();
            waypoint.setLat(baseLat + latOffset);
            waypoint.setLng(baseLng + lngOffset);

            // 유효성 검사 (한국 좌표 범위)
            if (waypoint.getLat() >= 33.0 && waypoint.getLat() <= 39.0 &&
                    waypoint.getLng() >= 124.0 && waypoint.getLng() <= 132.0) {
                return waypoint;
            }

            return null;

        } catch (Exception e) {
            logger.error("방향 기반 경유지 생성 오류: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 가장 가까운 그림자 영역 방향 찾기
     */
    private RoutePoint findNearestShadowPoint(double lat, double lng, List<ShadowArea> shadowAreas) {
        try {
            if (shadowAreas.isEmpty()) return null;

            RoutePoint reference = new RoutePoint(lat, lng);
            List<RoutePoint> shadowCenters = calculateShadowCenters(shadowAreas);

            // 거리별로 정렬해서 가장 가까운 것 선택
            return shadowCenters.stream()
                    .min((p1, p2) -> Double.compare(
                            calculateDistance(lat, lng, p1.getLat(), p1.getLng()),
                            calculateDistance(lat, lng, p2.getLat(), p2.getLng())
                    ))
                    .orElse(null);

        } catch (Exception e) {
            logger.error("가장 가까운 그림자 방향 찾기 오류: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 생성 불가 후보 생성
     */
    private RouteCandidate createUnavailableCandidate(String type, String displayName, String reason) {
        RouteCandidate unavailable = new RouteCandidate();
        unavailable.setType(type);
        unavailable.setDisplayName(displayName);
        unavailable.setDescription("생성 불가: " + reason);
        unavailable.setColor("#CCCCCC"); // 회색
        unavailable.setRoute(null); // 경로 없음
        unavailable.setScore(0.0);

        return unavailable;
    }

    /**
     *  최단경로 생성
     */
    private Route generateShortestRoute(double startLat, double startLng, double endLat, double endLng ,  LocalDateTime  dateTime) {
        try {
            logger.debug("최단경로 생성 중...");

            String routeJson = tmapApiService.getWalkingRoute(startLat, startLng, endLat, endLng);
            Route route = shadowRouteService.parseBasicRoute(routeJson);

            route.setRouteType("shortest");
            route.setWaypointCount(0);

            try {
                SunPosition sunPos = shadowService.calculateSunPosition(startLat, startLng, dateTime);
                List<ShadowArea> shadowAreas = shadowRouteService.calculateBuildingShadows(
                        startLat, startLng, endLat, endLng, sunPos);

                if (!shadowAreas.isEmpty()) {
                    shadowRouteService.applyShadowInfoFromDB(route, shadowAreas);
                    logger.debug("최단경로 그림자 정보 적용: {}%", route.getShadowPercentage());
                }
            } catch (Exception e) {
                logger.warn("최단경로 그림자 정보 적용 실패: " + e.getMessage());
                route.setShadowPercentage(0);
            }

            return route;

        } catch (Exception e) {
            logger.error("최단경로 생성 실패: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 목적지 방향을 고려하여 경유지 방향 제약
     */
    private double constrainDirectionToDestination(double preferredDirection, double destinationDirection) {
        try {
            // 목적지 방향 ±60도 범위 내에서만 경유지 설정 허용
            double maxAngleDiff = 60.0;

            // 두 방향 간의 각도 차이 계산
            double angleDiff = Math.abs(preferredDirection - destinationDirection);
            if (angleDiff > 180) {
                angleDiff = 360 - angleDiff;
            }

            // 각도 차이가 허용 범위 내면 그대로 사용
            if (angleDiff <= maxAngleDiff) {
                return preferredDirection;
            }

            // 허용 범위를 벗어나면 가장 가까운 허용 방향으로 조정
            double constrainedDirection;
            if (preferredDirection > destinationDirection) {
                if (preferredDirection - destinationDirection <= 180) {
                    constrainedDirection = (destinationDirection + maxAngleDiff) % 360;
                } else {
                    constrainedDirection = (destinationDirection - maxAngleDiff + 360) % 360;
                }
            } else {
                if (destinationDirection - preferredDirection <= 180) {
                    constrainedDirection = (destinationDirection - maxAngleDiff + 360) % 360;
                } else {
                    constrainedDirection = (destinationDirection + maxAngleDiff) % 360;
                }
            }

            return constrainedDirection;

        } catch (Exception e) {
            logger.error("방향 제약 계산 오류: " + e.getMessage(), e);
            return destinationDirection;
        }
    }

    /**
     * 방위각 계산 (북쪽 기준 0-360도)
     */
    private double calculateBearing(RoutePoint from, RoutePoint to) {
        double deltaLng = Math.toRadians(to.getLng() - from.getLng());
        double fromLat = Math.toRadians(from.getLat());
        double toLat = Math.toRadians(to.getLat());

        double y = Math.sin(deltaLng) * Math.cos(toLat);
        double x = Math.cos(fromLat) * Math.sin(toLat) -
                Math.sin(fromLat) * Math.cos(toLat) * Math.cos(deltaLng);

        double bearing = Math.toDegrees(Math.atan2(y, x));
        return (bearing + 360) % 360;
    }

    /**
     * 경유지가 목적지 방향으로 진행하는지 검증
     */
    private boolean isWaypointProgressive(RoutePoint start, RoutePoint waypoint, RoutePoint end) {
        try {
            // 기본 거리 계산
            double distanceToWaypoint = calculateDistance(
                    start.getLat(), start.getLng(),
                    waypoint.getLat(), waypoint.getLng());

            double directDistance = calculateDistance(
                    start.getLat(), start.getLng(),
                    end.getLat(), end.getLng());

            double waypointToEnd = calculateDistance(
                    waypoint.getLat(), waypoint.getLng(),
                    end.getLat(), end.getLng());

            // 우회 비율 검증
            double totalViaWaypoint = distanceToWaypoint + waypointToEnd;
            double detourRatio = totalViaWaypoint / directDistance;
            if (detourRatio > 1.15) {
                logger.debug("우회 비율 과다: {}% > 115%", (int)(detourRatio * 100));
                return false;
            }

            // 목적지 접근도 검증
            double approachRatio = waypointToEnd / directDistance;
            if (approachRatio > 0.75) {
                logger.debug("목적지 접근 부족: {}% 남음", (int)(approachRatio * 100));
                return false;
            }

            // 방향 일치도 검증
            double startToWaypointBearing = calculateBearing(start, waypoint);
            double startToEndBearing = calculateBearing(start, end);
            double bearingDiff = Math.abs(startToWaypointBearing - startToEndBearing);
            if (bearingDiff > 180) bearingDiff = 360 - bearingDiff;

            if (bearingDiff > 75) {
                logger.debug("방향 편차 과다: {}도 > 75도", (int)bearingDiff);
                return false;
            }

            // 경유지가 출발-목적지 직선을 기준으로 너무 멀리 벗어나지 않는지 검증
            double perpDistance = calculatePointToLineDistance(waypoint, start, end);
            double maxPerpDistance = directDistance * 0.25; // 직선거리의 25% 이내
            if (perpDistance > maxPerpDistance) {
                logger.debug("직선 이탈 과다: {}m > {}m", (int)perpDistance, (int)maxPerpDistance);
                return false;
            }

            // 경유지가 출발지나 목적지에 너무 가깝지 않은지 검증
            double minDistanceFromStart = directDistance * 0.15; // 직선거리의 15% 이상
            double minDistanceFromEnd = directDistance * 0.15;   // 직선거리의 15% 이상

            if (distanceToWaypoint < minDistanceFromStart) {
                logger.debug("출발지에 너무 가까움: {}m < {}m", (int)distanceToWaypoint, (int)minDistanceFromStart);
                return false;
            }

            if (waypointToEnd < minDistanceFromEnd) {
                logger.debug("목적지에 너무 가까움: {}m < {}m", (int)waypointToEnd, (int)minDistanceFromEnd);
                return false;
            }

            // 경유지가 실제로 목적지 방향으로 전진하는지
            if (!isActuallyProgressing(start, waypoint, end)) {
                logger.debug("목적지 방향 전진 실패");
                return false;
            }

            logger.debug("✅ 경유지 검증 통과: 우회={}%, 접근={}%, 방향차이={}도, 직선이탈={}m",
                    (int)(detourRatio * 100),
                    (int)((1 - approachRatio) * 100),
                    (int)bearingDiff,
                    (int)perpDistance);
            return true;

        } catch (Exception e) {
            logger.error("경유지 진행성 검증 오류: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     *  실제로 목적지 방향으로 전진하는지 벡터 검증
     */
    private boolean isActuallyProgressing(RoutePoint start, RoutePoint waypoint, RoutePoint end) {
        try {
            // 출발지 → 목적지 벡터
            double targetVectorLat = end.getLat() - start.getLat();
            double targetVectorLng = end.getLng() - start.getLng();

            // 출발지 → 경유지 벡터
            double waypointVectorLat = waypoint.getLat() - start.getLat();
            double waypointVectorLng = waypoint.getLng() - start.getLng();

            // 벡터 내적 계산 (같은 방향이면 양수)
            double dotProduct = targetVectorLat * waypointVectorLat + targetVectorLng * waypointVectorLng;

            // 목적지 벡터의 크기 제곱
            double targetMagnitudeSquared = targetVectorLat * targetVectorLat + targetVectorLng * targetVectorLng;

            // 내적이 목적지 벡터 크기의 50% 이상이어야 함 (실제 전진)
            double minDotProduct = targetMagnitudeSquared * 0.5;

            boolean isProgressing = dotProduct >= minDotProduct;

            if (!isProgressing) {
                logger.debug("벡터 내적 검증 실패: {} < {}", dotProduct, minDotProduct);
            }

            return isProgressing;

        } catch (Exception e) {
            logger.error("벡터 전진 검증 오류: " + e.getMessage(), e);
            return true; // 오류 시 허용
        }
    }


    /**
     * 그림자 영역들의 중심점 계산
     */
    private List<RoutePoint> calculateShadowCenters(List<ShadowArea> shadowAreas) {
        List<RoutePoint> centers = new ArrayList<>();

        for (ShadowArea area : shadowAreas) {
            try {
                String shadowGeometry = area.getShadowGeometry();
                if (shadowGeometry == null || shadowGeometry.trim().isEmpty()) {
                    logger.debug("그림자 영역 geometry 데이터 없음");
                    continue;
                }

                // GeoJSON 파싱하여 실제 중심점 계산
                RoutePoint center = parseGeoJsonCenter(shadowGeometry);
                if (center != null) {
                    centers.add(center);
                    logger.debug("그림자 중심점 계산: lat={}, lng={}", center.getLat(), center.getLng());
                }

            } catch (Exception e) {
                logger.warn("그림자 중심점 계산 실패: " + e.getMessage());
            }
        }

        logger.info("그림자 영역 {}개 → 유효 중심점 {}개", shadowAreas.size(), centers.size());
        return centers;
    }

    /**
     * GeoJSON에서 중심점 추출
     */
    private RoutePoint parseGeoJsonCenter(String geoJsonString) {
        try {
            // JSON 파싱
            ObjectMapper mapper = new ObjectMapper();
            JsonNode geoJson = mapper.readTree(geoJsonString);

            // geometry 타입 확인
            JsonNode geometryNode = geoJson.has("geometry") ? geoJson.get("geometry") : geoJson;
            String geometryType = geometryNode.get("type").asText();
            JsonNode coordinatesNode = geometryNode.get("coordinates");

            if (coordinatesNode == null) {
                logger.debug("coordinates 노드가 없음");
                return null;
            }

            switch (geometryType) {
                case "Polygon":
                    return calculatePolygonCenter(coordinatesNode);

                case "MultiPolygon":
                    return calculateMultiPolygonCenter(coordinatesNode);

                case "Point":
                    return parsePointCoordinates(coordinatesNode);

                case "LineString":
                    return calculateLineStringCenter(coordinatesNode);

                default:
                    logger.debug("지원하지 않는 geometry 타입: {}", geometryType);
                    return null;
            }

        } catch (Exception e) {
            logger.error("GeoJSON 중심점 파싱 오류: " + e.getMessage());
            return null;
        }
    }


    /**
     * Polygon의 중심점 계산 (Centroid)
     */
    private RoutePoint calculatePolygonCenter(JsonNode coordinatesNode) {
        try {
            // Polygon의 첫 번째 링(외곽선) 사용
            JsonNode outerRing = coordinatesNode.get(0);
            if (outerRing == null || !outerRing.isArray()) {
                return null;
            }

            double sumLat = 0.0;
            double sumLng = 0.0;
            int pointCount = 0;

            // 모든 좌표의 평균 계산 (간단한 centroid)
            for (JsonNode point : outerRing) {
                if (point.isArray() && point.size() >= 2) {
                    double lng = point.get(0).asDouble();
                    double lat = point.get(1).asDouble();

                    // 유효한 좌표인지 확인
                    if (isValidCoordinate(lat, lng)) {
                        sumLat += lat;
                        sumLng += lng;
                        pointCount++;
                    }
                }
            }

            if (pointCount > 0) {
                double centerLat = sumLat / pointCount;
                double centerLng = sumLng / pointCount;
                return new RoutePoint(centerLat, centerLng);
            }

        } catch (Exception e) {
            logger.error("Polygon 중심점 계산 오류: " + e.getMessage());
        }

        return null;
    }

    /**
     * MultiPolygon의 중심점 계산
     */
    private RoutePoint calculateMultiPolygonCenter(JsonNode coordinatesNode) {
        try {
            List<RoutePoint> polygonCenters = new ArrayList<>();

            // 각 Polygon의 중심점 계산
            for (JsonNode polygon : coordinatesNode) {
                RoutePoint center = calculatePolygonCenter(polygon);
                if (center != null) {
                    polygonCenters.add(center);
                }
            }

            // 모든 Polygon 중심점들의 평균 계산
            if (!polygonCenters.isEmpty()) {
                double sumLat = polygonCenters.stream().mapToDouble(RoutePoint::getLat).sum();
                double sumLng = polygonCenters.stream().mapToDouble(RoutePoint::getLng).sum();

                double centerLat = sumLat / polygonCenters.size();
                double centerLng = sumLng / polygonCenters.size();

                return new RoutePoint(centerLat, centerLng);
            }

        } catch (Exception e) {
            logger.error("MultiPolygon 중심점 계산 오류: " + e.getMessage());
        }

        return null;
    }

    /**
     * Point 좌표 파싱
     */
    private RoutePoint parsePointCoordinates(JsonNode coordinatesNode) {
        try {
            if (coordinatesNode.isArray() && coordinatesNode.size() >= 2) {
                double lng = coordinatesNode.get(0).asDouble();
                double lat = coordinatesNode.get(1).asDouble();

                if (isValidCoordinate(lat, lng)) {
                    return new RoutePoint(lat, lng);
                }
            }
        } catch (Exception e) {
            logger.error("Point 좌표 파싱 오류: " + e.getMessage());
        }

        return null;
    }

    /**
     * LineString의 중심점 계산
     */
    private RoutePoint calculateLineStringCenter(JsonNode coordinatesNode) {
        try {
            double sumLat = 0.0;
            double sumLng = 0.0;
            int pointCount = 0;

            for (JsonNode point : coordinatesNode) {
                if (point.isArray() && point.size() >= 2) {
                    double lng = point.get(0).asDouble();
                    double lat = point.get(1).asDouble();

                    if (isValidCoordinate(lat, lng)) {
                        sumLat += lat;
                        sumLng += lng;
                        pointCount++;
                    }
                }
            }

            if (pointCount > 0) {
                double centerLat = sumLat / pointCount;
                double centerLng = sumLng / pointCount;
                return new RoutePoint(centerLat, centerLng);
            }

        } catch (Exception e) {
            logger.error("LineString 중심점 계산 오류: " + e.getMessage());
        }

        return null;
    }

    /**
     * 좌표 유효성 검사 (한국 범위)
     */
    private boolean isValidCoordinate(double lat, double lng) {
        // 한국 좌표 범위: 위도 33-39, 경도 124-132
        return lat >= 33.0 && lat <= 39.0 && lng >= 124.0 && lng <= 132.0;
    }

    /**
     * 가장 가까운 그림자 중심점 찾기
     */
    private RoutePoint findClosestShadowCenter(RoutePoint reference, List<RoutePoint> shadowCenters) {
        RoutePoint closest = null;
        double minDistance = Double.MAX_VALUE;

        for (RoutePoint center : shadowCenters) {
            double distance = calculateDistance(
                    reference.getLat(), reference.getLng(),
                    center.getLat(), center.getLng()
            );

            if (distance < minDistance) {
                minDistance = distance;
                closest = center;
            }
        }

        return closest;
    }

    /**
     * 기본 경로의 약간의 변형 생성 (균형 경로 폴백용)
     */
    private Route createSlightVariation(Route baseRoute, double startLat, double startLng, double endLat, double endLng) {
        try {
            // 고정 시드로 일관성 있는 작은 변형
            long variationSeed = generateConsistentSeed(startLat, startLng, endLat, endLng) + 3000;
            Random random = new Random(variationSeed);

            List<RoutePoint> basePoints = baseRoute.getPoints();
            int midIndex = basePoints.size() / 2;
            RoutePoint midPoint = basePoints.get(midIndex);

            // 아주 작은 오프셋 (30m 내외)
            double smallOffset = 0.0003; // 약 30m
            double direction = random.nextDouble() * 2 * Math.PI;

            double waypointLat = midPoint.getLat() + Math.cos(direction) * smallOffset;
            double waypointLng = midPoint.getLng() + Math.sin(direction) * smallOffset;

            String routeJson = tmapApiService.getWalkingRouteWithWaypoint(
                    startLat, startLng, waypointLat, waypointLng, endLat, endLng);

            Route variant = shadowRouteService.parseBasicRoute(routeJson);
            variant.setRouteType("balanced");
            variant.setWaypointCount(1);

            return variant;

        } catch (Exception e) {
            logger.error("작은 변형 경로 생성 실패: " + e.getMessage(), e);
            return baseRoute; // 실패 시 원본 반환
        }
    }


    /**
     * 일관된 시드 생성 (동일한 출발지/도착지에 대해 항상 같은 결과)
     */
    private long generateConsistentSeed(double startLat, double startLng, double endLat, double endLng) {
        // 좌표를 정규화하여 소수점 3자리까지만 사용 (약 100m 정밀도)
        int normalizedStartLat = (int) (startLat * 1000);
        int normalizedStartLng = (int) (startLng * 1000);
        int normalizedEndLat = (int) (endLat * 1000);
        int normalizedEndLng = (int) (endLng * 1000);

        return ((long) normalizedStartLat << 48) |
                ((long) normalizedStartLng << 32) |
                ((long) normalizedEndLat << 16) |
                (long) normalizedEndLng;
    }

    /**
     * 두 지점 간 거리 계산
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
     * 밤이거나 날씨가 나쁠 때: 최단경로만 3개 반환
     */
    private List<RouteCandidate> generateShortestRouteOnly(
            double startLat, double startLng, double endLat, double endLng, LocalDateTime dateTime) {

        try {
            logger.info("밤 시간 또는 나쁜 날씨로 인해 최단경로만 생성");

            String routeJson = tmapApiService.getWalkingRoute(startLat, startLng, endLat, endLng);
            Route shortestRoute = shadowRouteService.parseBasicRoute(routeJson);

            List<RouteCandidate> candidates = new ArrayList<>();

            if (shortestRoute != null && !shortestRoute.getPoints().isEmpty()) {
                // 최단경로 성공
                RouteCandidate shortest = new RouteCandidate("shortest", "최단경로", shortestRoute);
                candidates.add(shortest);
            } else {
                // 최단경로도 실패
                candidates.add(createUnavailableCandidate("shortest", "최단경로", "경로 생성 실패"));
            }

            // 나머지는 안전상 생성 불가
            String safetyReason = "안전상 생성 불가 (밤시간/나쁜날씨)";
            candidates.add(createUnavailableCandidate("shade", "그늘이 많은경로", safetyReason));
            candidates.add(createUnavailableCandidate("balanced", "균형경로", safetyReason));

            return candidates;

        } catch (Exception e) {
            logger.error("안전 모드 경로 생성 오류: " + e.getMessage(), e);

            // 모든 경로 생성 불가
            List<RouteCandidate> emergencyCandidates = new ArrayList<>();
            emergencyCandidates.add(createUnavailableCandidate("shortest", "최단경로", "시스템 오류"));
            emergencyCandidates.add(createUnavailableCandidate("shade", "그늘이 많은경로", "시스템 오류"));
            emergencyCandidates.add(createUnavailableCandidate("balanced", "균형경로", "시스템 오류"));

            return emergencyCandidates;
        }
    }
}
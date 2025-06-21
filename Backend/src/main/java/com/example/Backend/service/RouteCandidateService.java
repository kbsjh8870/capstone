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
            logger.info("=== 그림자 경로 생성 시작 ===");

            // 태양 위치 및 그림자 영역 계산
            SunPosition sunPos = shadowService.calculateSunPosition(startLat, startLng, dateTime);
            List<ShadowArea> shadowAreas = shadowRouteService.calculateBuildingShadows(
                    startLat, startLng, endLat, endLng, sunPos);

            if (shadowAreas.isEmpty()) {
                logger.debug("그림자 영역 없음");
                return generateShortestRoute(startLat, startLng, endLat, endLng, dateTime);
            }

            // 기본 경로
            Route baseRoute = generateShortestRoute(startLat, startLng, endLat, endLng, dateTime);
            if (baseRoute == null) {
                return null;
            }

            // 여러 경유지 후보 생성
            List<RoutePoint> waypointCandidates = createMultipleWaypointCandidates(
                    startLat, startLng, endLat, endLng, shadowAreas, sunPos, "shade");

            if (waypointCandidates.isEmpty()) {
                logger.warn("그림자 경유지 후보 생성 실패");
                return baseRoute;
            }

            // 여러 후보 중 최적 경로 선택
            Route bestRoute = evaluateAndSelectBestRoute(waypointCandidates,
                    startLat, startLng, endLat, endLng, shadowAreas, baseRoute, "shade");

            if (bestRoute != null && isSignificantlyBetter(bestRoute, baseRoute, "shade")) {
                bestRoute.setRouteType("shade");
                bestRoute.setWaypointCount(1);
                logger.info("최적 그림자 경로 선택: 그늘 {}%, 거리 {}m, 우회 {}%",
                        bestRoute.getShadowPercentage(), (int)bestRoute.getDistance(),
                        (int)((bestRoute.getDistance() / baseRoute.getDistance() - 1) * 100));
                return bestRoute;
            } else {
                logger.info("적합한 그림자 경로 없음");
                return baseRoute;
            }

        } catch (Exception e) {
            logger.error("그림자 경로 생성 실패: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     *  다중 경유지 기반 균형 경로 생성
     */
    private Route generateOptimizedBalancedRoute(double startLat, double startLng, double endLat, double endLng, LocalDateTime dateTime) {
        try {
            logger.info("=== 균형 경로 생성 시작 ===");

            SunPosition sunPos = shadowService.calculateSunPosition(startLat, startLng, dateTime);
            List<ShadowArea> shadowAreas = shadowRouteService.calculateBuildingShadows(
                    startLat, startLng, endLat, endLng, sunPos);

            // 기본 경로 (비교 기준)
            Route baseRoute = generateShortestRoute(startLat, startLng, endLat, endLng, dateTime);
            if (baseRoute == null) {
                return null;
            }

            // 여러 경유지 후보 생성
            List<RoutePoint> waypointCandidates = createMultipleWaypointCandidates(
                    startLat, startLng, endLat, endLng, shadowAreas, sunPos, "balanced");

            if (waypointCandidates.isEmpty()) {
                logger.warn("균형 경유지 후보 생성 실패 - 기본 경로 변형 사용");
                return createSlightVariation(baseRoute, startLat, startLng, endLat, endLng);
            }

            // 여러 후보 중 최적 경로 선택
            Route bestRoute = evaluateAndSelectBestRoute(waypointCandidates,
                    startLat, startLng, endLat, endLng, shadowAreas, baseRoute, "balanced");

            if (bestRoute != null && isSignificantlyBetter(bestRoute, baseRoute, "balanced")) {
                bestRoute.setRouteType("balanced");
                bestRoute.setWaypointCount(1);
                logger.info("최적 균형 경로 선택: 그늘 {}%, 거리 {}m, 우회 {}%",
                        bestRoute.getShadowPercentage(), (int)bestRoute.getDistance(),
                        (int)((bestRoute.getDistance() / baseRoute.getDistance() - 1) * 100));
                return bestRoute;
            } else {
                logger.info("적합한 균형 경로 없음");
                return createSlightVariation(baseRoute, startLat, startLng, endLat, endLng);
            }

        } catch (Exception e) {
            logger.error("균형 경로 생성 실패: " + e.getMessage(), e);
            Route baseRoute = generateShortestRoute(startLat, startLng, endLat, endLng, dateTime);
            return createSlightVariation(baseRoute, startLat, startLng, endLat, endLng);
        }
    }

    /**
     * 여러 경유지 후보 생성
     */
    private List<RoutePoint> createMultipleWaypointCandidates(double startLat, double startLng,
                                                              double endLat, double endLng,
                                                              List<ShadowArea> shadowAreas,
                                                              SunPosition sunPos, String routeType) {
        List<RoutePoint> candidates = new ArrayList<>();

        try {
            List<RoutePoint> basePoints = Arrays.asList(
                    new RoutePoint(startLat, startLng),
                    new RoutePoint(endLat, endLng)
            );

            // 여러 거리와 방향으로 경유지 후보 생성
            double[] distances = {35.0, 50.0, 65.0};  // 3가지 거리
            double[] angleOffsets = {-30.0, 0.0, 30.0}; // 3가지 방향 (기본 방향 ± 30도)

            for (double distance : distances) {
                for (double angleOffset : angleOffsets) {
                    try {
                        RoutePoint waypoint = createStrategicWaypointWithParams(
                                basePoints, sunPos, "shade".equals(routeType),
                                shadowAreas, distance, angleOffset);

                        if (waypoint != null) {
                            candidates.add(waypoint);
                        }
                    } catch (Exception e) {
                        logger.debug("경유지 후보 생성 실패 (거리: {}m, 각도: {}도): {}",
                                distance, angleOffset, e.getMessage());
                    }
                }
            }

            // 중복 제거 및 유효성 검사
            RoutePoint startPoint = new RoutePoint(startLat, startLng);
            RoutePoint endPoint = new RoutePoint(endLat, endLng);

            List<RoutePoint> validCandidates = candidates.stream()
                    .filter(wp -> isWaypointProgressive(startPoint, wp, endPoint))
                    .distinct()
                    .limit(15) // 최대 15개 후보
                    .collect(Collectors.toList());

            logger.info("경유지 후보 생성 완료: {}개 → {}개 유효", candidates.size(), validCandidates.size());
            return validCandidates;

        } catch (Exception e) {
            logger.error("경유지 후보 생성 오류: " + e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * 파라미터가 있는 전략적 경유지 생성
     */
    private RoutePoint createStrategicWaypointWithParams(List<RoutePoint> basePoints,
                                                         SunPosition sunPos, boolean avoidShadow,
                                                         List<ShadowArea> shadowAreas,
                                                         double distance, double angleOffset) {
        if (basePoints.size() < 2) return null;

        try {
            RoutePoint startPoint = basePoints.get(0);
            RoutePoint endPoint = basePoints.get(basePoints.size() - 1);
            RoutePoint middlePoint = new RoutePoint(
                    (startPoint.getLat() + endPoint.getLat()) / 2,
                    (startPoint.getLng() + endPoint.getLng()) / 2
            );

            // 목적지 방향 계산
            double destinationDirection = calculateBearing(startPoint, endPoint);

            // 원하는 경유지 방향 계산
            double preferredDirection;
            if (avoidShadow) {
                preferredDirection = sunPos.getAzimuth(); // 태양 방향
            } else {
                preferredDirection = (sunPos.getAzimuth() + 180) % 360; // 그림자 방향
            }

            // 각도 오프셋 적용
            preferredDirection = (preferredDirection + angleOffset + 360) % 360;

            // 목적지 방향 제약
            double constrainedDirection = constrainDirectionToDestination(
                    preferredDirection, destinationDirection);

            // 경유지 생성
            RoutePoint waypoint = createWaypointAtDirection(
                    middlePoint.getLat(), middlePoint.getLng(), constrainedDirection, distance);

            return waypoint;

        } catch (Exception e) {
            logger.error("파라미터 경유지 생성 오류: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 여러 후보 중 최적 경로 선택
     */
    private Route evaluateAndSelectBestRoute(List<RoutePoint> waypointCandidates,
                                             double startLat, double startLng, double endLat, double endLng,
                                             List<ShadowArea> shadowAreas, Route baseRoute, String routeType) {
        try {
            logger.info("=== 경로 후보 평가 시작: {}개 경유지 ===", waypointCandidates.size());

            List<Route> candidateRoutes = evaluateWaypointsBatch(
                    waypointCandidates, startLat, startLng, endLat, endLng, shadowAreas, 8);

            if (candidateRoutes.isEmpty()) {
                logger.info("생성된 후보 경로 없음");
                return null;
            }

            // 품질 필터링 - 극단적 우회 제거
            List<Route> qualityRoutes = candidateRoutes.stream()
                    .filter(route -> isRouteQualityGood(route, baseRoute))
                    .collect(Collectors.toList());

            logger.info("품질 필터링: {}개 → {}개", candidateRoutes.size(), qualityRoutes.size());

            if (qualityRoutes.isEmpty()) {
                logger.info("품질 기준을 통과한 경로 없음");
                return null;
            }

            // 타입별 최적 경로 선택
            Route bestRoute;
            if ("shade".equals(routeType)) {
                bestRoute = selectBestShadeRouteAdvanced(qualityRoutes, shadowAreas);
            } else {
                bestRoute = selectBestBalancedRouteAdvanced(qualityRoutes, baseRoute);
            }

            if (bestRoute != null) {
                logger.info("최적 경로 선택 완료: 타입={}, 그늘={}%, 우회={}%",
                        routeType, bestRoute.getShadowPercentage(),
                        (int)((bestRoute.getDistance() / baseRoute.getDistance() - 1) * 100));
            }

            return bestRoute;

        } catch (Exception e) {
            logger.error("경로 후보 평가 오류: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 경로 품질 검사 (극단적 우회 방지)
     */
    private boolean isRouteQualityGood(Route route, Route baseRoute) {
        try {
            if (route == null || route.getPoints().isEmpty() || baseRoute == null) {
                return false;
            }

            // 우회 비율 제한
            double detourRatio = route.getDistance() / baseRoute.getDistance();
            if (detourRatio > 1.3) { // 130% 이하만 허용
                logger.debug("우회 비율 초과: {}% > 130%", (int)(detourRatio * 100));
                return false;
            }

            // 최소 포인트 수
            if (route.getPoints().size() < 3) {
                logger.debug("포인트 수 부족: {}개", route.getPoints().size());
                return false;
            }

            // 연속성 검사 (기존 메서드 재활용)
            if (!isRouteContinuous(route)) {
                logger.debug("경로 연속성 실패");
                return false;
            }

            // 진행성 검사 (기존 메서드 재활용)
            if (!isProgressiveRoute(route)) {
                logger.debug("경로 진행성 실패");
                return false;
            }

            logger.debug("경로 품질 검증 통과: 우회={}%", (int)((detourRatio - 1) * 100));
            return true;

        } catch (Exception e) {
            logger.error("경로 품질 검사 오류: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 경로 연속성 검증 (포인트 간 거리가 비정상적으로 크지 않은지 확인)
     */
    private boolean isRouteContinuous(Route route) {
        try {
            List<RoutePoint> points = route.getPoints();
            if (points.size() < 2) return true;

            int discontinuousCount = 0;
            final double MAX_SEGMENT_DISTANCE = 500.0; // 500m 이상 점프는 비정상

            for (int i = 0; i < points.size() - 1; i++) {
                RoutePoint current = points.get(i);
                RoutePoint next = points.get(i + 1);

                double segmentDistance = calculateDistance(
                        current.getLat(), current.getLng(),
                        next.getLat(), next.getLng()
                );

                if (segmentDistance > MAX_SEGMENT_DISTANCE) {
                    discontinuousCount++;
                    logger.debug("긴 구간 발견: {}m (포인트 {} → {})",
                            (int)segmentDistance, i, i + 1);
                }
            }

            // 전체 구간의 5% 이상이 비정상적으로 길면 실패
            double discontinuousRatio = (double) discontinuousCount / (points.size() - 1);
            boolean isContinuous = discontinuousRatio <= 0.05;

            logger.debug("경로 연속성: {}% 비정상 구간 (허용: 5%)", (int)(discontinuousRatio * 100));
            return isContinuous;

        } catch (Exception e) {
            logger.error("경로 연속성 검증 오류: " + e.getMessage(), e);
            return true; // 오류 시 허용
        }
    }

    /**
     * 전진성 검증 강화 (역방향 이동이나 과도한 우회 방지)
     */
    private boolean isProgressiveRoute(Route route) {
        try {
            List<RoutePoint> points = route.getPoints();
            if (points.size() < 5) return true;

            RoutePoint start = points.get(0);
            RoutePoint end = points.get(points.size() - 1);

            // 전체적인 목적지 방향 계산
            double overallDirection = Math.atan2(
                    end.getLng() - start.getLng(),
                    end.getLat() - start.getLat()
            );

            // 경로 구간들의 방향성 분석
            int forwardSegments = 0;
            int backwardSegments = 0;
            int totalSegments = 0;

            // 10개 포인트마다 샘플링하여 분석
            int stepSize = Math.max(1, points.size() / 20); // 최대 20개 샘플
            for (int i = 0; i < points.size() - stepSize; i += stepSize) {
                RoutePoint current = points.get(i);
                RoutePoint next = points.get(Math.min(i + stepSize, points.size() - 1));

                double segmentDirection = Math.atan2(
                        next.getLng() - current.getLng(),
                        next.getLat() - current.getLat()
                );

                // 전체 방향과의 각도 차이 계산
                double angleDiff = Math.abs(segmentDirection - overallDirection);
                if (angleDiff > Math.PI) {
                    angleDiff = 2 * Math.PI - angleDiff;
                }

                if (angleDiff <= Math.PI / 2) { // 90도 이내
                    forwardSegments++;
                } else {
                    backwardSegments++;
                    logger.debug("--- 역방향 구간 감지: 포인트 {} → {}, 각도차이: {}도",
                            i, i + stepSize, Math.toDegrees(angleDiff));
                }
                totalSegments++;
            }

            // 75% 이상이 전진 방향이어야 함
            double forwardRatio = totalSegments > 0 ? (double) forwardSegments / totalSegments : 1.0;
            boolean isProgressive = forwardRatio >= 0.75;

            logger.debug("경로 전진성: {}% 전진 구간 (기준: 75%)", (int)(forwardRatio * 100));

            return isProgressive;

        } catch (Exception e) {
            logger.error("전진성 검증 오류: " + e.getMessage(), e);
            return true;
        }
    }

    /**
     * 기본 경로 대비 의미있는 개선인지 확인
     */
    private boolean isSignificantlyBetter(Route candidate, Route baseRoute, String routeType) {
        try {
            if (candidate == null || baseRoute == null) {
                return false;
            }

            double detourRatio = candidate.getDistance() / baseRoute.getDistance();

            if ("shade".equals(routeType)) {
                // 그림자 경로: 그늘이 최소 10% 이상 증가해야 함
                int shadowDiff = candidate.getShadowPercentage() - baseRoute.getShadowPercentage();
                boolean hasSignificantShadow = shadowDiff >= 10;

                logger.debug("그림자 경로 평가: 그늘 차이={}%, 우회={}%, 의미있음={}",
                        shadowDiff, (int)((detourRatio - 1) * 100), hasSignificantShadow);

                return hasSignificantShadow;

            } else { // balanced
                // 균형 경로: 적당한 그늘 증가 + 합리적 우회
                int shadowDiff = candidate.getShadowPercentage() - baseRoute.getShadowPercentage();
                boolean hasModerateImprovement = shadowDiff >= 5 && detourRatio <= 1.2;

                logger.debug("균형 경로 평가: 그늘 차이={}%, 우회={}%, 균형적={}",
                        shadowDiff, (int)((detourRatio - 1) * 100), hasModerateImprovement);

                return hasModerateImprovement;
            }

        } catch (Exception e) {
            logger.error("의미있는 개선 검사 오류: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     *  배치 처리로 경유지 평가
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
     *  그림자가 가장 많은 경로 선택
     */
    private Route selectBestShadeRouteAdvanced(List<Route> candidateRoutes, List<ShadowArea> shadowAreas) {
        try {
            if (candidateRoutes.isEmpty()) {
                logger.info("선택할 그림자 경로 후보가 없음");
                return null;
            }

            // 1차: 그림자 비율 순으로 정렬
            List<Route> sortedByShadow = candidateRoutes.stream()
                    .sorted((r1, r2) -> Integer.compare(r2.getShadowPercentage(), r1.getShadowPercentage()))
                    .collect(Collectors.toList());

            // 상위 3개 후보 로깅
            logger.info("=== 그림자 경로 상위 후보들 ===");
            for (int i = 0; i < Math.min(3, sortedByShadow.size()); i++) {
                Route route = sortedByShadow.get(i);
                logger.info("{}위: 그늘 {}%, 거리 {}m",
                        i + 1, route.getShadowPercentage(), (int)route.getDistance());
            }

            // 2차: 그림자 비율이 높으면서 우회가 적당한 경로 선택
            Route bestRoute = sortedByShadow.stream()
                    .filter(route -> route.getShadowPercentage() >= 20) // 최소 20% 그늘
                    .findFirst()
                    .orElse(sortedByShadow.get(0)); // 없으면 그냥 가장 그늘 많은 것

            logger.info("선택된 그림자 경로: 그늘 {}%, 거리 {}m",
                    bestRoute.getShadowPercentage(), (int)bestRoute.getDistance());

            return bestRoute;

        } catch (Exception e) {
            logger.error("최적 그림자 경로 선택 오류: " + e.getMessage(), e);
            return candidateRoutes.isEmpty() ? null : candidateRoutes.get(0);
        }
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
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
            logger.info("=== 그림자 경로 생성 ===");

            // 태양 위치 및 그림자 영역 계산
            SunPosition sunPos = shadowService.calculateSunPosition(startLat, startLng, dateTime);
            logger.info("태양 위치: 고도={}도, 방위각={}도", sunPos.getAltitude(), sunPos.getAzimuth());

            List<ShadowArea> shadowAreas = shadowRouteService.calculateBuildingShadows(
                    startLat, startLng, endLat, endLng, sunPos);
            logger.info("그림자 영역: {}개 발견", shadowAreas.size());

            if (shadowAreas.isEmpty()) {
                logger.warn("그림자 영역이 없음 - 시간: {}, 태양고도: {}도", dateTime, sunPos.getAltitude());
                return generateShortestRoute(startLat, startLng, endLat, endLng, dateTime);
            }

            // 기본 경로
            Route baseRoute = generateShortestRoute(startLat, startLng, endLat, endLng, dateTime);
            if (baseRoute == null) {
                return null;
            }

            shadowRouteService.applyShadowInfoFromDB(baseRoute, shadowAreas);
            logger.info("기본 경로 그림자: {}%", baseRoute.getShadowPercentage());

            // 간단한 경유지 변형들 생성
            List<RoutePoint> waypointVariants = createSimpleWaypointVariants(
                    startLat, startLng, endLat, endLng, shadowAreas, sunPos, true);

            if (waypointVariants.isEmpty()) {
                logger.warn("그림자 경유지 생성 실패");
                return baseRoute;
            }

            // 극단적 우회가 아닌 경로 선택
            Route bestRoute = selectNonExtremeRoute(waypointVariants,
                    startLat, startLng, endLat, endLng, shadowAreas, baseRoute);

            if (bestRoute != null && isSignificantShadowImprovement(bestRoute, baseRoute)) {
                bestRoute.setRouteType("shade");
                bestRoute.setWaypointCount(1);
                logger.info("그림자 경로 선택: 그늘 {}%, 우회 {}%",
                        bestRoute.getShadowPercentage(),
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
            logger.info("=== 균형 경로 생성 ===");

            SunPosition sunPos = shadowService.calculateSunPosition(startLat, startLng, dateTime);
            logger.info("태양 위치: 고도={}도, 방위각={}도", sunPos.getAltitude(), sunPos.getAzimuth());

            List<ShadowArea> shadowAreas = shadowRouteService.calculateBuildingShadows(
                    startLat, startLng, endLat, endLng, sunPos);
            logger.info("그림자 영역: {}개 발견", shadowAreas.size());

            // 기본 경로 (비교 기준)
            Route baseRoute = generateShortestRoute(startLat, startLng, endLat, endLng, dateTime);
            if (baseRoute == null) {
                return null;
            }

            if (!shadowAreas.isEmpty()) {
                shadowRouteService.applyShadowInfoFromDB(baseRoute, shadowAreas);
                logger.info("기본 경로 그림자: {}%", baseRoute.getShadowPercentage());
            }


            // 간단한 경유지 변형들 생성
            List<RoutePoint> waypointVariants = createSimpleWaypointVariants(
                    startLat, startLng, endLat, endLng, shadowAreas, sunPos, false);

            if (waypointVariants.isEmpty()) {
                logger.warn("균형 경유지 생성 실패 - 기본 경로 변형 사용");
                return createSlightVariation(baseRoute, startLat, startLng, endLat, endLng);
            }

            // 극단적 우회가 아닌 경로 선택
            Route bestRoute = selectNonExtremeRoute(waypointVariants,
                    startLat, startLng, endLat, endLng, shadowAreas, baseRoute);

            if (bestRoute != null && isModerateImprovement(bestRoute, baseRoute)) {
                bestRoute.setRouteType("balanced");
                bestRoute.setWaypointCount(1);
                logger.info("균형 경로 선택: 그늘 {}%, 우회 {}%",
                        bestRoute.getShadowPercentage(),
                        (int)((bestRoute.getDistance() / baseRoute.getDistance() - 1) * 100));
                return bestRoute;
            } else {
                logger.info("적합한 균형 경로 없음 (기본 그늘: {}%)",
                        baseRoute.getShadowPercentage());
                Route variation = createSlightVariation(baseRoute, startLat, startLng, endLat, endLng);

                if (variation != null && !shadowAreas.isEmpty()) {
                    shadowRouteService.applyShadowInfoFromDB(variation, shadowAreas);
                    logger.info("변형 경로 그림자: {}%", variation.getShadowPercentage());
                }

                return variation;
            }

        } catch (Exception e) {
            logger.error("균형 경로 생성 실패: " + e.getMessage(), e);
            Route baseRoute = generateShortestRoute(startLat, startLng, endLat, endLng, dateTime);
            return createSlightVariation(baseRoute, startLat, startLng, endLat, endLng);
        }
    }

    /**
     * 간단한 경유지 변형들 생성 (2-3개 정도)
     */
    private List<RoutePoint> createSimpleWaypointVariants(double startLat, double startLng,
                                                          double endLat, double endLng,
                                                          List<ShadowArea> shadowAreas,
                                                          SunPosition sunPos, boolean avoidShadow) {
        List<RoutePoint> variants = new ArrayList<>();

        try {
            List<RoutePoint> basePoints = Arrays.asList(
                    new RoutePoint(startLat, startLng),
                    new RoutePoint(endLat, endLng)
            );

            // 기본 경유지
            RoutePoint mainWaypoint = createStrategicWaypoint(basePoints, sunPos, avoidShadow, shadowAreas);
            if (mainWaypoint != null) {
                variants.add(mainWaypoint);
            }

            // 거리 조정 (±20m)
            RoutePoint variant1 = createWaypointVariant(basePoints, sunPos, avoidShadow, shadowAreas, 0.0, 20.0);
            if (variant1 != null) {
                variants.add(variant1);
            }

            // 각도 조정 (±20도)
            RoutePoint variant2 = createWaypointVariant(basePoints, sunPos, avoidShadow, shadowAreas, 20.0, 0.0);
            if (variant2 != null) {
                variants.add(variant2);
            }

            logger.debug("간단한 경유지 변형 생성: {}개", variants.size());
            return variants;

        } catch (Exception e) {
            logger.error("간단한 경유지 변형 생성 오류: " + e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * 경유지 변형 생성 (각도/거리 조정)
     */
    private RoutePoint createWaypointVariant(List<RoutePoint> basePoints, SunPosition sunPos,
                                             boolean avoidShadow, List<ShadowArea> shadowAreas,
                                             double angleOffset, double distanceOffset) {
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

            // 각도 조정 적용
            preferredDirection = (preferredDirection + angleOffset + 360) % 360;

            // 목적지 방향 제약 적용
            double constrainedDirection = constrainDirectionToDestination(
                    preferredDirection, destinationDirection);

            // 거리 조정 적용
            double adjustedDistance = 40.0 + distanceOffset;

            // 경유지 생성
            return createWaypointAtDirection(
                    middlePoint.getLat(), middlePoint.getLng(), constrainedDirection, adjustedDistance);

        } catch (Exception e) {
            logger.error("경유지 변형 생성 오류: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 극단적 우회가 아닌 경로 선택
     */
    private Route selectNonExtremeRoute(List<RoutePoint> waypointVariants,
                                        double startLat, double startLng, double endLat, double endLng,
                                        List<ShadowArea> shadowAreas, Route baseRoute) {
        try {
            List<Route> candidateRoutes = new ArrayList<>();

            // 각 경유지로 경로 생성
            for (RoutePoint waypoint : waypointVariants) {
                try {
                    String routeJson = tmapApiService.getWalkingRouteWithWaypoint(
                            startLat, startLng, waypoint.getLat(), waypoint.getLng(), endLat, endLng);
                    Route route = shadowRouteService.parseBasicRoute(routeJson);

                    if (route != null && !route.getPoints().isEmpty()) {
                        shadowRouteService.applyShadowInfoFromDB(route, shadowAreas);

                        // 🔍 디버깅: 그림자 정보 확인
                        logger.info("경유지 경로 생성: 포인트={}개, 그림자={}%",
                                route.getPoints().size(), route.getShadowPercentage());

                        // 그림자가 있는 포인트 개수 확인
                        long shadowPointCount = route.getPoints().stream()
                                .mapToLong(p -> p.isInShadow() ? 1 : 0)
                                .sum();
                        logger.info("실제 그림자 포인트: {}개/{}", shadowPointCount, route.getPoints().size());

                        candidateRoutes.add(route);
                    }
                } catch (Exception e) {
                    logger.debug("경유지 경로 생성 실패: " + e.getMessage());
                }
            }

            if (candidateRoutes.isEmpty()) {
                logger.warn("생성된 후보 경로 없음");
                return null;
            }

            // 극단적 우회 필터링 (180% 이상만 제외)
            List<Route> reasonableRoutes = candidateRoutes.stream()
                    .filter(route -> !isExtremeDetour(route, baseRoute))
                    .collect(Collectors.toList());

            logger.info("극단 우회 필터링: {}개 → {}개", candidateRoutes.size(), reasonableRoutes.size());

            if (reasonableRoutes.isEmpty()) {
                logger.info("모든 경로가 극단적 우회로 판정됨");
                return null;
            }

            logger.info("=== 최종 후보 경로들의 그림자 정보 ===");
            for (int i = 0; i < reasonableRoutes.size(); i++) {
                Route route = reasonableRoutes.get(i);
                long shadowPoints = route.getPoints().stream()
                        .mapToLong(p -> p.isInShadow() ? 1 : 0)
                        .sum();
                logger.info("후보 {}: 그림자={}%, 포인트={}/{}개",
                        i + 1, route.getShadowPercentage(), shadowPoints, route.getPoints().size());
            }

            // 가장 좋은 경로 선택 (그림자 비율 우선)
            Route bestRoute = reasonableRoutes.stream()
                    .max((r1, r2) -> Integer.compare(r1.getShadowPercentage(), r2.getShadowPercentage()))
                    .orElse(null);

            if (bestRoute != null) {
                long shadowPoints = bestRoute.getPoints().stream()
                        .mapToLong(p -> p.isInShadow() ? 1 : 0)
                        .sum();
                logger.info("선택된 경로: 그림자={}%, 실제 그림자 포인트={}개",
                        bestRoute.getShadowPercentage(), shadowPoints);

                if (bestRoute.getShadowPercentage() == 0 && !shadowAreas.isEmpty()) {
                    logger.warn("그림자 정보 없음 ");
                }
            }

            return bestRoute;

        } catch (Exception e) {
            logger.error("극단 우회 방지 경로 선택 오류: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 극단적 우회 판정 (간단한 기준)
     */
    private boolean isExtremeDetour(Route route, Route baseRoute) {
        try {
            if (route == null || baseRoute == null || route.getPoints().isEmpty()) {
                return true;
            }

            // 180% 이상 우회면 극단적 우회로 판정
            double detourRatio = route.getDistance() / baseRoute.getDistance();
            boolean isExtreme = detourRatio > 1.8;

            if (isExtreme) {
                logger.debug("극단적 우회 감지: {}% > 180%", (int)(detourRatio * 100));
            } else {
                logger.debug("합리적 우회: {}%", (int)((detourRatio - 1) * 100));
            }

            return isExtreme;

        } catch (Exception e) {
            logger.error("극단적 우회 판정 오류: " + e.getMessage(), e);
            return true; // 오류 시 제외
        }
    }

    /**
     * 그림자 경로의 의미있는 개선 확인
     */
    private boolean isSignificantShadowImprovement(Route candidate, Route baseRoute) {
        if (candidate == null || baseRoute == null) {
            return false;
        }

        // 그늘이 최소 8% 이상 증가해야 함
        int shadowDiff = candidate.getShadowPercentage() - baseRoute.getShadowPercentage();
        return shadowDiff >= 8;
    }

    /**
     * 균형 경로의 적당한 개선 확인
     */
    private boolean isModerateImprovement(Route candidate, Route baseRoute) {
        if (candidate == null || baseRoute == null) {
            return false;
        }

        // 그늘이 최소 3% 이상 증가하면 OK
        int shadowDiff = candidate.getShadowPercentage() - baseRoute.getShadowPercentage();
        double detourRatio = candidate.getDistance() / baseRoute.getDistance();

        return shadowDiff >= 3 && detourRatio <= 1.6; // 160% 이하 우회
    }

    private RoutePoint createStrategicWaypoint(List<RoutePoint> basePoints, SunPosition sunPos,
                                               boolean avoidShadow, List<ShadowArea> shadowAreas) {
        if (basePoints.size() < 2) return null;

        try {
            RoutePoint startPoint = basePoints.get(0);
            RoutePoint endPoint = basePoints.get(basePoints.size() - 1);

            // 중간점 계산
            RoutePoint middlePoint = new RoutePoint(
                    (startPoint.getLat() + endPoint.getLat()) / 2,
                    (startPoint.getLng() + endPoint.getLng()) / 2
            );

            // 목적지 방향 계산 (북쪽 기준 0-360도)
            double destinationDirection = calculateBearing(startPoint, endPoint);

            // 원하는 경유지 방향 계산 (태양/그림자 방향)
            double preferredDirection;
            if (avoidShadow) {
                preferredDirection = sunPos.getAzimuth(); // 태양 방향
            } else {
                preferredDirection = (sunPos.getAzimuth() + 180) % 360; // 그림자 방향
            }

            //  목적지 방향 제약 적용
            double constrainedDirection = constrainDirectionToDestination(
                    preferredDirection, destinationDirection);

            // 경유지 거리 (짧게 유지)
            double detourMeters = 40.0;

            // 지리적 좌표로 변환
            double directionRad = Math.toRadians(constrainedDirection);
            double latDegreeInMeters = 111000.0;
            double lngDegreeInMeters = 111000.0 * Math.cos(Math.toRadians(middlePoint.getLat()));

            double latOffset = detourMeters * Math.cos(directionRad) / latDegreeInMeters;
            double lngOffset = detourMeters * Math.sin(directionRad) / lngDegreeInMeters;

            RoutePoint waypoint = new RoutePoint();
            waypoint.setLat(middlePoint.getLat() + latOffset);
            waypoint.setLng(middlePoint.getLng() + lngOffset);

            // 경유지가 목적지 방향으로 진행하는지 검증
            if (!isWaypointProgressive(startPoint, waypoint, endPoint)) {
                logger.debug("경유지가 목적지 방향으로 진행하지 않음 - 거부");
                return null;
            }

            logger.debug("제약된 경유지 생성: 원하는방향={}도, 목적지방향={}도, 최종방향={}도",
                    preferredDirection, destinationDirection, constrainedDirection);

            return waypoint;

        } catch (Exception e) {
            logger.error("제약된 경유지 계산 오류: " + e.getMessage(), e);
            return null;
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

            logger.debug("경유지 검증 통과: 우회={}%, 접근={}%, 방향차이={}도, 직선이탈={}m",
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
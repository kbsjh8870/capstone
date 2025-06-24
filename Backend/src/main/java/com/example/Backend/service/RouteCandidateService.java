package com.example.Backend.service;

import com.example.Backend.model.Route;
import com.example.Backend.model.RoutePoint;
import com.example.Backend.model.RouteCandidate;
import com.example.Backend.model.SunPosition;
import com.example.Backend.model.ShadowArea;
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
     * 다중 경유지 최적화 경로 생성
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

            // 병렬 처리
            CompletableFuture<Route> shadeFuture = CompletableFuture.supplyAsync(() ->
                    generateUniqueShadeRoute(startLat, startLng, endLat, endLng, dateTime, shortestRoute));

            CompletableFuture<Route> balancedFuture = CompletableFuture.supplyAsync(() ->
                    generateUniqueBalancedRoute(startLat, startLng, endLat, endLng, dateTime, shortestRoute));

            // 결과 수집 (타임아웃 적용)
            try {
                Route shadeRoute = shadeFuture.get(25, TimeUnit.SECONDS);
                if (shadeRoute != null) {
                    if (!isRouteSimilarToExisting(shadeRoute, candidates)) {
                        if (validateEnhancedRouteQuality(shadeRoute, shortestRoute, "shade")) {
                            RouteCandidate shadeCandidate = new RouteCandidate("shade", "그늘이 많은경로", shadeRoute);
                            String efficiencyInfo = shadeCandidate.calculateEfficiencyDisplay(shortestRoute);
                            shadeCandidate.setDescription(shadeCandidate.getDescription() + " · " + efficiencyInfo);
                            candidates.add(shadeCandidate);
                            logger.info("그림자경로 추가: {}m, {}분, 그늘 {}%",
                                    (int)shadeRoute.getDistance(), shadeRoute.getDuration(), shadeRoute.getShadowPercentage());
                        } else {
                            logger.info("그림자경로: 품질 기준 미달로 제외");
                            candidates.add(createUnavailableCandidate("shade", "그늘이 많은경로", "품질 기준 미달"));
                        }
                    } else {
                        logger.info("그림자경로: 기존 경로와 유사하여 제외");
                        candidates.add(createUnavailableCandidate("shade", "그늘이 많은경로", "기존 경로와 유사"));
                    }
                } else {
                    candidates.add(createUnavailableCandidate("shade", "그늘이 많은경로", "생성 실패"));
                }
            } catch (TimeoutException e) {
                logger.warn("그림자 경로 생성 시간 초과 (25초)");
                shadeFuture.cancel(true);
                candidates.add(createUnavailableCandidate("shade", "그늘이 많은경로", "처리 시간 초과"));
            } catch (Exception e) {
                logger.error("그림자 경로 생성 오류: " + e.getMessage());
                candidates.add(createUnavailableCandidate("shade", "그늘이 많은경로", "생성 오류"));
            }

            try {
                Route balancedRoute = balancedFuture.get(25, TimeUnit.SECONDS);
                if (balancedRoute != null) {
                    if (!isRouteSimilarToExisting(balancedRoute, candidates)) {
                        if (validateEnhancedRouteQuality(balancedRoute, shortestRoute, "balanced")) {
                            RouteCandidate balancedCandidate = new RouteCandidate("balanced", "균형경로", balancedRoute);
                            String efficiencyInfo = balancedCandidate.calculateEfficiencyDisplay(shortestRoute);
                            balancedCandidate.setDescription(balancedCandidate.getDescription() + " · " + efficiencyInfo);
                            candidates.add(balancedCandidate);
                            logger.info("균형경로 추가: {}m, {}분, 그늘 {}%",
                                    (int)balancedRoute.getDistance(), balancedRoute.getDuration(), balancedRoute.getShadowPercentage());
                        } else {
                            logger.info("균형경로: 품질 기준 미달로 제외");
                            candidates.add(createUnavailableCandidate("balanced", "균형경로", "품질 기준 미달"));
                        }
                    } else {
                        logger.info("균형경로: 기존 경로와 유사하여 제외");
                        candidates.add(createUnavailableCandidate("balanced", "균형경로", "기존 경로와 유사"));
                    }
                } else {
                    candidates.add(createUnavailableCandidate("balanced", "균형경로", "생성 실패"));
                }
            } catch (TimeoutException e) {
                logger.warn("균형 경로 생성 시간 초과 (25초)");
                balancedFuture.cancel(true);
                candidates.add(createUnavailableCandidate("balanced", "균형경로", "처리 시간 초과"));
            } catch (Exception e) {
                logger.error("균형 경로 생성 오류: " + e.getMessage());
                candidates.add(createUnavailableCandidate("balanced", "균형경로", "생성 오류"));
            }

            long totalTime = System.currentTimeMillis() - startTime;
            logger.info("총 처리시간: {}ms, 최종 후보: {}개", totalTime, candidates.size());

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
            String routeType="shade";

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
                    startLat, startLng, endLat, endLng, shadowAreas, baseRoute, routeType,1);

            if (bestRoute != null && isSignificantShadowImprovement(bestRoute, baseRoute)) {
                bestRoute.setRouteType(routeType);
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
            String routeType="balanced";

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
                    startLat, startLng, endLat, endLng, shadowAreas, baseRoute,routeType,1);

            if (bestRoute != null && isModerateImprovement(bestRoute, baseRoute)) {
                bestRoute.setRouteType(routeType);
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
                                        List<ShadowArea> shadowAreas, Route baseRoute, String routeType, int attemptNumber) {
        try {
            List<Route> candidateRoutes = new ArrayList<>();

            // 경유지 변형들 생성
            SunPosition sunPos = shadowService.calculateSunPosition(startLat, startLng, LocalDateTime.now());
            boolean avoidShadow = "shade".equals(routeType);

            List<RoutePoint> diverseWaypoints = createDiverseWaypointVariants(
                    startLat, startLng, endLat, endLng, shadowAreas, sunPos, avoidShadow, attemptNumber);

            // 기존 경유지 + 다양성 경유지 합치기
            List<RoutePoint> allWaypoints = new ArrayList<>(waypointVariants);
            allWaypoints.addAll(diverseWaypoints);

            // 각 경유지로 경로 생성
            for (RoutePoint waypoint : allWaypoints) {
                try {
                    String routeJson = tmapApiService.getWalkingRouteWithWaypoint(
                            startLat, startLng, waypoint.getLat(), waypoint.getLng(), endLat, endLng);
                    Route route = shadowRouteService.parseBasicRoute(routeJson);

                    if (route != null && !route.getPoints().isEmpty()) {
                        shadowRouteService.applyShadowInfoFromDB(route, shadowAreas);
                        route.setRouteType(routeType);
                        candidateRoutes.add(route);
                    }
                } catch (Exception e) {
                    logger.debug("경유지 경로 생성 실패: " + e.getMessage());
                }
            }

            if (candidateRoutes.isEmpty()) {
                logger.warn("생성된 후보 경로 없음 (시도 {})", attemptNumber);
                return null;
            }

            // 경로 타입을 고려한 극단적 우회 필터링
            List<Route> reasonableRoutes = candidateRoutes.stream()
                    .filter(route -> !isExtremeDetour(route, baseRoute, routeType))
                    .collect(Collectors.toList());

            logger.info("극단 우회 필터링 [{}] 시도 {}: {}개 → {}개",
                    routeType, attemptNumber, candidateRoutes.size(), reasonableRoutes.size());

            if (reasonableRoutes.isEmpty()) {
                logger.info("모든 경로가 극단적 우회로 판정됨 [{}] 시도 {}", routeType, attemptNumber);
                return null;
            }

            // 다양성을 위해 다른 선택 기준 적용
            Route bestRoute;
            if (attemptNumber == 1) {
                // 첫 번째 시도: 그림자 비율 우선
                bestRoute = reasonableRoutes.stream()
                        .max((r1, r2) -> Integer.compare(r1.getShadowPercentage(), r2.getShadowPercentage()))
                        .orElse(null);
            } else if (attemptNumber == 2) {
                // 두 번째 시도: 거리 효율성 우선
                bestRoute = reasonableRoutes.stream()
                        .min((r1, r2) -> Double.compare(r1.getDistance(), r2.getDistance()))
                        .orElse(null);
            } else {
                // 세 번째 시도: 균형 (거리와 그림자의 조화)
                bestRoute = reasonableRoutes.stream()
                        .max((r1, r2) -> {
                            double score1 = calculateBalanceScore(r1, baseRoute);
                            double score2 = calculateBalanceScore(r2, baseRoute);
                            return Double.compare(score1, score2);
                        })
                        .orElse(null);
            }

            if (bestRoute != null) {
                logger.info("선택된 경로 [{}] 시도 {}: 그림자={}%, 거리={}m",
                        routeType, attemptNumber, bestRoute.getShadowPercentage(), (int)bestRoute.getDistance());
            }

            return bestRoute;

        } catch (Exception e) {
            logger.error("다양성 강화 경로 선택 오류: " + e.getMessage(), e);
            return null;
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

    /**
     *  경로 진행 방향 분석 - 되돌아오는 구간과 비효율적 우회 감지
     */
    private RouteProgressionAnalysis analyzeRouteProgression(Route route, String routeType) {
        try {
            List<RoutePoint> points = route.getPoints();
            if (points.size() < 3) {
                return new RouteProgressionAnalysis(false, "포인트 수 부족");
            }

            RoutePoint startPoint = points.get(0);
            RoutePoint endPoint = points.get(points.size() - 1);

            // 각 포인트에서 목적지까지의 거리 계산
            double[] distancesToDestination = new double[points.size()];
            for (int i = 0; i < points.size(); i++) {
                distancesToDestination[i] = calculateDistance(
                        points.get(i).getLat(), points.get(i).getLng(),
                        endPoint.getLat(), endPoint.getLng()
                );
            }

            // 진행 방향 분석
            int progressingSegments = 0;
            int regressingSegments = 0;
            int maxRegressingStreak = 0;
            int currentRegressingStreak = 0;
            double totalRegressingDistance = 0.0;
            double maxRegressingSegmentDistance = 0.0;

            // 연속 구간 분석
            List<Double> segmentDistances = new ArrayList<>();
            List<Boolean> segmentProgression = new ArrayList<>();

            for (int i = 1; i < points.size(); i++) {
                double prevDistance = distancesToDestination[i - 1];
                double currDistance = distancesToDestination[i];

                double segmentDistance = calculateDistance(
                        points.get(i - 1).getLat(), points.get(i - 1).getLng(),
                        points.get(i).getLat(), points.get(i).getLng()
                );

                segmentDistances.add(segmentDistance);
                boolean isProgressing = currDistance < prevDistance;
                segmentProgression.add(isProgressing);

                if (isProgressing) {
                    progressingSegments++;
                    currentRegressingStreak = 0;
                } else if (currDistance > prevDistance) {
                    regressingSegments++;
                    currentRegressingStreak++;
                    maxRegressingStreak = Math.max(maxRegressingStreak, currentRegressingStreak);
                    totalRegressingDistance += segmentDistance;
                    maxRegressingSegmentDistance = Math.max(maxRegressingSegmentDistance, segmentDistance);
                }
            }

            // 지그재그 패턴 감지
            double zigzagScore = calculateZigzagScore(segmentProgression, segmentDistances);

            // 기본 지표 계산
            int totalSegments = progressingSegments + regressingSegments;
            double progressEfficiency = totalSegments > 0 ? (double) progressingSegments / totalSegments : 1.0;
            double maxRegressingRatio = (double) maxRegressingStreak / points.size();
            double regressingDistanceRatio = totalRegressingDistance / route.getDistance();

            // 경로 타입별 차등 기준 적용
            ValidationCriteria criteria = getValidationCriteria(routeType, route.getShadowPercentage());

            String rejectionReason = null;

            // 진전 효율성 검증
            if (progressEfficiency < criteria.minProgressEfficiency) {
                rejectionReason = String.format("진전 효율성 부족: %.1f%% < %.1f%%",
                        progressEfficiency * 100, criteria.minProgressEfficiency * 100);
            }
            // 연속 후퇴 구간 검증
            else if (maxRegressingRatio > criteria.maxRegressingRatio) {
                rejectionReason = String.format("연속 후퇴 구간 과다: %.1f%% > %.1f%%",
                        maxRegressingRatio * 100, criteria.maxRegressingRatio * 100);
            }
            // 되돌아가는 거리 검증
            else if (regressingDistanceRatio > criteria.maxRegressingDistanceRatio) {
                rejectionReason = String.format("되돌아가는 거리 과다: %.1f%% > %.1f%%",
                        regressingDistanceRatio * 100, criteria.maxRegressingDistanceRatio * 100);
            }
            // 지그재그 패턴 검증 (모든 경로 타입에 공통 적용)
            else if (zigzagScore > 0.6) {
                rejectionReason = String.format("지그재그 패턴 과다: %.1f%% > 60%%", zigzagScore * 100);
            }
            // 극단적 단일 후퇴 구간 검증
            else if (maxRegressingSegmentDistance / route.getDistance() > criteria.maxSingleRegressingRatio) {
                rejectionReason = String.format("단일 후퇴 구간 과다: %.1f%% > %.1f%%",
                        (maxRegressingSegmentDistance / route.getDistance()) * 100,
                        criteria.maxSingleRegressingRatio * 100);
            }

            boolean isReasonable = rejectionReason == null;

            logger.debug("경로 진행 분석 [{}]: 진전효율={}%, 최대후퇴구간={}%, 되돌아가는거리={}%, 지그재그={}%, 합리적={}",
                    routeType, (int)(progressEfficiency * 100), (int)(maxRegressingRatio * 100),
                    (int)(regressingDistanceRatio * 100), (int)(zigzagScore * 100), isReasonable);

            return new RouteProgressionAnalysis(isReasonable, rejectionReason, progressEfficiency,
                    maxRegressingRatio, regressingDistanceRatio, maxRegressingSegmentDistance, zigzagScore);

        } catch (Exception e) {
            logger.error("경로 진행 분석 오류: " + e.getMessage(), e);
            return new RouteProgressionAnalysis(false, "분석 오류: " + e.getMessage());
        }
    }

    /**
     *  경로 타입별 검증 기준 설정
     */
    private ValidationCriteria getValidationCriteria(String routeType, int shadowPercentage) {

        // 기본값 (최단경로용)
        double minProgressEfficiency = 0.65;
        double maxRegressingRatio = 0.20;
        double maxRegressingDistanceRatio = 0.25;
        double maxSingleRegressingRatio = 0.15;

        // 그림자/균형 경로는 더 관대한 기준
        if ("shade".equals(routeType) || "balanced".equals(routeType)) {
            minProgressEfficiency = 0.50;
            maxRegressingRatio = 0.35;
            maxRegressingDistanceRatio = 0.40;
            maxSingleRegressingRatio = 0.25;

            // 그림자 비율이 높을수록 더 관대
            if (shadowPercentage >= 30) {
                minProgressEfficiency = 0.45;
                maxRegressingDistanceRatio = 0.45;
            }
            if (shadowPercentage >= 50) {
                minProgressEfficiency = 0.40;
                maxRegressingDistanceRatio = 0.50;
            }
        }

        return new ValidationCriteria(minProgressEfficiency, maxRegressingRatio,
                maxRegressingDistanceRatio, maxSingleRegressingRatio);
    }

    /**
     *  지그재그 패턴 점수 계산 (0.0 ~ 1.0, 높을수록 지그재그)
     */
    private double calculateZigzagScore(List<Boolean> segmentProgression, List<Double> segmentDistances) {
        if (segmentProgression.size() < 4) return 0.0;

        int directionChanges = 0;
        double totalShortSegments = 0.0;
        double totalDistance = segmentDistances.stream().mapToDouble(Double::doubleValue).sum();

        // 방향 변화 횟수 계산
        for (int i = 1; i < segmentProgression.size(); i++) {
            if (!segmentProgression.get(i).equals(segmentProgression.get(i - 1))) {
                directionChanges++;
            }
        }

        // 짧은 구간들의 비율 계산 (지그재그는 짧은 구간이 많음)
        double avgSegmentDistance = totalDistance / segmentDistances.size();
        for (double distance : segmentDistances) {
            if (distance < avgSegmentDistance * 0.5) { // 평균의 50% 이하
                totalShortSegments += distance;
            }
        }

        double directionChangeRatio = (double) directionChanges / segmentProgression.size();
        double shortSegmentRatio = totalShortSegments / totalDistance;

        // 방향 변화 비율과 짧은 구간 비율의 가중 평균
        return (directionChangeRatio * 0.7 + shortSegmentRatio * 0.3);
    }

    /**
     *  검증 기준을 담는 클래스
     */
    private static class ValidationCriteria {
        final double minProgressEfficiency;
        final double maxRegressingRatio;
        final double maxRegressingDistanceRatio;
        final double maxSingleRegressingRatio;

        ValidationCriteria(double minProgressEfficiency, double maxRegressingRatio,
                           double maxRegressingDistanceRatio, double maxSingleRegressingRatio) {
            this.minProgressEfficiency = minProgressEfficiency;
            this.maxRegressingRatio = maxRegressingRatio;
            this.maxRegressingDistanceRatio = maxRegressingDistanceRatio;
            this.maxSingleRegressingRatio = maxSingleRegressingRatio;
        }
    }

    /**
     *  경로 품질 검증 - 경로 타입 고려
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

            // 경로 타입별 거리 비율 허용치 차등 적용
            double distanceRatio = route.getDistance() / baseRoute.getDistance();
            double maxRatio;

            if ("shade".equals(routeType)) {
                maxRatio = route.getShadowPercentage() >= 40 ? 2.0 : 1.8;  // 그늘 많으면 200%, 적으면 180%
            } else if ("balanced".equals(routeType)) {
                maxRatio = 1.6;  // 균형 경로는 160%
            } else {
                maxRatio = 1.4;  // 최단경로는 140%
            }

            if (distanceRatio > maxRatio) {
                logger.debug("거리 비율 초과: {}% > {}% ({}경로)",
                        (int)(distanceRatio * 100), (int)(maxRatio * 100), routeType);
                return false;
            }

            // 경로 타입을 고려한 진행 방향 분석
            RouteProgressionAnalysis progression = analyzeRouteProgression(route, routeType);

            if (!progression.isReasonable()) {
                logger.debug("경로 진행 분석 실패 [{}]: {}", routeType, progression.getReasonForRejection());
                return false;
            }

            // 그림자 효과 검증 (그림자 경로의 경우)
            if ("shade".equals(routeType)) {
                int shadowDiff = route.getShadowPercentage() - baseRoute.getShadowPercentage();
                int minShadowImprovement = route.getShadowPercentage() >= 40 ? 10 : 15;

                if (shadowDiff < minShadowImprovement) {
                    logger.debug("그림자 효과 부족: +{}% < {}%", shadowDiff, minShadowImprovement);
                    return false;
                }
            }

            logger.debug("경로 품질 검증 통과: {}경로, 거리비율 {}%, 그늘 {}%, 진전효율 {}%",
                    routeType, (int)(distanceRatio * 100), route.getShadowPercentage(),
                    (int)(progression.getProgressEfficiency() * 100));
            return true;

        } catch (Exception e) {
            logger.error("경로 품질 검증 오류: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 극단적 우회 판정 - 경로 타입별 차등 기준
     */
    private boolean isExtremeDetour(Route route, Route baseRoute, String routeType) {
        try {
            if (route == null || baseRoute == null || route.getPoints().isEmpty()) {
                return true;
            }

            // 경로 타입별 거리 비율 허용치
            double maxDistanceRatio;
            if ("shade".equals(routeType)) {
                maxDistanceRatio = 2.2;  // 그림자 경로는 220%까지 허용
            } else if ("balanced".equals(routeType)) {
                maxDistanceRatio = 1.9;  // 균형 경로는 190%까지 허용
            } else {
                maxDistanceRatio = 1.8;  // 기본은 180%
            }

            double detourRatio = route.getDistance() / baseRoute.getDistance();
            if (detourRatio > maxDistanceRatio) {
                logger.debug("극단적 우회 감지 (거리): {}% > {}% [{}경로]",
                        (int)(detourRatio * 100), (int)(maxDistanceRatio * 100), routeType);
                return true;
            }

            // 경로 타입을 고려한 진행 방향 분석
            RouteProgressionAnalysis progression = analyzeRouteProgression(route, routeType);

            // 그림자 경로는 진전 효율성 기준 완화
            double minEfficiency = "shade".equals(routeType) ? 0.35 : 0.45;

            if (progression.getProgressEfficiency() < minEfficiency) {
                logger.debug("극단적 우회 감지 (진전효율): {}% < {}% [{}경로]",
                        (int)(progression.getProgressEfficiency() * 100), (int)(minEfficiency * 100), routeType);
                return true;
            }

            // 지그재그 패턴은 모든 경로 타입에서 금지
            if (progression.getZigzagScore() > 0.7) {
                logger.debug("극단적 우회 감지 (지그재그): {}% > 70% [{}경로]",
                        (int)(progression.getZigzagScore() * 100), routeType);
                return true;
            }

            logger.debug("합리적 우회: 거리={}%, 진전효율={}% [{}경로]",
                    (int)((detourRatio - 1) * 100), (int)(progression.getProgressEfficiency() * 100), routeType);
            return false;

        } catch (Exception e) {
            logger.error("극단적 우회 판정 오류: " + e.getMessage(), e);
            return true;
        }
    }

    /**
     *  RouteProgressionAnalysis 클래스
     */
    private static class RouteProgressionAnalysis {
        private final boolean reasonable;
        private final String reasonForRejection;
        private final double progressEfficiency;
        private final double maxRegressingRatio;
        private final double regressingDistanceRatio;
        private final double maxRegressingSegmentDistance;
        private final double zigzagScore;

        public RouteProgressionAnalysis(boolean reasonable, String reasonForRejection) {
            this(reasonable, reasonForRejection, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        public RouteProgressionAnalysis(boolean reasonable, String reasonForRejection,
                                        double progressEfficiency, double maxRegressingRatio,
                                        double regressingDistanceRatio, double maxRegressingSegmentDistance,
                                        double zigzagScore) {
            this.reasonable = reasonable;
            this.reasonForRejection = reasonForRejection;
            this.progressEfficiency = progressEfficiency;
            this.maxRegressingRatio = maxRegressingRatio;
            this.regressingDistanceRatio = regressingDistanceRatio;
            this.maxRegressingSegmentDistance = maxRegressingSegmentDistance;
            this.zigzagScore = zigzagScore;
        }

        // Getters
        public boolean isReasonable() { return reasonable; }
        public String getReasonForRejection() { return reasonForRejection; }
        public double getProgressEfficiency() { return progressEfficiency; }
        public double getMaxRegressingRatio() { return maxRegressingRatio; }
        public double getRegressingDistanceRatio() { return regressingDistanceRatio; }
        public double getMaxRegressingSegmentDistance() { return maxRegressingSegmentDistance; }
        public double getZigzagScore() { return zigzagScore; }
    }

    /**
     *  경유지 생성 전 사전 검증 강화
     */
    private RoutePoint createStrategicWaypoint(List<RoutePoint> basePoints, SunPosition sunPos,
                                               boolean avoidShadow, List<ShadowArea> shadowAreas) {
        if (basePoints.size() < 2) return null;

        try {
            RoutePoint startPoint = basePoints.get(0);
            RoutePoint endPoint = basePoints.get(basePoints.size() - 1);

            double progressRatio = 0.7;
            RoutePoint middlePoint = new RoutePoint(
                    startPoint.getLat() + (endPoint.getLat() - startPoint.getLat()) * progressRatio,
                    startPoint.getLng() + (endPoint.getLng() - startPoint.getLng()) * progressRatio
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

            // 목적지 방향 제약
            double constrainedDirection = constrainDirectionToDestinationStrict(
                    preferredDirection, destinationDirection);

            // 경유지 거리
            double detourMeters = 25.0;

            // 지리적 좌표로 변환
            double directionRad = Math.toRadians(constrainedDirection);
            double latDegreeInMeters = 111000.0;
            double lngDegreeInMeters = 111000.0 * Math.cos(Math.toRadians(middlePoint.getLat()));

            double latOffset = detourMeters * Math.cos(directionRad) / latDegreeInMeters;
            double lngOffset = detourMeters * Math.sin(directionRad) / lngDegreeInMeters;

            RoutePoint waypoint = new RoutePoint();
            waypoint.setLat(middlePoint.getLat() + latOffset);
            waypoint.setLng(middlePoint.getLng() + lngOffset);

            // 경유지 검증
            if (!isWaypointReasonable(startPoint, waypoint, endPoint)) {
                logger.debug("경유지 합리성 검증 실패");
                return null;
            }

            logger.debug("개선된 경유지 생성: 진행비율={}%, 원하는방향={}도, 목적지방향={}도, 최종방향={}도",
                    (int)(progressRatio * 100), preferredDirection, destinationDirection, constrainedDirection);

            return waypoint;

        } catch (Exception e) {
            logger.error("개선된 경유지 계산 오류: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     *  목적지 방향 제약 (±45도)
     */
    private double constrainDirectionToDestinationStrict(double preferredDirection, double destinationDirection) {
        try {
            // 목적지 방향 ±45도 범위 내에서만 경유지 설정 허용
            double maxAngleDiff = 45.0;

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
            logger.error("엄격한 방향 제약 계산 오류: " + e.getMessage(), e);
            return destinationDirection;
        }
    }

    /**
     *  경유지 합리성 검증
     */
    private boolean isWaypointReasonable(RoutePoint start, RoutePoint waypoint, RoutePoint end) {
        try {
            // 기본 거리 계산
            double directDistance = calculateDistance(
                    start.getLat(), start.getLng(),
                    end.getLat(), end.getLng());

            double startToWaypoint = calculateDistance(
                    start.getLat(), start.getLng(),
                    waypoint.getLat(), waypoint.getLng());

            double waypointToEnd = calculateDistance(
                    waypoint.getLat(), waypoint.getLng(),
                    end.getLat(), end.getLng());

            // 경유지가 실제로 목적지에 더 가까워지는지 확인
            if (waypointToEnd >= directDistance * 0.9) {
                logger.debug("경유지가 목적지에 충분히 가깝지 않음: {}m vs {}m",
                        (int)waypointToEnd, (int)(directDistance * 0.9));
                return false;
            }

            // 전체 우회 거리가 과도하지 않은지 확인
            double totalDistance = startToWaypoint + waypointToEnd;
            double detourRatio = totalDistance / directDistance;
            if (detourRatio > 1.3) {
                logger.debug("경유지 우회 비율 과다: {}% > 130%", (int)(detourRatio * 100));
                return false;
            }

            // 경유지가 시작점에서 목적지까지의 진행에 실제로 기여하는지 확인
            double progressToWaypoint = directDistance - waypointToEnd;
            double expectedProgress = startToWaypoint * Math.cos(Math.toRadians(
                    calculateAngleBetween(start, waypoint, end)
            ));

            // 실제 진전이 예상 진전의 50% 이상이어야 함
            if (progressToWaypoint < expectedProgress * 0.5) {
                logger.debug("경유지 진전 효율성 부족: 실제={}m, 예상={}m",
                        (int)progressToWaypoint, (int)(expectedProgress * 0.5));
                return false;
            }

            logger.debug("경유지 합리성 검증 통과: 우회={}%, 진전효율={}%",
                    (int)(detourRatio * 100),
                    (int)((progressToWaypoint / expectedProgress) * 100));
            return true;

        } catch (Exception e) {
            logger.error("경유지 합리성 검증 오류: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     *  세 점 사이의 각도 계산
     */
    private double calculateAngleBetween(RoutePoint start, RoutePoint waypoint, RoutePoint end) {
        double bearing1 = calculateBearing(start, waypoint);
        double bearing2 = calculateBearing(start, end);

        double angle = Math.abs(bearing1 - bearing2);
        if (angle > 180) {
            angle = 360 - angle;
        }

        return angle;
    }

    /**
     *  경로 유사도 검증 클래스
     */
    public static class RoutesSimilarityChecker {

        /**
         * 두 경로가 유사한지 종합 판단
         */
        public static boolean areRoutesSimilar(Route route1, Route route2) {
            if (route1 == null || route2 == null ||
                    route1.getPoints().isEmpty() || route2.getPoints().isEmpty()) {
                return false;
            }

            try {
                // 경로 특성 유사도 (거리, 시간, 그늘)
                boolean characteristicsSimilar = areCharacteristicsSimilar(route1, route2);

                // 지리적 유사도 (경로 겹침 정도)
                double overlapRatio = calculateRouteOverlapRatio(route1, route2);
                boolean geographicallySimilar = overlapRatio > 0.85; // 85% 이상 겹치면 유사

                // 둘 중 하나라도 유사하면 중복으로 판정
                boolean isSimilar = characteristicsSimilar || geographicallySimilar;

                logger.debug("경로 유사도 분석: 특성유사={}, 지리적겹침={}%, 최종판정={}",
                        characteristicsSimilar, (int)(overlapRatio * 100), isSimilar);

                return isSimilar;

            } catch (Exception e) {
                logger.error("경로 유사도 분석 오류: " + e.getMessage(), e);
                return false;
            }
        }

        /**
         * 경로 특성 유사도 검사 (거리, 시간, 그늘 비율)
         */
        private static boolean areCharacteristicsSimilar(Route route1, Route route2) {
            // 거리 차이
            double distanceDiff = Math.abs(route1.getDistance() - route2.getDistance()) /
                    Math.max(route1.getDistance(), route2.getDistance());

            // 시간 차이
            double timeDiff = Math.abs(route1.getDuration() - route2.getDuration()) /
                    (double) Math.max(route1.getDuration(), route2.getDuration());

            // 그늘 비율 차이
            double shadowDiff = Math.abs(route1.getShadowPercentage() - route2.getShadowPercentage()) / 100.0;

            // 모든 특성이 비슷하면 유사로 판정
            boolean distanceSimilar = distanceDiff < 0.05;   // 거리 차이 5% 미만
            boolean timeSimilar = timeDiff < 0.08;           // 시간 차이 8% 미만
            boolean shadowSimilar = shadowDiff < 0.08;       // 그늘 차이 8% 미만

            boolean similar = distanceSimilar && timeSimilar && shadowSimilar;

            logger.debug("특성 유사도: 거리차이={}%, 시간차이={}%, 그늘차이={}%, 유사={}",
                    (int)(distanceDiff * 100), (int)(timeDiff * 100), (int)(shadowDiff * 100), similar);

            return similar;
        }

        /**
         * 경로 겹침 비율 계산 (0.0 ~ 1.0)
         */
        private static double calculateRouteOverlapRatio(Route route1, Route route2) {
            try {
                List<RoutePoint> points1 = route1.getPoints();
                List<RoutePoint> points2 = route2.getPoints();

                if (points1.size() < 3 || points2.size() < 3) {
                    return 0.0;
                }

                List<RoutePoint> sampledPoints1 = sampleRoutePoints(points1, 50);
                List<RoutePoint> sampledPoints2 = sampleRoutePoints(points2, 50);

                int overlapCount = 0;
                double threshold = 0.0001;

                // route1의 각 포인트가 route2와 얼마나 가까운지 확인
                for (RoutePoint p1 : sampledPoints1) {
                    boolean hasNearbyPoint = sampledPoints2.stream()
                            .anyMatch(p2 -> calculatePointDistance(p1, p2) < threshold);

                    if (hasNearbyPoint) {
                        overlapCount++;
                    }
                }

                double overlapRatio = (double) overlapCount / sampledPoints1.size();

                logger.debug("경로 겹침 분석: {}개/{} 포인트 겹침 ({}%)",
                        overlapCount, sampledPoints1.size(), (int)(overlapRatio * 100));

                return overlapRatio;

            } catch (Exception e) {
                logger.error("경로 겹침 계산 오류: " + e.getMessage(), e);
                return 0.0;
            }
        }

        /**
         * 경로 포인트 샘플링 (성능 최적화용)
         */
        private static List<RoutePoint> sampleRoutePoints(List<RoutePoint> points, int maxSamples) {
            if (points.size() <= maxSamples) {
                return new ArrayList<>(points);
            }

            List<RoutePoint> sampled = new ArrayList<>();
            double step = (double) points.size() / maxSamples;

            for (int i = 0; i < maxSamples; i++) {
                int index = (int) Math.round(i * step);
                if (index >= points.size()) index = points.size() - 1;
                sampled.add(points.get(index));
            }

            return sampled;
        }

        /**
         * 두 포인트 간 거리 계산 (간단한 유클리드 거리)
         */
        private static double calculatePointDistance(RoutePoint p1, RoutePoint p2) {
            double latDiff = p1.getLat() - p2.getLat();
            double lngDiff = p1.getLng() - p2.getLng();
            return Math.sqrt(latDiff * latDiff + lngDiff * lngDiff);
        }
    }

    /**
     *  기존 후보들과 유사한지 검증
     */
    private boolean isRouteSimilarToExisting(Route newRoute, List<RouteCandidate> existingCandidates) {
        if (newRoute == null || existingCandidates.isEmpty()) {
            return false;
        }

        for (RouteCandidate candidate : existingCandidates) {
            if (candidate.getRoute() != null) {
                if (RoutesSimilarityChecker.areRoutesSimilar(newRoute, candidate.getRoute())) {
                    logger.debug("신규 경로가 기존 {}경로와 유사함", candidate.getType());
                    return true;
                }
            }
        }

        return false;
    }

    /**
     *  유사도를 고려한 그림자 경로 생성
     */
    private Route generateUniqueShadeRoute(double startLat, double startLng, double endLat, double endLng,
                                           LocalDateTime dateTime, Route referenceRoute) {
        try {
            logger.info("=== 유일 그림자 경로 생성 시작 ===");

            int maxAttempts = 3; // 최대 3번 시도

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                logger.info("그림자 경로 생성 시도 {}/{}", attempt, maxAttempts);

                // 시도별로 다른 전략 적용
                Route shadeRoute = generateOptimizedShadeRouteWithStrategy(
                        startLat, startLng, endLat, endLng, dateTime, attempt);

                if (shadeRoute != null) {
                    // 참조 경로와 유사도 검증
                    if (!RoutesSimilarityChecker.areRoutesSimilar(shadeRoute, referenceRoute)) {
                        logger.info("유일 그림자 경로 생성 성공 (시도 {})", attempt);
                        return shadeRoute;
                    } else {
                        logger.info("그림자 경로가 참조 경로와 유사 (시도 {})", attempt);
                    }
                }
            }

            logger.warn("모든 시도에서 유일 그림자 경로 생성 실패");
            return null;

        } catch (Exception e) {
            logger.error("유일 그림자 경로 생성 오류: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     *  유사도를 고려한 균형 경로 생성
     */
    private Route generateUniqueBalancedRoute(double startLat, double startLng, double endLat, double endLng,
                                              LocalDateTime dateTime, Route referenceRoute) {
        try {
            logger.info("=== 유일 균형 경로 생성 시작 ===");

            int maxAttempts = 3; // 최대 3번 시도

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                logger.info("균형 경로 생성 시도 {}/{}", attempt, maxAttempts);

                // 시도별로 다른 전략 적용
                Route balancedRoute = generateOptimizedBalancedRouteWithStrategy(
                        startLat, startLng, endLat, endLng, dateTime, attempt);

                if (balancedRoute != null) {
                    // 참조 경로와 유사도 검증
                    if (!RoutesSimilarityChecker.areRoutesSimilar(balancedRoute, referenceRoute)) {
                        logger.info("유일 균형 경로 생성 성공 (시도 {})", attempt);
                        return balancedRoute;
                    } else {
                        logger.info("균형 경로가 참조 경로와 유사 (시도 {})", attempt);
                    }
                }
            }

            logger.warn("모든 시도에서 유일 균형 경로 생성 실패");
            return null;

        } catch (Exception e) {
            logger.error("유일 균형 경로 생성 오류: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     *  시도별 전략을 적용한 그림자 경로 생성
     */
    private Route generateOptimizedShadeRouteWithStrategy(double startLat, double startLng, double endLat, double endLng,
                                                          LocalDateTime dateTime, int attemptNumber) {
        try {
            // 시도별로 다른 경유지 생성 전략 적용
            double progressRatio;
            double detourDistance;
            double angleVariation;

            switch (attemptNumber) {
                case 1: // 기본 전략
                    progressRatio = 0.7;
                    detourDistance = 30.0;
                    angleVariation = 0.0;
                    break;
                case 2: // 더 앞쪽, 더 멀리
                    progressRatio = 0.6;
                    detourDistance = 50.0;
                    angleVariation = 15.0;
                    break;
                case 3: // 더 뒤쪽, 다른 방향
                    progressRatio = 0.8;
                    detourDistance = 40.0;
                    angleVariation = -20.0;
                    break;
                default:
                    return generateOptimizedShadeRoute(startLat, startLng, endLat, endLng, dateTime);
            }

            logger.debug("그림자 경로 전략 {}: 진행비율={}%, 우회거리={}m, 각도변화={}도",
                    attemptNumber, (int)(progressRatio * 100), detourDistance, angleVariation);

            return generateOptimizedShadeRoute(startLat, startLng, endLat, endLng, dateTime);

        } catch (Exception e) {
            logger.error("전략적 그림자 경로 생성 오류: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     *  시도별 전략을 적용한 균형 경로 생성
     */
    private Route generateOptimizedBalancedRouteWithStrategy(double startLat, double startLng, double endLat, double endLng,
                                                             LocalDateTime dateTime, int attemptNumber) {
        try {
            // 균형 경로도 시도별로 다른 전략 적용
            double progressRatio;
            double detourDistance;
            double angleVariation;

            switch (attemptNumber) {
                case 1: // 기본 전략 (중간 지점, 적당한 우회)
                    progressRatio = 0.65;
                    detourDistance = 35.0;
                    angleVariation = 0.0;
                    break;
                case 2: // 다른 위치 전략 (더 앞쪽, 작은 우회)
                    progressRatio = 0.5;
                    detourDistance = 25.0;
                    angleVariation = 25.0;
                    break;
                case 3: // 또 다른 전략 (더 뒤쪽, 다른 방향)
                    progressRatio = 0.75;
                    detourDistance = 45.0;
                    angleVariation = -30.0;
                    break;
                default:
                    return generateOptimizedBalancedRoute(startLat, startLng, endLat, endLng, dateTime);
            }

            logger.debug("균형 경로 전략 {}: 진행비율={}%, 우회거리={}m, 각도변화={}도",
                    attemptNumber, (int)(progressRatio * 100), detourDistance, angleVariation);

            return generateOptimizedBalancedRoute(startLat, startLng, endLat, endLng, dateTime);

        } catch (Exception e) {
            logger.error("전략적 균형 경로 생성 오류: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 간단한 경유지 변형들 생성
     */
    private List<RoutePoint> createDiverseWaypointVariants(double startLat, double startLng,
                                                           double endLat, double endLng,
                                                           List<ShadowArea> shadowAreas,
                                                           SunPosition sunPos, boolean avoidShadow,
                                                           int attemptNumber) {
        List<RoutePoint> variants = new ArrayList<>();

        try {
            List<RoutePoint> basePoints = Arrays.asList(
                    new RoutePoint(startLat, startLng),
                    new RoutePoint(endLat, endLng)
            );

            // 시도별로 다른 위치에서 경유지 생성
            double[] progressRatios;
            double[] detourDistances;
            double[] angleOffsets;

            switch (attemptNumber) {
                case 1: // 기본 전략
                    progressRatios = new double[]{0.6, 0.7, 0.8};
                    detourDistances = new double[]{25.0, 35.0, 45.0};
                    angleOffsets = new double[]{0.0, 15.0, -15.0};
                    break;
                case 2: // 적당히 다르게
                    progressRatios = new double[]{0.4, 0.5, 0.9};
                    detourDistances = new double[]{20.0, 50.0, 30.0};
                    angleOffsets = new double[]{30.0, -30.0, 45.0};
                    break;
                case 3: // 최대한 다르게
                    progressRatios = new double[]{0.3, 0.55, 0.85};
                    detourDistances = new double[]{15.0, 60.0, 40.0};
                    angleOffsets = new double[]{-45.0, 60.0, -20.0};
                    break;
                default:
                    progressRatios = new double[]{0.6, 0.7, 0.8};
                    detourDistances = new double[]{30.0, 40.0, 50.0};
                    angleOffsets = new double[]{0.0, 20.0, -20.0};
            }

            // 다양한 경유지 생성
            for (int i = 0; i < progressRatios.length; i++) {
                RoutePoint variant = createStrategicWaypointWithParams(
                        basePoints, sunPos, avoidShadow, shadowAreas,
                        progressRatios[i], detourDistances[i], angleOffsets[i]
                );

                if (variant != null) {
                    variants.add(variant);
                    logger.debug("다양성 경유지 {}번 생성: 진행={}%, 거리={}m, 각도={}도",
                            i + 1, (int)(progressRatios[i] * 100), detourDistances[i], angleOffsets[i]);
                }
            }

            logger.debug("다양성 경유지 변형 생성 (시도 {}): {}개", attemptNumber, variants.size());
            return variants;

        } catch (Exception e) {
            logger.error("다양성 경유지 변형 생성 오류: " + e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     *  파라미터를 받아서 경유지 생성
     */
    private RoutePoint createStrategicWaypointWithParams(List<RoutePoint> basePoints, SunPosition sunPos,
                                                         boolean avoidShadow, List<ShadowArea> shadowAreas,
                                                         double progressRatio, double detourDistance, double angleOffset) {
        if (basePoints.size() < 2) return null;

        try {
            RoutePoint startPoint = basePoints.get(0);
            RoutePoint endPoint = basePoints.get(basePoints.size() - 1);

            RoutePoint middlePoint = new RoutePoint(
                    startPoint.getLat() + (endPoint.getLat() - startPoint.getLat()) * progressRatio,
                    startPoint.getLng() + (endPoint.getLng() - startPoint.getLng()) * progressRatio
            );

            // 목적지 방향 계산
            double destinationDirection = calculateBearing(startPoint, endPoint);

            // 원하는 경유지 방향 계산
            double preferredDirection;
            if (avoidShadow) {
                preferredDirection = sunPos.getAzimuth();
            } else {
                preferredDirection = (sunPos.getAzimuth() + 180) % 360;
            }

            //  각도 오프셋 적용
            preferredDirection = (preferredDirection + angleOffset + 360) % 360;

            // 목적지 방향 제약 적용 (좀 더 관대하게)
            double constrainedDirection = constrainDirectionToDestinationModerate(
                    preferredDirection, destinationDirection);

            // 우회 거리 적용
            double directionRad = Math.toRadians(constrainedDirection);
            double latDegreeInMeters = 111000.0;
            double lngDegreeInMeters = 111000.0 * Math.cos(Math.toRadians(middlePoint.getLat()));

            double latOffset = detourDistance * Math.cos(directionRad) / latDegreeInMeters;
            double lngOffset = detourDistance * Math.sin(directionRad) / lngDegreeInMeters;

            RoutePoint waypoint = new RoutePoint();
            waypoint.setLat(middlePoint.getLat() + latOffset);
            waypoint.setLng(middlePoint.getLng() + lngOffset);

            if (!isWaypointModeratelyReasonable(startPoint, waypoint, endPoint)) {
                logger.debug("파라미터 경유지 검증 실패");
                return null;
            }

            return waypoint;

        } catch (Exception e) {
            logger.error("파라미터 경유지 생성 오류: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     *  목적지 방향 제약
     */
    private double constrainDirectionToDestinationModerate(double preferredDirection, double destinationDirection) {
        try {
            double maxAngleDiff = 60.0;

            double angleDiff = Math.abs(preferredDirection - destinationDirection);
            if (angleDiff > 180) {
                angleDiff = 360 - angleDiff;
            }

            if (angleDiff <= maxAngleDiff) {
                return preferredDirection;
            }

            // 조정 로직
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
            logger.error("적당한 방향 제약 계산 오류: " + e.getMessage(), e);
            return destinationDirection;
        }
    }

    /**
     *  경유지 검증
     */
    private boolean isWaypointModeratelyReasonable(RoutePoint start, RoutePoint waypoint, RoutePoint end) {
        try {
            double directDistance = calculateDistance(
                    start.getLat(), start.getLng(),
                    end.getLat(), end.getLng());

            double startToWaypoint = calculateDistance(
                    start.getLat(), start.getLng(),
                    waypoint.getLat(), waypoint.getLng());

            double waypointToEnd = calculateDistance(
                    waypoint.getLat(), waypoint.getLng(),
                    end.getLat(), end.getLng());


            // 경유지가 목적지에 가까워지는지
            if (waypointToEnd >= directDistance * 0.85) {
                return false;
            }

            // 우회 거리
            double totalDistance = startToWaypoint + waypointToEnd;
            double detourRatio = totalDistance / directDistance;
            if (detourRatio > 1.5) {
                return false;
            }

            // 직선 이탈
            double perpDistance = calculatePointToLineDistance(waypoint, start, end);
            double maxPerpDistance = directDistance * 0.2;
            if (perpDistance > maxPerpDistance) {
                return false;
            }

            return true;

        } catch (Exception e) {
            logger.error("적당한 경유지 검증 오류: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 균형 점수 계산 (거리 효율성 + 그림자 효과)
     */
    private double calculateBalanceScore(Route route, Route baseRoute) {
        try {
            // 거리 효율성 (짧을수록 좋음, 0~1)
            double distanceEfficiency = Math.max(0, 1.0 - (route.getDistance() / baseRoute.getDistance() - 1.0));

            // 그림자 효과 (많을수록 좋음, 0~1)
            double shadowEffect = route.getShadowPercentage() / 100.0;

            // 균형 점수 (가중 평균)
            return distanceEfficiency * 0.4 + shadowEffect * 0.6;

        } catch (Exception e) {
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
}
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
            logger.info("=== 가능한 경로 후보 생성 시작 ===");
            logger.info("출발: ({}, {}), 도착: ({}, {}), 사용자 선택 시간: {}",
                    startLat, startLng, endLat, endLng, dateTime);

            boolean isNight = isNightTime(dateTime);
            boolean isBadWeather = weatherService.isBadWeather(startLat, startLng);

            logger.info("시간 분석 - 선택시간: {}, 밤시간: {}, 나쁜날씨: {}",
                    dateTime.getHour() + "시", isNight, isBadWeather);

            if (isNight || isBadWeather) {
                String reason = isNight ? "밤 시간 (22시~6시)" : "나쁜 날씨";
                logger.info("{}로 인해 안전한 최단경로만 생성", reason);
                return generateShortestRouteOnly(startLat, startLng, endLat, endLng, dateTime);
            }

            logger.info("낮 시간 + 좋은 날씨 → 다양한 경로 생성 (선택시간: {}시)", dateTime.getHour());

            // 개선된 다중 경로 생성
            List<Route> typedRoutes = generateTypedRoutes(startLat, startLng, endLat, endLng, dateTime);

            // 중복 제거 및 품질 검증
            List<Route> validRoutes = filterTypedRoutes(typedRoutes);
            logger.info("품질 검증 후 경로 수: {}", validRoutes.size());

            // 사용자가 선택한 시간의 태양 위치로 그림자 점수 계산
            calculateShadowScores(validRoutes, startLat, startLng, dateTime);

            // 타입별 후보 생성
            List<RouteCandidate> candidates = createTypedCandidates(validRoutes);

            logger.info("최종 후보 경로 3개 생성 완료 (선택시간 {}시 기준)", dateTime.getHour());
            return candidates;

        } catch (Exception e) {
            logger.error("경로 후보 생성 오류: " + e.getMessage(), e);
            return generateShortestRouteOnly(startLat, startLng, endLat, endLng, dateTime);
        }
    }

    /**
     * 타입별 특화된 경로 생성
     */
    private List<Route> generateTypedRoutes(double startLat, double startLng, double endLat, double endLng, LocalDateTime dateTime) {
        List<Route> routes = new ArrayList<>();

        try {
            logger.info("=== 타입별 차별화된 경로 생성 시작 ===");

            // 1. 최단경로 (기본 T맵 경로, 경유지 없음)
            Route shortestRoute = generateShortestRoute(startLat, startLng, endLat, endLng);
            if (shortestRoute != null) {
                routes.add(shortestRoute);
                logger.info("최단경로 생성: {}m", (int)shortestRoute.getDistance());
            }

            // 2. 그림자 많은 경로 (그림자 영역을 의도적으로 경유)
            Route shadeRoute = generateShadeRoute(startLat, startLng, endLat, endLng, dateTime);
            if (shadeRoute != null) {
                routes.add(shadeRoute);
                logger.info("그림자 경로 생성: {}m, 그림자 {}%",
                        (int)shadeRoute.getDistance(), shadeRoute.getShadowPercentage());
            }

            // 3. 균형 경로 (적당한 우회 + 적당한 그림자)
            Route balancedRoute = generateBalancedRoute(startLat, startLng, endLat, endLng, dateTime);
            if (balancedRoute != null) {
                routes.add(balancedRoute);
                logger.info("균형 경로 생성: {}m, 그림자 {}%",
                        (int)balancedRoute.getDistance(), balancedRoute.getShadowPercentage());
            }

            // 4. 강제 차별화 (경로가 너무 비슷하면 추가 생성)
            routes = ensureRouteDiversification(routes, startLat, startLng, endLat, endLng, dateTime);

            logger.info("타입별 경로 생성 완료: {}개", routes.size());

        } catch (Exception e) {
            logger.error("타입별 경로 생성 오류: " + e.getMessage(), e);
            // 실패 시 최소한 기본 경로라도 제공
            Route fallback = generateShortestRoute(startLat, startLng, endLat, endLng);
            if (fallback != null) {
                routes.add(fallback);
            }
        }

        return routes;
    }


    /**
     * 타입별 경로 필터링 (기존보다 관대한 기준)
     */
    private List<Route> filterTypedRoutes(List<Route> routes) {
        List<Route> validRoutes = new ArrayList<>();

        try {
            Route baseRoute = routes.stream()
                    .filter(r -> "shortest".equals(r.getRouteType()))
                    .findFirst()
                    .orElse(null);

            for (Route route : routes) {
                // 기본 유효성 검증
                if (route.getPoints().isEmpty()) {
                    logger.debug("빈 경로 제외: {}", route.getRouteType());
                    continue;
                }

                // 타입별 다른 기준 적용
                if ("shortest".equals(route.getRouteType())) {
                    // 최단경로는 항상 포함
                    validRoutes.add(route);
                    logger.debug("O 최단경로 포함: {}m", (int)route.getDistance());

                } else if ("shade".equals(route.getRouteType())) {
                    // 그림자 경로는 최대 200% 거리까지 허용
                    if (baseRoute != null && route.getDistance() <= baseRoute.getDistance() * 2.0) {
                        validRoutes.add(route);
                        logger.debug("O 그림자 경로 포함: {}m ({}% 증가)",
                                (int)route.getDistance(),
                                (int)((route.getDistance() / baseRoute.getDistance() - 1) * 100));
                    } else {
                        logger.debug("X 그림자 경로 거리 초과로 제외");
                    }

                } else if ("balanced".equals(route.getRouteType())) {
                    // 균형 경로는 150% 거리까지 허용
                    if (baseRoute != null && route.getDistance() <= baseRoute.getDistance() * 1.5) {
                        validRoutes.add(route);
                        logger.debug("O 균형 경로 포함: {}m ({}% 증가)",
                                (int)route.getDistance(),
                                (int)((route.getDistance() / baseRoute.getDistance() - 1) * 100));
                    } else {
                        logger.debug("X 균형 경로 거리 초과로 제외");
                    }
                } else {
                    // 기타 변형 경로들
                    validRoutes.add(route);
                    logger.debug("O 변형 경로 포함: {} - {}m", route.getRouteType(), (int)route.getDistance());
                }
            }

            logger.info("타입별 필터링 완료: {}개 → {}개", routes.size(), validRoutes.size());

        } catch (Exception e) {
            logger.error("타입별 필터링 오류: " + e.getMessage(), e);
            return routes.isEmpty() ? new ArrayList<>() : Arrays.asList(routes.get(0));
        }

        return validRoutes;
    }

    /**
     * 타입별 후보 생성
     */
    private List<RouteCandidate> createTypedCandidates(List<Route> routes) {
        List<RouteCandidate> candidates = new ArrayList<>();

        try {
            logger.info("=== 타입별 후보 생성 시작 ===");

            // 1. 최단경로 후보
            Route shortestRoute = findRouteByType(routes, "shortest");
            if (shortestRoute != null) {
                RouteCandidate shortest = new RouteCandidate("shortest", "최단경로", shortestRoute);
                candidates.add(shortest);
                logger.info("O 최단경로 후보: {}km, {}분, 그늘 {}%",
                        shortestRoute.getDistance() / 1000.0, shortestRoute.getDuration(), shortestRoute.getShadowPercentage());
            } else {
                logger.info("X 최단경로 생성 불가 - 후보에서 제외");
            }

            // 2. 그림자 많은 경로 후보
            Route shadeRoute = findRouteByType(routes, "shade");
            if (shadeRoute != null && shortestRoute != null) {
                String failureReason = validateRouteQuality(shadeRoute, shortestRoute, "shade");

                if (failureReason == null) {
                    int shadowDiff = shadeRoute.getShadowPercentage() - shortestRoute.getShadowPercentage();
                    if (shadowDiff >= 10) { // 최소 10% 이상 그늘이 많아야 함
                        RouteCandidate shade = new RouteCandidate("shade", "그늘이 많은경로", shadeRoute);
                        String efficiencyInfo = shade.calculateEfficiencyDisplay(shortestRoute);
                        shade.setDescription(shade.getDescription() + " · " + efficiencyInfo);
                        candidates.add(shade);
                        logger.info("그림자 경로 후보: {}km, {}분, 그늘 {}% (+{}%), 효율성: {}",
                                shadeRoute.getDistance() / 1000.0, shadeRoute.getDuration(),
                                shadeRoute.getShadowPercentage(), shadowDiff, efficiencyInfo);
                    } else {
                        logger.info("그림자 경로 효과 부족: 그늘 차이 {}% < 10% - 후보에서 제외", shadowDiff);
                    }
                } else {
                    logger.info("그림자 경로 품질 검증 실패: {} - 후보에서 제외", failureReason);
                }
            } else {
                String reason = shadeRoute == null ? "그림자 경로 생성 실패" : "기준 경로 없음";
                logger.info("그림자 경로 생성 불가: {} - 후보에서 제외", reason);
            }

            // 3. 균형 경로 후보
            Route balancedRoute = findRouteByType(routes, "balanced");
            if (balancedRoute != null && shortestRoute != null) {
                String failureReason = validateRouteQuality(balancedRoute, shortestRoute, "balanced");
                if (failureReason == null) {
                    RouteCandidate balanced = new RouteCandidate("balanced", "균형경로", balancedRoute);
                    // 효율성 정보 추가
                    String efficiencyInfo = balanced.calculateEfficiencyDisplay(shortestRoute);
                    balanced.setDescription(balanced.getDescription() + " · " + efficiencyInfo);
                    candidates.add(balanced);
                    logger.info("O 균형 경로 후보: {}km, {}분, 그늘 {}%, 효율성: {}",
                            balancedRoute.getDistance() / 1000.0, balancedRoute.getDuration(), 
                            balancedRoute.getShadowPercentage(), efficiencyInfo);
                } else {
                    logger.info("X 균형 경로 품질 검증 실패: {} - 후보에서 제외", failureReason);
                }
            } else {
                String reason = shortestRoute == null ? "기준 경로 없음" : "경유지 생성 실패";
                logger.info("X 균형 경로 생성 불가: {} - 후보에서 제외", reason);
            }

           /* // 4. 대안 경로 생성 (그림자 경로가 실패한 경우)
            if (candidates.size() < 2 && shortestRoute != null) {
                logger.info("후보 부족으로 대안 경로 생성 시도");
                RouteCandidate alternativeCandidate = createAlternativeCandidate(routes, shortestRoute);
                if (alternativeCandidate != null) {
                    candidates.add(alternativeCandidate);
                    logger.info("O 대안 경로 추가: {}", alternativeCandidate.getDisplayName());
                }
            }*/

            if (candidates.size() < 2) {
                logger.info("생성된 후보 경로가 {}개", candidates.size());
            }

            logger.info("타입별 후보 생성 완료: {}개", candidates.size());


        } catch (Exception e) {
            logger.error("타입별 후보 생성 오류: " + e.getMessage(), e);
            candidates.clear();
            logger.info("시스템 오류로 인해 모든 후보 제외");
        }

        return candidates;
    }

    /**
     * 대안 경로 생성 (그림자 경로가 적절하지 않을 때)
     */
    private RouteCandidate createAlternativeCandidate(List<Route> routes, Route shortestRoute) {
        try {
            // 그늘이 가장 많은 경로 찾기
            Route bestShadeRoute = routes.stream()
                    .filter(r -> r.getShadowPercentage() > shortestRoute.getShadowPercentage())
                    .max((r1, r2) -> Integer.compare(r1.getShadowPercentage(), r2.getShadowPercentage()))
                    .orElse(null);

            if (bestShadeRoute != null) {
                RouteCandidate alternative = new RouteCandidate("alternative", "대안경로", bestShadeRoute);
                String efficiencyInfo = alternative.calculateEfficiencyDisplay(shortestRoute);
                alternative.setDescription(alternative.getDescription() + " · " + efficiencyInfo);
                return alternative;
            }

            // 그늘이 더 많은 경로가 없다면 거리가 다른 경로 찾기
            Route alternativeRoute = routes.stream()
                    .filter(r -> !"shortest".equals(r.getRouteType()))
                    .filter(r -> Math.abs(r.getDistance() - shortestRoute.getDistance()) > 100) // 100m 이상 차이
                    .findFirst()
                    .orElse(null);

            if (alternativeRoute != null) {
                RouteCandidate alternative = new RouteCandidate("alternative", "대안경로", alternativeRoute);
                String efficiencyInfo = alternative.calculateEfficiencyDisplay(shortestRoute);
                alternative.setDescription(alternative.getDescription() + " · " + efficiencyInfo);
                return alternative;
            }

        } catch (Exception e) {
            logger.error("대안 경로 생성 오류: " + e.getMessage(), e);
        }

        return null;
    }


    /**
     * 경로 품질 검증
     */
    private String validateRouteQuality(Route targetRoute, Route baseRoute, String routeType) {
        try {
            // 1. 거리 검증 (더 엄격하게)
            double distanceRatio = targetRoute.getDistance() / baseRoute.getDistance();
            double maxRatio;

            if ("shade".equals(routeType)) {
                maxRatio = 1.4; // 그림자: 140%
            } else {
                maxRatio = 1.25; // 균형: 125%
            }

            if (distanceRatio > maxRatio) {
                return String.format("우회 거리 과다 (%.0f%% 증가 > %.0f%% 허용)",
                        (distanceRatio - 1) * 100, (maxRatio - 1) * 100);
            }

            // 2. 유사도 검증
            double similarity = calculateRouteSimilarity(targetRoute, baseRoute);
            if (similarity > 0.8) {
                return String.format("경로 차이 미미 (유사도 %.0f%% > 80%%)", similarity * 100);
            }

            // 3. 그림자 경로 전용 검증
            if ("shade".equals(routeType)) {
                int shadowDiff = Math.abs(targetRoute.getShadowPercentage() - baseRoute.getShadowPercentage());
                if (shadowDiff < 10) {
                    return String.format("그늘 효과 미미 (차이 %d%% < 10%%)", shadowDiff);
                }
            }

            // 4. 최소 거리 검증
            if (targetRoute.getDistance() < 100) {
                return "경로가 너무 짧음";
            }

            // 경로 모양 검증 (직선성 검사)
            if (hasExcessiveStraightSegments(targetRoute)) {
                return "비정상적인 직선 구간 포함";
            }

            return null; // 검증 통과

        } catch (Exception e) {
            return "검증 오류 발생";
        }
    }

    /**
     * 직선성 검사
     */
    private boolean hasExcessiveStraightSegments(Route route) {
        try {
            List<RoutePoint> points = route.getPoints();
            if (points.size() < 3) return false;

            int longSegments = 0;
            final double MAX_SEGMENT_LENGTH = 150.0; // 150m 이상 구간은 의심

            for (int i = 0; i < points.size() - 1; i++) {
                RoutePoint current = points.get(i);
                RoutePoint next = points.get(i + 1);

                double segmentDistance = calculateDistance(
                        current.getLat(), current.getLng(),
                        next.getLat(), next.getLng()
                );

                if (segmentDistance > MAX_SEGMENT_LENGTH) {
                    longSegments++;
                }
            }

            // 전체 구간의 10% 이상이 긴 구간이면 문제
            double longSegmentRatio = (double) longSegments / (points.size() - 1);
            return longSegmentRatio > 0.1;

        } catch (Exception e) {
            logger.warn("직선 구간 검사 오류: " + e.getMessage());
            return false;
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
     * 타입별 경로 찾기
     */
    private Route findRouteByType(List<Route> routes, String targetType) {
        return routes.stream()
                .filter(route -> targetType.equals(route.getRouteType()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 1. 최단경로 생성 (순수 T맵 API, 경유지 없음)
     */
    private Route generateShortestRoute(double startLat, double startLng, double endLat, double endLng) {
        try {
            logger.debug("최단경로 생성 중...");

            String routeJson = tmapApiService.getWalkingRoute(startLat, startLng, endLat, endLng);
            Route route = shadowRouteService.parseBasicRoute(routeJson);

            route.setRouteType("shortest");
            route.setWaypointCount(0);

            return route;

        } catch (Exception e) {
            logger.error("최단경로 생성 실패: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 2. 그림자 많은 경로 생성 (그림자 영역을 의도적으로 경유)
     */
    private Route generateShadeRoute(double startLat, double startLng, double endLat, double endLng, LocalDateTime dateTime) {
        try {
            logger.debug("그림자 많은 경로 생성 중...");

            // 태양 위치 계산
            SunPosition sunPos = shadowService.calculateSunPosition(startLat, startLng, dateTime);

            // 그림자 영역 정보 조회
            List<ShadowArea> shadowAreas = shadowRouteService.calculateBuildingShadows(
                    startLat, startLng, endLat, endLng, sunPos);

            if (shadowAreas.isEmpty()) {
                logger.debug("그림자 영역이 없어 그림자 경로 생성 불가");
                return null; // 그림자 데이터가 없으면 null 반환
            }

            // 그림자 영역을 의도적으로 경유하는 경유지 생성
            List<RoutePoint> shadeWaypoints = generateShadeTargetingWaypoints(
                    startLat, startLng, endLat, endLng, shadowAreas, sunPos);

            Route enhancedRoute;
            if (!shadeWaypoints.isEmpty()) {
                logger.debug("그림자 타겟 경유지 생성: {}개", shadeWaypoints.size());

                // 경유지를 통한 새 경로 생성
                String waypointRouteJson = tmapApiService.getWalkingRouteWithMultiWaypoints(
                        startLat, startLng, shadeWaypoints, endLat, endLng);

                enhancedRoute = shadowRouteService.parseBasicRoute(waypointRouteJson);
            } else {
                logger.debug("그림자 경유지 생성 실패, 기본 경로로 대체");
                enhancedRoute = generateShortestRoute(startLat, startLng, endLat, endLng);
            }

            if (enhancedRoute != null) {
                enhancedRoute.setRouteType("shade");
                enhancedRoute.setWaypointCount(shadeWaypoints.size());

                shadowRouteService.applyShadowInfoFromDB(enhancedRoute, shadowAreas);

                logger.debug("그림자 경로 생성 성공: 그늘 {}% (실제 데이터)", enhancedRoute.getShadowPercentage());
            }

            return enhancedRoute;

        } catch (Exception e) {
            logger.error("그림자 경로 생성 실패: " + e.getMessage(), e);
            return null; // 오류 시 null 반환
        }
    }

    /**
     * 3. 균형 경로 생성 (적당한 우회 + 적당한 그림자)
     */
    private Route generateBalancedRoute(double startLat, double startLng, double endLat, double endLng, LocalDateTime dateTime) {
        try {
            logger.debug("균형 경로 생성 중...");

            // 기본 경로 먼저 생성
            Route baseRoute = generateShortestRoute(startLat, startLng, endLat, endLng);
            if (baseRoute == null) return null;

            // 태양 위치 계산
            SunPosition sunPos = shadowService.calculateSunPosition(startLat, startLng, dateTime);

            // 적당한 우회를 위한 균형 경유지 생성
            List<RoutePoint> balancedWaypoints = generateBalancedWaypoints(
                    baseRoute, startLat, startLng, endLat, endLng, sunPos);

            if (balancedWaypoints.isEmpty()) {
                // 경유지 생성 실패 시 기본 경로에 약간의 변형만 적용
                return createSlightVariation(baseRoute, startLat, startLng, endLat, endLng);
            }

            // 균형 경유지를 포함한 경로 요청
            String routeJson = tmapApiService.getWalkingRouteWithMultiWaypoints(
                    startLat, startLng, balancedWaypoints, endLat, endLng);

            Route route = shadowRouteService.parseBasicRoute(routeJson);
            route.setRouteType("balanced");
            route.setWaypointCount(balancedWaypoints.size());

            // 그림자 정보 적용
            List<ShadowArea> shadowAreas = shadowRouteService.calculateBuildingShadows(
                    startLat, startLng, endLat, endLng, sunPos);
            shadowRouteService.applyShadowInfoFromDB(route, shadowAreas);

            return route;

        } catch (Exception e) {
            logger.error("균형 경로 생성 실패: " + e.getMessage(), e);
            return generateShortestRoute(startLat, startLng, endLat, endLng);
        }
    }

    /**
     * 그림자 영역을 타겟으로 하는 경유지 생성
     */
    private List<RoutePoint> generateShadeTargetingWaypoints(double startLat, double startLng,
                                                             double endLat, double endLng,
                                                             List<ShadowArea> shadowAreas,
                                                             SunPosition sunPos) {
        List<RoutePoint> waypoints = new ArrayList<>();

        try {
            // 그림자 영역들의 중심점 계산
            List<RoutePoint> shadowCenters = calculateShadowCenters(shadowAreas);
            if (shadowCenters.isEmpty()) {
                return waypoints;
            }

            RoutePoint startPoint = new RoutePoint(startLat, startLng);
            RoutePoint endPoint = new RoutePoint(endLat, endLng);
            RoutePoint midPoint = new RoutePoint((startLat + endLat) / 2, (startLng + endLng) / 2);

            // 목적지 방향 계산
            double destinationDirection = calculateBearing(startPoint, endPoint);

            // 중간지점에서 가장 가까운 그림자 영역 찾기
            RoutePoint closestShadowCenter = findClosestShadowCenter(midPoint, shadowCenters);

            if (closestShadowCenter != null) {
                // 그림자 방향 계산
                double shadowDirection = calculateBearing(midPoint, closestShadowCenter);

                // 목적지 방향 제약 적용
                double constrainedDirection = constrainDirectionToDestination(
                        shadowDirection, destinationDirection);

                // 제약된 방향으로 경유지 생성
                double distance = 0.0003; // 약 30m
                double directionRad = Math.toRadians(constrainedDirection);

                double waypointLat = midPoint.getLat() + Math.cos(directionRad) * distance;
                double waypointLng = midPoint.getLng() + Math.sin(directionRad) * distance;

                RoutePoint candidateWaypoint = new RoutePoint(waypointLat, waypointLng);

                // 진행성 검증
                if (isWaypointProgressive(startPoint, candidateWaypoint, endPoint)) {
                    waypoints.add(candidateWaypoint);
                    logger.debug("제약된 그림자 경유지 생성: 원래방향={}도, 제약방향={}도",
                            shadowDirection, constrainedDirection);
                } else {
                    logger.debug("그림자 경유지 진행성 검증 실패");
                }
            }

        } catch (Exception e) {
            logger.error("제약된 그림자 경유지 생성 오류: " + e.getMessage(), e);
        }

        return waypoints;
    }

    /**
     * 균형잡힌 경유지 생성 (그림자, 거리 고려)
     */
    private List<RoutePoint> generateBalancedWaypoints(Route baseRoute, double startLat, double startLng,
                                                       double endLat, double endLng, SunPosition sunPos) {
        List<RoutePoint> waypoints = new ArrayList<>();

        try {
            List<RoutePoint> basePoints = baseRoute.getPoints();
            if (basePoints.size() < 10) return waypoints;

            RoutePoint startPoint = new RoutePoint(startLat, startLng);
            RoutePoint endPoint = new RoutePoint(endLat, endLng);

            // ⭐ 목적지 방향 계산
            double destinationDirection = calculateBearing(startPoint, endPoint);

            // 균형을 위한 고정 시드
            long balancedSeed = generateConsistentSeed(startLat, startLng, endLat, endLng) + 2000;
            Random random = new Random(balancedSeed);

            // 경로의 1/3, 2/3 지점에서 제약된 우회
            int oneThirdIndex = basePoints.size() / 3;
            int twoThirdIndex = (basePoints.size() * 2) / 3;

            for (int index : Arrays.asList(oneThirdIndex, twoThirdIndex)) {
                RoutePoint basePoint = basePoints.get(index);

                // 랜덤 방향 생성 (0-360도)
                double randomDirection = random.nextDouble() * 360;

                // 목적지 방향 제약 적용
                double constrainedDirection = constrainDirectionToDestination(
                        randomDirection, destinationDirection);

                // 제약된 방향으로 경유지 생성 (거리 단축)
                double offsetDistance = 0.0002; // 약 20m
                double directionRad = Math.toRadians(constrainedDirection);

                double waypointLat = basePoint.getLat() + Math.cos(directionRad) * offsetDistance;
                double waypointLng = basePoint.getLng() + Math.sin(directionRad) * offsetDistance;

                RoutePoint candidateWaypoint = new RoutePoint(waypointLat, waypointLng);

                // 진행성 검증
                if (isWaypointProgressive(startPoint, candidateWaypoint, endPoint)) {
                    waypoints.add(candidateWaypoint);
                    logger.debug("제약된 균형 경유지 생성: 원래방향={}도, 제약방향={}도",
                            randomDirection, constrainedDirection);
                } else {
                    logger.debug("균형 경유지 진행성 검증 실패");
                }
            }

        } catch (Exception e) {
            logger.error("제약된 균형 경유지 생성 오류: " + e.getMessage(), e);
        }

        return waypoints;
    }

    /**
     * 목적지 방향을 고려하여 경유지 방향 제약
     */
    private double constrainDirectionToDestination(double preferredDirection, double destinationDirection) {
        try {
            // 목적지 방향 ±90도 범위 내에서만 경유지 설정 허용
            double maxAngleDiff = 90.0;

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
            double distanceToWaypoint = calculateDistance(
                    start.getLat(), start.getLng(),
                    waypoint.getLat(), waypoint.getLng());

            double directDistance = calculateDistance(
                    start.getLat(), start.getLng(),
                    end.getLat(), end.getLng());

            double waypointToEnd = calculateDistance(
                    waypoint.getLat(), waypoint.getLng(),
                    end.getLat(), end.getLng());

            // 경유지를 거친 총 거리가 직선 거리의 130% 이하여야 함
            double totalViaWaypoint = distanceToWaypoint + waypointToEnd;
            double detourRatio = totalViaWaypoint / directDistance;
            if (detourRatio > 1.3) {
                logger.debug("경유지 우회 비율 과다: {}% > 130%", (int)(detourRatio * 100));
                return false;
            }

            // 경유지가 출발지보다 목적지에 더 가까워야 함
            if (waypointToEnd >= directDistance * 0.85) { // 85% 이상 가까워져야 함
                logger.debug("경유지가 목적지 접근 부족: 경유지→목적지={}m, 직선거리={}m ({}%)",
                        (int)waypointToEnd, (int)directDistance, (int)(waypointToEnd/directDistance*100));
                return false;
            }

            return true;

        } catch (Exception e) {
            logger.error("경유지 진행성 검증 오류: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 그림자 영역들의 중심점 계산
     */
    private List<RoutePoint> calculateShadowCenters(List<ShadowArea> shadowAreas) {
        List<RoutePoint> centers = new ArrayList<>();

        // 실제 구현에서는 GeoJSON 파싱하여 중심점 계산
        // 여기서는 간단한 예시만 제공
        for (ShadowArea area : shadowAreas) {
            try {
                // shadowGeometry에서 중심점 추출 (실제로는 GeoJSON 파싱 필요)
                // 임시로 랜덤 포인트 생성
                RoutePoint center = new RoutePoint(37.5665 + Math.random() * 0.01, 126.978 + Math.random() * 0.01);
                centers.add(center);
            } catch (Exception e) {
                logger.warn("그림자 중심점 계산 실패: " + e.getMessage());
            }
        }

        return centers;
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
     * 강제 차별화 (경로들이 너무 비슷하면 추가 다양성 확보)
     */
    private List<Route> ensureRouteDiversification(List<Route> routes, double startLat, double startLng,
                                                   double endLat, double endLng, LocalDateTime dateTime) {
        try {
            if (routes.size() < 2) return routes;

            // 경로 간 유사도 체크
            List<Route> diversifiedRoutes = new ArrayList<>();
            diversifiedRoutes.add(routes.get(0)); // 첫 번째는 항상 포함

            for (int i = 1; i < routes.size(); i++) {
                Route candidate = routes.get(i);
                boolean isTooSimilar = false;

                for (Route existing : diversifiedRoutes) {
                    double similarity = calculateRouteSimilarity(candidate, existing);
                    if (similarity > 0.8) { // 80% 이상 유사하면 너무 비슷함
                        isTooSimilar = true;
                        logger.debug("유사도 {}%로 경로 제외: {} vs {}",
                                (int)(similarity * 100), candidate.getRouteType(), existing.getRouteType());
                        break;
                    }
                }

                if (!isTooSimilar) {
                    diversifiedRoutes.add(candidate);
                }
            }

            // 다양성이 부족하면 추가 경로 생성
            while (diversifiedRoutes.size() < 3) {
                Route additionalRoute = generateAdditionalVariation(diversifiedRoutes,
                        startLat, startLng, endLat, endLng, diversifiedRoutes.size());
                if (additionalRoute != null) {
                    diversifiedRoutes.add(additionalRoute);
                } else {
                    break; // 더 이상 생성할 수 없으면 중단
                }
            }

            logger.info("강제 차별화 완료: {}개 → {}개 경로", routes.size(), diversifiedRoutes.size());
            return diversifiedRoutes;

        } catch (Exception e) {
            logger.error("강제 차별화 오류: " + e.getMessage(), e);
            return routes; // 오류 시 원본 반환
        }
    }

    /**
     * 추가 변형 경로 생성
     */
    private Route generateAdditionalVariation(List<Route> existingRoutes, double startLat, double startLng,
                                              double endLat, double endLng, int variationIndex) {
        try {
            // 변형 인덱스에 따른 고유 시드
            long variationSeed = generateConsistentSeed(startLat, startLng, endLat, endLng) +
                    (variationIndex + 1) * 1000;
            Random random = new Random(variationSeed);

            // 변형 강도를 인덱스에 따라 조정
            double offsetMultiplier = (variationIndex + 1) * 0.0004; // 40m, 80m, 120m...

            List<RoutePoint> waypoints = new ArrayList<>();

            // 2개 경유지 생성 (더 확실한 차별화)
            for (int i = 1; i <= 2; i++) {
                double ratio = (double) i / 3; // 1/3, 2/3 지점
                double waypointLat = startLat + (endLat - startLat) * ratio;
                double waypointLng = startLng + (endLng - startLng) * ratio;

                // 랜덤 오프셋 적용
                double offsetDirection = random.nextDouble() * 2 * Math.PI;
                waypointLat += Math.cos(offsetDirection) * offsetMultiplier;
                waypointLng += Math.sin(offsetDirection) * offsetMultiplier;

                waypoints.add(new RoutePoint(waypointLat, waypointLng));
            }

            String routeJson = tmapApiService.getWalkingRouteWithMultiWaypoints(
                    startLat, startLng, waypoints, endLat, endLng);

            Route variation = shadowRouteService.parseBasicRoute(routeJson);
            variation.setRouteType("variation_" + variationIndex);
            variation.setWaypointCount(waypoints.size());

            logger.debug("추가 변형 경로 {}번 생성: {}m", variationIndex, (int)variation.getDistance());
            return variation;

        } catch (Exception e) {
            logger.error("추가 변형 경로 생성 실패: " + e.getMessage(), e);
            return null;
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
     * 그림자 점수 계산
     */
    private void calculateShadowScores(List<Route> routes, double lat, double lng, LocalDateTime dateTime) {
        try {
            logger.info("실제 DB 기반 그늘 점수 계산 시작");

            SunPosition sunPos = shadowService.calculateSunPosition(lat, lng, dateTime);
            logger.debug("태양 위치: 고도={}도, 방위각={}도", sunPos.getAltitude(), sunPos.getAzimuth());

            for (Route route : routes) {
                try {
                    List<ShadowArea> shadowAreas = shadowRouteService.calculateBuildingShadows(
                            lat, lng, lat, lng, sunPos);

                    shadowRouteService.applyShadowInfoFromDB(route, shadowAreas);

                    logger.debug("경로 {}: 실제 DB 기반 그늘 {}%",
                            route.getRouteType(), route.getShadowPercentage());

                } catch (Exception e) {
                    logger.warn("개별 경로 그늘 점수 계산 오류: " + e.getMessage());
                    route.setShadowPercentage(0);
                }
            }

            logger.info("실제 DB 기반 그늘 점수 계산 완료");

        } catch (Exception e) {
            logger.error("그늘 점수 계산 오류: " + e.getMessage(), e);
            for (Route route : routes) {
                route.setShadowPercentage(0);
            }
        }
    }

    /**
     * 경로 유사도 계산
     */
    private double calculateRouteSimilarity(Route route1, Route route2) {
        try {
            List<RoutePoint> points1 = route1.getPoints();
            List<RoutePoint> points2 = route2.getPoints();

            if (points1.isEmpty() || points2.isEmpty()) {
                return 0.0;
            }

            // 거리 차이 비교
            double distanceDiff = Math.abs(route1.getDistance() - route2.getDistance());
            double maxDistance = Math.max(route1.getDistance(), route2.getDistance());
            double distanceSimilarity = maxDistance > 0 ?
                    1.0 - Math.min(1.0, distanceDiff / maxDistance) : 1.0;

            // 경로 포인트 근접도 비교
            int matchCount = 0;
            int totalSamples = Math.min(10, Math.min(points1.size(), points2.size()));

            if (totalSamples > 0) {
                for (int i = 0; i < totalSamples; i++) {
                    int idx1 = (i * (points1.size() - 1)) / Math.max(1, totalSamples - 1);
                    int idx2 = (i * (points2.size() - 1)) / Math.max(1, totalSamples - 1);

                    idx1 = Math.min(idx1, points1.size() - 1);
                    idx2 = Math.min(idx2, points2.size() - 1);

                    RoutePoint p1 = points1.get(idx1);
                    RoutePoint p2 = points2.get(idx2);

                    double distance = calculateDistance(p1.getLat(), p1.getLng(), p2.getLat(), p2.getLng());

                    if (distance < 50) {
                        matchCount++;
                    }
                }
            }

            double pointSimilarity = totalSamples > 0 ? (double) matchCount / totalSamples : 0.0;

            // 시작점과 끝점 근접도 확인
            double startEndSimilarity = 1.0;
            if (points1.size() > 0 && points2.size() > 0) {
                RoutePoint start1 = points1.get(0);
                RoutePoint end1 = points1.get(points1.size() - 1);
                RoutePoint start2 = points2.get(0);
                RoutePoint end2 = points2.get(points2.size() - 1);

                double startDistance = calculateDistance(start1.getLat(), start1.getLng(), start2.getLat(), start2.getLng());
                double endDistance = calculateDistance(end1.getLat(), end1.getLng(), end2.getLat(), end2.getLng());

                if (startDistance > 100 || endDistance > 100) {
                    startEndSimilarity = 0.5;
                }
            }

            double finalSimilarity = (distanceSimilarity * 0.3 + pointSimilarity * 0.7) * startEndSimilarity;

            return finalSimilarity;

        } catch (Exception e) {
            logger.warn("경로 유사도 계산 오류: " + e.getMessage());
            return 0.0;
        }
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
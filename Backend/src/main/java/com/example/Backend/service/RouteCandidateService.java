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
            logger.info("=== 개선된 경로 후보 생성 시작 ===");
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
     * 타입별 후보 생성 (기존 선정 방식을 대체)
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
                logger.info("최단경로 후보: {}km, {}분",
                        shortestRoute.getDistance() / 1000.0, shortestRoute.getDuration());
            }

            // 2. 그림자 많은 경로 후보
            Route shadeRoute = findRouteByType(routes, "shade");
            if (shadeRoute != null) {
                RouteCandidate shade = new RouteCandidate("shade", "그늘이 많은경로", shadeRoute);
                candidates.add(shade);
                logger.info("그림자 경로 후보: {}km, {}분, 그늘 {}%",
                        shadeRoute.getDistance() / 1000.0, shadeRoute.getDuration(), shadeRoute.getShadowPercentage());
            }

            // 3. 균형 경로 후보
            Route balancedRoute = findRouteByType(routes, "balanced");
            if (balancedRoute != null) {
                RouteCandidate balanced = new RouteCandidate("balanced", "균형경로", balancedRoute);
                candidates.add(balanced);
                logger.info("균형 경로 후보: {}km, {}분, 그늘 {}%",
                        balancedRoute.getDistance() / 1000.0, balancedRoute.getDuration(), balancedRoute.getShadowPercentage());
            }

            // 4. 부족한 후보는 변형 경로로 채우기
            while (candidates.size() < 3 && routes.size() > candidates.size()) {
                Route alternativeRoute = findAlternativeRouteForCandidates(routes, candidates);
                if (alternativeRoute != null) {
                    String candidateType = "alternate" + (candidates.size() + 1);
                    String displayName = "추천경로 " + (candidates.size() + 1);

                    RouteCandidate alternative = new RouteCandidate(candidateType, displayName, alternativeRoute);
                    candidates.add(alternative);
                    logger.info("추가 경로 후보: {} - {}km", displayName, alternativeRoute.getDistance() / 1000.0);
                } else {
                    break;
                }
            }

            // 5. 여전히 부족하면 최단경로로 채우기
            while (candidates.size() < 3 && shortestRoute != null) {
                String fallbackType = "fallback" + candidates.size();
                String fallbackName = "안전경로 " + candidates.size();

                RouteCandidate fallback = new RouteCandidate(fallbackType, fallbackName, shortestRoute);
                candidates.add(fallback);
                logger.info("폴백 경로 후보: {}", fallbackName);
            }

            logger.info("타입별 후보 생성 완료: {}개", candidates.size());

        } catch (Exception e) {
            logger.error("타입별 후보 생성 오류: " + e.getMessage(), e);

            // 오류 시 최소한의 후보라도 생성
            if (!routes.isEmpty()) {
                Route fallbackRoute = routes.get(0);
                candidates.add(new RouteCandidate("fallback", "기본경로", fallbackRoute));
            }
        }

        return candidates;
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
     * 후보용 대안 경로 찾기
     */
    private Route findAlternativeRouteForCandidates(List<Route> routes, List<RouteCandidate> existingCandidates) {
        // 이미 사용된 경로들 제외
        Set<String> usedTypes = existingCandidates.stream()
                .map(c -> c.getRoute().getRouteType())
                .collect(Collectors.toSet());

        return routes.stream()
                .filter(route -> !usedTypes.contains(route.getRouteType()))
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
                logger.debug("그림자 영역이 없어 기본 경로 사용");
                return generateShortestRoute(startLat, startLng, endLat, endLng);
            }

            // 그림자 영역을 의도적으로 경유하는 경유지 생성
            List<RoutePoint> shadeWaypoints = generateShadeTargetingWaypoints(
                    startLat, startLng, endLat, endLng, shadowAreas, sunPos);

            if (shadeWaypoints.isEmpty()) {
                logger.debug("그림자 경유지 생성 실패, 기본 경로 사용");
                return generateShortestRoute(startLat, startLng, endLat, endLng);
            }

            // 그림자 경유지를 포함한 경로 요청
            String routeJson = tmapApiService.getWalkingRouteWithMultiWaypoints(
                    startLat, startLng, shadeWaypoints, endLat, endLng);

            Route route = shadowRouteService.parseBasicRoute(routeJson);
            route.setRouteType("shade");
            route.setWaypointCount(shadeWaypoints.size());

            // 그림자 정보 적용
            shadowRouteService.applyShadowInfoFromDB(route, shadowAreas);

            return route;

        } catch (Exception e) {
            logger.error("그림자 경로 생성 실패: " + e.getMessage(), e);
            return generateShortestRoute(startLat, startLng, endLat, endLng);
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

            // 경로 상에서 그림자 영역에 가장 가까운 지점들을 경유지로 선택
            RoutePoint midPoint = new RoutePoint((startLat + endLat) / 2, (startLng + endLng) / 2);

            // 중간지점에서 가장 가까운 그림자 영역 찾기
            RoutePoint closestShadowCenter = findClosestShadowCenter(midPoint, shadowCenters);

            if (closestShadowCenter != null) {
                // 그림자 중심으로 조금 더 가까이 이동한 경유지 생성
                double shadowDirection = Math.atan2(
                        closestShadowCenter.getLng() - midPoint.getLng(),
                        closestShadowCenter.getLat() - midPoint.getLat()
                );

                // 그림자 쪽으로 100m 정도 이동한 지점
                double waypointLat = midPoint.getLat() + Math.cos(shadowDirection) * 0.0009; // 약 100m
                double waypointLng = midPoint.getLng() + Math.sin(shadowDirection) * 0.0009;

                waypoints.add(new RoutePoint(waypointLat, waypointLng));

                logger.debug("그림자 타겟 경유지 생성: ({}, {}) → 그림자 중심까지 {}m",
                        waypointLat, waypointLng,
                        calculateDistance(waypointLat, waypointLng,
                                closestShadowCenter.getLat(), closestShadowCenter.getLng()));
            }

        } catch (Exception e) {
            logger.error("그림자 타겟 경유지 생성 오류: " + e.getMessage(), e);
        }

        return waypoints;
    }

    /**
     * 균형잡힌 경유지 생성 (그림자도 고려하되 거리도 중요시)
     */
    private List<RoutePoint> generateBalancedWaypoints(Route baseRoute, double startLat, double startLng,
                                                       double endLat, double endLng, SunPosition sunPos) {
        List<RoutePoint> waypoints = new ArrayList<>();

        try {
            List<RoutePoint> basePoints = baseRoute.getPoints();
            if (basePoints.size() < 10) return waypoints;

            // 균형을 위한 고정 시드 (타입별로 다르게)
            long balancedSeed = generateConsistentSeed(startLat, startLng, endLat, endLng) + 2000;
            Random random = new Random(balancedSeed);

            // 경로의 1/3, 2/3 지점에서 적당한 우회
            int oneThirdIndex = basePoints.size() / 3;
            int twoThirdIndex = (basePoints.size() * 2) / 3;

            for (int index : Arrays.asList(oneThirdIndex, twoThirdIndex)) {
                RoutePoint basePoint = basePoints.get(index);

                // 적당한 오프셋 (50-100m 범위)
                double offsetDistance = 0.0005 + random.nextDouble() * 0.0005; // 50-100m
                double offsetDirection = random.nextDouble() * 2 * Math.PI;

                double waypointLat = basePoint.getLat() + Math.cos(offsetDirection) * offsetDistance;
                double waypointLng = basePoint.getLng() + Math.sin(offsetDirection) * offsetDistance;

                waypoints.add(new RoutePoint(waypointLat, waypointLng));

                logger.debug("균형 경유지 생성: ({}, {}) - 기준점에서 {}m 이격",
                        waypointLat, waypointLng, offsetDistance * 111000);
            }

        } catch (Exception e) {
            logger.error("균형 경유지 생성 오류: " + e.getMessage(), e);
        }

        return waypoints;
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
     * 변형 경로 유효성 검증
     */
    private boolean isValidVariant(Route baseRoute, Route variant) {
        try {
            // 1. 거리 검증 (기본 경로 대비 200% 이하)
            double distanceRatio = variant.getDistance() / baseRoute.getDistance();
            if (distanceRatio > 2.0) {
                logger.debug("변형 경로 거리 초과: {}% (기준: 200%)", (int)(distanceRatio * 100));
                return false;
            }

            // 2. 포인트 수 검증 (기본 경로 대비 30% 이상)
            if (variant.getPoints().size() < baseRoute.getPoints().size() * 0.3) {
                logger.debug("변형 경로 포인트 수 부족");
                return false;
            }

            // 3. 방향성 검증 (역방향 이동 체크)
            if (!isForwardProgression(variant)) {
                logger.debug("변형 경로 역방향 이동 감지");
                return false;
            }

            logger.debug("변형 경로 유효성 검증 통과: 거리 비율 {}%", (int)(distanceRatio * 100));
            return true;

        } catch (Exception e) {
            logger.error("변형 경로 유효성 검증 오류: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 경로가 전진하는지 확인 (역방향 이동 방지)
     */
    private boolean isForwardProgression(Route route) {
        try {
            List<RoutePoint> points = route.getPoints();
            if (points.size() < 3) return true;

            RoutePoint start = points.get(0);
            RoutePoint end = points.get(points.size() - 1);

            // 목적지로의 대략적인 방향 계산
            double targetDirection = Math.atan2(
                    end.getLng() - start.getLng(),
                    end.getLat() - start.getLat()
            );

            // 경로의 중간 지점들이 대체로 목적지 방향으로 향하는지 확인
            int forwardCount = 0;
            int totalSegments = 0;

            for (int i = 0; i < points.size() - 5; i += 5) { // 5개씩 건너뛰며 샘플링
                RoutePoint current = points.get(i);
                RoutePoint next = points.get(i + 5);

                double segmentDirection = Math.atan2(
                        next.getLng() - current.getLng(),
                        next.getLat() - current.getLat()
                );

                double directionDiff = Math.abs(segmentDirection - targetDirection);
                if (directionDiff > Math.PI) {
                    directionDiff = 2 * Math.PI - directionDiff;
                }

                // 90도 이내면 전진으로 간주
                if (directionDiff <= Math.PI / 2) {
                    forwardCount++;
                }
                totalSegments++;
            }

            // 70% 이상의 구간이 전진 방향이면 유효
            double forwardRatio = totalSegments > 0 ? (double) forwardCount / totalSegments : 1.0;
            boolean isValid = forwardRatio >= 0.7;

            logger.debug("전진 비율: {}% (기준: 70%)", (int)(forwardRatio * 100));
            return isValid;

        } catch (Exception e) {
            logger.error("전진성 검증 오류: " + e.getMessage(), e);
            return true; // 오류 시 허용
        }
    }

    /**
     * 경로 필터링 및 품질 검증
     */
    private List<Route> filterAndValidateRoutes(List<Route> routes, double startLat, double startLng,
                                                double endLat, double endLng) {
        List<Route> validRoutes = new ArrayList<>();

        try {
            // 기본 경로는 항상 포함
            Route baseRoute = routes.stream()
                    .filter(r -> "shortest".equals(r.getRouteType()))
                    .findFirst()
                    .orElse(null);

            if (baseRoute != null) {
                validRoutes.add(baseRoute);
                logger.debug("기본 경로 포함: 거리={}m", (int)baseRoute.getDistance());
            }

            // 변형 경로들 검증 및 중복 제거
            for (Route route : routes) {
                if ("shortest".equals(route.getRouteType())) continue;

                // 품질 검증
                if (baseRoute != null && !isValidVariant(baseRoute, route)) {
                    logger.debug("품질 검증 실패로 경로 제외: {}", route.getRouteType());
                    continue;
                }

                // 중복 검증
                boolean isDuplicate = false;
                for (Route existing : validRoutes) {
                    if (calculateRouteSimilarity(route, existing) > 0.85) {
                        isDuplicate = true;
                        logger.debug("중복 경로로 제외: {} vs {}", route.getRouteType(), existing.getRouteType());
                        break;
                    }
                }

                if (!isDuplicate) {
                    validRoutes.add(route);
                    logger.debug("유효 경로 추가: {}, 거리={}m", route.getRouteType(), (int)route.getDistance());
                }
            }

            logger.info("경로 필터링 완료: {}개 → {}개", routes.size(), validRoutes.size());

        } catch (Exception e) {
            logger.error("경로 필터링 오류: " + e.getMessage(), e);
            // 오류 시 최소한 첫 번째 경로라도 반환
            if (!routes.isEmpty()) {
                validRoutes.add(routes.get(0));
            }
        }

        return validRoutes;
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
     * 3개 후보 선정
     */
    private List<RouteCandidate> selectTopThreeCandidates(List<Route> routes) {
        if (routes.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            logger.info("3개 후보 경로 선정 시작");

            Route shortestRoute = routes.stream()
                    .min(Comparator.comparing(Route::getDistance))
                    .orElse(routes.get(0));

            Route shadeRoute = routes.stream()
                    .max(Comparator.comparing(Route::getShadowPercentage))
                    .orElse(routes.get(0));

            Route balancedRoute = routes.stream()
                    .min(Comparator.comparing(route -> calculateBalanceScore(route, routes)))
                    .orElse(routes.get(0));

            List<Route> selectedRoutes = Arrays.asList(shortestRoute, shadeRoute, balancedRoute);
            List<Route> finalRoutes = ensureUniqueSelection(selectedRoutes, routes);

            List<RouteCandidate> candidates = new ArrayList<>();
            candidates.add(new RouteCandidate("shortest", "최단경로", finalRoutes.get(0)));
            candidates.add(new RouteCandidate("shade", "그늘이 많은경로", finalRoutes.get(1)));
            candidates.add(new RouteCandidate("balanced", "균형경로", finalRoutes.get(2)));

            logger.info("3개 후보 선정 완료:");
            for (RouteCandidate candidate : candidates) {
                logger.info("  {}: {}km, {}분, 그늘 {}%",
                        candidate.getDisplayName(),
                        candidate.getRoute().getDistance() / 1000.0,
                        candidate.getRoute().getDuration(),
                        candidate.getRoute().getShadowPercentage());
            }

            return candidates;

        } catch (Exception e) {
            logger.error("후보 선정 오류: " + e.getMessage(), e);
            Route fallbackRoute = routes.get(0);
            return Arrays.asList(
                    new RouteCandidate("shortest", "추천경로 1", fallbackRoute),
                    new RouteCandidate("shade", "추천경로 2", fallbackRoute),
                    new RouteCandidate("balanced", "추천경로 3", fallbackRoute)
            );
        }
    }

    /**
     * 균형 점수 계산
     */
    private double calculateBalanceScore(Route route, List<Route> allRoutes) {
        try {
            double minDistance = allRoutes.stream().mapToDouble(Route::getDistance).min().orElse(1.0);
            double maxDistance = allRoutes.stream().mapToDouble(Route::getDistance).max().orElse(1.0);
            double distanceRange = maxDistance - minDistance;

            double normalizedDistance = distanceRange > 0 ?
                    (route.getDistance() - minDistance) / distanceRange : 0;

            double normalizedShade = route.getShadowPercentage() / 100.0;

            double shadeScore;
            if (normalizedShade < 0.3) {
                shadeScore = 0.3 - normalizedShade;
            } else if (normalizedShade > 0.7) {
                shadeScore = normalizedShade - 0.7;
            } else {
                shadeScore = 0;
            }

            return normalizedDistance * 0.6 + shadeScore * 0.4;

        } catch (Exception e) {
            logger.warn("균형 점수 계산 오류: " + e.getMessage());
            return 0.5;
        }
    }

    /**
     * 고유한 경로 선택 보장
     */
    private List<Route> ensureUniqueSelection(List<Route> selectedRoutes, List<Route> allRoutes) {
        List<Route> uniqueRoutes = new ArrayList<>();

        for (Route selected : selectedRoutes) {
            boolean isAlreadySelected = false;

            for (Route existing : uniqueRoutes) {
                if (calculateRouteSimilarity(selected, existing) > 0.9) {
                    isAlreadySelected = true;
                    break;
                }
            }

            if (!isAlreadySelected) {
                uniqueRoutes.add(selected);
            } else {
                Route alternative = findAlternativeRoute(uniqueRoutes, allRoutes);
                uniqueRoutes.add(alternative);
            }
        }

        while (uniqueRoutes.size() < 3 && uniqueRoutes.size() < allRoutes.size()) {
            for (Route route : allRoutes) {
                if (uniqueRoutes.size() >= 3) break;

                boolean isUnique = true;
                for (Route existing : uniqueRoutes) {
                    if (calculateRouteSimilarity(route, existing) > 0.9) {
                        isUnique = false;
                        break;
                    }
                }

                if (isUnique) {
                    uniqueRoutes.add(route);
                }
            }
            break;
        }

        return uniqueRoutes;
    }

    /**
     * 대안 경로 찾기
     */
    private Route findAlternativeRoute(List<Route> selectedRoutes, List<Route> allRoutes) {
        for (Route candidate : allRoutes) {
            boolean isUnique = true;

            for (Route selected : selectedRoutes) {
                if (calculateRouteSimilarity(candidate, selected) > 0.9) {
                    isUnique = false;
                    break;
                }
            }

            if (isUnique) {
                return candidate;
            }
        }

        return allRoutes.get(0);
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

            RouteCandidate shortest = new RouteCandidate("shortest", "최단경로", shortestRoute);
            RouteCandidate alternate1 = new RouteCandidate("alternate1", "추천경로", shortestRoute);
            RouteCandidate alternate2 = new RouteCandidate("alternate2", "안전경로", shortestRoute);

            return Arrays.asList(shortest, alternate1, alternate2);

        } catch (Exception e) {
            logger.error("최단경로 생성 오류: " + e.getMessage(), e);
            return new ArrayList<>();
        }
    }
}
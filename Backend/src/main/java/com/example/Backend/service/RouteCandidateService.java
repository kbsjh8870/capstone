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
            List<Route> allRoutes = generateImprovedMultipleRoutes(startLat, startLng, endLat, endLng);

            // 중복 제거 및 품질 검증
            List<Route> validRoutes = filterAndValidateRoutes(allRoutes, startLat, startLng, endLat, endLng);
            logger.info("품질 검증 후 경로 수: {}", validRoutes.size());

            // 사용자가 선택한 시간의 태양 위치로 그림자 점수 계산
            calculateShadowScores(validRoutes, startLat, startLng, dateTime);

            // 3개 후보 선정
            List<RouteCandidate> candidates = selectTopThreeCandidates(validRoutes);

            logger.info("최종 후보 경로 3개 생성 완료 (선택시간 {}시 기준)", dateTime.getHour());
            return candidates;

        } catch (Exception e) {
            logger.error("경로 후보 생성 오류: " + e.getMessage(), e);
            return generateShortestRouteOnly(startLat, startLng, endLat, endLng, dateTime);
        }
    }

    /**
     * 다중 경로 생성 (기본 경로 기반)
     */
    private List<Route> generateImprovedMultipleRoutes(double startLat, double startLng, double endLat, double endLng) {
        List<Route> routes = new ArrayList<>();

        try {
            // 1. 기본 최단경로 먼저 생성
            logger.info("기본 최단경로 생성 중...");
            String shortestJson = tmapApiService.getWalkingRoute(startLat, startLng, endLat, endLng);
            Route baseRoute = shadowRouteService.parseBasicRoute(shortestJson);
            baseRoute.setRouteType("shortest");
            routes.add(baseRoute);

            // 2. 기본 경로를 기반으로 한 변형 경로들 생성
            logger.info("기본 경로 기반 변형 경로 생성 중...");
            List<Route> variantRoutes = generateRouteVariants(baseRoute, startLat, startLng, endLat, endLng);
            routes.addAll(variantRoutes);

            logger.info("총 {}개 경로 생성 완료", routes.size());

        } catch (Exception e) {
            logger.error("개선된 다중 경로 생성 오류: " + e.getMessage(), e);
        }

        return routes;
    }

    /**
     * 기본 경로를 기반으로 한 변형 경로 생성
     */
    private List<Route> generateRouteVariants(Route baseRoute, double startLat, double startLng, double endLat, double endLng) {
        List<Route> variants = new ArrayList<>();

        try {
            List<RoutePoint> basePoints = baseRoute.getPoints();
            if (basePoints.size() < 10) {
                logger.warn("기본 경로가 너무 짧아 변형 경로 생성 제한");
                return variants;
            }

            // 시드 생성 (출발지/도착지 좌표 기반으로 일관성 확보)
            long seed = generateConsistentSeed(startLat, startLng, endLat, endLng);
            Random random = new Random(seed);

            // 3가지 유형의 변형 경로 생성
            for (int variantType = 1; variantType <= 3; variantType++) {
                Route variant = generateSingleVariant(baseRoute, startLat, startLng, endLat, endLng,
                        variantType, random);
                if (variant != null) {
                    variants.add(variant);
                }
            }

            logger.info("{}개 변형 경로 생성 완료", variants.size());

        } catch (Exception e) {
            logger.error("변형 경로 생성 오류: " + e.getMessage(), e);
        }

        return variants;
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
     * 단일 변형 경로 생성
     */
    private Route generateSingleVariant(Route baseRoute, double startLat, double startLng,
                                        double endLat, double endLng, int variantType, Random random) {
        try {
            List<RoutePoint> smartWaypoints = generateSmartWaypoints(baseRoute, variantType, random);

            if (smartWaypoints.isEmpty()) {
                logger.warn("변형 타입 {}에 대한 스마트 경유지 생성 실패", variantType);
                return null;
            }

            String routeJson = tmapApiService.getWalkingRouteWithMultiWaypoints(
                    startLat, startLng, smartWaypoints, endLat, endLng);

            Route variant = shadowRouteService.parseBasicRoute(routeJson);
            variant.setRouteType("variant_" + variantType);
            variant.setWaypointCount(smartWaypoints.size());

            // 경로 품질 검증
            if (isValidVariant(baseRoute, variant)) {
                logger.debug("변형 경로 {}번 생성 성공", variantType);
                return variant;
            } else {
                logger.warn("변형 경로 {}번 품질 검증 실패", variantType);
                return null;
            }

        } catch (Exception e) {
            logger.warn("변형 경로 {}번 생성 실패: {}", variantType, e.getMessage());
            return null;
        }
    }

    /**
     * 스마트 경유지 생성 (기본 경로 기반)
     */
    private List<RoutePoint> generateSmartWaypoints(Route baseRoute, int variantType, Random random) {
        List<RoutePoint> waypoints = new ArrayList<>();
        List<RoutePoint> basePoints = baseRoute.getPoints();

        try {
            // 변형 타입별 다른 전략 적용
            switch (variantType) {
                case 1: // 경로 중간 지점에서 작은 우회
                    waypoints.addAll(generateMidRouteWaypoints(basePoints, random, 0.0005)); // 약 50m 오프셋
                    break;
                case 2: // 경로 1/3, 2/3 지점에서 중간 우회
                    waypoints.addAll(generateSegmentWaypoints(basePoints, random, 0.0008)); // 약 80m 오프셋
                    break;
                case 3: // 다중 소규모 우회
                    waypoints.addAll(generateMultipleSmallWaypoints(basePoints, random, 0.0003)); // 약 30m 오프셋
                    break;
            }

            logger.debug("변형 타입 {}에 대해 {}개 스마트 경유지 생성", variantType, waypoints.size());

        } catch (Exception e) {
            logger.error("스마트 경유지 생성 오류: " + e.getMessage(), e);
        }

        return waypoints;
    }

    /**
     * 경로 중간 지점 기반 경유지 생성
     */
    private List<RoutePoint> generateMidRouteWaypoints(List<RoutePoint> basePoints, Random random, double offsetRange) {
        List<RoutePoint> waypoints = new ArrayList<>();

        if (basePoints.size() >= 10) {
            int midIndex = basePoints.size() / 2;
            RoutePoint midPoint = basePoints.get(midIndex);

            // 작은 오프셋으로 경유지 생성
            double offsetLat = (random.nextDouble() - 0.5) * offsetRange;
            double offsetLng = (random.nextDouble() - 0.5) * offsetRange;

            RoutePoint waypoint = new RoutePoint(
                    midPoint.getLat() + offsetLat,
                    midPoint.getLng() + offsetLng
            );

            waypoints.add(waypoint);
            logger.debug("중간 경유지 생성: ({}, {})", waypoint.getLat(), waypoint.getLng());
        }

        return waypoints;
    }

    /**
     * 구간별 경유지 생성
     */
    private List<RoutePoint> generateSegmentWaypoints(List<RoutePoint> basePoints, Random random, double offsetRange) {
        List<RoutePoint> waypoints = new ArrayList<>();

        if (basePoints.size() >= 15) {
            // 1/3 지점
            int oneThirdIndex = basePoints.size() / 3;
            RoutePoint oneThirdPoint = basePoints.get(oneThirdIndex);

            // 2/3 지점
            int twoThirdIndex = (basePoints.size() * 2) / 3;
            RoutePoint twoThirdPoint = basePoints.get(twoThirdIndex);

            // 각 지점에서 오프셋 적용
            for (RoutePoint basePoint : Arrays.asList(oneThirdPoint, twoThirdPoint)) {
                double offsetLat = (random.nextDouble() - 0.5) * offsetRange;
                double offsetLng = (random.nextDouble() - 0.5) * offsetRange;

                RoutePoint waypoint = new RoutePoint(
                        basePoint.getLat() + offsetLat,
                        basePoint.getLng() + offsetLng
                );

                waypoints.add(waypoint);
                logger.debug("구간 경유지 생성: ({}, {})", waypoint.getLat(), waypoint.getLng());
            }
        }

        return waypoints;
    }

    /**
     * 다중 소규모 경유지 생성
     */
    private List<RoutePoint> generateMultipleSmallWaypoints(List<RoutePoint> basePoints, Random random, double offsetRange) {
        List<RoutePoint> waypoints = new ArrayList<>();

        if (basePoints.size() >= 20) {
            // 경로를 4등분하여 3개 경유지 생성
            for (int i = 1; i <= 3; i++) {
                int index = (basePoints.size() * i) / 4;
                RoutePoint basePoint = basePoints.get(index);

                double offsetLat = (random.nextDouble() - 0.5) * offsetRange;
                double offsetLng = (random.nextDouble() - 0.5) * offsetRange;

                RoutePoint waypoint = new RoutePoint(
                        basePoint.getLat() + offsetLat,
                        basePoint.getLng() + offsetLng
                );

                waypoints.add(waypoint);
                logger.debug("소규모 경유지 {}번 생성: ({}, {})", i, waypoint.getLat(), waypoint.getLng());
            }
        }

        return waypoints;
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
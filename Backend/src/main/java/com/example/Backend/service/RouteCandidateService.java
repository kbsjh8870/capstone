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
            logger.info("=== 경로 후보 생성 시작 ===");
            logger.info("출발: ({}, {}), 도착: ({}, {}), 사용자 선택 시간: {}",
                    startLat, startLng, endLat, endLng, dateTime);

            // 1. 사용자가 선택한 시간 기준으로 조건 확인
            boolean isNight = isNightTime(dateTime);
            boolean isBadWeather = weatherService.isBadWeather(startLat, startLng);

            logger.info("시간 분석 - 선택시간: {}, 밤시간: {}, 나쁜날씨: {}",
                    dateTime.getHour() + "시", isNight, isBadWeather);

            // 2. 밤이거나 날씨가 나쁘면 안전한 최단경로만 제공
            if (isNight || isBadWeather) {
                String reason = isNight ? "밤 시간 (22시~6시)" : "나쁜 날씨";
                logger.info("{}로 인해 안전한 최단경로만 생성", reason);
                return generateShortestRouteOnly(startLat, startLng, endLat, endLng, dateTime);
            }

            // 3. 낮 시간 + 좋은 날씨 = 다양한 경로 생성
            logger.info("낮 시간 + 좋은 날씨 → 다양한 경로 생성 (선택시간: {}시)", dateTime.getHour());

            // 다중 경로 생성
            List<Route> allRoutes = generateMultipleRoutes(startLat, startLng, endLat, endLng);

            // 중복 제거
            List<Route> uniqueRoutes = removeDuplicateRoutes(allRoutes);
            logger.info("중복 제거 후 경로 수: {}", uniqueRoutes.size());

            // 사용자가 선택한 시간의 태양 위치로 그림자 점수 계산
            calculateShadowScores(uniqueRoutes, startLat, startLng, dateTime);

            // 3개 후보 선정
            List<RouteCandidate> candidates = selectTopThreeCandidates(uniqueRoutes);

            logger.info("최종 후보 경로 3개 생성 완료 (선택시간 {}시 기준)", dateTime.getHour());
            return candidates;

        } catch (Exception e) {
            logger.error("경로 후보 생성 오류: " + e.getMessage(), e);
            return generateShortestRouteOnly(startLat, startLng, endLat, endLng, dateTime);
        }
    }

    private void calculateShadowScores(List<Route> routes, double lat, double lng, LocalDateTime dateTime) {
        try {
            logger.info("실제 DB 기반 그늘 점수 계산 시작");

            // 태양 위치 계산
            SunPosition sunPos = shadowService.calculateSunPosition(lat, lng, dateTime);
            logger.debug("태양 위치: 고도={}도, 방위각={}도", sunPos.getAltitude(), sunPos.getAzimuth());

            for (Route route : routes) {
                try {
                    // 기존 ShadowRouteService의 완벽한 로직 활용
                    List<ShadowArea> shadowAreas = shadowRouteService.calculateBuildingShadows(
                            lat, lng, lat, lng, sunPos);

                    // 실제 DB 건물 데이터로 그림자 정보 적용
                    shadowRouteService.applyShadowInfoFromDB(route, shadowAreas);

                    logger.debug("경로 {}: 실제 DB 기반 그늘 {}%",
                            route.getRouteType(), route.getShadowPercentage());

                } catch (Exception e) {
                    logger.warn("개별 경로 그늘 점수 계산 오류: " + e.getMessage());
                    route.setShadowPercentage(0); // 기본값
                }
            }

            logger.info("실제 DB 기반 그늘 점수 계산 완료");

        } catch (Exception e) {
            logger.error("그늘 점수 계산 오류: " + e.getMessage(), e);
            // 오류 시 기본값 0으로 설정
            for (Route route : routes) {
                route.setShadowPercentage(0);
            }
        }
    }

    private List<RouteCandidate> selectTopThreeCandidates(List<Route> routes) {
        if (routes.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            logger.info("3개 후보 경로 선정 시작");

            // 1. 최단경로 선정
            Route shortestRoute = routes.stream()
                    .min(Comparator.comparing(Route::getDistance))
                    .orElse(routes.get(0));

            // 2. 그늘이 가장 많은 경로 선정
            Route shadeRoute = routes.stream()
                    .max(Comparator.comparing(Route::getShadowPercentage))
                    .orElse(routes.get(0));

            // 3. 균형 경로 선정 (거리와 그늘의 가중 점수)
            Route balancedRoute = routes.stream()
                    .min(Comparator.comparing(route -> calculateBalanceScore(route, routes)))
                    .orElse(routes.get(0));

            // 동일한 경로가 선택되지 않도록 보정
            List<Route> selectedRoutes = Arrays.asList(shortestRoute, shadeRoute, balancedRoute);
            List<Route> finalRoutes = ensureUniqueSelection(selectedRoutes, routes);

            // RouteCandidate 객체 생성
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
            // 오류 시 첫 번째 경로만 반환
            Route fallbackRoute = routes.get(0);
            return Arrays.asList(
                    new RouteCandidate("shortest", "추천경로 1", fallbackRoute),
                    new RouteCandidate("shade", "추천경로 2", fallbackRoute),
                    new RouteCandidate("balanced", "추천경로 3", fallbackRoute)
            );
        }
    }

    /**
     * 균형 점수 계산 (거리와 그늘의 가중평균)
     */
    private double calculateBalanceScore(Route route, List<Route> allRoutes) {
        try {
            // 전체 경로 중에서의 상대적 위치 계산
            double minDistance = allRoutes.stream().mapToDouble(Route::getDistance).min().orElse(1.0);
            double maxDistance = allRoutes.stream().mapToDouble(Route::getDistance).max().orElse(1.0);
            double distanceRange = maxDistance - minDistance;

            double normalizedDistance = distanceRange > 0 ?
                    (route.getDistance() - minDistance) / distanceRange : 0;

            double normalizedShade = route.getShadowPercentage() / 100.0;

            // 균형 점수: 거리는 짧을수록, 그늘은 적당할수록 좋음
            // 그늘 30-70% 구간을 선호하도록 점수 조정
            double shadeScore;
            if (normalizedShade < 0.3) {
                shadeScore = 0.3 - normalizedShade; // 그늘이 너무 적으면 페널티
            } else if (normalizedShade > 0.7) {
                shadeScore = normalizedShade - 0.7; // 그늘이 너무 많으면 페널티
            } else {
                shadeScore = 0; // 적당한 그늘은 보너스
            }

            return normalizedDistance * 0.6 + shadeScore * 0.4;

        } catch (Exception e) {
            logger.warn("균형 점수 계산 오류: " + e.getMessage());
            return 0.5; // 기본값
        }
    }

    /**
     * 동일한 경로 선택 방지 (서로 다른 경로 보장)
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
                // 유사한 경로가 이미 선택된 경우 대안 찾기
                Route alternative = findAlternativeRoute(uniqueRoutes, allRoutes);
                uniqueRoutes.add(alternative);
            }
        }

        // 3개 미만인 경우 남은 경로로 채우기
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
            break; // 무한루프 방지
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

        // 대안이 없으면 첫 번째 경로 반환
        return allRoutes.get(0);
    }

    /**
     * 두 지점 간 거리 계산 (Haversine 공식)
     */
    private double calculateDistance(double lat1, double lng1, double lat2, double lng2) {
        final int R = 6371000; // 지구 반지름 (미터)
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

            // 동일한 경로를 3개의 후보로 반환
            RouteCandidate shortest = new RouteCandidate("shortest", "최단경로", shortestRoute);
            RouteCandidate alternate1 = new RouteCandidate("alternate1", "추천경로", shortestRoute);
            RouteCandidate alternate2 = new RouteCandidate("alternate2", "안전경로", shortestRoute);

            return Arrays.asList(shortest, alternate1, alternate2);

        } catch (Exception e) {
            logger.error("최단경로 생성 오류: " + e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * 다중 경로 생성 (최단경로 1개 + 랜덤 경유지 조합 9개)
     */
    private List<Route> generateMultipleRoutes(double startLat, double startLng, double endLat, double endLng) {
        List<Route> routes = new ArrayList<>();

        try {
            // 1. 최단경로 1개
            logger.info("최단경로 생성 중...");
            String shortestJson = tmapApiService.getWalkingRoute(startLat, startLng, endLat, endLng);
            Route shortestRoute = shadowRouteService.parseBasicRoute(shortestJson);
            shortestRoute.setRouteType("shortest");
            routes.add(shortestRoute);

            // 2. 랜덤 경유지 조합들
            logger.info("랜덤 경유지 경로 생성 중...");
            routes.addAll(generateRoutesWithRandomWaypoints(startLat, startLng, endLat, endLng, 1, 3));
            routes.addAll(generateRoutesWithRandomWaypoints(startLat, startLng, endLat, endLng, 2, 3));
            routes.addAll(generateRoutesWithRandomWaypoints(startLat, startLng, endLat, endLng, 3, 3));

            logger.info("총 {}개 경로 생성 완료", routes.size());

        } catch (Exception e) {
            logger.error("다중 경로 생성 오류: " + e.getMessage(), e);
        }

        return routes;
    }

    /**
     * 랜덤 경유지를 통한 경로 생성
     */
    private List<Route> generateRoutesWithRandomWaypoints(
            double startLat, double startLng, double endLat, double endLng,
            int waypointCount, int routeCount) {

        List<Route> routes = new ArrayList<>();

        for (int i = 0; i < routeCount; i++) {
            try {
                List<RoutePoint> waypoints = generateRandomWaypoints(
                        startLat, startLng, endLat, endLng, waypointCount);

                String routeJson = tmapApiService.getWalkingRouteWithMultiWaypoints(
                        startLat, startLng, waypoints, endLat, endLng);

                Route route = shadowRouteService.parseBasicRoute(routeJson);
                route.setRouteType("waypoint_" + waypointCount);
                route.setWaypointCount(waypointCount);
                routes.add(route);

                logger.debug("경유지 {}개 경로 {}번 생성 완료", waypointCount, i + 1);

            } catch (Exception e) {
                logger.warn("경유지 경로 생성 실패 (경유지 {}개, {}번째): {}", waypointCount, i + 1, e.getMessage());
            }
        }

        return routes;
    }

    /**
     * 최단경로 반경 100m 내에서 랜덤 경유지 생성
     */
    private List<RoutePoint> generateRandomWaypoints(
            double startLat, double startLng, double endLat, double endLng, int count) {

        List<RoutePoint> waypoints = new ArrayList<>();
        Random random = new Random();

        for (int i = 0; i < count; i++) {
            // 출발지와 도착지 사이의 중간 지점들 계산
            double ratio = (i + 1.0) / (count + 1.0); // 균등 분할
            double midLat = startLat + (endLat - startLat) * ratio;
            double midLng = startLng + (endLng - startLng) * ratio;

            // 100m 반경 내에서 랜덤 오프셋 (약 0.0009도 = 100m)
            double offsetLat = (random.nextDouble() - 0.5) * 0.0018; // ±100m
            double offsetLng = (random.nextDouble() - 0.5) * 0.0018;

            RoutePoint waypoint = new RoutePoint(midLat + offsetLat, midLng + offsetLng);
            waypoints.add(waypoint);

            logger.debug("경유지 {}번: ({}, {})", i + 1, waypoint.getLat(), waypoint.getLng());
        }

        return waypoints;
    }

    /**
     * 중복 경로 제거 (경로 유사도 기반)
     */
    private List<Route> removeDuplicateRoutes(List<Route> routes) {
        List<Route> uniqueRoutes = new ArrayList<>();

        for (Route route : routes) {
            boolean isDuplicate = false;

            for (Route existingRoute : uniqueRoutes) {
                double similarity = calculateRouteSimilarity(route, existingRoute);
                if (similarity > 0.85) { // 85% 이상 유사하면 중복으로 간주
                    isDuplicate = true;
                    logger.debug("중복 경로 제거: 유사도 {}%", similarity * 100);
                    break;
                }
            }

            if (!isDuplicate) {
                uniqueRoutes.add(route);
            }
        }

        logger.info("중복 제거: {}개 → {}개", routes.size(), uniqueRoutes.size());
        return uniqueRoutes;
    }

    /**
     * 두 경로의 유사도 계산 (0.0 ~ 1.0)
     */
    private double calculateRouteSimilarity(Route route1, Route route2) {
        try {
            List<RoutePoint> points1 = route1.getPoints();
            List<RoutePoint> points2 = route2.getPoints();

            if (points1.isEmpty() || points2.isEmpty()) {
                return 0.0;
            }

            // 1. 거리 차이 비교 (30% 가중치)
            double distanceDiff = Math.abs(route1.getDistance() - route2.getDistance());
            double maxDistance = Math.max(route1.getDistance(), route2.getDistance());
            double distanceSimilarity = maxDistance > 0 ?
                    1.0 - Math.min(1.0, distanceDiff / maxDistance) : 1.0;

            // 2. 경로 포인트 근접도 비교 (70% 가중치)
            int matchCount = 0;
            int totalSamples = Math.min(10, Math.min(points1.size(), points2.size())); // 최대 10개 샘플

            if (totalSamples > 0) {
                for (int i = 0; i < totalSamples; i++) {
                    // 균등 간격으로 샘플링
                    int idx1 = (i * (points1.size() - 1)) / Math.max(1, totalSamples - 1);
                    int idx2 = (i * (points2.size() - 1)) / Math.max(1, totalSamples - 1);

                    // 인덱스 범위 보정
                    idx1 = Math.min(idx1, points1.size() - 1);
                    idx2 = Math.min(idx2, points2.size() - 1);

                    RoutePoint p1 = points1.get(idx1);
                    RoutePoint p2 = points2.get(idx2);

                    double distance = calculateDistance(p1.getLat(), p1.getLng(), p2.getLat(), p2.getLng());

                    // 50m 이내면 일치로 간주
                    if (distance < 50) {
                        matchCount++;
                    }
                }
            }

            double pointSimilarity = totalSamples > 0 ? (double) matchCount / totalSamples : 0.0;

            // 3. 시작점과 끝점 근접도 확인 (추가 보정)
            double startEndSimilarity = 1.0;
            if (points1.size() > 0 && points2.size() > 0) {
                RoutePoint start1 = points1.get(0);
                RoutePoint end1 = points1.get(points1.size() - 1);
                RoutePoint start2 = points2.get(0);
                RoutePoint end2 = points2.get(points2.size() - 1);

                double startDistance = calculateDistance(start1.getLat(), start1.getLng(), start2.getLat(), start2.getLng());
                double endDistance = calculateDistance(end1.getLat(), end1.getLng(), end2.getLat(), end2.getLng());

                // 시작점과 끝점이 각각 100m 이상 떨어져 있으면 다른 경로로 간주
                if (startDistance > 100 || endDistance > 100) {
                    startEndSimilarity = 0.5; // 패널티 적용
                }
            }

            // 4. 최종 유사도 계산 (가중평균)
            double finalSimilarity = (distanceSimilarity * 0.3 + pointSimilarity * 0.7) * startEndSimilarity;

            logger.debug("경로 유사도 계산: 거리유사도={}%, 포인트유사도={}%, 시작끝점보정={}, 최종={}%",
                    Math.round(distanceSimilarity * 100),
                    Math.round(pointSimilarity * 100),
                    startEndSimilarity,
                    Math.round(finalSimilarity * 100));

            return finalSimilarity;

        } catch (Exception e) {
            logger.warn("경로 유사도 계산 오류: " + e.getMessage());
            return 0.0; // 오류 시 다른 경로로 간주
        }
    }
}
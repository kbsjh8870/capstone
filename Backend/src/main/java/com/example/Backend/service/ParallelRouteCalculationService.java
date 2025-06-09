package com.example.Backend.service;

import com.example.Backend.model.Route;
import com.example.Backend.model.RoutePoint;
import com.example.Backend.model.ShadowArea;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.List;
import java.util.ArrayList;

@Service
@EnableAsync
public class ParallelRouteCalculationService {

    @Autowired
    private TmapApiService tmapApiService;

    @Autowired
    private ShadowService shadowService;

    @Autowired
    private ShadowRouteService shadowRouteService;

    /**
     * 병렬로 여러 경로 계산
     */

    private static final Logger logger = LoggerFactory.getLogger(ParallelRouteCalculationService.class);
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 병렬로 여러 경로 계산
     */
    public CompletableFuture<List<Route>> calculateRoutesInParallel(
            double startLat, double startLng,
            double endLat, double endLng,
            List<List<RoutePoint>> waypointStrategies,
            List<ShadowArea> shadowAreas,
            boolean preferShadow,
            LocalDateTime dateTime) {

        List<CompletableFuture<Route>> futures = new ArrayList<>();

        // 각 경유지 전략에 대해 병렬로 경로 계산
        for (List<RoutePoint> waypoints : waypointStrategies) {
            CompletableFuture<Route> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return calculateSingleRoute(
                            startLat, startLng, endLat, endLng,
                            waypoints, shadowAreas, preferShadow, dateTime
                    );
                } catch (Exception e) {
                    logger.error("경로 계산 오류: " + e.getMessage(), e);
                    return null;
                }
            }, executorService);

            futures.add(future);
        }

        // 모든 계산 완료 후 결과 수집
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<Route> routes = new ArrayList<>();
                    for (CompletableFuture<Route> future : futures) {
                        try {
                            Route route = future.get();
                            if (route != null) {
                                routes.add(route);
                            }
                        } catch (Exception e) {
                            logger.error("경로 수집 오류: " + e.getMessage(), e);
                        }
                    }
                    return routes;
                });
    }

    /**
     * 단일 경로 계산
     */
    private Route calculateSingleRoute(
            double startLat, double startLng,
            double endLat, double endLng,
            List<RoutePoint> waypoints,
            List<ShadowArea> shadowAreas,
            boolean preferShadow,
            LocalDateTime dateTime) throws Exception {

        // T맵 API 호출
        String routeJson;
        if (waypoints.isEmpty()) {
            routeJson = tmapApiService.getWalkingRoute(startLat, startLng, endLat, endLng);
        } else if (waypoints.size() == 1) {
            RoutePoint wp = waypoints.get(0);
            routeJson = tmapApiService.AsgetWalkingRouteWithWaypoint(
                    startLat, startLng, wp.getLat(), wp.getLng(), endLat, endLng
            );
        } else {
            routeJson = tmapApiService.getWalkingRouteWithMultiWaypoints(
                    startLat, startLng, waypoints, endLat, endLng
            );
        }

        // 경로 파싱
        Route route = parseBasicRoute(routeJson);
        route.setDateTime(dateTime);
        route.setAvoidShadow(!preferShadow);

        // 그림자 정보 계산 - ShadowRouteService의 메서드 사용
        shadowRouteService.applyShadowInfoFromDB(route, shadowAreas);

        // 경로 타입 설정
        if (waypoints.isEmpty()) {
            route.setRouteType("direct");
        } else if (waypoints.size() == 1) {
            route.setRouteType("single_waypoint");
        } else {
            route.setRouteType("multi_waypoint");
        }

        route.setWaypointCount(waypoints.size());

        return route;
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

            for (JsonNode feature : features) {
                JsonNode properties = feature.path("properties");

                if (properties.has("distance")) {
                    totalDistance += properties.path("distance").asDouble();
                }
                if (properties.has("time")) {
                    totalDuration += properties.path("time").asInt();
                }

                JsonNode geometry = feature.path("geometry");
                if (geometry.path("type").asText().equals("LineString")) {
                    JsonNode coordinates = geometry.path("coordinates");

                    for (JsonNode coord : coordinates) {
                        double lng = coord.get(0).asDouble();
                        double lat = coord.get(1).asDouble();

                        RoutePoint point = new RoutePoint();
                        point.setLat(lat);
                        point.setLng(lng);
                        point.setInShadow(false);
                        points.add(point);
                    }
                }
            }

            route.setPoints(points);
            route.setDistance(totalDistance);
            route.setDuration(totalDuration / 60);

            logger.debug("T맵 경로 파싱 완료: {}개 포인트, 거리={}m", points.size(), totalDistance);

        } catch (Exception e) {
            logger.error("경로 파싱 오류: " + e.getMessage(), e);
            route.setPoints(new ArrayList<>());
            route.setDistance(0);
            route.setDuration(0);
        }

        return route;
    }

    /**
     * 셧다운 처리
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
    }
}

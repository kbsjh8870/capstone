package com.example.Backend.service;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.example.Backend.model.RoutePoint;

@Service
@Slf4j
public class TmapApiService {

    @Value("${tmap.api.key}")
    private String tmapApiKey;
    private final String TMAP_BASE_URL = "https://apis.openapi.sk.com/tmap";
    private final RestTemplate restTemplate;

    public TmapApiService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 기본 보행자 경로 요청
     */
    public String getWalkingRoute(double startLat, double startLng, double endLat, double endLng) {
        try {
            String url = TMAP_BASE_URL + "/routes/pedestrian?version=1" +
                    "&startX=" + startLng +
                    "&startY=" + startLat +
                    "&endX=" + endLng +
                    "&endY=" + endLat +
                    "&reqCoordType=WGS84GEO" +
                    "&resCoordType=WGS84GEO" +
                    "&startName=" + URLEncoder.encode("출발지", StandardCharsets.UTF_8) +
                    "&endName=" + URLEncoder.encode("도착지", StandardCharsets.UTF_8) +
                    "&searchOption=0";

            log.debug("T맵 API 요청 URL: {}", url);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Accept", "application/json");
            headers.set("appKey", tmapApiKey);

            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            return response.getBody();
        } catch (Exception e) {
            log.error("T맵 API 호출 오류:", e);
            throw new RuntimeException("T맵 API 호출 중 오류 발생", e);
        }
    }

    /**
     * 경유지가 포함된 보행자 경로 요청
     */
    public String getWalkingRouteWithWaypoint(
            double startLat, double startLng,
            double waypointLat, double waypointLng,
            double endLat, double endLng) {

        try {
            // T맵 경유지 API URL 구성
            String url = TMAP_BASE_URL + "/routes/pedestrian?version=1" +
                    "&startX=" + startLng +
                    "&startY=" + startLat +
                    "&endX=" + endLng +
                    "&endY=" + endLat +
                    "&passList=" + waypointLng + "," + waypointLat + // 경유지 추가
                    "&reqCoordType=WGS84GEO" +
                    "&resCoordType=WGS84GEO" +
                    "&startName=" + URLEncoder.encode("출발지", StandardCharsets.UTF_8) +
                    "&endName=" + URLEncoder.encode("도착지", StandardCharsets.UTF_8) +
                    "&searchOption=0";

            log.debug("T맵 경유지 API 요청 URL: {}", url);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Accept", "application/json");
            headers.set("appKey", tmapApiKey);

            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            return response.getBody();
        } catch (Exception e) {
            log.error("T맵 경유지 API 호출 오류:", e);
            throw new RuntimeException("T맵 경유지 API 호출 중 오류 발생", e);
        }
    }

    /**
     * 다중 경유지가 포함된 보행자 경로 요청
     */
    public String getWalkingRouteWithMultiWaypoints(
            double startLat, double startLng,
            List<RoutePoint> waypoints,
            double endLat, double endLng) {

        try {
            // 경유지 목록 생성
            StringBuilder passList = new StringBuilder();
            for (int i = 0; i < waypoints.size(); i++) {
                RoutePoint waypoint = waypoints.get(i);
                passList.append(waypoint.getLng()).append(",").append(waypoint.getLat());

                if (i < waypoints.size() - 1) {
                    passList.append("_");
                }
            }

            // T맵 다중 경유지 API URL 구성
            String url = TMAP_BASE_URL + "/routes/pedestrian?version=1" +
                    "&startX=" + startLng +
                    "&startY=" + startLat +
                    "&endX=" + endLng +
                    "&endY=" + endLat +
                    "&passList=" + passList +
                    "&reqCoordType=WGS84GEO" +
                    "&resCoordType=WGS84GEO" +
                    "&startName=" + URLEncoder.encode("출발지", StandardCharsets.UTF_8) +
                    "&endName=" + URLEncoder.encode("도착지", StandardCharsets.UTF_8) +
                    "&searchOption=0";

            log.debug("T맵 다중 경유지 API 요청 URL: {}", url);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Accept", "application/json");
            headers.set("appKey", tmapApiKey);

            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            return response.getBody();
        } catch (Exception e) {
            log.error("T맵 다중 경유지 API 호출 오류:", e);
            throw new RuntimeException("T맵 다중 경유지 API 호출 중 오류 발생", e);
        }
    }
}
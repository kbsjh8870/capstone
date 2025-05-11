package com.example.Backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

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

    public String getWalkingRoute(double startLat, double startLng, double endLat, double endLng) {
        try {
            // 필수 파라미터만 포함
            String url = TMAP_BASE_URL + "/routes/pedestrian?version=1" +
                    "&startX=" + startLng +
                    "&startY=" + startLat +
                    "&endX=" + endLng +
                    "&endY=" + endLat +
                    "&reqCoordType=WGS84GEO" +
                    "&resCoordType=WGS84GEO";

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
}
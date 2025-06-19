package com.example.Backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class WeatherService {

    private static final Logger logger = LoggerFactory.getLogger(WeatherService.class);

    @Value("${weather.api.key:}")
    private String weatherApiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public WeatherService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 날씨가 나쁜지 확인 (흐림, 비, 이슬비 등)
     */
    public boolean isBadWeather(double lat, double lng) {

        //  임시 디버깅용 항상 좋은 날씨로 반환
        logger.info("테스트용 - 날씨 무시");
        return false;

       /* try {

            logger.info("🔧 DEBUG - weatherApiKey 값: '{}'", weatherApiKey);
            logger.info("🔧 DEBUG - weatherApiKey 길이: {}", weatherApiKey != null ? weatherApiKey.length() : "null");
            logger.info("🔧 DEBUG - isEmpty 체크: {}", weatherApiKey == null || weatherApiKey.trim().isEmpty());

            if (weatherApiKey == null || weatherApiKey.trim().isEmpty()) {
                logger.debug("날씨 API 키가 설정되지 않음 → 기본값(좋은 날씨) 사용");
                return false;
            }

            logger.debug("날씨 정보 조회 시작: 위치=({}, {})", lat, lng);

            String url = String.format(
                    "https://api.openweathermap.org/data/2.5/weather?lat=%f&lon=%f&appid=%s&units=metric",
                    lat, lng, weatherApiKey);

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode weatherData = objectMapper.readTree(response.getBody());
                boolean isBad = analyzeWeatherData(weatherData);

                logger.info(" 날씨 분석 결과: {} → {}",
                        getWeatherDescription(lat, lng),
                        isBad ? "나쁜 날씨 (최단경로 권장)" : "좋은 날씨 (다양한 경로 제공)");

                return isBad;
            } else {
                logger.warn("날씨 API 응답 오류: 상태코드={}", response.getStatusCode());
                return false;
            }

        } catch (Exception e) {
            logger.warn(" 날씨 정보 조회 실패: {} → 기본값(좋은 날씨) 사용", e.getMessage());
            return false;
        }*/
    }

    /**
     * 상세 날씨 정보 조회 (디버깅 및 로그용)
     */
    public String getWeatherDescription(double lat, double lng) {
        try {
            if (weatherApiKey == null || weatherApiKey.trim().isEmpty()) {
                return "날씨 정보 없음";
            }

            String url = String.format(
                    "https://api.openweathermap.org/data/2.5/weather?lat=%f&lon=%f&appid=%s&units=metric&lang=kr",
                    lat, lng, weatherApiKey);

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode weatherData = objectMapper.readTree(response.getBody());

                String description = weatherData.get("weather").get(0).get("description").asText();
                double temp = weatherData.get("main").get("temp").asDouble();
                int humidity = weatherData.get("main").get("humidity").asInt();
                int cloudiness = weatherData.get("clouds").get("all").asInt();

                return String.format("%s, %.1f°C, 습도 %d%%, 구름 %d%%",
                        description, temp, humidity, cloudiness);
            }

        } catch (Exception e) {
            logger.warn("상세 날씨 정보 조회 실패: " + e.getMessage());
        }

        return "날씨 정보 조회 실패";
    }

    /**
     * 날씨 기반 경로 추천 메시지 생성
     */
    public String getRouteRecommendationMessage(double lat, double lng) {
        boolean isBad = isBadWeather(lat, lng);
        String weatherDesc = getWeatherDescription(lat, lng);

        if (isBad) {
            return String.format("현재 날씨(%s)가 좋지 않아 안전한 최단경로를 추천드립니다.", weatherDesc);
        } else {
            return String.format("현재 날씨(%s)가 좋아 다양한 경로를 추천드립니다.", weatherDesc);
        }
    }
}
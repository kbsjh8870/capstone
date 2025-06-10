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
        try {
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
        }
    }

    /**
     * 날씨 데이터 분석
     */
    private boolean analyzeWeatherData(JsonNode weatherData) {
        try {
            // 현재 날씨 상태
            JsonNode weatherArray = weatherData.get("weather");
            String mainWeather = weatherArray.get(0).get("main").asText();
            String description = weatherArray.get(0).get("description").asText();

            // 구름 정보
            JsonNode clouds = weatherData.get("clouds");
            int cloudiness = clouds != null ? clouds.get("all").asInt(0) : 0;

            // 비 정보
            JsonNode rain = weatherData.get("rain");
            double rainVolume = 0.0;
            if (rain != null && rain.has("1h")) {
                rainVolume = rain.get("1h").asDouble(0.0); // 1시간 강수량
            }

            // 시야 거리
            int visibility = weatherData.has("visibility") ? weatherData.get("visibility").asInt(10000) : 10000; // 기본값 10km

            logger.debug("날씨 분석: 상태={}, 설명={}, 구름={}%, 강수량={}mm, 시야={}m",
                    mainWeather, description, cloudiness, rainVolume, visibility);

            // 나쁜 날씨 조건들
            boolean isRainy = "Rain".equals(mainWeather) || "Drizzle".equals(mainWeather) || rainVolume > 0.1;
            boolean isCloudy = "Clouds".equals(mainWeather) && cloudiness >= 80; // 80% 이상 흐림
            boolean isStormy = "Thunderstorm".equals(mainWeather);
            boolean isSnowy = "Snow".equals(mainWeather);
            boolean isFoggy = "Mist".equals(mainWeather) || "Fog".equals(mainWeather) || visibility < 1000;

            boolean isBadWeather = isRainy || isCloudy || isStormy || isSnowy || isFoggy;

            logger.info("날씨 판단 결과: {} (비={}, 흐림={}, 폭풍={}, 눈={}, 안개={})",
                    isBadWeather ? "나쁨" : "좋음", isRainy, isCloudy, isStormy, isSnowy, isFoggy);

            return isBadWeather;

        } catch (Exception e) {
            logger.error("날씨 데이터 분석 오류: " + e.getMessage(), e);
            return false;
        }
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
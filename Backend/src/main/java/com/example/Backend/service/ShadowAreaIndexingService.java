package com.example.Backend.service;

import com.example.Backend.model.SunPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ShadowAreaIndexingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ShadowService shadowService;

    // 시간대별 그늘 밀도 맵 캐시
    private final Map<String, ShadowDensityGrid> shadowDensityCache = new ConcurrentHashMap<>();

    private static final Logger logger = LoggerFactory.getLogger(ShadowAreaIndexingService.class);

    /**
     * 그늘 밀도 격자
     */
    public static class ShadowDensityGrid {
        private final double minLat, maxLat, minLng, maxLng;
        private final int gridSize;
        private final double[][] densityValues;
        private final LocalDateTime calculatedTime;

        public ShadowDensityGrid(double minLat, double maxLat, double minLng, double maxLng,
                                 int gridSize, LocalDateTime calculatedTime) {
            this.minLat = minLat;
            this.maxLat = maxLat;
            this.minLng = minLng;
            this.maxLng = maxLng;
            this.gridSize = gridSize;
            this.densityValues = new double[gridSize][gridSize];
            this.calculatedTime = calculatedTime;
        }

        public void setDensity(int x, int y, double density) {
            if (x >= 0 && x < gridSize && y >= 0 && y < gridSize) {
                densityValues[x][y] = density;
            }
        }

        public double getDensity(double lat, double lng) {
            int x = (int) ((lat - minLat) / (maxLat - minLat) * gridSize);
            int y = (int) ((lng - minLng) / (maxLng - minLng) * gridSize);

            if (x >= 0 && x < gridSize && y >= 0 && y < gridSize) {
                return densityValues[x][y];
            }
            return 0.0;
        }

        /**
         * 고밀도 그늘 지역 찾기
         */
        public List<ShadowHotspot> findHotspots(double threshold) {
            List<ShadowHotspot> hotspots = new ArrayList<>();

            for (int i = 0; i < gridSize; i++) {
                for (int j = 0; j < gridSize; j++) {
                    if (densityValues[i][j] >= threshold) {
                        double lat = minLat + (i + 0.5) * (maxLat - minLat) / gridSize;
                        double lng = minLng + (j + 0.5) * (maxLng - minLng) / gridSize;
                        hotspots.add(new ShadowHotspot(lat, lng, densityValues[i][j]));
                    }
                }
            }

            // 밀도 순으로 정렬
            hotspots.sort((a, b) -> Double.compare(b.density, a.density));
            return hotspots;
        }
    }

    /**
     * 그늘 밀집 지역
     */
    public static class ShadowHotspot {
        public final double lat;
        public final double lng;
        public final double density;

        public ShadowHotspot(double lat, double lng, double density) {
            this.lat = lat;
            this.lng = lng;
            this.density = density;
        }
    }

    /**
     * 특정 지역의 그늘 밀도 격자 생성
     */
    public ShadowDensityGrid createShadowDensityGrid(
            double minLat, double maxLat, double minLng, double maxLng,
            LocalDateTime dateTime, int gridSize) {

        String cacheKey = String.format("%.4f,%.4f,%.4f,%.4f,%s",
                minLat, maxLat, minLng, maxLng, dateTime.getHour());

        // 캐시 확인
        ShadowDensityGrid cached = shadowDensityCache.get(cacheKey);
        if (cached != null &&
                cached.calculatedTime.toLocalDate().equals(dateTime.toLocalDate())) {
            return cached;
        }

        // 새로운 격자 생성
        ShadowDensityGrid grid = new ShadowDensityGrid(
                minLat, maxLat, minLng, maxLng, gridSize, dateTime
        );

        // 태양 위치 계산
        double centerLat = (minLat + maxLat) / 2;
        double centerLng = (minLng + maxLng) / 2;
        SunPosition sunPos = shadowService.calculateSunPosition(centerLat, centerLng, dateTime);

        // 건물 데이터 조회 및 그림자 계산
        String sql = """
            WITH area AS (
                SELECT ST_MakeEnvelope(?, ?, ?, ?, 4326) as bbox
            )
            SELECT 
                id,
                "A16" as height,
                ST_AsGeoJSON(geom) as building_geom,
                ST_X(ST_Centroid(geom)) as center_lng,
                ST_Y(ST_Centroid(geom)) as center_lat
            FROM public."AL_D010_26_20250304" b, area a
            WHERE ST_Intersects(b.geom, a.bbox)
              AND b."A16" > 3
            """;

        List<Map<String, Object>> buildings = jdbcTemplate.queryForList(sql,
                minLng, minLat, maxLng, maxLat);

        // 각 격자점의 그늘 밀도 계산
        double latStep = (maxLat - minLat) / gridSize;
        double lngStep = (maxLng - minLng) / gridSize;

        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                double lat = minLat + (i + 0.5) * latStep;
                double lng = minLng + (j + 0.5) * lngStep;

                double density = calculatePointShadowDensity(lat, lng, buildings, sunPos);
                grid.setDensity(i, j, density);
            }
        }

        // 캐시 저장
        shadowDensityCache.put(cacheKey, grid);

        return grid;
    }

    /**
     * 특정 지점의 그늘 밀도 계산
     */
    private double calculatePointShadowDensity(
            double lat, double lng,
            List<Map<String, Object>> buildings,
            SunPosition sunPos) {

        int shadowCount = 0;
        int checkRadius = 50; // 50m 반경

        for (Map<String, Object> building : buildings) {
            double buildingLat = ((Number) building.get("center_lat")).doubleValue();
            double buildingLng = ((Number) building.get("center_lng")).doubleValue();
            double height = ((Number) building.get("height")).doubleValue();

            // 거리 계산
            double distance = calculateDistance(lat, lng, buildingLat, buildingLng);
            if (distance > checkRadius + 100) continue; // 건물 크기 고려

            // 그림자 방향 및 길이 계산
            double shadowDirection = (sunPos.getAzimuth() + 180) % 360;
            double shadowLength = height / Math.tan(Math.toRadians(sunPos.getAltitude()));

            // 그림자가 해당 지점에 도달하는지 확인
            if (isPointInShadow(lat, lng, buildingLat, buildingLng,
                    shadowDirection, shadowLength)) {
                shadowCount++;
            }
        }

        // 밀도 계산 (0-100%)
        return Math.min(100, shadowCount * 20); // 5개 이상이면 100%
    }

    /**
     * 지점이 건물 그림자 안에 있는지 확인
     */
    private boolean isPointInShadow(double pointLat, double pointLng,
                                    double buildingLat, double buildingLng,
                                    double shadowDirection, double shadowLength) {

        // 건물에서 지점까지의 방향
        double direction = calculateDirection(buildingLat, buildingLng, pointLat, pointLng);
        double distance = calculateDistance(buildingLat, buildingLng, pointLat, pointLng);

        // 방향 차이 (그림자 방향과 실제 방향)
        double angleDiff = Math.abs(direction - shadowDirection);
        if (angleDiff > 180) angleDiff = 360 - angleDiff;

        // 그림자 범위 내에 있는지 확인 (±30도, 그림자 길이 이내)
        return angleDiff < 30 && distance < shadowLength;
    }

    /**
     * 두 지점 간 방향 계산 (도)
     */
    private double calculateDirection(double lat1, double lng1, double lat2, double lng2) {
        double dLng = lng2 - lng1;
        double dLat = lat2 - lat1;

        double angle = Math.toDegrees(Math.atan2(dLng, dLat));
        return (angle + 360) % 360;
    }

    /**
     * 두 지점 간 거리 계산 (미터)
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
     * 매 시간마다 인기 지역의 그늘 격자 사전 계산
     */
    @Scheduled(cron = "0 0 * * * *") // 매 시 정각
    public void preCalculatePopularAreaShadows() {
        logger.info("인기 지역 그늘 격자 사전 계산 시작");

        // 부산 주요 지역들
        List<Area> popularAreas = Arrays.asList(
                new Area("해운대", 35.1587, 35.1687, 129.1550, 129.1650),
                new Area("광안리", 35.1480, 35.1580, 129.1100, 129.1200),
                new Area("서면", 35.1540, 35.1640, 129.0540, 129.0640),
                new Area("남포동", 35.0960, 35.1060, 129.0250, 129.0350)
        );

        LocalDateTime now = LocalDateTime.now();

        // 다음 3시간 동안의 그늘 격자 미리 계산
        for (int hour = 0; hour < 3; hour++) {
            LocalDateTime targetTime = now.plusHours(hour);

            for (Area area : popularAreas) {
                try {
                    ShadowDensityGrid grid = createShadowDensityGrid(
                            area.minLat, area.maxLat, area.minLng, area.maxLng,
                            targetTime, 50 // 50x50 격자
                    );

                    logger.info("{} 지역 {}시 그늘 격자 계산 완료",
                            area.name, targetTime.getHour());

                } catch (Exception e) {
                    logger.error("{} 지역 그늘 계산 오류: {}", area.name, e.getMessage());
                }
            }
        }

        // 오래된 캐시 정리
        cleanOldCache();
    }

    /**
     * 오래된 캐시 정리
     */
    private void cleanOldCache() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(6);

        shadowDensityCache.entrySet().removeIf(entry -> {
            ShadowDensityGrid grid = entry.getValue();
            return grid.calculatedTime.isBefore(cutoff);
        });
    }

    private static class Area {
        final String name;
        final double minLat, maxLat, minLng, maxLng;

        Area(String name, double minLat, double maxLat, double minLng, double maxLng) {
            this.name = name;
            this.minLat = minLat;
            this.maxLat = maxLat;
            this.minLng = minLng;
            this.maxLng = maxLng;
        }
    }
}
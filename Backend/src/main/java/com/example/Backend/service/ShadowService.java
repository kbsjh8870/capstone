package com.example.Backend.service;

import com.example.Backend.model.SunPosition;
import com.example.Backend.model.RoutePoint;
import com.example.Backend.model.ShadowArea;
import com.example.Backend.util.SunPositionCalculator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ShadowService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 주어진 시간과 위치에 대한 태양 위치(고도, 방위각) 계산
     */
    public SunPosition calculateSunPosition(double latitude, double longitude, LocalDateTime dateTime) {
        return SunPositionCalculator.calculate(latitude, longitude, dateTime);
    }

    /**
     * 주어진 경로 주변의 건물 그림자 정보 조회
     */
    public List<ShadowArea> getShadowsAlongRoute(List<RoutePoint> routePoints, SunPosition sunPos) {
        // 경로를 LineString으로 변환
        String routeWkt = convertRoutePointsToWkt(routePoints);

        // 그림자 길이 계산 (태양 고도에 따라)
        double shadowLength = SunPositionCalculator.calculateShadowLength(100, sunPos.getAltitude());

        // SQL 쿼리로 경로 주변 건물의 그림자 영역 계산
        String sql = "WITH route AS (" +
                "  SELECT ST_GeomFromText(?, 4326) as geom" +
                ")" +
                "SELECT " +
                "  b.id, " +
                "  b.\"A16\" as height, " +
                "  ST_AsGeoJSON(b.geom) as building_geom, " +
                "  ST_AsGeoJSON(calculate_building_shadow(b.geom, b.\"A16\", ?, ?)) as shadow_geom " +
                "FROM public.\"AL_D010_26_20250304\" b, route r " +
                "WHERE ST_DWithin(b.geom, r.geom, 100) " +
                "  AND b.\"A16\" > 0";

        List<Map<String, Object>> results = jdbcTemplate.queryForList(
                sql, routeWkt, sunPos.getAzimuth(), sunPos.getAltitude());

        // 결과 파싱 및 그림자 영역 생성
        List<ShadowArea> shadowAreas = new ArrayList<>();

        for (Map<String, Object> row : results) {
            ShadowArea area = new ShadowArea();
            area.setId(((Number) row.get("id")).longValue());
            area.setHeight(((Number) row.get("height")).doubleValue());
            area.setBuildingGeometry((String) row.get("building_geom"));
            area.setShadowGeometry((String) row.get("shadow_geom"));
            shadowAreas.add(area);
        }

        return shadowAreas;
    }

    /**
     * 경로 포인트들을 WKT LineString으로 변환
     */
    private String convertRoutePointsToWkt(List<RoutePoint> points) {
        StringBuilder sb = new StringBuilder("LINESTRING(");
        for (int i = 0; i < points.size(); i++) {
            RoutePoint point = points.get(i);
            sb.append(point.getLng()).append(" ").append(point.getLat());
            if (i < points.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append(")");
        return sb.toString();
    }
}
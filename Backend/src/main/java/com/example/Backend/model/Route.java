package com.example.Backend.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class Route {
    @JsonProperty("id")
    private long id;

    @JsonProperty("points")
    private List<RoutePoint> points = new ArrayList<>();

    @JsonProperty("distance")
    private double distance; // 미터

    @JsonProperty("duration")
    private int duration; // 분

    @JsonProperty("basicRoute")
    private boolean isBasicRoute; // 기본 경로 여부

    @JsonProperty("avoidShadow")
    private boolean avoidShadow; // 그림자 회피 여부

    @JsonProperty("shadowPercentage")
    private int shadowPercentage; // 그림자 경로 비율 (%)

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
    @JsonProperty("dateTime")
    private LocalDateTime dateTime;

    @JsonProperty("shadowAreas")
    private List<ShadowArea> shadowAreas = new ArrayList<>();


    @JsonProperty("efficiencyScore")
    private double efficiencyScore; // 경로 효율성 점수

    @JsonProperty("waypointCount")
    private int waypointCount; // 경유지 개수

    @JsonProperty("routeType")
    private String routeType; // "basic", "shadow_hotspot", "directional", "segmented"

    @JsonProperty("shadowSegments")
    private List<ShadowSegment> shadowSegments = new ArrayList<>(); // 그림자 구간 상세 정보


    /**
     * 경로의 그림자 비율 계산
     */
    public void calculateShadowPercentage(List<ShadowArea> shadowAreas) {
        // 전체 경로 길이
        double totalLength = 0;
        for (int i = 0; i < points.size() - 1; i++) {
            RoutePoint p1 = points.get(i);
            RoutePoint p2 = points.get(i + 1);
            totalLength += calculateDistance(p1.getLat(), p1.getLng(), p2.getLat(), p2.getLng());
        }

        // 그림자 구간 길이
        double shadowLength = 0;
        for (int i = 0; i < points.size(); i++) {
            RoutePoint point = points.get(i);
            if (isPointInShadow(point, shadowAreas)) {
                // 인접 포인트까지의 거리 계산 (시작/끝 포인트 제외)
                if (i > 0) {
                    RoutePoint prev = points.get(i - 1);
                    shadowLength += calculateDistance(prev.getLat(), prev.getLng(),
                            point.getLat(), point.getLng()) / 2;
                }
                if (i < points.size() - 1) {
                    RoutePoint next = points.get(i + 1);
                    shadowLength += calculateDistance(point.getLat(), point.getLng(),
                            next.getLat(), next.getLng()) / 2;
                }
            }
        }

        // 그림자 비율 계산 (%)
        this.shadowPercentage = totalLength > 0 ? (int) ((shadowLength / totalLength) * 100) : 0;
    }

    private boolean isPointInShadow(RoutePoint point, List<ShadowArea> shadowAreas) {
        // 점이 그림자 영역 내에 있는지 확인하는 구현
        // GeoJSON 파싱 및 공간 연산이 필요함
        return false; // 실제 구현 필요
    }

    private double calculateDistance(double lat1, double lng1, double lat2, double lng2) {
        // 두 지점 간 거리 계산 (Haversine 공식)
        final int R = 6371000; // 지구 반경 (미터)
        double latDistance = Math.toRadians(lat2 - lat1);
        double lngDistance = Math.toRadians(lng2 - lng1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lngDistance / 2) * Math.sin(lngDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    /**
     * 그림자 구간 정보
     */
    @Data
    public static class ShadowSegment {
        private int startIndex;
        private int endIndex;
        private double length; // 미터
        private double shadowDensity; // 0-100%

        public ShadowSegment(int startIndex, int endIndex, double length, double shadowDensity) {
            this.startIndex = startIndex;
            this.endIndex = endIndex;
            this.length = length;
            this.shadowDensity = shadowDensity;
        }
    }

    /**
     * 그림자 구간 분석
     */
    public void analyzeShadowSegments() {
        shadowSegments.clear();

        int segmentStart = -1;
        double segmentLength = 0;

        for (int i = 0; i < points.size(); i++) {
            RoutePoint point = points.get(i);

            if (point.isInShadow()) {
                if (segmentStart == -1) {
                    segmentStart = i;
                }

                if (i > 0) {
                    RoutePoint prevPoint = points.get(i - 1);
                    segmentLength += calculateDistance(
                            prevPoint.getLat(), prevPoint.getLng(),
                            point.getLat(), point.getLng()
                    );
                }
            } else {
                if (segmentStart != -1) {
                    // 그림자 구간 종료
                    shadowSegments.add(new ShadowSegment(
                            segmentStart, i - 1, segmentLength, 100.0
                    ));
                    segmentStart = -1;
                    segmentLength = 0;
                }
            }
        }

        // 마지막 구간 처리
        if (segmentStart != -1) {
            shadowSegments.add(new ShadowSegment(
                    segmentStart, points.size() - 1, segmentLength, 100.0
            ));
        }
    }

    /**
     * 경로 요약 정보 생성
     */
    public String getSummary() {
        return String.format(
                "%s 경로: %.1fkm, %d분, 그늘 %d%%, 효율성 %.2f",
                routeType != null ? routeType : "기본",
                distance / 1000.0,
                duration,
                shadowPercentage,
                efficiencyScore
        );
    }
}
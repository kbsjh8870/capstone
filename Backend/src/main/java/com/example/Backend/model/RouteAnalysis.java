package com.example.Backend.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class RouteAnalysis {
    private long routeId;
    private double totalDistance;
    private int totalDuration;
    private int shadowPercentage;
    private double efficiencyScore;

    // 구간별 분석
    private List<SegmentAnalysis> segments;

    // 시간대별 그림자 변화
    private Map<Integer, Integer> hourlyShhadowPercentage; // key: hour, value: shadow%

    // 경로 특성
    private RouteCharacteristics characteristics;

    @Data
    public static class SegmentAnalysis {
        private int startIndex;
        private int endIndex;
        private double distance;
        private int duration;
        private boolean inShadow;
        private double shadowDensity;
    }

    @Data
    public static class RouteCharacteristics {
        private int uphillSections;
        private int downhillSections;
        private int turningPoints;
        private double averageShadowDensity;
        private String recommendedTimeOfDay; // "morning", "afternoon", "evening"
    }
}
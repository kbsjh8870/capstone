package com.example.Backend.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class RouteCandidate {

    @JsonProperty("type")
    private String type;           // "shortest", "shade", "balanced"

    @JsonProperty("displayName")
    private String displayName;    // "최단경로", "그늘이 많은경로", "균형경로"

    @JsonProperty("route")
    private Route route;           // 실제 경로 데이터

    @JsonProperty("description")
    private String description;    // "1.2km, 15분, 그늘 25%"

    @JsonProperty("score")
    private double score;          // 경로 점수 (정렬용)

    @JsonProperty("color")
    private String color;          // 지도 표시용 색상 코드

    public RouteCandidate() {
    }

    public RouteCandidate(String type, String displayName, Route route) {
        this.type = type;
        this.displayName = displayName;
        this.route = route;
        this.description = generateDescription(route);
        this.score = calculateScore(route);
        this.color = determineColor(type);
    }

    /**
     * 경로 설명 생성
     */
    private String generateDescription(Route route) {
        if (route == null) {
            return "경로 정보 없음";
        }

        double distanceKm = route.getDistance() / 1000.0;
        int durationMin = route.getDuration();
        int shadowPercentage = route.getShadowPercentage();

        return String.format("%.1fkm · %d분 · 그늘 %d%%",
                distanceKm, durationMin, shadowPercentage);
    }

    /**
     * 경로 점수 계산 (정렬 및 비교용)
     */
    private double calculateScore(Route route) {
        if (route == null) {
            return 0.0;
        }

        // 거리와 그늘 비율을 고려한 종합 점수
        double normalizedDistance = Math.min(1.0, route.getDistance() / 2000.0); // 2km 기준 정규화
        double normalizedShade = route.getShadowPercentage() / 100.0;

        // 타입별 가중치 적용
        switch (type) {
            case "shortest":
                return 1.0 - normalizedDistance; // 거리가 짧을수록 높은 점수
            case "shade":
                return normalizedShade; // 그늘이 많을수록 높은 점수
            case "balanced":
                // 적당한 그늘(30-70%)과 적당한 거리를 선호
                double shadeScore = normalizedShade > 0.3 && normalizedShade < 0.7 ?
                        1.0 - Math.abs(0.5 - normalizedShade) * 2 : 0.5;
                return (1.0 - normalizedDistance) * 0.6 + shadeScore * 0.4;
            default:
                return 0.5;
        }
    }

    /**
     * 타입별 색상 결정
     */
    private String determineColor(String type) {
        switch (type) {
            case "shortest":
                return "#2196F3"; // 파란색
            case "shade":
                return "#4CAF50"; // 녹색
            case "balanced":
                return "#FF9800"; // 주황색
            default:
                return "#757575"; // 회색
        }
    }

    /**
     * 상세 정보 포함한 설명 생성 (최단경로 비교 효율성 포함)
     */
    @JsonProperty("detailedDescription")
    public String getDetailedDescription() {
        if (route == null) {
            return displayName + ": 경로 정보 없음";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(displayName).append("\n");
        sb.append(description);

        return sb.toString();
    }

    /**
     * 최단경로 대비 효율성 계산 (백엔드에서 전달받을 최단경로 정보 필요)
     */
    public String calculateEfficiencyDisplay(Route shortestRoute) {
        if (route == null || shortestRoute == null) {
            return "효율성 정보 없음";
        }

        // 거리 비교
        double distanceRatio = route.getDistance() / shortestRoute.getDistance();
        String distanceInfo;
        if (distanceRatio <= 1.05) {
            distanceInfo = "거리유사";
        } else if (distanceRatio <= 1.2) {
            distanceInfo = String.format("+%.0f%%", (distanceRatio - 1) * 100);
        } else {
            distanceInfo = String.format("+%.0f%%", (distanceRatio - 1) * 100);
        }

        // 그늘 비교
        int shadowDiff = route.getShadowPercentage() - shortestRoute.getShadowPercentage();
        String shadowInfo;
        if (Math.abs(shadowDiff) <= 2) {
            shadowInfo = "그늘비슷";
        } else if (shadowDiff > 0) {
            shadowInfo = String.format("그늘+%d%%", shadowDiff);
        } else {
            shadowInfo = String.format("그늘%d%%", shadowDiff);
        }

        // 종합 효율성
        String efficiency;
        if ("shortest".equals(type)) {
            efficiency = "기준경로";
        } else {
            // 거리 증가 대비 그늘 효과 평가
            if (distanceRatio <= 1.1 && shadowDiff >= 10) {
                efficiency = "고효율";
            } else if (distanceRatio <= 1.3 && shadowDiff >= 5) {
                efficiency = "중효율";
            } else if (distanceRatio > 1.5) {
                efficiency = "저효율";
            } else {
                efficiency = "보통";
            }
        }

        return String.format("%s · %s · %s", distanceInfo, shadowInfo, efficiency);
    }

    /**
     * 경로 우선순위 (낮을수록 우선)
     */
    @JsonProperty("priority")
    public int getPriority() {
        switch (type) {
            case "shortest":
                return 1;
            case "balanced":
                return 2;
            case "shade":
                return 3;
            default:
                return 99;
        }
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public void setColor(String color) {
        this.color = color;
    }
}
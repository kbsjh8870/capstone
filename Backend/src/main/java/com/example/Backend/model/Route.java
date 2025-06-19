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

    @JsonProperty("routeType")
    private String routeType = ""; // 경로 타입 ("shortest", "waypoint_1" 등)

    @JsonProperty("waypointCount")
    private int waypointCount = 0; // 경유지 개수

    public String getRouteType() {
        return routeType;
    }

    public void setRouteType(String routeType) {
        this.routeType = routeType;
    }

    public int getWaypointCount() {
        return waypointCount;
    }

    public void setWaypointCount(int waypointCount) {
        this.waypointCount = waypointCount;
    }
}
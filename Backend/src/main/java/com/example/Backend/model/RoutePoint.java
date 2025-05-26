package com.example.Backend.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class RoutePoint {
    @JsonProperty("lat")
    private double lat;

    @JsonProperty("lng")
    private double lng;

    @JsonProperty("inShadow")
    private boolean inShadow = false; // 기본값 명시적 설정

    public RoutePoint() {
        this.inShadow = false; // 생성자에서도 명시적 설정
    }

    public RoutePoint(double lat, double lng) {
        this.lat = lat;
        this.lng = lng;
        this.inShadow = false;
    }

    public RoutePoint(double lat, double lng, boolean inShadow) {
        this.lat = lat;
        this.lng = lng;
        this.inShadow = inShadow;
    }
}
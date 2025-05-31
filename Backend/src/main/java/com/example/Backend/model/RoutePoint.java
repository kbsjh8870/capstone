package com.example.Backend.model;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;

@Data
public class RoutePoint {
    @JsonProperty("lat")
    private double lat;

    @JsonProperty("lng")
    private double lng;

    @JsonProperty("inShadow")
    private boolean inShadow = false;

    // 명시적 JSON 처리 메서드 강화
    @JsonGetter("inShadow")
    public boolean isInShadow() {
        return this.inShadow;
    }

    @JsonSetter("inShadow")
    public void setInShadow(boolean inShadow) {
        this.inShadow = inShadow;
    }

    // toString 메서드에 inShadow 포함 (디버깅용)
    @Override
    public String toString() {
        return String.format("RoutePoint{lat=%.6f, lng=%.6f, inShadow=%s}", lat, lng, inShadow);
    }

    public RoutePoint() {
        this.inShadow = false;
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

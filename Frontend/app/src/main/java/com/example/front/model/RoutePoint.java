package com.example.front.model;

/**
 * 경로 상의 한 지점을 나타내는 클래스
 */
public class RoutePoint {
    private double lat;  // 위도
    private double lng;  // 경도  
    private boolean inShadow;  // 그림자 영역에 있는지 여부

    public RoutePoint() {
    }

    public RoutePoint(double lat, double lng, boolean inShadow) {
        this.lat = lat;
        this.lng = lng;
        this.inShadow = inShadow;
    }

    public double getLat() {
        return lat;
    }

    public void setLat(double lat) {
        this.lat = lat;
    }

    public double getLng() {
        return lng;
    }

    public void setLng(double lng) {
        this.lng = lng;
    }

    public boolean isInShadow() {
        return inShadow;
    }

    public void setInShadow(boolean inShadow) {
        this.inShadow = inShadow;
    }
}

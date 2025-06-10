package com.example.front.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Route {
    private long id;
    private List<RoutePoint> points = new ArrayList<>();
    private double distance;
    private int duration;
    private boolean isBasicRoute;
    private boolean avoidShadow;
    private int shadowPercentage;
    private double efficiencyScore;
    private String routeType;
    private int waypointCount;

    // Getters and Setters
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public List<RoutePoint> getPoints() {
        return points;
    }

    public void setPoints(List<RoutePoint> points) {
        this.points = points;
    }

    public double getDistance() {
        return distance;
    }

    public void setDistance(double distance) {
        this.distance = distance;
    }

    public int getDuration() {
        return duration;
    }

    public void setDuration(int duration) {
        this.duration = duration;
    }

    public boolean isBasicRoute() {
        return isBasicRoute;
    }

    public void setBasicRoute(boolean basicRoute) {
        isBasicRoute = basicRoute;
    }

    public boolean isAvoidShadow() {
        return avoidShadow;
    }

    public void setAvoidShadow(boolean avoidShadow) {
        this.avoidShadow = avoidShadow;
    }

    public int getShadowPercentage() {
        return shadowPercentage;
    }

    public void setShadowPercentage(int shadowPercentage) {
        this.shadowPercentage = shadowPercentage;
    }

    public double getEfficiencyScore() {
        return efficiencyScore;
    }

    public void setEfficiencyScore(double efficiencyScore) {
        this.efficiencyScore = efficiencyScore;
    }

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

    /**
     * RoutePoint 리스트를 JSONArray로 변환
     */
    public JSONArray getPointsAsJsonArray() throws JSONException {
        JSONArray jsonArray = new JSONArray();

        for (RoutePoint point : points) {
            JSONObject pointJson = new JSONObject();
            pointJson.put("lat", point.getLat());
            pointJson.put("lng", point.getLng());
            pointJson.put("inShadow", point.isInShadow());
            jsonArray.put(pointJson);
        }

        return jsonArray;
    }

    public static Route fromJson(JSONObject json) throws JSONException {
        Route route = new Route();
        route.setId(json.optLong("id", 0));
        route.setDistance(json.optDouble("distance", 0.0));
        route.setDuration(json.optInt("duration", 0));
        route.setBasicRoute(json.optBoolean("basicRoute", false));
        route.setAvoidShadow(json.optBoolean("avoidShadow", true));
        route.setShadowPercentage(json.optInt("shadowPercentage", 0));
        route.setRouteType(json.optString("routeType", ""));
        route.setWaypointCount(json.optInt("waypointCount", 0));

        // Points 파싱
        if (json.has("points")) {
            JSONArray pointsArray = json.getJSONArray("points");
            List<RoutePoint> points = new ArrayList<>();

            for (int i = 0; i < pointsArray.length(); i++) {
                JSONObject pointJson = pointsArray.getJSONObject(i);
                RoutePoint point = new RoutePoint(
                        pointJson.getDouble("lat"),
                        pointJson.getDouble("lng"),
                        pointJson.optBoolean("inShadow", false)
                );
                points.add(point);
            }
            route.setPoints(points);
        }

        return route;
    }

}
package com.example.front.model;

import org.json.JSONException;
import org.json.JSONObject;

public class RouteCandidate {
    private String type;
    private String displayName;
    private String description;
    private String detailedDescription;
    private String color;
    private String icon;
    private int priority;
    private double score;
    private Route route;

    public RouteCandidate() {
    }

    // JSON에서 파싱
    public static RouteCandidate fromJson(JSONObject json) throws JSONException {
        RouteCandidate candidate = new RouteCandidate();
        candidate.type = json.getString("type");
        candidate.displayName = json.getString("displayName");
        candidate.description = json.getString("description");
        candidate.detailedDescription = json.optString("detailedDescription", "");
        candidate.color = json.getString("color");
        candidate.icon = json.optString("icon", "📍");
        candidate.priority = json.optInt("priority", 99);
        candidate.score = json.optDouble("score", 0.0);

        // Route 객체 파싱
        if (json.has("route")) {
            candidate.route = Route.fromJson(json.getJSONObject("route"));
        }

        return candidate;
    }

    // Getters and Setters
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getDetailedDescription() { return detailedDescription; }
    public void setDetailedDescription(String detailedDescription) { this.detailedDescription = detailedDescription; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }

    public Route getRoute() { return route; }
    public void setRoute(Route route) { this.route = route; }
}
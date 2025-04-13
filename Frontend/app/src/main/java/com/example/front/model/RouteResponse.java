// model/RouteResponse.java
package com.example.front.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class RouteResponse {
    @SerializedName("features")
    private List<Feature> features;

    public List<Feature> getFeatures() {
        return features;
    }

    public static class Feature {
        @SerializedName("geometry")
        private Geometry geometry;

        @SerializedName("properties")
        private Properties properties;

        public Geometry getGeometry() {
            return geometry;
        }

        public Properties getProperties() {
            return properties;
        }
    }

    public static class Geometry {
        @SerializedName("coordinates")
        private List<List<Double>> coordinates;

        public List<List<Double>> getCoordinates() {
            return coordinates;
        }
    }

    public static class Properties {
        @SerializedName("totalDistance")
        private int totalDistance;

        @SerializedName("totalTime")
        private int totalTime;

        public int getTotalDistance() {
            return totalDistance;
        }

        public int getTotalTime() {
            return totalTime;
        }
    }
}
package com.example.Backend.model;

import lombok.Data;

@Data
public class ShadowArea {
    private long id;
    private double height;
    private String buildingGeometry; // GeoJSON
    private String shadowGeometry; // GeoJSON
}
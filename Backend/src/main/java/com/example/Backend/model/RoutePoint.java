package com.example.Backend.model;

import lombok.Data;

@Data
public class RoutePoint {
    private double lat;
    private double lng;
    private boolean inShadow;
}
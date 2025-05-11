package com.example.Backend.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SunPosition {
    private double altitude; // 태양 고도각 (도)
    private double azimuth;  // 태양 방위각 (도)
}
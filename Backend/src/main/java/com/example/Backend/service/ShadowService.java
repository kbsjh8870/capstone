package com.example.Backend.service;

import com.example.Backend.model.SunPosition;
import com.example.Backend.model.RoutePoint;
import com.example.Backend.model.ShadowArea;
import com.example.Backend.util.SunPositionCalculator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ShadowService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 주어진 시간과 위치에 대한 태양 위치(고도, 방위각) 계산
     */
    public SunPosition calculateSunPosition(double latitude, double longitude, LocalDateTime dateTime) {
        return SunPositionCalculator.calculate(latitude, longitude, dateTime);
    }
}
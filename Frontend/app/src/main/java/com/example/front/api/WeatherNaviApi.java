package com.example.front.api;

import com.example.front.model.RouteResponse;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;

public interface WeatherNaviApi {
    @GET("/api/route/walking")
    Call<RouteResponse> getWalkingRoute(
            @Query("startLat") double startLat,
            @Query("startLng") double startLng,
            @Query("endLat") double endLat,
            @Query("endLng") double endLng
    );
}

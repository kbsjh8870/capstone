// activity/MapActivity.java
package com.example.front.activity;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.example.front.R;
import com.example.front.api.ApiClient;
import com.example.front.api.WeatherNaviApi;
import com.example.front.model.RouteResponse;
import com.skt.Tmap.TMapData;
import com.skt.Tmap.TMapPoint;
import com.skt.Tmap.TMapPolyLine;
import com.skt.Tmap.TMapView;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MapActivity extends AppCompatActivity {

    private TMapView tMapView;
    private WeatherNaviApi weatherNaviApi;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);

        // Retrofit 클라이언트 초기화
        weatherNaviApi = ApiClient.getClient().create(WeatherNaviApi.class);

        // TMap 초기화
        tMapView = findViewById(R.id.map_view);
        tMapView.setSKTMapApiKey("YOUR_TMAP_API_KEY");

        // 위치 권한 확인
        checkLocationPermission();
    }

    private void checkLocationPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
        } else {
            // 위치 권한이 있는 경우 지도 초기화
            initMap();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initMap();
            } else {
                Toast.makeText(this, "위치 권한이 필요합니다", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void initMap() {
        // 지도 초기 설정
        tMapView.setZoomLevel(15);
        tMapView.setIconVisibility(true);
        tMapView.setTrackingMode(true);
        tMapView.setSightVisible(true);

        // 테스트를 위한 시작점과 도착점 설정 (서울 시청과 광화문)
        double startLat = 37.566295;
        double startLng = 126.977945;
        double endLat = 37.575268;
        double endLng = 126.976896;

        // 백엔드 API를 통해 경로 데이터 요청
        requestRoute(startLat, startLng, endLat, endLng);
    }

    private void requestRoute(double startLat, double startLng, double endLat, double endLng) {
        Call<RouteResponse> call = weatherNaviApi.getWalkingRoute(startLat, startLng, endLat, endLng);

        call.enqueue(new Callback<RouteResponse>() {
            @Override
            public void onResponse(Call<RouteResponse> call, Response<RouteResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    drawRoute(response.body());
                } else {
                    Toast.makeText(MapActivity.this, "경로 데이터를 불러오는데 실패했습니다", Toast.LENGTH_SHORT).show();
                    // 실패 시 TMap SDK를 직접 사용하여 경로 가져오기
                    getDirectRouteTmap(startLat, startLng, endLat, endLng);
                }
            }

            @Override
            public void onFailure(Call<RouteResponse> call, Throwable t) {
                Toast.makeText(MapActivity.this, "서버 연결 실패: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                // 실패 시 TMap SDK를 직접 사용하여 경로 가져오기
                getDirectRouteTmap(startLat, startLng, endLat, endLng);
            }
        });
    }

    private void drawRoute(RouteResponse routeResponse) {
        try {
            TMapPolyLine tMapPolyLine = new TMapPolyLine();
            tMapPolyLine.setLineColor(Color.BLUE);
            tMapPolyLine.setLineWidth(5);

            List<TMapPoint> points = new ArrayList<>();

            // API 응답에서 좌표 추출
            for (RouteResponse.Feature feature : routeResponse.getFeatures()) {
                if (feature.getGeometry() != null && feature.getGeometry().getCoordinates() != null) {
                    for (List<Double> coordinate : feature.getGeometry().getCoordinates()) {
                        if (coordinate.size() >= 2) {
                            double lng = coordinate.get(0);
                            double lat = coordinate.get(1);
                            TMapPoint point = new TMapPoint(lat, lng);
                            points.add(point);
                        }
                    }
                }
            }

            for (TMapPoint point : points) {
                tMapPolyLine.addLinePoint(point);
            }

            // 지도에 경로선 추가
            tMapView.addTMapPolyLine("walkingRoute", tMapPolyLine);

            // 시작점과 끝점으로 지도 화면 이동
            if (!points.isEmpty()) {
                tMapView.setCenterPoint((points.get(0).getLongitude() + points.get(points.size() - 1).getLongitude()) / 2,
                        (points.get(0).getLatitude() + points.get(points.size() - 1).getLatitude()) / 2);
            }

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "경로 그리기 실패: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 백엔드 API 호출 실패 시 직접 TMap SDK를 통해 경로 요청
    private void getDirectRouteTmap(double startLat, double startLng, double endLat, double endLng) {
        TMapPoint startPoint = new TMapPoint(startLat, startLng);
        TMapPoint endPoint = new TMapPoint(endLat, endLng);

        TMapData tMapData = new TMapData();

        tMapData.findPathDataWithType(TMapData.TMapPathType.PEDESTRIAN_PATH, startPoint, endPoint,
                new TMapData.FindPathDataListenerCallback() {
                    @Override
                    public void onFindPathData(TMapPolyLine polyLine) {
                        runOnUiThread(() -> {
                            polyLine.setLineColor(Color.RED);
                            polyLine.setLineWidth(5);
                            tMapView.addTMapPolyLine("directWalkingRoute", polyLine);
                        });
                    }
                }
        );
    }
}
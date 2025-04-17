package com.example.front.activity;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.RelativeLayout;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import com.example.front.R;
import com.skt.Tmap.TMapGpsManager;
import com.skt.Tmap.TMapPoint;
import com.skt.Tmap.TMapView;

public class MapActivity extends AppCompatActivity implements TMapGpsManager.onLocationChangedCallback{
    private TMapView tMapView;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);

        try {
            // 레이아웃 찾기
            RelativeLayout mapLayout = findViewById(R.id.map_layout);

            // TMapView 생성
            tMapView = new TMapView(this);

            // API 키 설정
            String apiKey = "vU7PQGq7eR8bMAnPeg6F285MVcSpXW9W7wNV57JZ";
            tMapView.setSKTMapApiKey(apiKey);

            // 지도 설정
            tMapView.setZoomLevel(15);
           // tMapView.setCenterPoint(35.148528664420034, 129.0345323654562);

            // 레이아웃에 추가
            mapLayout.addView(tMapView);

            checkLocationPermission();

            Toast.makeText(this, "TMapView 초기화 성공", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "오류: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
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
        // 위치 서비스가 활성화되어 있는지 확인
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        boolean isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        if (!isGpsEnabled && !isNetworkEnabled) {
            Toast.makeText(this, "위치 서비스를 활성화해주세요", Toast.LENGTH_LONG).show();
            // 위치 설정 화면으로 이동하는 코드 추가 가능
            return;
        }

        tMapView.setIconVisibility(true);
        showMyLocation();
    }


    private TMapGpsManager tMapGps;

    private void showMyLocation() {
        try {
            Log.d("Location", "위치 추적 시작");

            // 위치 매니저 초기화
            tMapGps = new TMapGpsManager(this);

            // 설정
            tMapGps.setMinTime(1000);
            tMapGps.setMinDistance(5);

            // 프로바이더 설정 - 둘 다 시도해보세요
            tMapGps.setProvider(TMapGpsManager.NETWORK_PROVIDER);
            //tMapGps.setProvider(TMapGpsManager.GPS_PROVIDER);

            // 콜백 설정
        /*    boolean callbackResult = tMapGps.setLocationCallback();
            Log.d("Location", "콜백 설정 결과: " + callbackResult);*/

            // GPS 시작
            tMapGps.OpenGps();

            tMapView.setTrackingMode(true);
            tMapView.setSightVisible(true);

            Toast.makeText(this, "위치 추적 시작", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e("Location", "위치 추적 오류: " + e.getMessage(), e);
            Toast.makeText(this, "위치 추적 오류: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onLocationChange(Location location) {
        if (location != null) {
            double lat = location.getLatitude();
            double lon = location.getLongitude();
            Log.d("Location", "위치 업데이트: lat=" + lat + ", lon=" + lon);

            // 지도 중심 이동 및 위치 마커 표시
            tMapView.setCenterPoint(lon, lat);
            tMapView.setLocationPoint(lon, lat);
        } else {
            Log.e("Location", "위치 정보가 null입니다");
        }
    }
}
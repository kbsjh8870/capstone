package com.example.front.activity;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
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
import com.skt.Tmap.TMapView;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

public class MapActivity extends AppCompatActivity implements TMapGpsManager.onLocationChangedCallback {

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1000;
    private TMapView tMapView;
    private TMapGpsManager tMapGps;

    private RelativeLayout mapLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);

        mapLayout = findViewById(R.id.map_layout);

        // 백엔드에서 API 키 받아오기
        fetchTmapApiKeyFromBackend();
    }

    private void fetchTmapApiKeyFromBackend() {
        new Thread(() -> {
            try {
                Log.d("TmapAPI", "요청 시작");
                URL url = new URL("http://172.31.43.94:8080/api/config/tmap-key");

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);  // 5초 타임아웃 설정
                conn.setReadTimeout(5000);

                int responseCode = conn.getResponseCode();
                Log.d("TmapAPI", "응답 코드: " + responseCode);

                if (responseCode != 200) {
                    throw new IOException("서버 응답 오류: " + responseCode);
                }

                InputStream in = new BufferedInputStream(conn.getInputStream());
                BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                StringBuilder result = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    result.append(line);
                }

                String apiKey = result.toString().replace("\"", "");
                Log.d("TmapAPI", "API 키 가져옴: " + apiKey);

                runOnUiThread(() -> initTMapView(apiKey));
            } catch (Exception e) {
                Log.e("TmapAPI", "API 키 불러오기 실패: " + e.getMessage(), e);
                runOnUiThread(() ->
                        Toast.makeText(MapActivity.this, "API 키 불러오기 실패: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                );
            }
        }).start();
    }


    private void initTMapView(String apiKey) {
        try {
            tMapView = new TMapView(this);
            tMapView.setSKTMapApiKey(apiKey);
            tMapView.setZoomLevel(15);
            mapLayout.addView(tMapView);

            checkLocationPermission();

            Toast.makeText(this, "TMapView 초기화 성공", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "TMapView 초기화 오류: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }

    private void checkLocationPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
        } else {
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
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        boolean isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        if (!isGpsEnabled && !isNetworkEnabled) {
            Toast.makeText(this, "위치 서비스를 활성화해주세요", Toast.LENGTH_LONG).show();
            return;
        }

        tMapView.setIconVisibility(true);
        showMyLocation();
    }

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

            tMapView.setCenterPoint(lon, lat);
            tMapView.setLocationPoint(lon, lat);
        } else {
            Log.e("Location", "위치 정보가 null입니다");
        }
    }
}

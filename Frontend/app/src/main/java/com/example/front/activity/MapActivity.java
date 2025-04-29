package com.example.front.activity;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.front.R;
import com.example.front.adapter.POIAdapter;
import com.skt.Tmap.TMapData;
import com.skt.Tmap.TMapGpsManager;
import com.skt.Tmap.TMapMarkerItem;
import com.skt.Tmap.poi_item.TMapPOIItem;
import com.skt.Tmap.TMapPoint;
import com.skt.Tmap.TMapPolyLine;
import com.skt.Tmap.TMapView;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

public class MapActivity extends AppCompatActivity implements TMapGpsManager.onLocationChangedCallback {

    private static final String TAG = "MapActivity";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1000;

    private TMapView tMapView;
    private TMapGpsManager tMapGps;
    private TMapData tMapData;
    private RelativeLayout mapLayout;
    private EditText etDestination;
    private Button btnSearch;
    private RecyclerView rvSearchResults;
    private ProgressBar progressBar;
    private TextView tvRouteInfo;
    private POIAdapter poiAdapter;
    private TMapPoint currentLocation; // 현재 위치
    private TMapPoint destinationPoint; // 목적지 위치
    private TMapPolyLine currentRoute; // 현재 표시 중인 경로
    private String destinationName; // 목적지 이름

    private static final String SERVER_URL = "http://52.78.249.131:8080";
    private static final int DEFAULT_ZOOM_LEVEL = 15;
    private static final int ROUTE_LINE_COLOR = Color.parseColor("#2196F3"); // 파란색
    private static final float ROUTE_LINE_WIDTH = 5.0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);

        initViews();
        setupListeners();
        fetchTmapApiKeyFromBackend();
    }

    /**
     * UI 요소 초기화
     */
    private void initViews() {
        mapLayout = findViewById(R.id.map_layout);
        etDestination = findViewById(R.id.et_destination);
        btnSearch = findViewById(R.id.btn_search);
        rvSearchResults = findViewById(R.id.rv_search_results);
        progressBar = findViewById(R.id.progress_bar);
        tvRouteInfo = findViewById(R.id.tv_route_info);

        // RecyclerView 설정
        rvSearchResults.setLayoutManager(new LinearLayoutManager(this));
        poiAdapter = new POIAdapter(this::selectDestination);
        rvSearchResults.setAdapter(poiAdapter);

        // 초기 상태 설정
        progressBar.setVisibility(View.GONE);
        tvRouteInfo.setVisibility(View.GONE);
        rvSearchResults.setVisibility(View.GONE);
    }

    /**
     * 이벤트 리스너 설정
     */
    private void setupListeners() {
        // 검색 버튼 클릭
        btnSearch.setOnClickListener(v -> searchDestination());

        // 키보드 검색 버튼 클릭
        etDestination.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchDestination();
                return true;
            }
            return false;
        });
    }

    /**
     * 백엔드 서버에서 T맵 API 키 가져오기
     */
    private void fetchTmapApiKeyFromBackend() {
        progressBar.setVisibility(View.VISIBLE);

        new Thread(() -> {
            try {
                Log.d(TAG, "API 키 요청 시작");
                URL url = new URL(SERVER_URL + "/api/config/tmap-key");

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "응답 코드: " + responseCode);

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
                Log.d(TAG, "API 키 가져옴: " + apiKey);

                runOnUiThread(() -> {
                    initTMapView(apiKey);
                    progressBar.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                Log.e(TAG, "API 키 불러오기 실패: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    Toast.makeText(MapActivity.this,
                            "API 키 불러오기 실패: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                    progressBar.setVisibility(View.GONE);
                });
            }
        }).start();
    }

    /**
     * TMapView 초기화
     * @param apiKey T맵 API 키
     */
    private void initTMapView(String apiKey) {
        try {
            // TMapView 설정
            tMapView = new TMapView(this);
            tMapView.setSKTMapApiKey(apiKey);
            tMapView.setZoomLevel(DEFAULT_ZOOM_LEVEL);
            tMapView.setIconVisibility(true);
            tMapView.setMapType(TMapView.MAPTYPE_STANDARD);

            // 맵 레이아웃에 추가
            mapLayout.addView(tMapView);

            // TMapData 초기화
            tMapData = new TMapData();

            // 위치 권한 확인 및 초기화
            checkLocationPermission();

            Toast.makeText(this, "지도 초기화 성공", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "지도 초기화 오류: " + e.getMessage(), e);
            Toast.makeText(this, "지도 초기화 오류: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 위치 권한 확인
     */
    private void checkLocationPermission() {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
        } else {
            initLocationTracking();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initLocationTracking();
            } else {
                Toast.makeText(this, "위치 권한이 필요합니다", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 위치 추적 초기화
     */
    private void initLocationTracking() {
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        boolean isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        if (!isGpsEnabled && !isNetworkEnabled) {
            Toast.makeText(this, "위치 서비스를 활성화해주세요", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            Log.d(TAG, "위치 추적 시작");

            // TMapGpsManager 초기화
            tMapGps = new TMapGpsManager(this);
            tMapGps.setMinTime(1000); // 위치 업데이트 최소 시간 간격 (ms)
            tMapGps.setMinDistance(5); // 위치 업데이트 최소 거리 간격 (m)

            // 위치 공급자 설정 (네트워크 우선)
            if (isNetworkEnabled) {
                tMapGps.setProvider(TMapGpsManager.NETWORK_PROVIDER);
            } else if (isGpsEnabled) {
                tMapGps.setProvider(TMapGpsManager.GPS_PROVIDER);
            }

            // GPS 시작
            tMapGps.OpenGps();

            // 지도 설정
            tMapView.setTrackingMode(true); // 현재 위치 추적 모드
            tMapView.setSightVisible(true); // 시야 표시

            Toast.makeText(this, "위치 추적을 시작합니다", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "위치 추적 오류: " + e.getMessage(), e);
            Toast.makeText(this, "위치 추적 오류: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 목적지 검색 처리
     */
    private void searchDestination() {
        String destination = etDestination.getText().toString().trim();
        if (destination.isEmpty()) {
            Toast.makeText(this, "목적지를 입력해주세요", Toast.LENGTH_SHORT).show();
            return;
        }

        // 키보드 숨기기
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(etDestination.getWindowToken(), 0);

        // 로딩 표시
        progressBar.setVisibility(View.VISIBLE);
        rvSearchResults.setVisibility(View.GONE);

        // POI 검색 요청
        searchPOI(destination);
    }

    /**
     * POI(Point Of Interest) 검색
     */
    private void searchPOI(String keyword) {
        try {
            tMapData.findAllPOI(keyword, new TMapData.FindAllPOIListenerCallback() {
                @Override
                public void onFindAllPOI(ArrayList<TMapPOIItem> poiItems) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);

                        if (poiItems == null || poiItems.isEmpty()) {
                            Toast.makeText(MapActivity.this,
                                    "검색 결과가 없습니다",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }

                        poiAdapter.setItems(poiItems);
                        if (currentLocation != null) {
                            poiAdapter.setCurrentLocation(currentLocation);
                        }
                        rvSearchResults.setVisibility(View.VISIBLE);
                    });
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "POI 검색 오류: " + e.getMessage(), e);
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(MapActivity.this,
                        "검색 중 오류가 발생했습니다: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            });
        }
    }

    /**
     * 목적지 선택 처리
     */
    private void selectDestination(TMapPOIItem item) {
        // 검색 결과 목록 숨기기
        rvSearchResults.setVisibility(View.GONE);

        // 목적지 정보 저장
        destinationPoint = item.getPOIPoint();
        destinationName = item.getPOIName();

        // 선택한 목적지 정보 표시
        etDestination.setText(destinationName);

        // 목적지 마커 추가
        addDestinationMarker();

        // 현재 위치가 있다면 경로 요청
        if (currentLocation != null) {
            requestRoute();
        } else {
            Toast.makeText(this,
                    "현재 위치를 확인 중입니다. 잠시 후 경로가 표시됩니다.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 목적지 마커 추가
     */
    private void addDestinationMarker() {
        // 기존 마커 제거
        tMapView.removeAllMarkerItem();

        if (destinationPoint == null) return;

        // 마커 생성 및 설정
        TMapMarkerItem marker = new TMapMarkerItem();
        marker.setTMapPoint(destinationPoint);
        marker.setVisible(TMapMarkerItem.VISIBLE);
        marker.setCanShowCallout(true);
        marker.setCalloutTitle(destinationName);
        marker.setAutoCalloutVisible(true);

        // 지도에 마커 추가
        tMapView.addMarkerItem("destination", marker);

        // 지도 중심을 목적지로 이동
        tMapView.setCenterPoint(destinationPoint.getLongitude(), destinationPoint.getLatitude());
    }

    /**
     * 경로 요청
     */
    private void requestRoute() {
        if (currentLocation == null || destinationPoint == null) {
            Log.e(TAG, "경로 요청 불가: 현재 위치 또는 목적지가 없습니다");
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        tvRouteInfo.setVisibility(View.GONE);

        // TMap API를 사용하여 경로 요청
        findPathWithTmapAPI();
    }

    /**
     * TMap API를 사용하여 경로 검색
     */
    private void findPathWithTmapAPI() {
        try {
            // 기존 경로 제거
            if (currentRoute != null) {
                tMapView.removeTMapPath();
                currentRoute = null;
            }

            // 보행자 경로 요청
            tMapData.findPathDataWithType(
                    TMapData.TMapPathType.PEDESTRIAN_PATH, // PEDESTRIAN_PATH -> 보행자 경로 
                    currentLocation,
                    destinationPoint,
                    new TMapData.FindPathDataListenerCallback() {
                        @Override
                        public void onFindPathData(TMapPolyLine polyLine) {
                            runOnUiThread(() -> {
                                progressBar.setVisibility(View.GONE);

                                // 경로 스타일 설정
                                polyLine.setLineColor(ROUTE_LINE_COLOR);
                                polyLine.setLineWidth(ROUTE_LINE_WIDTH);

                                // 경로 추가
                                tMapView.addTMapPath(polyLine);
                                currentRoute = polyLine;

                                // 경로 정보 표시
                                displayRouteInfo(polyLine);

                                // 경로가 모두 보이도록 지도 조정
                                tMapView.zoomToTMapPoint(currentLocation, destinationPoint);
                            });
                        }
                    }
            );
        } catch (Exception e) {
            Log.e(TAG, "경로 검색 오류: " + e.getMessage(), e);
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(MapActivity.this,
                        "경로 검색 중 오류가 발생했습니다: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            });
        }
    }

    /**
     * 경로 정보 표시
     */
    private void displayRouteInfo(TMapPolyLine polyLine) {
        double distanceKm = polyLine.getDistance() / 1000.0;
        int timeMinutes = (int) (polyLine.getDistance() / 67.0); // 평균 보행 속도 약 4km/h (67m/분)

        String routeInfo = String.format("거리: %.1f km | 예상 시간: %d분", distanceKm, timeMinutes);
        tvRouteInfo.setText(routeInfo);
        tvRouteInfo.setVisibility(View.VISIBLE);
    }

    /**
     * 위치 변경 콜백 인터페이스 구현
     */
    @Override
    public void onLocationChange(Location location) {
        if (location != null) {
            double lat = location.getLatitude();
            double lon = location.getLongitude();

            Log.d(TAG, "위치 업데이트: lat=" + lat + ", lon=" + lon);

            // 현재 위치 업데이트
            currentLocation = new TMapPoint(lat, lon);
            poiAdapter.setCurrentLocation(currentLocation);
            // 지도에 현재 위치 표시
            tMapView.setLocationPoint(lon, lat);

            // 트래킹 모드가 아니라면 지도 중심 업데이트
            if (!tMapView.getIsTracking()) {
                tMapView.setCenterPoint(lon, lat);
            }

            // 목적지가 설정되어 있고 경로가 없다면 경로 요청
            if (destinationPoint != null && currentRoute == null) {
                requestRoute();
            }
        } else {
            Log.e(TAG, "위치 정보가 null입니다");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 리소스 해제
        if (tMapGps != null) {
            tMapGps.CloseGps();
        }
    }
}
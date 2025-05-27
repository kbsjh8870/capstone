package com.example.front.activity;

import android.Manifest;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.front.R;
import com.example.front.adapter.POIAdapter;
import com.example.front.model.RoutePoint;
import com.skt.Tmap.*;
import com.skt.Tmap.poi_item.TMapPOIItem;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private List<TMapPolyLine> routes = new ArrayList<>();
    private List<TMapPolyLine> shadowSegments = new ArrayList<>(); // 그림자 구간 폴리라인 리스트
    private Map<String, TMapPolygon> buildingPolygons = new HashMap<>();
    private Map<String, TMapPolygon> shadowPolygons = new HashMap<>();
    private LocalDateTime selectedDateTime = LocalDateTime.now();
    private boolean avoidShadow = true;  // 기본값: 그림자 회피

    // 경로 색상
    private static final int COLOR_BASIC_ROUTE = Color.parseColor("#2196F3");  // 파란색 - 기본 경로
    private static final int COLOR_AVOID_SHADOW = Color.parseColor("#FF5722"); // 주황색 - 그림자 회피 경로
    private static final int COLOR_FOLLOW_SHADOW = Color.parseColor("#9C27B0"); // 보라색 - 그림자 따라가기 경로

    private boolean isInitialRouteDisplay = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);

        initViews();
        setupListeners();
        setupShadowSettingsUI(); // 그림자 설정 UI 초기화
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
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(30000);

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

        // 기존 경로 제거
        clearAllRoutes();

        RadioGroup shadowOptions = findViewById(R.id.radio_group_shadow);
        shadowOptions.clearCheck();

        // 목적지 정보 저장
        destinationPoint = item.getPOIPoint();
        destinationName = item.getPOIName();

        // 선택한 목적지 정보 표시
        etDestination.setText(destinationName);

        // 목적지 마커 추가
        addDestinationMarker();

        isInitialRouteDisplay = true;

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

    /**
     * 그림자 정보를 고려한 경로 요청
     */
    private void requestShadowRoutes() {
        Log.d(TAG, "그림자 경로 요청 시작: avoidShadow=" + avoidShadow);

        if (currentLocation == null || destinationPoint == null) {
            Log.e(TAG, "경로 요청 불가: 현재 위치 또는 목적지가 없습니다");
            return;
        }

        // UI 스레드에서 실행되는지 확인
        if (Looper.myLooper() == Looper.getMainLooper()) {
            progressBar.setVisibility(View.VISIBLE);
            tvRouteInfo.setVisibility(View.GONE);
        } else {
            runOnUiThread(() -> {
                progressBar.setVisibility(View.VISIBLE);
                tvRouteInfo.setVisibility(View.GONE);
            });
        }

        // 하단 버튼 컨테이너 숨기기
        runOnUiThread(() -> {
            LinearLayout routeButtonContainer = findViewById(R.id.route_button_container);
            routeButtonContainer.setVisibility(View.GONE);
        });

        // 기존에 표시된 경로와 그림자 영역 제거
        clearAllRoutes();

        // 그림자 경로 API 호출
        Thread apiThread = new Thread(() -> {
            try {
                // 현재 타임스탬프 출력 (API 호출 시점 확인)
                long timestamp = System.currentTimeMillis();
                Log.d(TAG, "API 호출 시작 시간: " + timestamp);

                // API 호출 URL 구성
                String url = String.format(
                        "%s/api/routes/shadow?startLat=%f&startLng=%f&endLat=%f&endLng=%f&avoidShadow=%b&dateTime=%s",
                        SERVER_URL,
                        currentLocation.getLatitude(),
                        currentLocation.getLongitude(),
                        destinationPoint.getLatitude(),
                        destinationPoint.getLongitude(),
                        avoidShadow,
                        URLEncoder.encode(selectedDateTime.toString(), "UTF-8"));

                Log.d(TAG, "그림자 경로 요청 URL: " + url);

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "API 응답 코드: " + responseCode);

                if (responseCode != 200) {
                    throw new IOException("서버 응답 오류: " + responseCode);
                }

                // 응답 파싱
                InputStream in = new BufferedInputStream(conn.getInputStream());
                BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                StringBuilder response = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }

                Log.d(TAG, "API 응답 길이: " + response.length() + " bytes");

                JSONArray routesArray = new JSONArray(response.toString());
                Log.d(TAG, "경로 개수: " + routesArray.length());

                // UI 업데이트는 메인 스레드에서 수행
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);

                    try {
                        displayShadowRoutes(routesArray);
                    } catch (Exception e) {
                        Log.e(TAG, "경로 표시 오류: " + e.getMessage(), e);
                        Toast.makeText(MapActivity.this,
                                "경로 표시 중 오류가 발생했습니다: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "그림자 경로 요청 오류: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(MapActivity.this,
                            "그림자 경로 요청 중 오류가 발생했습니다: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });

        // API 호출 스레드 시작
        apiThread.start();
    }

    /**
     * 지도에 표시된 경로와 그림자 영역 제거
     */
    private void clearMapOverlays() {
        // 경로 제거
        for (TMapPolyLine route : routes) {
            tMapView.removeTMapPolyLine(route.getID());
        }
        routes.clear();

        // 그림자 구간 폴리라인 제거
        for (TMapPolyLine shadowSegment : shadowSegments) {
            tMapView.removeTMapPolyLine(shadowSegment.getID());
        }
        shadowSegments.clear();

        // 건물 폴리곤 제거
        for (String key : buildingPolygons.keySet()) {
            tMapView.removeTMapPolygon(key);
        }
        buildingPolygons.clear();

        // 그림자 폴리곤 제거
        for (String key : shadowPolygons.keySet()) {
            tMapView.removeTMapPolygon(key);
        }
        shadowPolygons.clear();

        // 현재 경로 참조 제거
        currentRoute = null;
    }

    /**
     * 그림자 경로 표시
     */
    private void displayShadowRoutes(JSONArray routesArray) throws JSONException {
        if (routesArray.length() == 0) {
            Toast.makeText(this, "경로를 찾을 수 없습니다", Toast.LENGTH_SHORT).show();
            return;
        }

        clearMapOverlays();
        routes.clear();
        tMapView.removeAllTMapPolyLine();
        tMapView.removeTMapPath();

        Log.d(TAG, "경로 표시 시작: 수신된 경로 수=" + routesArray.length());

        List<TMapPoint> allPoints = new ArrayList<>();

        for (int r = 0; r < routesArray.length(); r++) {
            JSONObject routeObj = routesArray.getJSONObject(r);

            boolean isBasicRoute = routeObj.optBoolean("basicRoute", false);
            JSONArray pointsArray = routeObj.getJSONArray("points");
            double distance = routeObj.optDouble("distance", 0.0);
            int duration = routeObj.optInt("duration", 0);
            boolean routeAvoidShadow = routeObj.optBoolean("avoidShadow", true);
            int shadowPercentage = routeObj.optInt("shadowPercentage", 0);

            Log.d(TAG, "경로 " + r + ": 기본경로=" + isBasicRoute + ", 그림자회피=" + routeAvoidShadow +
                    ", 그림자비율=" + shadowPercentage + "%, 포인트수=" + pointsArray.length());

            // 경로 생성
            TMapPolyLine polyLine = new TMapPolyLine();
            polyLine.setID("route_" + r);

            // 경로 스타일 설정
            if (isBasicRoute) {
                polyLine.setLineColor(COLOR_BASIC_ROUTE);
                polyLine.setLineWidth(5.0f);
            } else {
                if (routeAvoidShadow) {
                    polyLine.setLineColor(COLOR_AVOID_SHADOW);
                } else {
                    polyLine.setLineColor(COLOR_FOLLOW_SHADOW);
                }
                polyLine.setLineWidth(5.0f);

                String shadowTypeText = routeAvoidShadow ? "그림자 X" : "그림자 O";
                String routeInfo = String.format(
                        "%s 경로: %.1f km | %d분 | 그림자 %d%%",
                        shadowTypeText,
                        distance / 1000.0,
                        duration,
                        shadowPercentage);
                tvRouteInfo.setText(routeInfo);
                tvRouteInfo.setVisibility(View.VISIBLE);
            }

            // 경로 좌표 추가 - Backend 원본 데이터만 사용
            for (int i = 0; i < pointsArray.length(); i++) {
                JSONObject point = pointsArray.getJSONObject(i);
                double lat = point.getDouble("lat");
                double lng = point.getDouble("lng");

                TMapPoint tMapPoint = new TMapPoint(lat, lng);
                polyLine.addLinePoint(tMapPoint);
                allPoints.add(tMapPoint);
            }

            routes.add(polyLine);

            // *** 핵심 수정: 그림자 경로에는 Backend의 실제 DB 데이터만 사용 ***
            if (!isBasicRoute) {
                Log.d(TAG, "실제 DB 그림자 데이터로 오버레이 생성");
                createRealShadowOverlayFromBackend(pointsArray, r);
            }
        }

        // 지도 조정
        if (isInitialRouteDisplay && !allPoints.isEmpty()) {
            adjustMapView(allPoints);
            isInitialRouteDisplay = false;
        }

        // 현재 선택된 옵션에 맞는 경로 표시
        if (avoidShadow && routes.size() > 1) {
            RadioGroup shadowOptions = findViewById(R.id.radio_group_shadow);
            RadioButton radioAvoidShadow = findViewById(R.id.radio_avoid_shadow);
            radioAvoidShadow.setChecked(true);
            displayOnlyShadowRoute();
        } else if (!avoidShadow && routes.size() > 1) {
            RadioGroup shadowOptions = findViewById(R.id.radio_group_shadow);
            RadioButton radioFollowShadow = findViewById(R.id.radio_follow_shadow);
            radioFollowShadow.setChecked(true);
            displayOnlyShadowRoute();
        }
    }

    private void createRealShadowOverlayFromBackend(JSONArray pointsArray, int routeIndex) {
        try {
            Log.d(TAG, "=== 실제 DB 그림자 오버레이 생성 ===");
            Log.d(TAG, "전체 포인트 수: " + pointsArray.length());

            List<TMapPoint> currentShadowSegment = new ArrayList<>();
            int shadowSegmentCount = 0;
            int totalShadowPoints = 0;

            // *** Backend에서 받은 실제 inShadow 값만 사용 ***
            for (int i = 0; i < pointsArray.length(); i++) {
                JSONObject point = pointsArray.getJSONObject(i);
                double lat = point.getDouble("lat");
                double lng = point.getDouble("lng");
                boolean inShadow = point.optBoolean("inShadow", false); // Backend 원본 데이터

                TMapPoint tMapPoint = new TMapPoint(lat, lng);

                if (inShadow) {
                    currentShadowSegment.add(tMapPoint);
                    totalShadowPoints++;

                    // 첫 5개 그림자 포인트 로깅
                    if (totalShadowPoints <= 5) {
                        Log.d(TAG, "실제 DB 그림자 포인트: idx=" + i + ", 위치=(" + lat + ", " + lng + ")");
                    }
                } else {
                    // 그림자 구간이 끝나면 오버레이 생성
                    if (currentShadowSegment.size() >= 2) {
                        createShadowOverlayPolyLine(currentShadowSegment, routeIndex, shadowSegmentCount++);
                        Log.d(TAG, "그림자 구간 생성: " + shadowSegmentCount + "번째, 포인트 수=" + currentShadowSegment.size());
                    }
                    currentShadowSegment.clear();
                }
            }

            // 마지막 그림자 구간 처리
            if (currentShadowSegment.size() >= 2) {
                createShadowOverlayPolyLine(currentShadowSegment, routeIndex, shadowSegmentCount);
                Log.d(TAG, "마지막 그림자 구간 생성: 포인트 수=" + currentShadowSegment.size());
            }

            Log.d(TAG, "실제 DB 그림자 오버레이 생성 완료: 총 " + totalShadowPoints + "개 그림자 포인트, " + shadowSegmentCount + "개 구간");

        } catch (Exception e) {
            Log.e(TAG, "실제 DB 그림자 오버레이 생성 오류: " + e.getMessage(), e);
        }
    }


    /*private void createShadowOverlayFromPoints(JSONArray pointsArray, int routeIndex) {
        try {
            Log.d(TAG, "=== 그림자 오버레이 생성 시작 ===");
            Log.d(TAG, "전체 포인트 수: " + pointsArray.length());

            List<TMapPoint> currentShadowSegment = new ArrayList<>();
            int shadowSegmentCount = 0;
            int totalShadowPoints = 0;

            for (int i = 0; i < pointsArray.length(); i++) {
                JSONObject point = pointsArray.getJSONObject(i);
                double lat = point.getDouble("lat");
                double lng = point.getDouble("lng");
                boolean inShadow = point.optBoolean("inShadow", false);

                TMapPoint tMapPoint = new TMapPoint(lat, lng);

                if (inShadow) {
                    currentShadowSegment.add(tMapPoint);
                    totalShadowPoints++;

                    // *** 단일 그림자 포인트도 구간으로 만들기 ***
                    if (currentShadowSegment.size() == 1) {
                        // 앞 포인트 추가 (있다면)
                        if (i > 0) {
                            JSONObject prevPoint = pointsArray.getJSONObject(i - 1);
                            TMapPoint prevTMapPoint = new TMapPoint(
                                    prevPoint.getDouble("lat"),
                                    prevPoint.getDouble("lng")
                            );
                            currentShadowSegment.add(0, prevTMapPoint); // 맨 앞에 추가
                        }

                        // 뒤 포인트 추가 (있다면)
                        if (i < pointsArray.length() - 1) {
                            JSONObject nextPoint = pointsArray.getJSONObject(i + 1);
                            TMapPoint nextTMapPoint = new TMapPoint(
                                    nextPoint.getDouble("lat"),
                                    nextPoint.getDouble("lng")
                            );
                            currentShadowSegment.add(nextTMapPoint); // 맨 뒤에 추가
                        }
                    }
                } else {
                    // *** 1개 포인트도 구간으로 생성 ***
                    if (currentShadowSegment.size() >= 1) {
                        createShadowOverlayPolyLine(currentShadowSegment, routeIndex, shadowSegmentCount++);
                        Log.d(TAG, "그림자 구간 생성: " + shadowSegmentCount + "번째, 포인트 수=" + currentShadowSegment.size());
                    }
                    currentShadowSegment.clear();
                }
            }

            // 마지막 그림자 구간 처리
            if (currentShadowSegment.size() >= 1) {
                createShadowOverlayPolyLine(currentShadowSegment, routeIndex, shadowSegmentCount);
                Log.d(TAG, "마지막 그림자 구간 생성: 포인트 수=" + currentShadowSegment.size());
            }

            Log.d(TAG, "그림자 오버레이 생성 완료: 총 " + totalShadowPoints + "개 그림자 포인트, " + shadowSegmentCount + "개 구간");

        } catch (Exception e) {
            Log.e(TAG, "그림자 오버레이 생성 오류: " + e.getMessage(), e);
        }
    }*/

    /**
     * 그림자 구간 오버레이 폴리라인 생성
     */
    private void createShadowOverlayPolyLine(List<TMapPoint> points, int routeIndex, int segmentIndex) {
        if (points.size() < 2) return;

        TMapPolyLine shadowOverlay = new TMapPolyLine();
        shadowOverlay.setID("real_shadow_" + routeIndex + "_" + segmentIndex);

        // *** 그림자 오버레이 스타일 - 항상 동일 ***
        shadowOverlay.setLineColor(Color.BLACK); // 검은색
        shadowOverlay.setLineWidth(15.0f); // 두꺼운 선
        shadowOverlay.setLineAlpha(200); // 약간 투명

        // 포인트 추가
        for (TMapPoint point : points) {
            shadowOverlay.addLinePoint(point);
        }

        // 지도에 추가
        tMapView.addTMapPolyLine(shadowOverlay.getID(), shadowOverlay);
        shadowSegments.add(shadowOverlay);

        Log.d(TAG, "실제 DB 그림자 오버레이 추가: " + shadowOverlay.getID() + " (포인트 " + points.size() + "개)");
    }


    /**
     * 선택된 그림자 경로 표시
     */
    private void displayOnlyShadowRoute() {
        // 모든 경로 제거
        tMapView.removeAllTMapPolyLine();

        // 현재 경로 색상을 저장할 변수
        int currentRouteColor = COLOR_BASIC_ROUTE;

        // 그림자 경로만 표시 (routes 리스트에서 인덱스 1 이상)
        for (int i = 1; i < routes.size(); i++) {
            TMapPolyLine route = routes.get(i);
            tMapView.addTMapPolyLine(route.getID(), route);
            Log.d(TAG, "그림자 경로 표시: " + route.getID());
        }

        // *** 실제 DB 그림자 오버레이를 최상위에 표시 ***
        Log.d(TAG, "실제 DB 그림자 오버레이 개수: " + shadowSegments.size());

        for (TMapPolyLine shadowSegment : shadowSegments) {
            // 기존 것 제거하고 다시 추가 (최상위로)
            tMapView.removeTMapPolyLine(shadowSegment.getID());

            // 스타일 재설정
            shadowSegment.setLineColor(Color.BLACK);
            shadowSegment.setLineWidth(15.0f);
            shadowSegment.setLineAlpha(200);

            tMapView.addTMapPolyLine(shadowSegment.getID(), shadowSegment);

            Log.d(TAG, "실제 DB 그림자 오버레이 최상위 표시: " + shadowSegment.getID());
        }

        // 범례 표시
        LinearLayout shadowLegend = findViewById(R.id.shadow_legend);
        shadowLegend.setVisibility(View.VISIBLE);

        // 범례 색상 업데이트 - 항상 동일
        View legendShadow = findViewById(R.id.legend_shadow);
        legendShadow.setBackgroundColor(Color.BLACK); // 검은색 (실제 그림자)

        View legendSunny = findViewById(R.id.legend_sunny);
        if (avoidShadow) {
            legendSunny.setBackgroundColor(Color.parseColor("#9C27B0"));
        } else {
            legendSunny.setBackgroundColor(Color.parseColor("#822AC2"));
        }

        Log.d(TAG, "실제 DB 그림자 범례 표시 완료");
    }

    /**
     * 지도 뷰 조정
     */
    private void adjustMapView(List<TMapPoint> allPoints) {
        if (allPoints.isEmpty()) return;

        // 위도, 경도의 최소/최대값 계산
        double minLat = Double.MAX_VALUE, maxLat = Double.MIN_VALUE;
        double minLng = Double.MAX_VALUE, maxLng = Double.MIN_VALUE;

        for (TMapPoint point : allPoints) {
            minLat = Math.min(minLat, point.getLatitude());
            maxLat = Math.max(maxLat, point.getLatitude());
            minLng = Math.min(minLng, point.getLongitude());
            maxLng = Math.max(maxLng, point.getLongitude());
        }

        // 지도 중심점 설정 (모든 포인트의 중앙)
        double centerLat = (minLat + maxLat) / 2;
        double centerLng = (minLng + maxLng) / 2;
        tMapView.setCenterPoint(centerLng, centerLat);

        // 줌 범위 설정 (약간의 여백 추가)
        double latSpan = maxLat - minLat;
        double lonSpan = maxLng - minLng;
        tMapView.zoomToSpan(latSpan * 1.2, lonSpan * 1.2);

        Log.d(TAG, "지도 뷰 조정 완료 - 중심: " + centerLat + ", " + centerLng);
    }

    /**
     * 경로상의 그림자 구간을 시각적으로 표시
     */
    private void addShadowSegments(JSONArray pointsArray) {
        try {
            List<TMapPoint> shadowPoints = new ArrayList<>();
            List<TMapPoint> currentShadowSegment = new ArrayList<>();
            int shadowSegmentCount = 0;

            // 경로 포인트를 순회하며 그림자 구간 찾기
            for (int i = 0; i < pointsArray.length(); i++) {
                JSONObject point = pointsArray.getJSONObject(i);
                double lat = point.getDouble("lat");
                double lng = point.getDouble("lng");
                boolean inShadow = point.optBoolean("inShadow", false);

                TMapPoint tMapPoint = new TMapPoint(lat, lng);

                if (inShadow) {
                    // 그림자 구간에 포인트 추가
                    currentShadowSegment.add(tMapPoint);
                } else {
                    // 그림자 구간이 끝나면 폴리라인 생성
                    if (currentShadowSegment.size() >= 2) {
                        createShadowSegmentPolyLine(currentShadowSegment, shadowSegmentCount++);
                    }
                    currentShadowSegment.clear();
                }
            }

            // 마지막 그림자 구간 처리
            if (currentShadowSegment.size() >= 2) {
                createShadowSegmentPolyLine(currentShadowSegment, shadowSegmentCount);
            }

            Log.d(TAG, "그림자 구간 생성 완료: " + shadowSegments.size() + "개 구간");

        } catch (Exception e) {
            Log.e(TAG, "그림자 구간 생성 오류: " + e.getMessage(), e);
        }
    }

    /**
     * 그림자 구간 폴리라인 생성
     */
    private void createShadowSegmentPolyLine(List<TMapPoint> points, int segmentIndex) {
        if (points.size() < 2) return;

        TMapPolyLine shadowSegment = new TMapPolyLine();
        shadowSegment.setID("shadow_segment_" + segmentIndex + "_" + System.currentTimeMillis());

        // 그림자 구간 스타일 설정 - 회색 반투명
        shadowSegment.setLineColor(Color.argb(180, 100, 100, 100)); // 회색 반투명
        shadowSegment.setLineWidth(8.0f); // 기본 경로보다 두껏게

        // 그림자 구간 포인트 추가
        for (TMapPoint point : points) {
            shadowSegment.addLinePoint(point);
        }

        // 지도에 추가
        tMapView.addTMapPolyLine(shadowSegment.getID(), shadowSegment);
        shadowSegments.add(shadowSegment);

        Log.d(TAG, "그림자 구간 추가: " + shadowSegment.getID() + ", 포인트 수: " + points.size());
    }

    /**
     * 그림자 영역 표시 ( 건물이 직접적으로 검은색으로 표시되서 주석 처리함 )
     */
    private void displayShadowAreas(JSONArray shadowAreasArray) throws JSONException {
        /*for (int i = 0; i < shadowAreasArray.length(); i++) {
            JSONObject shadowArea = shadowAreasArray.getJSONObject(i);
            long id = shadowArea.getLong("id");

            // 건물 영역 표시
            if (shadowArea.has("buildingGeometry")) {
                displayPolygonFromGeoJson(
                        shadowArea.getString("buildingGeometry"),
                        "building_" + id,
                        Color.argb(50, 100, 100, 100), // 반투명 회색
                        buildingPolygons);
            }

            // 그림자 영역 표시
            if (shadowArea.has("shadowGeometry")) {
                displayPolygonFromGeoJson(
                        shadowArea.getString("shadowGeometry"),
                        "shadow_" + id,
                        Color.argb(40, 0, 0, 0), // 반투명 검은색
                        shadowPolygons);
            }
        }*/
    }

    /**
     * GeoJSON 형식의 폴리곤을 지도에 표시
     */
    private void displayPolygonFromGeoJson(
            String geoJson,
            String id,
            int color,
            Map<String, TMapPolygon> polygonMap) {

        try {
            JSONObject jsonObject = new JSONObject(geoJson);
            if (!"Polygon".equals(jsonObject.getString("type")) &&
                    !"MultiPolygon".equals(jsonObject.getString("type"))) {
                return; // Polygon 또는 MultiPolygon만 처리
            }

            JSONArray coordinates;
            if ("Polygon".equals(jsonObject.getString("type"))) {
                coordinates = jsonObject.getJSONArray("coordinates");
                addPolygonToMap(coordinates.getJSONArray(0), id, color, polygonMap);
            } else { // MultiPolygon
                coordinates = jsonObject.getJSONArray("coordinates");
                for (int i = 0; i < coordinates.length(); i++) {
                    JSONArray polygonCoords = coordinates.getJSONArray(i);
                    addPolygonToMap(polygonCoords.getJSONArray(0), id + "_" + i, color, polygonMap);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "GeoJSON 파싱 오류: " + e.getMessage(), e);
        }
    }

    /**
     * 좌표 배열로부터 폴리곤 생성 및 지도에 추가
     */
    private void addPolygonToMap(
            JSONArray pointsArray,
            String id,
            int color,
            Map<String, TMapPolygon> polygonMap) throws JSONException {

        TMapPolygon polygon = new TMapPolygon();
        polygon.setID(id);
        polygon.setLineColor(Color.TRANSPARENT); // 테두리 없음
        polygon.setAreaColor(color);

        for (int i = 0; i < pointsArray.length(); i++) {
            JSONArray point = pointsArray.getJSONArray(i);
            double lng = point.getDouble(0); // GeoJSON은 [경도, 위도] 순서
            double lat = point.getDouble(1);
            polygon.addPolygonPoint(new TMapPoint(lat, lng));
        }

        tMapView.addTMapPolygon(id, polygon);
        polygonMap.put(id, polygon);
    }

    /**
     * 그림자 설정 UI 구성
     */
    private void setupShadowSettingsUI() {
        try {
            // 그림자 설정 패널 찾기
            LinearLayout shadowSettingsPanel = findViewById(R.id.shadow_settings_panel);

            // 날짜/시간 선택 버튼
            Button btnSelectTime = findViewById(R.id.btn_select_time);
            TextView tvSelectedTime = findViewById(R.id.tv_selected_time);

            // 현재 날짜/시간으로 초기화
            updateTimeDisplay(tvSelectedTime, selectedDateTime);

            // 그림자 옵션 라디오 버튼 그룹
            RadioGroup shadowOptions = findViewById(R.id.radio_group_shadow);
            RadioButton radioAvoidShadow = findViewById(R.id.radio_avoid_shadow);
            RadioButton radioFollowShadow = findViewById(R.id.radio_follow_shadow);

            shadowOptions.clearCheck();

            // 경로 선택 전용 상태 변수 추가
            final int ROUTE_TYPE_BASIC = 0;      // 기본 경로
            final int ROUTE_TYPE_AVOID = 1;      // 그림자 회피 경로
            final int ROUTE_TYPE_FOLLOW = 2;     // 그림자 따라가기 경로

            // 현재 선택된 경로 상태 (초기값: 기본 경로)
            final int[] currentRouteType = {ROUTE_TYPE_BASIC};

            // 날짜/시간 선택 버튼 이벤트
            btnSelectTime.setOnClickListener(v -> {
                showDateTimePickerDialog(selectedDateTime, newDateTime -> {
                    selectedDateTime = newDateTime;
                    updateTimeDisplay(tvSelectedTime, selectedDateTime);

                    isInitialRouteDisplay = false;

                    // 경로가 설정된 상태라면 재계산
                    if (currentLocation != null && destinationPoint != null) {
                        requestShadowRoutes();
                    }
                });
            });

            // 그림자 옵션 변경 이벤트
            shadowOptions.setOnCheckedChangeListener((group, checkedId) -> {
                if (checkedId == R.id.radio_avoid_shadow) {
                    Log.d(TAG, "그림자 회피 라디오 버튼 선택됨");
                    avoidShadow = true;
                    currentRouteType[0] = ROUTE_TYPE_AVOID;
                } else if (checkedId == R.id.radio_follow_shadow) {
                    Log.d(TAG, "그림자 따라가기 라디오 버튼 선택됨");
                    avoidShadow = false;
                    currentRouteType[0] = ROUTE_TYPE_FOLLOW;
                }

                isInitialRouteDisplay = false;

                // 경로가 설정된 상태라면 즉시 새 경로 요청
                if (currentLocation != null && destinationPoint != null) {
                    Log.d(TAG, "라디오 버튼 선택 변경으로 새 경로 요청: avoidShadow=" + avoidShadow);

                    // 로딩 상태 표시
                    progressBar.setVisibility(View.VISIBLE);
                    Toast.makeText(MapActivity.this, "경로를 계산하는 중...", Toast.LENGTH_SHORT).show();

                    // 즉시 경로 요청 실행 (별도 스레드에서)
                    new Thread(() -> {
                        requestShadowRoutes();
                    }).start();
                }
            });

            // 그림자 설정 패널 토글 버튼
            Button btnToggleShadowSettings = findViewById(R.id.btn_toggle_shadow_settings);
            btnToggleShadowSettings.setOnClickListener(v -> {
                boolean isVisible = shadowSettingsPanel.getVisibility() == View.VISIBLE;
                shadowSettingsPanel.setVisibility(isVisible ? View.GONE : View.VISIBLE);
                btnToggleShadowSettings.setText(isVisible ? "그림자 설정 표시" : "그림자 설정 숨기기");
            });
        } catch (Exception e) {
            Log.e(TAG, "그림자 설정 UI 초기화 오류: " + e.getMessage(), e);
        }
    }

    /**
     * 날짜/시간 선택 다이얼로그 표시
     */
    private void showDateTimePickerDialog(LocalDateTime initialDateTime,
                                          DateTimeSelectedListener listener) {
        // 날짜 선택 다이얼로그
        DatePickerDialog dateDialog = new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    // 시간 선택 다이얼로그
                    TimePickerDialog timeDialog = new TimePickerDialog(
                            this,
                            (view2, hourOfDay, minute) -> {
                                LocalDateTime selected = LocalDateTime.of(
                                        year, month + 1, dayOfMonth, hourOfDay, minute);
                                listener.onDateTimeSelected(selected);
                            },
                            initialDateTime.getHour(),
                            initialDateTime.getMinute(),
                            true
                    );
                    timeDialog.show();
                },
                initialDateTime.getYear(),
                initialDateTime.getMonthValue() - 1,
                initialDateTime.getDayOfMonth()
        );
        dateDialog.show();
    }

    /**
     * 날짜/시간 선택 리스너 인터페이스
     */
    interface DateTimeSelectedListener {
        void onDateTimeSelected(LocalDateTime dateTime);
    }

    /**
     * 날짜/시간 표시 업데이트
     */
    private void updateTimeDisplay(TextView textView, LocalDateTime dateTime) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            textView.setText(dateTime.format(formatter));
        } catch (Exception e) {
            Log.e(TAG, "날짜/시간 표시 오류: " + e.getMessage(), e);
        }
    }

    /**
     * clearAllRoutes 메서드 수정 - shadowSegments 추가 처리
     */
    private void clearAllRoutes() {
        Log.d(TAG, "모든 경로 및 오버레이 제거");

        try {
            // TMapView의 모든 폴리라인 먼저 제거
            tMapView.removeAllTMapPolyLine();

            // 경로 목록 제거
            for (TMapPolyLine route : routes) {
                try {
                    tMapView.removeTMapPolyLine(route.getID());
                } catch (Exception e) {
                    Log.e(TAG, "경로 제거 오류: " + e.getMessage());
                }
            }
            routes.clear();

            // 그림자 구간 오버레이 제거
            for (TMapPolyLine shadowSegment : shadowSegments) {
                try {
                    tMapView.removeTMapPolyLine(shadowSegment.getID());
                } catch (Exception e) {
                    Log.e(TAG, "그림자 오버레이 제거 오류: " + e.getMessage());
                }
            }
            shadowSegments.clear();

            // 기존 경로 제거
            if (currentRoute != null) {
                tMapView.removeTMapPath();
            }

            // 그림자 영역 제거
            for (String key : shadowPolygons.keySet()) {
                tMapView.removeTMapPolygon(key);
            }
            shadowPolygons.clear();

            // 건물 영역 제거
            for (String key : buildingPolygons.keySet()) {
                tMapView.removeTMapPolygon(key);
            }
            buildingPolygons.clear();

            // 현재 경로 참조 제거
            currentRoute = null;

            // UI 요소 숨기기
            tvRouteInfo.setVisibility(View.GONE);
            LinearLayout shadowLegend = findViewById(R.id.shadow_legend);
            shadowLegend.setVisibility(View.GONE);
            LinearLayout routeButtonContainer = findViewById(R.id.route_button_container);
            routeButtonContainer.setVisibility(View.GONE);

            Log.d(TAG, "모든 경로 제거 완료");
        } catch (Exception e) {
            Log.e(TAG, "경로 제거 오류: " + e.getMessage(), e);
        }
    }

    /**
     * 기본 경로만 표시하고 다른 경로는 숨김
     */
    private void showOnlyBasicRoute() {
        if (routes.isEmpty()) return;

        // 모든 경로 제거
        for (TMapPolyLine route : routes) {
            tMapView.removeTMapPolyLine(route.getID());
        }

        // 기본 경로만 다시 추가
        TMapPolyLine basicRoute = routes.get(0);
        basicRoute.setLineWidth(5.0f);
        tMapView.addTMapPolyLine(basicRoute.getID(), basicRoute);

        // 현재 경로 업데이트
        currentRoute = basicRoute;

        // 그림자 영역 숨기기
        for (String key : shadowPolygons.keySet()) {
            tMapView.removeTMapPolygon(key);
        }
    }

    /**
     * 경로 정보 계산 및 표시
     */
    private void calculateAndDisplayRouteInfo(String routeType) {
        double totalDistance = 0;

        // 기본 경로를 제외한 모든 세그먼트의 거리 합산
        for (int i = 1; i < routes.size(); i++) {
            TMapPolyLine segment = routes.get(i);

            // 세그먼트 ID로 그림자 구간 판단
            if (segment.getID().contains("segment") || segment.getID().contains("shadow_route")) {
                // 거리 계산 - TMapPolyLine의 거리 계산 메서드 사용
                ArrayList<TMapPoint> linePoints = segment.getLinePoint();
                if (linePoints != null && linePoints.size() > 1) {
                    for (int j = 0; j < linePoints.size() - 1; j++) {
                        TMapPoint p1 = linePoints.get(j);
                        TMapPoint p2 = linePoints.get(j + 1);
                        // 두 점 사이의 거리 계산 (Haversine 공식)
                        double lat1 = p1.getLatitude();
                        double lon1 = p1.getLongitude();
                        double lat2 = p2.getLatitude();
                        double lon2 = p2.getLongitude();

                        double R = 6371000; // 지구 반경 (미터)
                        double dLat = Math.toRadians(lat2 - lat1);
                        double dLon = Math.toRadians(lon2 - lon1);
                        double a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                                        Math.sin(dLon/2) * Math.sin(dLon/2);
                        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
                        double distance = R * c;

                        totalDistance += distance;
                    }
                }
            }
        }

        double distanceKm = totalDistance / 1000.0;
        int timeMinutes = (int) (totalDistance / 67.0);

        String routeInfo = String.format("%s: %.1f km | %d분",
                routeType, distanceKm, timeMinutes);
        tvRouteInfo.setText(routeInfo);
        tvRouteInfo.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // TODO: 앱이 전면에 나타날 때 경로 초기화가 필요하면 추가
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 리소스 해제
        if (tMapGps != null) {
            tMapGps.CloseGps();
        }
        clearAllRoutes();
    }
}
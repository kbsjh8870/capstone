package com.example.front.activity;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;
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
import com.example.front.model.Route;
import com.example.front.model.RouteCandidate;
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

    private List<RouteCandidate> routeCandidates = new ArrayList<>();
    private RouteCandidate selectedCandidate;
    private LinearLayout routeInfoContainer;
    private RadioGroup rgRouteSelection;
    private boolean isUpdatingRadioButtons = false;

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

        routeInfoContainer = findViewById(R.id.route_info_container);
        rgRouteSelection = findViewById(R.id.rg_route_selection);
        rgRouteSelection.setOnCheckedChangeListener(this::onRouteButtonSelected);

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

        // 목적지 정보 저장
        destinationPoint = item.getPOIPoint();
        destinationName = item.getPOIName();

        // 선택한 목적지 정보 표시
        etDestination.setText(destinationName);

        // 목적지 마커 추가
        addDestinationMarker();

        // 현재 위치가 있다면 즉시 후보 경로 요청
        if (currentLocation != null) {
            requestCandidateRoutes();
        } else {
            Toast.makeText(this, "현재 위치를 확인 중입니다. 잠시 후 경로가 표시됩니다.", Toast.LENGTH_SHORT).show();
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
     * 후보 경로 요청 - 사용자가 선택한 시간 사용
     */
    private void requestCandidateRoutes() {
        if (currentLocation == null || destinationPoint == null) {
            Log.e(TAG, "경로 요청 불가: 현재 위치 또는 목적지가 없습니다");
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        tvRouteInfo.setVisibility(View.GONE);

        Thread apiThread = new Thread(() -> {
            try {
                // 사용자가 선택한 시간 사용 (selectedDateTime)
                Log.d(TAG, "사용자 선택 시간: " + selectedDateTime);
                Log.d(TAG, "선택 시간 (시): " + selectedDateTime.getHour());

                String url = String.format(
                        "%s/api/routes/candidate-routes?startLat=%f&startLng=%f&endLat=%f&endLng=%f&dateTime=%s",
                        SERVER_URL,
                        currentLocation.getLatitude(),
                        currentLocation.getLongitude(),
                        destinationPoint.getLatitude(),
                        destinationPoint.getLongitude(),
                        URLEncoder.encode(selectedDateTime.toString(), "UTF-8")); // 사용자 선택 시간

                Log.d(TAG, "후보 경로 요청 URL (사용자 선택 시간): " + url);

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(30000);

                int responseCode = conn.getResponseCode();

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

                JSONObject responseJson = new JSONObject(response.toString());
                JSONArray candidatesArray = responseJson.getJSONArray("candidates");

                // ️ 날씨 메시지 확인
                String weatherMessage = responseJson.optString("weatherMessage", "");
                Log.d(TAG, "날씨 메시지: " + weatherMessage);

                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    try {
                        // 날씨 메시지가 있으면 토스트로 표시
                        if (!weatherMessage.isEmpty()) {
                            Toast.makeText(MapActivity.this, weatherMessage, Toast.LENGTH_LONG).show();
                        }

                        parseCandidatesAndShowDialog(candidatesArray);
                    } catch (Exception e) {
                        Log.e(TAG, "후보 경로 표시 오류: " + e.getMessage(), e);
                        Toast.makeText(MapActivity.this, "경로 표시 중 오류가 발생했습니다", Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "후보 경로 요청 오류: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(MapActivity.this, "경로 요청 중 오류가 발생했습니다: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });

        apiThread.start();
    }

    /**
     * 후보 경로 파싱 및 선택 다이얼로그 표시
     */
    private void parseCandidatesAndShowDialog(JSONArray candidatesArray) throws JSONException {
        routeCandidates.clear();

        for (int i = 0; i < candidatesArray.length(); i++) {
            JSONObject candidateJson = candidatesArray.getJSONObject(i);

            try {
                RouteCandidate candidate = RouteCandidate.fromJson(candidateJson);

                if (candidate.getRoute() != null &&
                        candidate.getRoute().getPoints() != null &&
                        !candidate.getRoute().getPoints().isEmpty()) {

                    routeCandidates.add(candidate);
                    Log.d(TAG, "유효한 후보 " + i + ": " + candidate.getDisplayName());
                } else {
                    Log.d(TAG, "무효한 후보 " + i + " 제외: " + candidate.getDisplayName());
                }

            } catch (Exception e) {
                Log.e(TAG, "후보 " + i + " 파싱 실패: " + e.getMessage(), e);
            }
        }

        Log.d(TAG, "파싱된 유효 후보 경로: " + routeCandidates.size() + "개");

        if (routeCandidates.isEmpty()) {
            Toast.makeText(this, "생성 가능한 경로가 없습니다", Toast.LENGTH_SHORT).show();
            return;
        }

        // 기존 정렬 로직 그대로
        routeCandidates.sort((c1, c2) -> Integer.compare(c1.getPriority(), c2.getPriority()));
        showRouteSelectionDialog();
        if (!routeCandidates.isEmpty()) {
            displayRouteInfoWithButtons(routeCandidates.get(0));
        }
    }

    /**
     * 경로 선택 다이얼로그 표시
     */
    private void showRouteSelectionDialog() {
        if (routeCandidates.isEmpty()) {
            Toast.makeText(this, "이용 가능한 경로가 없습니다", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🗺️ 경로를 선택하세요");

        String[] options = new String[routeCandidates.size()];
        for (int i = 0; i < routeCandidates.size(); i++) {
            RouteCandidate candidate = routeCandidates.get(i);

            options[i] = candidate.getIcon() + " " + candidate.getDisplayName() + "\n" +
                    candidate.getDescription();
        }

        builder.setItems(options, (dialog, which) -> {
            RouteCandidate selectedCandidate = routeCandidates.get(which);

            this.selectedCandidate = selectedCandidate;
            Log.d(TAG, "선택된 경로: " + selectedCandidate.getDisplayName());
            displaySelectedRoute(selectedCandidate);
        });

        builder.setNegativeButton("취소", (dialog, which) -> {
            Log.d(TAG, "경로 선택 취소됨");
        });

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    /**
     * 선택된 경로 표시
     */
    private void displaySelectedRoute(RouteCandidate candidate) {
        try {
            // 기존 경로 제거
            clearAllRoutes();

            Route route = candidate.getRoute();
            if (route == null || route.getPoints().isEmpty()) {
                Toast.makeText(this, "경로 데이터가 없습니다", Toast.LENGTH_SHORT).show();
                return;
            }

            TMapPolyLine polyLine = new TMapPolyLine();
            polyLine.setID("selected_route");

            int color = Color.parseColor(candidate.getColor());
            polyLine.setLineColor(color);
            polyLine.setLineWidth(6.0f);

            List<TMapPoint> allPoints = new ArrayList<>();
            for (RoutePoint point : route.getPoints()) {
                TMapPoint tMapPoint = new TMapPoint(point.getLat(), point.getLng());
                polyLine.addLinePoint(tMapPoint);
                allPoints.add(tMapPoint);
            }

            tMapView.addTMapPolyLine(polyLine.getID(), polyLine);
            routes.add(polyLine);

            displayShadowSegmentsForRoute(route);

            // 경로 설명에서 detailedDescription 사용 (경유지 제거, 효율성 표시)
            String routeDescription = candidate.getDetailedDescription();
            if (routeDescription != null && !routeDescription.isEmpty()) {
                tvRouteInfo.setText(routeDescription);
            } else {
                tvRouteInfo.setText(candidate.getDescription()); // 폴백
            }
            tvRouteInfo.setVisibility(View.VISIBLE);

            displayRouteInfoWithButtons(candidate);
            updateSelectedRadioButton(candidate);

            if (!allPoints.isEmpty()) {
                adjustMapView(allPoints);
            }

            Log.d(TAG, "경로 표시 완료: " + candidate.getDisplayName());

        } catch (Exception e) {
            Log.e(TAG, "경로 표시 오류: " + e.getMessage(), e);
            Toast.makeText(this, "경로 표시 중 오류가 발생했습니다", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 선택된 경로의 그림자 구간 표시
     */
    private void displayShadowSegmentsForRoute(Route route) {
        try {
            List<RoutePoint> points = route.getPoints();
            List<TMapPoint> currentShadowSegment = new ArrayList<>();
            int segmentCount = 0;

            for (int i = 0; i < points.size(); i++) {
                RoutePoint point = points.get(i);
                TMapPoint tMapPoint = new TMapPoint(point.getLat(), point.getLng());

                if (point.isInShadow()) {
                    currentShadowSegment.add(tMapPoint);
                } else {
                    // 그림자 구간 종료
                    if (currentShadowSegment.size() >= 2) {
                        createShadowOverlay(currentShadowSegment, segmentCount++);
                    }
                    currentShadowSegment.clear();
                }
            }

            // 마지막 그림자 구간 처리
            if (currentShadowSegment.size() >= 2) {
                createShadowOverlay(currentShadowSegment, segmentCount);
            }



        } catch (Exception e) {
            Log.e(TAG, "그림자 구간 표시 오류: " + e.getMessage(), e);
        }
    }

    /**
     * 그림자 오버레이 생성
     */
    private void createShadowOverlay(List<TMapPoint> points, int segmentIndex) {
        TMapPolyLine shadowOverlay = new TMapPolyLine();
        shadowOverlay.setID("shadow_segment_" + segmentIndex);
        shadowOverlay.setLineColor(Color.BLACK);
        shadowOverlay.setLineWidth(12.0f);
        shadowOverlay.setLineAlpha(200);

        for (TMapPoint point : points) {
            shadowOverlay.addLinePoint(point);
        }

        tMapView.addTMapPolyLine(shadowOverlay.getID(), shadowOverlay);
        shadowSegments.add(shadowOverlay);
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



            // 날짜/시간 선택 버튼 이벤트
            btnSelectTime.setOnClickListener(v -> {
                showDateTimePickerDialog(selectedDateTime, newDateTime -> {
                    selectedDateTime = newDateTime; // 사용자 선택 시간 저장
                    updateTimeDisplay(tvSelectedTime, selectedDateTime);

                    Log.d(TAG, "시간 변경됨: " + selectedDateTime + " (" + selectedDateTime.getHour() + "시)");

                    // 시간 변경 시 즉시 새 경로 계산
                    if (currentLocation != null && destinationPoint != null) {
                        Toast.makeText(MapActivity.this,
                                "선택한 시간(" + selectedDateTime.getHour() + "시)의 그림자를 계산 중...",
                                Toast.LENGTH_SHORT).show();
                        requestCandidateRoutes();
                    }
                });
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

            // 그림자 마커들도 제거
            tMapView.removeAllMarkerItem();

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

            clearRouteSelectionButtons();

            Log.d(TAG, "모든 경로 제거 완료");
        } catch (Exception e) {
            Log.e(TAG, "경로 제거 오류: " + e.getMessage(), e);
        }
    }

    /**
     * 경로 선택 라디오 버튼들 설정
     */
    private void setupRouteSelectionButtons() {
        try {
            Log.d(TAG, "회색바 라디오 버튼 설정 시작");

            isUpdatingRadioButtons = true;

            // 기존 라디오 버튼들 제거
            rgRouteSelection.removeAllViews();

            // 유효한 후보 경로들만 필터링
            List<RouteCandidate> validCandidates = new ArrayList<>();
            for (RouteCandidate candidate : routeCandidates) {
                if (candidate.getRoute() != null &&
                        candidate.getRoute().getPoints() != null &&
                        !candidate.getRoute().getPoints().isEmpty()) {
                    validCandidates.add(candidate);
                }
            }

            if (validCandidates.isEmpty()) {
                Log.d(TAG, "유효한 후보 경로가 없음");
                isUpdatingRadioButtons = false;
                return;
            }

            // 라디오 버튼 생성 (최대 3개)
            for (int i = 0; i < Math.min(3, validCandidates.size()); i++) {
                RouteCandidate candidate = validCandidates.get(i);

                RadioButton radioButton = new RadioButton(this);
                radioButton.setId(View.generateViewId());
                radioButton.setText(String.valueOf(i + 1)); // 1, 2, 3 숫자 표시
                radioButton.setTextSize(12);
                radioButton.setTextColor(Color.WHITE);
                radioButton.setPadding(8, 0, 8, 0);

                // 라디오 버튼 크기 조정
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                params.setMargins(4, 0, 4, 0);
                radioButton.setLayoutParams(params);

                // 버튼 스타일 설정
                radioButton.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
                radioButton.setScaleX(0.8f);
                radioButton.setScaleY(0.8f);

                // 태그에 후보 인덱스 저장
                int originalIndex = routeCandidates.indexOf(candidate);
                radioButton.setTag(originalIndex);

                rgRouteSelection.addView(radioButton);

                // 첫 번째 항목을 기본 선택
                if (i == 0) {
                    radioButton.setChecked(true);
                }

                Log.d(TAG, "라디오 버튼 " + (i + 1) + " 생성: " + candidate.getDisplayName());
            }
            isUpdatingRadioButtons = false;

            Log.d(TAG, "회색바 라디오 버튼 설정 완료: " + Math.min(3, validCandidates.size()) + "개");

        } catch (Exception e) {
            Log.e(TAG, "회색바 라디오 버튼 설정 오류: " + e.getMessage(), e);
        }
    }

    /**
     * 라디오 버튼 선택 이벤트 처리
     */
    private void onRouteButtonSelected(RadioGroup group, int checkedId) {
        try {
            if (isUpdatingRadioButtons) {
                Log.d(TAG, "라디오 버튼 업데이트 중이므로 이벤트 무시");
                return;
            }
            if (checkedId == -1) return; // 선택 해제된 경우

            RadioButton selectedButton = findViewById(checkedId);
            if (selectedButton == null || selectedButton.getTag() == null) return;

            int candidateIndex = (Integer) selectedButton.getTag();

            if (candidateIndex >= 0 && candidateIndex < routeCandidates.size()) {
                RouteCandidate selectedCandidate = routeCandidates.get(candidateIndex);

                Log.d(TAG, "회색바 라디오 버튼으로 경로 선택: " + selectedCandidate.getDisplayName());

                this.selectedCandidate = selectedCandidate;
                displaySelectedRouteOnly(selectedCandidate);

                // 선택 피드백
                String buttonText = selectedButton.getText().toString();
                Toast.makeText(this,
                        buttonText + "번: " + selectedCandidate.getDisplayName(),
                        Toast.LENGTH_SHORT).show();
            }

        } catch (Exception e) {
            Log.e(TAG, "회색바 라디오 버튼 선택 처리 오류: " + e.getMessage(), e);
        }
    }

    /**
     * 경로만 표시 (라디오 버튼 업데이트 없이)
     */
    private void displaySelectedRouteOnly(RouteCandidate candidate) {
        try {
            // 기존 경로 제거
            clearAllRoutesExceptButtons(); // 라디오 버튼은 유지하고 경로만 제거

            Route route = candidate.getRoute();
            if (route == null || route.getPoints().isEmpty()) {
                Toast.makeText(this, "경로 데이터가 없습니다", Toast.LENGTH_SHORT).show();
                return;
            }

            TMapPolyLine polyLine = new TMapPolyLine();
            polyLine.setID("selected_route");

            int color = Color.parseColor(candidate.getColor());
            polyLine.setLineColor(color);
            polyLine.setLineWidth(6.0f);

            List<TMapPoint> allPoints = new ArrayList<>();
            for (RoutePoint point : route.getPoints()) {
                TMapPoint tMapPoint = new TMapPoint(point.getLat(), point.getLng());
                polyLine.addLinePoint(tMapPoint);
                allPoints.add(tMapPoint);
            }

            tMapView.addTMapPolyLine(polyLine.getID(), polyLine);
            routes.add(polyLine);

            displayShadowSegmentsForRoute(route);

            // 경로 정보 텍스트만 업데이트 (라디오 버튼은 그대로)
            updateRouteInfoText(candidate);

            if (!allPoints.isEmpty()) {
                adjustMapView(allPoints);
            }

            Log.d(TAG, "경로만 표시 완료: " + candidate.getDisplayName());

        } catch (Exception e) {
            Log.e(TAG, "경로 표시 오류: " + e.getMessage(), e);
            Toast.makeText(this, "경로 표시 중 오류가 발생했습니다", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 경로 정보 텍스트만 업데이트
     */
    private void updateRouteInfoText(RouteCandidate candidate) {
        try {
            String routeDescription = candidate.getDetailedDescription();
            if (routeDescription == null || routeDescription.isEmpty()) {
                routeDescription = candidate.getDescription();
            }

            tvRouteInfo.setText(routeDescription);

            Log.d(TAG, "경로 정보 텍스트 업데이트 완료");

        } catch (Exception e) {
            Log.e(TAG, "경로 정보 텍스트 업데이트 오류: " + e.getMessage(), e);
        }
    }

    /**
     * 경로만 제거 (라디오 버튼은 유지)
     */
    private void clearAllRoutesExceptButtons() {
        Log.d(TAG, "라디오 버튼 제외하고 경로만 제거");

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

            Log.d(TAG, "라디오 버튼 제외한 경로 제거 완료");
        } catch (Exception e) {
            Log.e(TAG, "경로 제거 오류: " + e.getMessage(), e);
        }
    }

    /**
     * 경로 정보 표시 (라디오 버튼 포함) - 초기 설정
     */
    private void displayRouteInfoWithButtons(RouteCandidate candidate) {
        try {
            if (candidate == null || candidate.getRoute() == null) {
                routeInfoContainer.setVisibility(View.GONE);
                return;
            }

            // 경로 정보 텍스트 설정
            updateRouteInfoText(candidate);

            // 라디오 버튼들 설정 (초기에만)
            setupRouteSelectionButtons();

            // 현재 선택된 경로에 해당하는 라디오 버튼 선택
            updateSelectedRadioButtonSilently(candidate);

            // 컨테이너 표시
            routeInfoContainer.setVisibility(View.VISIBLE);

            Log.d(TAG, "경로 정보와 라디오 버튼 표시 완료");

        } catch (Exception e) {
            Log.e(TAG, "경로 정보 표시 오류: " + e.getMessage(), e);
            routeInfoContainer.setVisibility(View.GONE);
        }
    }

    /**
     * 선택된 경로에 해당하는 라디오 버튼 업데이트 (이벤트 발생 없이)
     */
    private void updateSelectedRadioButtonSilently(RouteCandidate candidate) {
        try {
            if (candidate == null || rgRouteSelection == null) return;

            // 이벤트 발생 방지
            isUpdatingRadioButtons = true;

            int candidateIndex = routeCandidates.indexOf(candidate);

            // 해당 인덱스를 태그로 가진 라디오 버튼 찾기
            for (int i = 0; i < rgRouteSelection.getChildCount(); i++) {
                View child = rgRouteSelection.getChildAt(i);
                if (child instanceof RadioButton) {
                    RadioButton radioButton = (RadioButton) child;
                    if (radioButton.getTag() != null &&
                            (Integer) radioButton.getTag() == candidateIndex) {
                        radioButton.setChecked(true);
                        break;
                    }
                }
            }

            // 플래그 해제
            isUpdatingRadioButtons = false;

        } catch (Exception e) {
            Log.e(TAG, "라디오 버튼 상태 업데이트 오류: " + e.getMessage(), e);
            isUpdatingRadioButtons = false; // 오류 시에도 플래그 해제
        }
    }

    /**
     * 선택된 경로에 해당하는 라디오 버튼 업데이트
     */
    private void updateSelectedRadioButton(RouteCandidate candidate) {
        try {
            if (candidate == null || rgRouteSelection == null) return;

            int candidateIndex = routeCandidates.indexOf(candidate);

            // 해당 인덱스를 태그로 가진 라디오 버튼 찾기
            for (int i = 0; i < rgRouteSelection.getChildCount(); i++) {
                View child = rgRouteSelection.getChildAt(i);
                if (child instanceof RadioButton) {
                    RadioButton radioButton = (RadioButton) child;
                    if (radioButton.getTag() != null &&
                            (Integer) radioButton.getTag() == candidateIndex) {
                        radioButton.setChecked(true);
                        break;
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "라디오 버튼 상태 업데이트 오류: " + e.getMessage(), e);
        }
    }

    /**
     * 라디오 버튼들 초기화
     */
    private void clearRouteSelectionButtons() {
        try {
            if (rgRouteSelection != null) {
                isUpdatingRadioButtons = true; // 이벤트 방지
                rgRouteSelection.removeAllViews();
                isUpdatingRadioButtons = false;
            }
            if (routeInfoContainer != null) {
                routeInfoContainer.setVisibility(View.GONE);
            }
            Log.d(TAG, "회색바 라디오 버튼 초기화 완료");
        } catch (Exception e) {
            Log.e(TAG, "회색바 라디오 버튼 초기화 오류: " + e.getMessage(), e);
            isUpdatingRadioButtons = false;
        }
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
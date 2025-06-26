# :sun_with_face: 그림자 고려한 보행자 경로 표시 지도 앱 :cityscape:


# 📂 프로젝트 구조

```
capstone/
├── Backend/                              # Spring Boot 백엔드
│   ├── src/main/java/com/example/Backend/
│   │   ├── controller/                   # REST API 컨트롤러
│   │   │   ├── ApiKeyController.java     # API 키 제공
│   │   │   ├── RouteCandidateController.java # 경로 후보 API
│   │   │   └── ShadowRouteController.java     # 그림자 경로 API
│   │   ├── service/                      # 비즈니스 로직
│   │   │   ├── RouteCandidateService.java     # 경로 후보 생성
│   │   │   ├── ShadowRouteService.java        # 그림자 경로 계산
│   │   │   ├── ShadowService.java             # 그림자 분석
│   │   │   ├── TmapApiService.java            # T맵 API 연동
│   │   │   └── WeatherService.java            # 날씨 API 연동
│   │   ├── model/                        # 데이터 모델
│   │   │   ├── Route.java                # 경로 정보
│   │   │   ├── RouteCandidate.java       # 경로 후보
│   │   │   ├── RoutePoint.java           # 경로 포인트
│   │   │   ├── ShadowArea.java           # 그림자 영역
│   │   │   └── SunPosition.java          # 태양 위치
│   │   ├── util/                         # 유틸리티
│   │   │   └── SunPositionCalculator.java     # 태양 위치 계산
│   │   ├── config/                       # 설정
│   │   │   └── AppConfig.java            # 애플리케이션 설정
│   │   └── BackendApplication.java       # 메인 애플리케이션
│   └── src/main/resources/
│       └── application.properties        # 환경 설정
│       └── application-API-KEY.properties # Tmap API key 저장(ec2 인스턴스 내에 저장됨)
│
├── Frontend/                             # Android 프론트엔드
│   └── app/src/main/java/com/example/front/
│       ├── activity/                     # 액티비티
│       │   └── MapActivity.java          # 메인 지도 화면
│       ├── adapter/                      # 어댑터
│       │   └── POIAdapter.java           # 검색 결과 리스트
│       ├── model/                        # 데이터 모델
│       │   ├── Route.java                # 경로 정보
│       │   ├── RouteCandidate.java       # 경로 후보
│       │   └── RoutePoint.java           # 경로 포인트
│       └── MainActivity.java             # 시작 화면
│
└── README.md                             # 프로젝트 문서
```

## 🗄️ 데이터베이스 구조

### 건물 정보 테이블 (`AL_D010_26_20250304`)

부산광역시 GIS 건물 통합 정보, 그림자 계산의 기반이 되는 핵심 데이터.

| 컬럼명 | 항목명 | 타입 | 설명 |
|--------|--------|------|------|
| `A0` | 원천도형ID | VARCHAR | 건물 도형의 고유 식별자 |
| `A1` | GIS건물통합식별번호 | VARCHAR | GIS 시스템 내 건물 통합 번호 |
| `A2` | 고유번호 | VARCHAR | 건물별 고유 식별 번호 |
| `A3` | 법정동코드 | VARCHAR | 행정구역 법정동 코드 |
| `A4` | 법정동명 | VARCHAR | 법정동 이름 |
| `A5` | 지번 | VARCHAR | 토지 지번 정보 |
| `A6` | 특수지코드 | VARCHAR | 특수 지역 구분 코드 |
| `A7` | 특수지구분명 | VARCHAR | 특수 지역 구분명 |
| `A8` | 건축물용도코드 | VARCHAR | 건물 용도 분류 코드 |
| `A9` | 건축물용도명 | VARCHAR | 건물 용도명 (주거용, 상업용 등) |
| `A10` | 건축물구조코드 | VARCHAR | 건물 구조 분류 코드 |
| `A11` | 건축물구조명 | VARCHAR | 건물 구조명 (철근콘크리트, 철골 등) |
| `A12` | 건축물면적(㎡) | DOUBLE | 건물 바닥 면적 |
| `A13` | 사용승인일자 | DATE | 건축물 사용 승인 받은 날짜 |
| `A14` | 연면적 | DOUBLE | 건물 전체 층 면적의 합 |
| `A15` | 대지면적(㎡) | DOUBLE | 건물이 위치한 토지 면적 |
| **`A16`** | **높이(m)** | **DOUBLE** | **건물 높이** |
| `A17` | 건폐율(%) | DOUBLE | 대지 면적 대비 건축 면적 비율 |
| `A18` | 용적율(%) | DOUBLE | 대지 면적 대비 연면적 비율 |
| `A19` | 건축물ID | VARCHAR | 건축물 고유 ID |
| `A20` | 위반건축물여부 | BOOLEAN | 불법 건축물 여부 |
| `A21` | 참조체계연계키 | VARCHAR | 다른 시스템과의 연계 키 |
| `A22` | 데이터기준일자 | DATE | 데이터 생성/갱신 기준일 |
| `A23` | 원천시도시군구코드 | VARCHAR | 시도/시군구 행정 코드 |
| `A24` | 건물명 | VARCHAR | 건물 이름 |
| `A25` | 건물동명 | VARCHAR | 건물 동 이름 |
| `A26` | 지상층_수 | INTEGER | 지상층 개수 |
| `A27` | 지하층_수 | INTEGER | 지하층 개수 |
| `A28` | 데이터생성변경일자 | TIMESTAMP | 데이터 최종 수정 일시 |
| **`geom`** | **건물 도형 정보** | **GEOMETRY** | **PostGIS 공간 데이터** ⭐ |


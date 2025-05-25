package com.example.Backend.util;

import com.example.Backend.model.SunPosition;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.JulianFields;

/**
 * 태양 위치 계산 유틸리티 클래스
 * NOAA 태양 위치 알고리즘 기반
 */
public class SunPositionCalculator {

    /**
     * 주어진 시간 및 위치에 대한 태양 위치(고도, 방위각) 계산
     */
    public static SunPosition calculate(double latitude, double longitude, LocalDateTime dateTime) {
        // 시간대 적용 (서울 시간대 - Asia/Seoul)
        ZonedDateTime zonedDateTime = dateTime.atZone(ZoneId.of("Asia/Seoul"));

        // *** 핵심 수정: UTC 시간으로 변환 ***
        ZonedDateTime utcDateTime = zonedDateTime.withZoneSameInstant(ZoneId.of("UTC"));

        // 1. 날짜를 율리우스 일로 변환 (UTC 기준)
        double julianDay = utcDateTime.getLong(JulianFields.JULIAN_DAY) +
                utcDateTime.getHour() / 24.0 +  // UTC 시간 사용
                utcDateTime.getMinute() / 1440.0 +
                utcDateTime.getSecond() / 86400.0;

        // 2. 율리우스 세기 계산
        double julianCentury = (julianDay - 2451545.0) / 36525.0;

        // 3. 기하학적 평균 경도 계산 (도)
        double geomMeanLongSun = (280.46646 + julianCentury * (36000.76983 + julianCentury * 0.0003032)) % 360;

        // 4. 기하학적 평균 근점 이각 계산 (도)
        double geomMeanAnomSun = 357.52911 + julianCentury * (35999.05029 - 0.0001537 * julianCentury);

        // 5. 지구 궤도 이심률 계산
        double eccentEarthOrbit = 0.016708634 - julianCentury * (0.000042037 + 0.0000001267 * julianCentury);

        // 6. 태양 중심 방정식 계산
        double sunEqOfCtr = Math.sin(Math.toRadians(geomMeanAnomSun)) *
                (1.914602 - julianCentury * (0.004817 + 0.000014 * julianCentury)) +
                Math.sin(Math.toRadians(2 * geomMeanAnomSun)) *
                        (0.019993 - 0.000101 * julianCentury) +
                Math.sin(Math.toRadians(3 * geomMeanAnomSun)) * 0.000289;

        // 7. 태양 진경도 계산
        double sunTrueLong = geomMeanLongSun + sunEqOfCtr;

        // 8. 태양 진근점 이각 계산
        double sunTrueAnom = geomMeanAnomSun + sunEqOfCtr;

        // 9. 태양 반지름 벡터 계산 (AU)
        double sunRadVector = (1.000001018 * (1 - eccentEarthOrbit * eccentEarthOrbit)) /
                (1 + eccentEarthOrbit * Math.cos(Math.toRadians(sunTrueAnom)));

        // 10. 태양 겉보기 경도 계산
        double sunAppLong = sunTrueLong - 0.00569 - 0.00478 *
                Math.sin(Math.toRadians(125.04 - 1934.136 * julianCentury));

        // 11. 지구 평균 축 기울기 계산
        double meanObliqEcliptic = 23 + (26 + ((21.448 - julianCentury *
                (46.815 + julianCentury * (0.00059 - julianCentury * 0.001813)))) / 60) / 60;

        // 12. 지구 축 기울기 보정
        double obliqCorr = meanObliqEcliptic + 0.00256 *
                Math.cos(Math.toRadians(125.04 - 1934.136 * julianCentury));

        // 13. 태양 적위 계산 (도)
        double sunDeclin = Math.toDegrees(Math.asin(Math.sin(Math.toRadians(obliqCorr)) *
                Math.sin(Math.toRadians(sunAppLong))));

        // 14. 이심 변수 계산
        double y = Math.tan(Math.toRadians(obliqCorr / 2)) * Math.tan(Math.toRadians(obliqCorr / 2));
        double eqOfTime = 4 * Math.toDegrees(y * Math.sin(2 * Math.toRadians(geomMeanLongSun)) -
                2 * eccentEarthOrbit * Math.sin(Math.toRadians(geomMeanAnomSun)) +
                4 * eccentEarthOrbit * y * Math.sin(Math.toRadians(geomMeanAnomSun)) *
                        Math.cos(2 * Math.toRadians(geomMeanLongSun)) -
                0.5 * y * y * Math.sin(4 * Math.toRadians(geomMeanLongSun)) -
                1.25 * eccentEarthOrbit * eccentEarthOrbit *
                        Math.sin(2 * Math.toRadians(geomMeanAnomSun)));

        // *** 핵심 수정: 시간각 계산을 Local Solar Time 기준으로 수정 ***
        // 15. 로컬 태양시 계산
        double localSolarTime = (utcDateTime.getHour() * 60 + utcDateTime.getMinute() +
                utcDateTime.getSecond() / 60.0 + eqOfTime + longitude * 4) / 60.0;

        // 시간각 계산 (정오 기준으로 15도씩 변화)
        double hourAngle = (localSolarTime - 12.0) * 15.0;

        // 16. 태양 천정각 계산 (도)
        double solarZenithAngle = Math.toDegrees(Math.acos(
                Math.sin(Math.toRadians(latitude)) * Math.sin(Math.toRadians(sunDeclin)) +
                        Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(sunDeclin)) *
                                Math.cos(Math.toRadians(hourAngle))));

        // 17. 태양 고도각 계산 (도)
        double solarElevationAngle = 90 - solarZenithAngle;

        // 18. 대기 굴절 보정
        double atmosphericRefraction;
        if (solarElevationAngle > 85) {
            atmosphericRefraction = 0;
        } else if (solarElevationAngle > 5) {
            atmosphericRefraction = 58.1 / Math.tan(Math.toRadians(solarElevationAngle)) -
                    0.07 / Math.pow(Math.tan(Math.toRadians(solarElevationAngle)), 3) +
                    0.000086 / Math.pow(Math.tan(Math.toRadians(solarElevationAngle)), 5);
        } else if (solarElevationAngle > -0.575) {
            atmosphericRefraction = 1735 + solarElevationAngle *
                    (-518.2 + solarElevationAngle * (103.4 + solarElevationAngle *
                            (-12.79 + solarElevationAngle * 0.711)));
        } else {
            atmosphericRefraction = -20.772 / Math.tan(Math.toRadians(solarElevationAngle));
        }
        atmosphericRefraction = atmosphericRefraction / 3600; // 도 단위로 변환

        // 19. 보정된 태양 고도각
        double solarElevationCorrected = solarElevationAngle + atmosphericRefraction;

        // 20. 태양 방위각 계산 (도, 북쪽=0, 동쪽=90, 남쪽=180, 서쪽=270)
        double solarAzimuth;
        if (hourAngle > 0) {
            solarAzimuth = (Math.toDegrees(Math.acos(
                    (Math.sin(Math.toRadians(latitude)) * Math.cos(Math.toRadians(solarZenithAngle)) -
                            Math.sin(Math.toRadians(sunDeclin))) /
                            (Math.cos(Math.toRadians(latitude)) * Math.sin(Math.toRadians(solarZenithAngle))))) + 180) % 360;
        } else {
            solarAzimuth = (540 - Math.toDegrees(Math.acos(
                    (Math.sin(Math.toRadians(latitude)) * Math.cos(Math.toRadians(solarZenithAngle)) -
                            Math.sin(Math.toRadians(sunDeclin))) /
                            (Math.cos(Math.toRadians(latitude)) * Math.sin(Math.toRadians(solarZenithAngle)))))) % 360;
        }

        // *** 디버깅 로그 추가 ***
        System.out.printf("태양 위치 계산: 입력시간=%s, UTC=%s, 경도=%.3f, 위도=%.3f%n",
                dateTime, utcDateTime, longitude, latitude);
        System.out.printf("계산 결과: 고도=%.2f도, 방위각=%.2f도, 시간각=%.2f도%n",
                solarElevationCorrected, solarAzimuth, hourAngle);

        return new SunPosition(solarElevationCorrected, solarAzimuth);
    }

    /**
     * 건물 그림자 길이 계산 (미터)
     */
    public static double calculateShadowLength(double buildingHeight, double solarElevation) {
        // 태양 고도가 0도 이하면 그림자가 무한대
        if (solarElevation <= 0) {
            return 10000; // 매우 큰 값 반환
        }

        // 그림자 길이 = 건물 높이 / tan(태양 고도)
        return buildingHeight / Math.tan(Math.toRadians(solarElevation));
    }
}
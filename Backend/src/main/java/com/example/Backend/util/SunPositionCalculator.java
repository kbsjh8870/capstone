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

        // 1. 날짜를 율리우스 일로 변환
        double julianDay = zonedDateTime.getLong(JulianFields.JULIAN_DAY) +
                (zonedDateTime.getHour() - 9) / 24.0 + // 9시간 조정 (한국 시간)
                zonedDateTime.getMinute() / 1440.0 +
                zonedDateTime.getSecond() / 86400.0;

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

        // 9. 태양 반경 벡터 계산 (AU)
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

        // 15. 시간각 계산
        // 로컬 시간을 사용 (이미 ZonedDateTime으로 시간대가 적용됨)
        double trueSolarTime = (zonedDateTime.getHour() * 60 + zonedDateTime.getMinute() +
                zonedDateTime.getSecond() / 60 + eqOfTime * 4 - longitude * 4) % 1440;

        double hourAngle;
        if (trueSolarTime < 0) {
            hourAngle = trueSolarTime / 4 + 180;
        } else {
            hourAngle = trueSolarTime / 4 - 180;
        }

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
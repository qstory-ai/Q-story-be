package com.qstory.backend.common.util;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import java.time.Year;
import java.time.ZoneId;

/**
 * 아이 나이는 "N세"를 직접 입력받지 않고 출생연도(~년생)로 받아 계산한다. 그래야 해가 바뀌어도
 * 저장된 값이 낡지 않는다(예전엔 "7세"가 그대로 남아 이듬해에도 7세였다).
 *
 * <p>계산 규칙은 <b>연 나이</b>(올해 - 출생연도)다. 생일을 받지 않으므로 만 나이는 정확히 낼 수 없고,
 * 학년·또래 구분에 쓰이는 "2019년생 = 7세" 관행과도 이 값이 맞는다. 프론트
 * entities/child/model/age-band.ts의 ageFromBirthYear와 같은 규칙.
 */
public final class ChildAge {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 서비스 대상 밖의 오타(예: 1999년생)를 걸러내는 범위 - 태어난 해부터 14년 전까지. */
    private static final int MAX_AGE = 14;

    private ChildAge() {}

    public static int currentYear() {
        return Year.now(KST).getValue();
    }

    /** null이면 null(출생연도 미입력 - 예전 데이터). 범위를 벗어나면 400. */
    public static Integer validateBirthYear(Integer birthYear) {
        if (birthYear == null) return null;
        int year = currentYear();
        if (birthYear > year || birthYear < year - MAX_AGE) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "아이의 출생연도를 다시 확인해 주세요.");
        }
        return birthYear;
    }

    public static int ageInYears(int birthYear) {
        return Math.max(0, currentYear() - birthYear);
    }

    /** 부모 아이 프로필의 연령대 구간(fe AGE_BANDS와 동일). */
    public static String parentBand(int birthYear) {
        int age = ageInYears(birthYear);
        if (age <= 5) return "4-5";
        if (age <= 7) return "6-7";
        if (age <= 9) return "8-9";
        if (age <= 11) return "10-11";
        return "12+";
    }

    /** 선생님 학생의 한 살 단위 라벨("7세") - 기존 tutor_student.age_band 표기와 같다. */
    public static String tutorLabel(int birthYear) {
        return ageInYears(birthYear) + "세";
    }
}

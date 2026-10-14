package com.qstory.backend.common.util;

import java.util.regex.Pattern;

/**
 * 아이 발화를 저장하기 전에 연락처만 가린다(개인정보 처리방침 제8조 4항, 공개 전 확인사항 A2).
 *
 * <p>보호자 리포트에는 아이가 한 말이 그대로 보여야 해서(Q-39) 이름이나 일반 숫자는 건드리지 않는다 - 전화번호(휴대전화·지역번호
 * 일반전화·인터넷전화), 이메일, 주민등록번호 모양만 {@code [전화번호]}·{@code [이메일]}·{@code [주민번호]}로 바꾼다. "3번", "10마리",
 * "2026년" 같은 이야기 속 숫자는 0으로 시작하는 번호 모양이 아니라서 그대로 남는다. STT가 숫자를 한글("공일공")로 적으면 잡지 못한다.
 */
public final class ContactInfoMasker {

    public static final String PHONE = "[전화번호]";
    public static final String EMAIL = "[이메일]";
    public static final String RESIDENT_NUMBER = "[주민번호]";

    /** 숫자 묶음 사이 구분 - 하이픈·점(앞뒤 공백 허용), 공백 하나, 또는 붙여 씀. */
    private static final String SEP = "(?:[ \\t]*[-.][ \\t]*|[ \\t]?)";

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[A-Za-z0-9._%+-]+[ \\t]*@[ \\t]*[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");

    /** 생년월일 6자리 + 성별 자리(1~8) + 6자리. 하이픈은 있어도 없어도 된다. */
    private static final Pattern RESIDENT_NUMBER_PATTERN = Pattern.compile(
            "(?<![0-9])[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[ \\t]*-?[ \\t]*[1-8][0-9]{6}(?![0-9])");

    /** 휴대전화 010·011·016~019, 국가번호(+82) 표기 포함. */
    private static final Pattern MOBILE_PATTERN = Pattern.compile(
            "(?<![0-9])(?:\\+82[ \\t-]?|0)1[016789]" + SEP + "[0-9]{3,4}" + SEP + "[0-9]{4}(?![0-9])");

    /** 지역번호 일반전화(02, 031~065), 인터넷전화 070, 평생번호 050X. */
    private static final Pattern LANDLINE_PATTERN = Pattern.compile(
            "(?<![0-9])0(?:2|[3-6][1-5]|70|50[2-8])" + SEP + "[0-9]{3,4}" + SEP + "[0-9]{4}(?![0-9])");

    private ContactInfoMasker() {}

    /** null은 null, 가릴 것이 없으면 같은 문자열을 돌려준다. */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) return text;
        String masked = EMAIL_PATTERN.matcher(text).replaceAll(EMAIL);
        masked = RESIDENT_NUMBER_PATTERN.matcher(masked).replaceAll(RESIDENT_NUMBER);
        masked = MOBILE_PATTERN.matcher(masked).replaceAll(PHONE);
        masked = LANDLINE_PATTERN.matcher(masked).replaceAll(PHONE);
        return masked;
    }
}

package com.qstory.backend.org.service;

/**
 * 알림 문구의 조사(076) - 아이·반 이름 끝 글자의 받침에 맞춰 붙인다("민서가", "하준이", "햇님반으로", "별반로"가 아니라
 * "별반으로"). 한글이 아닌 글자로 끝나면 "이(가)", "(으)로"처럼 둘 다 적는다.
 */
final class KoreanParticle {

    private KoreanParticle() {}

    /** 주격 조사 이/가. */
    static String subject(String word) {
        Integer batchim = batchim(word);
        if (batchim == null) return word + "이(가)";
        return word + (batchim == 0 ? "가" : "이");
    }

    /** 방향 조사 으로/로 - 받침이 없거나 ㄹ 받침이면 "로". */
    static String direction(String word) {
        Integer batchim = batchim(word);
        if (batchim == null) return word + "(으)로";
        return word + (batchim == 0 || batchim == 8 ? "로" : "으로");
    }

    /** 끝 글자의 받침 번호(0이면 받침 없음), 한글 음절이 아니면 null. */
    private static Integer batchim(String word) {
        if (word == null || word.isEmpty()) return null;
        char last = word.charAt(word.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) return null;
        return (last - 0xAC00) % 28;
    }
}

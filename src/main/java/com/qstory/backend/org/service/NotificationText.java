package com.qstory.backend.org.service;

/** 반 이름처럼 사용자가 정한 글자가 들어가는 알림 문구를 notifications 열 길이(038: title 160, body 400)에 맞춘다. */
final class NotificationText {

    private static final int TITLE_MAX = 160;
    private static final int BODY_MAX = 400;

    private NotificationText() {}

    static String title(String text) {
        return clip(text, TITLE_MAX);
    }

    static String body(String text) {
        return clip(text, BODY_MAX);
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}

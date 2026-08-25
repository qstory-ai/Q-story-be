package com.qstory.backend.launchnotification.dto;

public record LaunchNotificationSubmission(
        String parentName,
        String email,
        String phone,
        String childGender,
        /** "5세", "3개월"처럼 자유 텍스트다 - 돌 전 아이는 개월 수로 말하는 경우가 많아 숫자로 강제하지 않는다. */
        String childAge,
        String discoverySource,
        Boolean wantsContact) {}

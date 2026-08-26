package com.qstory.backend.tutor.dto;

public record CreateTutorScheduleRequest(
        String weekday, String startTime, String endTime, String startDate, String location, Boolean reminderEnabled) {}

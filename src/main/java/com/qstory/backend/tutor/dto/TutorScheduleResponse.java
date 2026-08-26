package com.qstory.backend.tutor.dto;

import com.qstory.backend.tutor.entity.TutorSchedule;
import java.util.UUID;

public record TutorScheduleResponse(
        UUID id, UUID tutorStudentId, String studentName, String weekday, String startTime, String endTime,
        String startDate, String location, boolean reminderEnabled) {

    public static TutorScheduleResponse of(TutorSchedule schedule) {
        return new TutorScheduleResponse(
                schedule.getId(), schedule.getTutorStudent().getId(), schedule.getTutorStudent().getName(),
                schedule.getWeekday().name(), schedule.getStartTime().toString(), schedule.getEndTime().toString(),
                schedule.getStartDate().toString(), schedule.getLocation(), schedule.isReminderEnabled());
    }
}

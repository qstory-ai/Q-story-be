package com.qstory.backend.org.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.qstory.backend.storyreport.dto.StoryCompletionSummary;
import com.qstory.backend.storyreport.entity.StoryCompletion;
import com.qstory.backend.tutor.entity.TutorStudent;
import java.util.List;
import java.util.UUID;

/**
 * 반 상세의 리포트 한 줄 - 기존 요약 항목(StoryCompletionSummary, endStatus 포함)에 진행한 선생님 이름과 참여 학생
 * 이름을 더한다. JSON은 요약 필드가 최상위에 펼쳐진다.
 */
public record ClassReportResponse(
        @JsonUnwrapped StoryCompletionSummary summary, String tutorName, List<String> studentNames) {

    public UUID id() {
        return summary.id();
    }

    public static ClassReportResponse of(StoryCompletion completion) {
        return new ClassReportResponse(
                StoryCompletionSummary.of(completion),
                completion.getUser().getDisplayName(),
                completion.getParticipants().stream().map(TutorStudent::getName).toList());
    }
}

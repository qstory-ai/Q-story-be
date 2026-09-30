package com.qstory.backend.org.dto;

import com.qstory.backend.tutor.entity.TutorStudent;
import java.util.UUID;

/**
 * 반 초대로 들어온 학부모가 "선생님 명단에서 우리 아이"를 고를 때 보는 한 줄 - 아직 학부모가 연결되지 않은
 * 학생의 이름만 내보낸다(연령·부모 정보 없음). 반 코드를 가진 사람만 볼 수 있다.
 */
public record ClassRosterEntryResponse(UUID id, String name) {

    public static ClassRosterEntryResponse of(TutorStudent student) {
        return new ClassRosterEntryResponse(student.getId(), student.getName());
    }
}

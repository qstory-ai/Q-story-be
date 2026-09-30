package com.qstory.backend.tutor.service;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.tutor.dto.BulkCreateTutorStudentsRequest;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** 일괄 등록의 입력 검증은 저장소를 건드리기 전에 끝나야 한다 - 의존성 없이 확인할 수 있다. */
class TutorStudentBulkValidationTest {

    private final TutorStudentService service =
            new TutorStudentService(null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    @Test
    void rejectsEmptyList() {
        assertThrows(ApiException.class, () -> service.createStudentsBulk(null,
                new BulkCreateTutorStudentsRequest(List.of(), "CLASS", null, null, null, 2019)));
        assertThrows(ApiException.class, () -> service.createStudentsBulk(null, null));
    }

    @Test
    void rejectsMoreThanTheLimit() {
        List<BulkCreateTutorStudentsRequest.Student> tooMany = IntStream.rangeClosed(1, TutorStudentService.BULK_STUDENT_LIMIT + 1)
                .mapToObj(i -> new BulkCreateTutorStudentsRequest.Student("학생" + i, null))
                .toList();
        assertThrows(ApiException.class, () -> service.createStudentsBulk(null,
                new BulkCreateTutorStudentsRequest(tooMany, "CLASS", null, null, null, 2019)));
    }
}

package com.qstory.backend.extension.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qstory.backend.common.util.ChildAge;
import com.qstory.backend.conversationrecord.ConversationAttribution;
import com.qstory.backend.common.enums.ConversationInputMode;
import com.qstory.backend.parent.child.entity.Child;
import com.qstory.backend.parent.child.repository.ChildRepository;
import com.qstory.backend.tutor.entity.TutorStudent;
import com.qstory.backend.tutor.repository.TutorStudentRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 나이대는 요청한 사람의 아이·학생 프로필에서만, 6세 이하 5-6 / 7세 이상 7-9, 모르면 UNKNOWN 그대로. */
class LearningAgeResolverTest {

    private final ChildRepository children = mock(ChildRepository.class);
    private final TutorStudentRepository students = mock(TutorStudentRepository.class);
    private final LearningAgeResolver resolver = new LearningAgeResolver(children, students);
    private final UUID userId = UUID.randomUUID();
    private final UUID childId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    @BeforeEach void on() { System.setProperty("QSTORY_STORYPAL_ENABLED", "true"); }
    @AfterEach void reset() { System.clearProperty("QSTORY_STORYPAL_ENABLED"); }

    private ConversationAttribution parentOf(UUID child) {
        return new ConversationAttribution(null, child, null, null, ConversationInputMode.TEXT, userId, "PARENT");
    }

    private Child child(Integer birthYear, String band) {
        Child child = mock(Child.class);
        when(child.getBirthYear()).thenReturn(birthYear);
        when(child.getAgeBand()).thenReturn(band);
        return child;
    }

    @Test
    void parentChildBirthYearSetsBandAndStartingDifficulty() {
        int sevenYearsOld = ChildAge.currentYear() - 7;
        Child seven = child(sevenYearsOld, null);
        when(children.findByIdAndParent_Id(childId, userId)).thenReturn(Optional.of(seven));

        LearningState next = resolver.apply(LearningState.initial("UNKNOWN"), parentOf(childId));

        assertEquals(LearningState.initial("7-9"), next);
    }

    @Test
    void midConversationKeepsStateAndOnlyChangesBand() {
        Child legacy = child(null, "4-5");
        when(children.findByIdAndParent_Id(childId, userId)).thenReturn(Optional.of(legacy));
        LearningState current = new LearningState("UNKNOWN", 1, 2, 0, 3, "WH", "SUPPORT");

        LearningState next = resolver.apply(current, parentOf(childId));

        assertEquals(new LearningState("5-6", 1, 2, 0, 3, "WH", "SUPPORT"), next);
    }

    @Test
    void tutorStudentLegacyLabelIsRead() {
        TutorStudent student = mock(TutorStudent.class);
        when(student.getBirthYear()).thenReturn(null);
        when(student.getAgeBand()).thenReturn("6세");
        when(students.findByIdAndTutor_IdAndDeletedAtIsNull(studentId, userId)).thenReturn(Optional.of(student));
        ConversationAttribution tutor =
                new ConversationAttribution(null, null, studentId, null, ConversationInputMode.VOICE, userId, "TUTOR");

        assertEquals("5-6", resolver.apply(LearningState.initial("UNKNOWN"), tutor).ageBand());
    }

    @Test
    void someoneElsesChildOrNoProfileStaysUnknown() {
        when(children.findByIdAndParent_Id(any(), any())).thenReturn(Optional.empty());
        LearningState unknown = LearningState.initial("UNKNOWN");

        assertSame(unknown, resolver.apply(unknown, parentOf(childId)));
        assertSame(unknown, resolver.apply(unknown,
                new ConversationAttribution(null, childId, null, null, ConversationInputMode.TEXT, null, null)));
    }

    @Test
    void knownBandOrStoryPalOffSkipsLookup() {
        LearningState known = LearningState.initial("5-6");
        assertSame(known, resolver.apply(known, parentOf(childId)));

        System.setProperty("QSTORY_STORYPAL_ENABLED", "false");
        LearningState unknown = LearningState.initial("UNKNOWN");
        assertSame(unknown, resolver.apply(unknown, parentOf(childId)));

        verify(children, never()).findByIdAndParent_Id(any(), any());
    }

    @Test
    void lookupFailureLeavesStateAlone() {
        when(children.findByIdAndParent_Id(any(), any())).thenThrow(new IllegalStateException("db down"));
        LearningState unknown = LearningState.initial("UNKNOWN");

        assertSame(unknown, resolver.apply(unknown, parentOf(childId)));
    }

    @Test
    void ageMapping() {
        assertEquals("UNKNOWN", LearningAgeResolver.band(null));
        assertEquals("5-6", LearningAgeResolver.band(4));
        assertEquals("5-6", LearningAgeResolver.band(6));
        assertEquals("7-9", LearningAgeResolver.band(7));
        assertEquals("7-9", LearningAgeResolver.band(11));
        assertEquals(8, LearningAgeResolver.ageOf(null, "8-9"));
    }
}

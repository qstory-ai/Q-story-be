package com.qstory.backend.story.repository;

import com.qstory.backend.story.entity.StoryDiscussionTopic;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryDiscussionTopicRepository extends JpaRepository<StoryDiscussionTopic, String> {

    List<StoryDiscussionTopic> findByStory_Id(String storyId);
}

package com.codescope.domain.repo.entity;

import com.codescope.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "topics")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Topic extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "topic_id")
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    public static Topic of(String name) {
        Topic topic = new Topic();
        topic.name = name;
        return topic;
    }
}

package com.codescope.domain.user;

import com.codescope.common.entity.BaseEntity;
import com.codescope.domain.repo.entity.Topic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "user_skills", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "topic_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSkill extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_skill_id")
    private Long userSkillId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "topic_id", nullable = false)
    private Topic topic;

    @Builder
    private UserSkill(User user, Topic topic) {
        this.user = user;
        this.topic = topic;
    }

    public static UserSkill of(User user, Topic topic) {
        return UserSkill.builder()
                .user(user)
                .topic(topic)
                .build();
    }
}

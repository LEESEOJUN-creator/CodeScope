package com.codescope.infra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

@Configuration
@EnableCaching
public class RedisConfig {

    @Bean
    public RedisCacheManager redisCacheManager(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        // 왜 new ObjectMapper()가 아니라 Spring Boot 자동설정 Bean을 복사해서 쓰는가
        // (2026-08-16, GET /api/repos/{id} 500 연쇄 조사):
        //   처음엔 new ObjectMapper()에 JavaTimeModule만 수동으로 얹었는데
        //   (LocalDateTime 직렬화 실패 해결용), Jackson2ObjectMapperBuilder가
        //   자동 등록해주는 ParameterNamesModule 등 다른 설정이 전부 빠진 상태였다.
        //   Spring이 주입해주는 objectMapper Bean은 JavaTimeModule을 포함해 이미
        //   다 갖춰져 있으므로 .copy()로 복제해 Redis 전용 설정(defaultTyping)만
        //   더하는 쪽이 더 안전하다 — HTTP 응답 직렬화와 Redis 캐시 직렬화가
        //   서로 다른 설정으로 갈라져 있다가 또 다른 필드 하나 때문에 이런 문제가
        //   재발하는 걸 막는다.
        ObjectMapper redisObjectMapper = objectMapper.copy();

        // 왜 new GenericJackson2JsonRedisSerializer(mapper)가 아니라 builder()를 쓰는가
        // (2026-08-16, 위 수정 직후 재발견): 그 생성자는 커스텀 ObjectMapper를 받으면
        // 타입 정보(@class 힌트)를 JSON에 안 붙인다(소스 확인 — GenericJackson2Json
        // RedisSerializer 소스의 (ObjectMapper) 생성자만 보면 setDefaultTyping 호출이
        // 없고, (String typeHintPropertyName) 계열 생성자만 호출함). 그 결과 캐시에는
        // 성공적으로 저장되지만, 읽어올 때 구체 타입(GithubRepositoryResponse)으로
        // 복원 못 하고 LinkedHashMap으로 역직렬화되어 ClassCastException이 났다.
        // builder().objectMapper(...).defaultTyping(true)는 커스텀 매퍼를 쓰면서도
        // 타입 힌트를 명시적으로 다시 활성화해준다.
        GenericJackson2JsonRedisSerializer serializer = GenericJackson2JsonRedisSerializer.builder()
                .objectMapper(redisObjectMapper)
                .defaultTyping(true)
                .build();

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}

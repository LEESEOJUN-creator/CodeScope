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
        // 왜 new ObjectMapper()가 아니라 Spring Boot 자동설정 Bean을 복사해서 쓰는가:
        //   Spring이 주입해주는 objectMapper Bean은 JavaTimeModule 등을 포함해 이미
        //   다 갖춰져 있다. 직접 새로 만들면 이런 설정이 빠져 LocalDateTime 직렬화
        //   실패 같은 문제가 재발한다 — .copy()로 복제해 Redis 전용 설정만 더한다.
        ObjectMapper redisObjectMapper = objectMapper.copy();

        // 왜 new GenericJackson2JsonRedisSerializer(mapper)가 아니라 builder()를 쓰는가:
        // 그 생성자는 커스텀 ObjectMapper를 받으면 타입 정보(@class 힌트)를 JSON에
        // 안 붙여서, 캐시 저장은 되지만 읽을 때 구체 타입으로 복원 못 하고
        // LinkedHashMap으로 역직렬화되어 ClassCastException이 난다.
        // builder().objectMapper(...).defaultTyping(true)가 타입 힌트를 다시 활성화한다.
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

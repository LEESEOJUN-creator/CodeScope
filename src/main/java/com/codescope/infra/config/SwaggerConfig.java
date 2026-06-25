package com.codescope.infra.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("CodeScope API")
                        .description("GitHub 오픈소스 생태계 실시간 분석 및 AI 추천 플랫폼")
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("이서준")
                                .email("seojun7988@naver.com")
                                .url("https://github.com/LEESEOJUN-creator/CodeScope")));
    }
}
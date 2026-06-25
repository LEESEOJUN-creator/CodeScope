package com.codescope.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Hello", description = "서버 연결 테스트")
@RestController
@RequestMapping("/api")
public class HelloController {

    @Operation(summary = "헬스 체크", description = "서버가 정상 동작하는지 확인합니다")
    @GetMapping("/hello")
    public ResponseEntity<String> hello() {
        return ResponseEntity.ok("CodeScope 서버 정상 동작 중");
    }
}
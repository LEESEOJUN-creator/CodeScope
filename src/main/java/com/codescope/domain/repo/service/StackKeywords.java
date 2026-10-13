package com.codescope.domain.repo.service;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class StackKeywords {
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("자바", "java"), Map.entry("파이썬", "python"), Map.entry("자바스크립트", "javascript"),
            Map.entry("타입스크립트", "typescript"), Map.entry("js", "javascript"), Map.entry("ts", "typescript"),
            Map.entry("spring boot", "spring-boot"), Map.entry("springboot", "spring-boot"),
            Map.entry("스프링부트", "spring-boot"), Map.entry("스프링 부트", "spring-boot"),
            Map.entry("k8s", "kubernetes"), Map.entry("golang", "go"), Map.entry("next.js", "nextjs"),
            Map.entry("node.js", "nodejs"), Map.entry("리액트", "react"), Map.entry("카프카", "kafka"));

    public List<String> parse(String input) {
        if (input == null || input.isBlank() || input.length() > 300) {
            throw new IllegalArgumentException("기술 스택을 1~300자 이내로 입력해주세요.");
        }
        var terms = Arrays.stream(input.split("[,;\\n]")).map(String::strip)
                .filter(s -> !s.isEmpty()).map(s -> s.toLowerCase(Locale.ROOT))
                .map(s -> ALIASES.getOrDefault(s, s)).distinct().collect(Collectors.toList());
        if (terms.isEmpty() || terms.size() > 10 || terms.stream().anyMatch(s -> s.length() > 50)) {
            throw new IllegalArgumentException("기술명은 각각 50자 이하, 최대 10개까지 입력해주세요.");
        }
        return List.copyOf(terms); // jev 같은 모호한 입력은 임의로 Java로 바꾸지 않는다.
    }
}


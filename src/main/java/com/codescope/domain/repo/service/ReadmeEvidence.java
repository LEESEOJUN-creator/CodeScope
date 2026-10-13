package com.codescope.domain.repo.service;

import java.util.ArrayList;
import java.util.regex.Pattern;

/** 생성용 근거는 검색용 문서와 달리 제목·명령·링크를 보존한다. */
public final class ReadmeEvidence {
    private static final int STORAGE_LIMIT = 12_000;
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern IMAGE = Pattern.compile("!?\\[!\\[[^\\]]*]\\([^)]*\\)]\\([^)]*\\)|!\\[[^\\]]*]\\([^)]*\\)|!\\[[^\\]]*]\\[[^\\]]*]");
    private static final Pattern HTML_IMAGE = Pattern.compile("<img\\b[^>]*>|<svg\\b.*?</svg\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}\\s+(.+)$");
    private static final Pattern USEFUL = Pattern.compile("overview|features?|getting.?started|quick.?start|install|usage|사용|설치|시작|기능|개요", Pattern.CASE_INSENSITIVE);

    private ReadmeEvidence() {}

    public static String prepare(String markdown) {
        if (markdown == null || markdown.isBlank()) return null;
        String text = COMMENT.matcher(markdown).replaceAll("");
        text = IMAGE.matcher(text).replaceAll("");
        text = HTML_IMAGE.matcher(text).replaceAll("");
        text = text.replace("\r\n", "\n").replaceAll("\\n{3,}", "\n\n").strip();
        return text.isBlank() ? null : take(text, STORAGE_LIMIT);
    }

    // 소개만으로 예산을 소진하지 않도록 실제 설치·사용·특징 섹션도 발췌한다.
    // 요약문이나 명령을 생성하지 않고 원문의 부분 문자열만 반환한다.
    public static String excerpt(String text, int maxChars) {
        if (text == null || maxChars <= 0) return "";
        if (text.length() <= maxChars) return text;
        var headings = new ArrayList<Integer>();
        var useful = new ArrayList<Boolean>();
        var matcher = HEADING.matcher(text);
        while (matcher.find()) {
            headings.add(matcher.start());
            useful.add(USEFUL.matcher(matcher.group(1)).find());
        }
        var sections = new ArrayList<String>();
        int introBudget = Math.max(1, maxChars / 4);
        for (int i = 0; i < headings.size() && sections.size() < 3; i++) {
            if (useful.get(i) && headings.get(i) >= introBudget) {
                int end = i + 1 < headings.size() ? headings.get(i + 1) : text.length();
                sections.add(text.substring(headings.get(i), end).strip());
            }
        }
        if (sections.isEmpty()) return take(text, maxChars);
        String separator = "\n\n[…]\n\n";
        int sectionBudget = (maxChars - introBudget - separator.length() * sections.size()) / sections.size();
        if (sectionBudget < 1) return take(text, maxChars);
        var result = new StringBuilder(take(text, introBudget));
        for (String section : sections) result.append(separator).append(take(section, sectionBudget));
        return result.toString();
    }

    private static String take(String text, int limit) {
        return text.substring(0, Math.min(text.length(), limit));
    }
}

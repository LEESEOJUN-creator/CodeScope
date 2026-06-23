PR을 생성할 거야. 아래 순서대로 진행해줘.

## 순서
1. 현재 브랜치 변경사항 확인
2. PR 내용 작성
3. 나한테 내용 보여주고 확인받기
4. git add, commit, push 자동 실행
5. gh pr create로 PR 자동 생성

## PR 형식
제목: [종류] 기능 한 줄 요약

본문:
## 변경 내용
- 변경한 내용 1
- 변경한 내용 2

## 왜 이렇게 구현했는가
기술 선택 이유 + 설계 결정 근거

## 테스트 결과
- [ ] 단위 테스트 통과 (./gradlew test)
- [ ] Swagger에서 API 동작 확인
- [ ] /review 통과

## 연관 이슈
closes #이슈번호

## gh 명령어 실행 형식
git add .
git commit -m "종류: 기능 설명 (#이슈번호)"
git push origin 현재브랜치명
gh pr create \
--title "[종류] 제목" \
--body "본문 내용" \
--base main

## 주의사항
- PR 내용 나한테 먼저 보여주고 확인받은 후 실행
- 커밋 메시지에 이슈 번호 반드시 포함
- closes #번호 있어야 머지 시 이슈 자동 종료
- ./gradlew test 통과 확인 후 PR 생성
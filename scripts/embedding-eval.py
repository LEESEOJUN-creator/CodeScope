#!/usr/bin/env python3
"""
임베딩 검색 품질 평가 — "이 질의라면 이 레포가 나와야 한다"는 정답 세트로
GET /api/recommend의 후보 순위(pgvector 유사도 순)를 채점한다.

왜 필요한가: 임베딩 방식(README 전처리, 모델 교체 등)을 바꿀 때 "좋아졌다"를
느낌이 아니라 숫자로 비교하기 위함. 같은 DB·같은 질의로 변경 전/후를 돌려 비교한다.

전제: 앱이 생성=stub, 임베딩=실제 모델로 떠 있어야 한다(LLM 비용 0, 후보 순위만 사용).
  LLM_GENERATION_PROVIDER=stub LLM_EMBEDDING_PROVIDER=ollama ./gradlew bootRunLoadTest

사용: python3 scripts/embedding-eval.py [--host http://localhost:8080] [--label before]
지표:
  - MRR@10: 정답 레포 중 가장 높은 순위의 역수 평균(1위=1.0, 2위=0.5, 10위 밖=0)
  - Hit@3 : 정답이 상위 3개 안에 든 질의 비율
  - Hub@3 : 정답이 아닌데 상위 3개에 가장 자주 끼어든 레포와 그 횟수. README 전체를
            청크 평균한 벡터는 의미가 흐려져 "무엇과도 조금씩 가까운" 허브가 되기 쉽다
            (2026-10-13 기준선: 링크 목록형 README인 project-based-learning·awesome-selfhosted가
             각각 무관한 질의 4개의 상위 3위에 등장)
정답 세트는 2026-10-13 수집된 레포 30개 기준이다. 레포 구성이 바뀌면 함께 갱신할 것.
"""
import argparse
import json
import sys
import urllib.parse
import urllib.request

# (질의, 정답 레포 목록) — 사용자 스택 입력과 같은 형태(영어 키워드 나열)
EVAL_SET = [
    ("Python, PyTorch, deep learning, machine learning", ["tensorflow/tensorflow"]),
    ("React, TypeScript, frontend web UI components", ["react/react"]),
    ("video downloader command line", ["yt-dlp/yt-dlp"]),
    ("self-hosted server applications for homelab", ["awesome-selfhosted/awesome-selfhosted"]),
    ("algorithms and data structures implementations",
     ["TheAlgorithms/Python", "trekhleb/javascript-algorithms"]),
    ("operating system kernel in C", ["torvalds/linux"]),
    ("AI coding agent in the terminal",
     ["anomalyco/opencode", "ultraworkers/claw-code", "NousResearch/hermes-agent", "openclaw/openclaw"]),
    ("workflow automation with visual nodes", ["n8n-io/n8n"]),
    ("system design, scalability, distributed systems interview", ["donnemartin/system-design-primer"]),
    ("free public REST APIs for side projects", ["public-apis/public-apis"]),
    ("code editor IDE extensions", ["microsoft/vscode"]),
    ("learn computer science curriculum for free",
     ["ossu/computer-science", "freeCodeCamp/freeCodeCamp", "EbookFoundation/free-programming-books"]),
]


def candidates(host, stack):
    url = f"{host}/api/recommend?stack={urllib.parse.quote(stack)}"
    with urllib.request.urlopen(url, timeout=60) as res:
        body = json.load(res)
    return [c["fullName"] for c in body["data"]["candidates"]]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="http://localhost:8080")
    parser.add_argument("--label", default="run")
    args = parser.parse_args()

    rr_sum, hit3 = 0.0, 0
    rows = []
    intrusions = {}
    for query, expected in EVAL_SET:
        ranked = candidates(args.host, query)
        ranks = [ranked.index(e) + 1 for e in expected if e in ranked]
        best = min(ranks) if ranks else None
        rr_sum += (1.0 / best) if best else 0.0
        hit3 += 1 if best and best <= 3 else 0
        for name in ranked[:3]:
            if name not in expected:
                intrusions[name] = intrusions.get(name, 0) + 1
        rows.append({"query": query, "best_rank": best, "top3": ranked[:3]})

    n = len(EVAL_SET)
    hub, hub_count = max(intrusions.items(), key=lambda kv: kv[1]) if intrusions else ("-", 0)
    print(f"[{args.label}] MRR@10={rr_sum / n:.3f}  Hit@3={hit3}/{n}  Hub@3={hub}({hub_count}/{n})")
    for r in rows:
        rank = r["best_rank"] if r["best_rank"] else "-"
        print(f"  rank={rank:>2}  {r['query'][:52]:<52}  top3={', '.join(r['top3'])}")
    json.dump({"label": args.label, "mrr": rr_sum / n, "hit3": hit3, "n": n,
               "hub": hub, "hub_count": hub_count, "rows": rows},
              sys.stderr, ensure_ascii=False)


if __name__ == "__main__":
    main()

# ──────────────────────────────────────
# Next.js 프론트엔드 이미지 (로컬 kind 배포용, k8s/overlays/local 참고)
#
# 왜 NEXT_PUBLIC_API_BASE_URL을 빌드 인자로 받는가: NEXT_PUBLIC_ 변수는
#   런타임이 아니라 `next build` 시점에 클라이언트 번들에 문자열로 박힌다.
#   브라우저가 직접 호출하는 주소라 클러스터 내부 DNS(codescope:8080)가 아니라
#   브라우저 기준 주소(port-forward된 localhost:8080)여야 한다.
# ──────────────────────────────────────
FROM node:22-alpine AS builder
WORKDIR /app

# 의존성 레이어를 소스 변경과 분리해 캐시 재사용
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
ARG NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
ENV NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL}
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build && npm prune --omit=dev

# ──────────────────────────────────────
# runner: 빌드 산출물과 런타임 의존성만 담는다
# ──────────────────────────────────────
FROM node:22-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1

COPY --from=builder /app/package.json ./
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/.next ./.next
COPY --from=builder /app/public ./public

# node 이미지에 기본으로 있는 비root 사용자
USER node
EXPOSE 3000
CMD ["npx", "next", "start", "-p", "3000"]

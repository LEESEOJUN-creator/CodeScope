<#
.SYNOPSIS
  CodeScope 로컬 개발 환경을 한 번에 기동한다.

.DESCRIPTION
  1) Docker Desktop 확인(꺼져있으면 실행 후 대기)
  2) kind 클러스터('codescope') 확인, 없으면 생성
  3) kubectl port-forward 3개(postgres:5432, redis:6379, kafka-headless:9094)를
     백그라운드로 실행
  4) Ollama(11434) 확인 — 안 떠 있으면 안내만 하고 계속 진행
  5) 다 되면 "IntelliJ에서 앱 실행하세요" 안내

  왜 docker-compose가 아니라 이 스크립트인가: DB/Redis/Kafka는 Day 6~7부터
  kind 클러스터의 Pod로 완전히 대체됐다(docker-compose.yml 참고). 로컬
  개발자가 매번 기억해서 port-forward 3개를 손으로 띄우는 대신, 여기서
  한 번에 처리한다.

.PARAMETER ClusterName
  kind 클러스터 이름. 기본값 'codescope'(이 레포에서 지금까지 써온 이름).
#>

param(
    [string]$ClusterName = 'codescope'
)

$ErrorActionPreference = 'Stop'
$LogDir = Join-Path $PSScriptRoot '.local-up-logs'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg) { Write-Host "  OK  $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "  !!  $msg" -ForegroundColor Yellow }

function Test-Port($port) {
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $connectTask = $client.ConnectAsync('localhost', $port)
        $completed = $connectTask.Wait(1500)
        $client.Close()
        return $completed
    } catch {
        return $false
    }
}

# ── 1) Docker Desktop 확인 ─────────────────────────────────────────
Write-Step 'Docker Desktop 확인 중...'
docker info *>$null 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Warn 'Docker가 응답하지 않습니다. Docker Desktop을 실행합니다...'
    $dockerExe = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
    if (Test-Path $dockerExe) {
        Start-Process $dockerExe
    } else {
        Write-Warn "Docker Desktop 실행 파일을 기본 경로에서 못 찾았습니다($dockerExe). 수동으로 켜주세요."
    }

    $ready = $false
    for ($i = 0; $i -lt 24; $i++) {
        Start-Sleep -Seconds 5
        docker info *>$null 2>&1
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Write-Host "  ... Docker 기동 대기 중 ($($i * 5)초 경과)"
    }
    if (-not $ready) {
        Write-Error 'Docker Desktop이 2분 안에 준비되지 않았습니다. 수동으로 켜고 다시 실행해주세요.'
        exit 1
    }
}
Write-Ok 'Docker 준비됨'

# ── 2) kind 클러스터 확인/생성 ──────────────────────────────────────
Write-Step "kind 클러스터('$ClusterName') 확인 중..."
$existingClusters = kind get clusters 2>$null
if ($existingClusters -notcontains $ClusterName) {
    Write-Warn "클러스터가 없어 새로 만듭니다(k8s 매니페스트/Pod는 별도로 다시 적용해야 할 수 있습니다)..."
    kind create cluster --name $ClusterName
} else {
    Write-Ok "클러스터 '$ClusterName' 이미 존재"
}
kubectl config use-context "kind-$ClusterName" *>$null

$pods = kubectl get pods --no-headers 2>$null
if (-not $pods) {
    Write-Warn "Pod가 하나도 없습니다 — postgres/redis/kafka가 아직 배포 안 됐을 수 있습니다. k8s/ 매니페스트 적용 여부를 확인하세요."
}

# ── 3) port-forward 3개 백그라운드 실행 ─────────────────────────────
Write-Step 'port-forward 시작 중 (postgres:5432, redis:6379, kafka:9094)...'

# 이미 떠 있는 포트는 건드리지 않는다 — 기존 port-forward를 중복 실행하면
# "Only one usage of each socket address" 에러만 나고 새 프로세스는
# 바로 죽는다(이 프로젝트에서 실측으로 확인된 동작).
$targets = @(
    @{ Service = 'postgres'; Port = 5432; LocalPort = 5432 },
    @{ Service = 'redis'; Port = 6379; LocalPort = 6379 },
    @{ Service = 'kafka-headless'; Port = 9094; LocalPort = 9094 }
)

foreach ($t in $targets) {
    if (Test-Port $t.LocalPort) {
        Write-Ok "포트 $($t.LocalPort) 이미 사용 중 — 기존 port-forward(또는 다른 프로세스)가 있다고 보고 건너뜀"
        continue
    }

    $outLog = Join-Path $LogDir "$($t.Service).out.log"
    $errLog = Join-Path $LogDir "$($t.Service).err.log"
    Start-Process kubectl `
        -ArgumentList @('port-forward', "svc/$($t.Service)", "$($t.LocalPort):$($t.Port)") `
        -WindowStyle Hidden `
        -RedirectStandardOutput $outLog `
        -RedirectStandardError $errLog
    Write-Host "  실행: kubectl port-forward svc/$($t.Service) $($t.LocalPort):$($t.Port)  (로그: $errLog)"
}

Start-Sleep -Seconds 3

foreach ($t in $targets) {
    if (Test-Port $t.LocalPort) {
        Write-Ok "포트 $($t.LocalPort) ($($t.Service)) 연결 확인"
    } else {
        Write-Warn "포트 $($t.LocalPort) ($($t.Service)) 아직 응답 없음 — 로그 확인: $LogDir\$($t.Service).err.log"
    }
}

# ── 4) Ollama 확인 ─────────────────────────────────────────────────
Write-Step 'Ollama 확인 중...'
if (Test-Port 11434) {
    Write-Ok 'Ollama 실행 중 (11434)'
} else {
    $ollamaExe = Join-Path $env:LOCALAPPDATA 'Programs\Ollama\ollama.exe'
    Write-Warn 'Ollama가 안 떠 있습니다. RAG 추천/트렌드 분석 기능에 필요합니다.'
    if (Test-Path $ollamaExe) {
        Write-Host "  직접 실행: & '$ollamaExe' serve"
    } else {
        Write-Host '  Ollama가 설치돼 있지 않다면 https://ollama.com 에서 설치 후 실행하세요.'
    }
}

# ── 5) 완료 안내 ───────────────────────────────────────────────────
Write-Host ''
Write-Host '준비 완료 — 이제 IntelliJ에서 CodescopeApplication을 실행하세요.' -ForegroundColor Green
Write-Host '  (JWT_SECRET / GITHUB_CLIENT_ID / GITHUB_CLIENT_SECRET / GITHUB_TOKEN 환경변수가'
Write-Host '   IntelliJ 실행 설정(Run Configuration)에 등록되어 있어야 합니다)'
Write-Host ''
Write-Host "종료하려면: scripts\local-down.ps1"

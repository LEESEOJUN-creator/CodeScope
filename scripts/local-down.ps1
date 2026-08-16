<#
.SYNOPSIS
  local-up.ps1으로 띄운 로컬 개발 환경을 정리한다.

.DESCRIPTION
  1) postgres/redis/kafka로 향하는 kubectl port-forward 프로세스를 전부 종료
  2) kind 클러스터는 기본적으로 유지한다 — -DeleteCluster를 주면 같이 삭제

.PARAMETER ClusterName
  kind 클러스터 이름. 기본값 'codescope'.

.PARAMETER DeleteCluster
  같이 지정하면 kind 클러스터까지 삭제한다(Pod/데이터 전부 사라짐 — 되돌릴
  수 없으니 다음 기동 시 재배포가 필요하다는 걸 감안할 것).

.EXAMPLE
  scripts\local-down.ps1
  # port-forward만 정리, 클러스터는 유지

.EXAMPLE
  scripts\local-down.ps1 -DeleteCluster
  # port-forward 정리 + 클러스터까지 삭제
#>

param(
    [string]$ClusterName = 'codescope',
    [switch]$DeleteCluster
)

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg) { Write-Host "  OK  $msg" -ForegroundColor Green }

Write-Step 'port-forward 프로세스 정리 중...'

# CIM으로 커맨드라인까지 봐서 "kubectl port-forward"인 것만 골라 죽인다 —
# 다른 목적의 kubectl 프로세스(로그 확인 등)를 실수로 같이 죽이지 않기 위함.
$targets = Get-CimInstance Win32_Process -Filter "Name = 'kubectl.exe'" |
    Where-Object { $_.CommandLine -match 'port-forward' }

if (-not $targets) {
    Write-Ok '정리할 port-forward 프로세스가 없습니다.'
} else {
    foreach ($p in $targets) {
        $short = $p.CommandLine.Substring(0, [Math]::Min(90, $p.CommandLine.Length))
        Write-Host "  종료: PID $($p.ProcessId) - $short"
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    }
    Write-Ok "port-forward $($targets.Count)개 종료함"
}

if ($DeleteCluster) {
    Write-Step "kind 클러스터('$ClusterName') 삭제 중..."
    kind delete cluster --name $ClusterName
    Write-Ok '클러스터 삭제 완료'
} else {
    Write-Host ''
    Write-Host "kind 클러스터('$ClusterName')는 유지합니다. 완전히 지우려면:" -ForegroundColor Yellow
    Write-Host '  scripts\local-down.ps1 -DeleteCluster'
}

Write-Host ''
Write-Host '정리 완료.' -ForegroundColor Green

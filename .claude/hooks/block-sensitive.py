"""
Claude Code PreToolUse Hook — 민감 파일 접근 차단
exit(2) 반환 시 해당 tool call을 block합니다.
"""

import sys
import json
import os
import re
from datetime import datetime

WHITELIST_PATTERNS = [
    "src/main/java",
    "src/test/java",
    "src/main/resources/application.yml",
    "build.gradle",
    "settings.gradle",
    "CLAUDE.md",
    "README.md",
    ".claude/commands",
    ".claude/hooks/block-sensitive.py",
    "k8s/",
]

SENSITIVE_FILE_PATTERNS = [
    ".env",
    ".env.local",
    ".env.production",
    "application-secret.yml",
    "application-local.yml",
    "application-prod.yml",
    "id_rsa",
    "id_ed25519",
    "id_ecdsa",
    ".pem",
    ".key",
    ".p12",
    ".pfx",
    ".jks",
    ".keystore",
    "credentials",
    ".aws/config",
    "oci_api_key",
    ".oci/config",
    ".docker/config.json",
    "kubeconfig",
    ".kube/config",
    ".git-credentials",
    "private_key",
    "token.json",
]

SENSITIVE_CMD_PATTERNS = [
    r"~/.aws",
    r"\.aws/credentials",
    r"\.aws/config",
    r"id_rsa",
    r"id_ed25519",
    r"\.oci/config",
    r"\.kube/config",
    r"printenv",
    r"env \| grep",
    r"curl.*token",
    r"cat \.env",
    r"cat ~/\.ssh",
]


def is_whitelisted(path):
    path_normalized = path.replace("\\", "/")
    return any(pattern in path_normalized for pattern in WHITELIST_PATTERNS)


def is_sensitive_path(path):
    path_lower = path.lower().replace("\\", "/")
    for pattern in SENSITIVE_FILE_PATTERNS:
        if pattern.lower() in path_lower:
            return True, pattern
    return False, ""


def is_sensitive_command(command):
    command_lower = command.lower()
    for pattern in SENSITIVE_CMD_PATTERNS:
        if re.search(pattern.lower(), command_lower):
            return True, pattern
    return False, ""


def log_blocked(reason, detail):
    try:
        log_path = os.path.join(os.environ.get("CLAUDE_PROJECT_DIR", "."), ".claude/hooks/security.log")
        timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        log_line = f"[{timestamp}] BLOCKED | {reason} | {detail}\n"
        with open(log_path, "a", encoding="utf-8") as f:
            f.write(log_line)
    except Exception:
        pass


def print_block_message(reason, detail):
    print(f"""
\U0001f6a8 보안 훅: 접근 차단됨
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
이유:   {reason}
대상:   {detail}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
대안:
  - 환경변수 값이 필요하면 application.yml의 ${{변수명}} 참조
  - 실제 값은 .env 또는 K8s Secret으로 관리
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
""", file=sys.stderr)


def main():
    try:
        data = json.load(sys.stdin)
    except Exception:
        sys.exit(0)

    tool_name = data.get("tool_name", "")
    tool_input = data.get("tool_input", {})

    if tool_name in ("Read", "Edit", "Write", "Glob", "MultiEdit"):
        path = tool_input.get("file_path") or tool_input.get("path") or ""

        if not path:
            sys.exit(0)

        if is_whitelisted(path):
            sys.exit(0)

        blocked, pattern = is_sensitive_path(path)
        if blocked:
            reason = f"민감한 파일 패턴 감지: {pattern}"
            print_block_message(reason, path)
            log_blocked(reason, path)
            sys.exit(2)

    if tool_name == "Bash":
        command = tool_input.get("command") or ""

        if not command:
            sys.exit(0)

        blocked, pattern = is_sensitive_command(command)
        if blocked:
            reason = f"민감한 명령어 패턴 감지: {pattern}"
            print_block_message(reason, command)
            log_blocked(reason, command)
            sys.exit(2)

    sys.exit(0)


if __name__ == "__main__":
    main()
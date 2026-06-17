"""代理检查模块 — 任务启动前必须通过验证，否则终止"""
from __future__ import annotations

import os
import subprocess
import sys

_PROXY_ADDR = "http://127.0.0.1:7897"
_CHECK_URL = "http://ip-api.com/json"
_REQUIRED_COUNTRY = "US"


def _get_exit_ip_info() -> dict:
    import urllib.request
    import json

    proxies = {"http": _PROXY_ADDR, "https": _PROXY_ADDR}
    proxy_handler = urllib.request.ProxyHandler(proxies)
    opener = urllib.request.build_opener(proxy_handler)
    try:
        with opener.open(_CHECK_URL, timeout=10) as resp:
            return json.loads(resp.read().decode())
    except Exception as e:
        return {"status": "fail", "error": str(e)}


def _enable_proxy_via_profile() -> bool:
    """调用 PowerShell profile 中的 proxy-on 函数"""
    result = subprocess.run(
        ["powershell", "-NoProfile", "-Command",
         f'. "$PROFILE"; proxy-on; Write-Host "PROXY_ENABLED"'],
        capture_output=True, text=True, timeout=15,
    )
    # 同时直接设置当前进程的环境变量
    os.environ["HTTP_PROXY"] = _PROXY_ADDR
    os.environ["HTTPS_PROXY"] = _PROXY_ADDR
    return "PROXY_ENABLED" in result.stdout


def ensure_us_proxy(max_retries: int = 2) -> None:
    """
    确保流量通过美国代理出口。
    - 若代理未开启则尝试开启
    - 若出口不是美国则终止
    - 若检测通过则打印确认信息

    在任何 Agent 任务启动前调用此函数。
    """
    # 确保环境变量已设置（.env 可能未被系统级 shell 继承）
    if not os.environ.get("HTTP_PROXY"):
        os.environ["HTTP_PROXY"] = _PROXY_ADDR
        os.environ["HTTPS_PROXY"] = _PROXY_ADDR

    for attempt in range(1, max_retries + 1):
        info = _get_exit_ip_info()

        if info.get("status") == "success":
            country = info.get("countryCode", "??")
            ip = info.get("query", "unknown")
            city = info.get("city", "")

            if country == _REQUIRED_COUNTRY:
                print(f"[proxy] ✓ 出口 IP: {ip} ({city}, {country}) — 代理正常")
                return

            print(
                f"[proxy] ✗ 出口 IP: {ip} ({country}) — 非美国出口，"
                f"尝试开启代理 (第 {attempt}/{max_retries} 次)"
            )
        else:
            err = info.get("error", "unknown error")
            print(f"[proxy] ✗ IP 检测失败: {err}，尝试开启代理 (第 {attempt}/{max_retries} 次)")

        if attempt < max_retries:
            enabled = _enable_proxy_via_profile()
            print(f"[proxy] proxy-on {'已执行' if enabled else '执行失败'}")

    # 最终失败 — 终止任务
    print("[proxy] ✗ 代理验证失败，任务已终止。请手动运行 proxy-on 后重试。")
    sys.exit(1)

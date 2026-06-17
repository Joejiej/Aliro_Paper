"""快速连通测试：验证代理 + 每个 Agent 的 LLM 是否可以正常调用
模型路由规则：
  claude-cli:<model>  → ClaudeCodeCLILLM (subprocess, Pro plan OAuth)
  gemini-cli:<model>  → GeminiCLILLM    (subprocess, Gemini CLI OAuth)
  deepseek-*          → ChatOpenAI       (DEEPSEEK_API_KEY, OpenAI 兼容接口)
"""
import sys
import os
sys.path.insert(0, os.path.dirname(__file__))

# ── Step 0: 代理检查 ────────────────────────────────────────────────────────
from src.utils.proxy_check import ensure_us_proxy
ensure_us_proxy()

from langchain_core.messages import HumanMessage
from src.utils.llm import get_llm

# 与 .env 中 MODEL_* 配置保持一致
AGENTS = [
    ("supervisor",   "claude-cli:claude-sonnet-4-6"),
    ("literature",   "claude-cli:claude-opus-4-8"),
    ("code_analyst", "claude-cli:claude-opus-4-8"),
    ("outline",      "claude-cli:claude-sonnet-4-6"),
    ("writer",       "claude-cli:claude-opus-4-8"),
    ("data_analyst", "deepseek-chat"),
    ("reviewer",     "deepseek-reasoner"),
    ("formatter",    "deepseek-chat"),
]

results = []

for agent, model in AGENTS:
    try:
        llm = get_llm(model)
        resp = llm.invoke([HumanMessage(content="Reply with exactly two words: TEST OK")])
        answer = resp.content.strip()[:80]
        results.append((agent, model, "OK", answer))
    except Exception as e:
        err = str(e)[:120]
        results.append((agent, model, "FAIL", err))

print("\n" + "="*72)
print(f"{'Agent':<14} {'Status':<6} {'Model':<28} {'Response/Error'}")
print("="*72)
for agent, model, status, detail in results:
    icon = "✓" if status == "OK" else "✗"
    print(f"{icon} {agent:<13} {status:<6} {model:<28} {detail}")
print("="*72 + "\n")

failed = [a for a, _, s, _ in results if s != "OK"]
if failed:
    print(f"失败 Agent: {', '.join(failed)}")
    sys.exit(1)
else:
    print("所有 Agent 连通测试通过 ✓")

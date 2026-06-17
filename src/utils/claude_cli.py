"""Claude Code CLI wrapper — calls `claude -p` via subprocess, uses Pro plan OAuth session."""
from __future__ import annotations

import os
import subprocess
from typing import Any, List, Optional

from langchain_core.callbacks import CallbackManagerForLLMRun
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage
from langchain_core.outputs import ChatGeneration, ChatResult

# claude CLI 绝对路径，避免 subprocess PATH 差异；可通过环境变量覆盖
_DEFAULT_CLAUDE_CMD = os.getenv(
    "CLAUDE_CMD_PATH",
    r"E:\noed js\node_global\claude.cmd",
)


def _messages_to_prompt(messages: List[BaseMessage]) -> str:
    parts: list[str] = []
    for m in messages:
        if isinstance(m, SystemMessage):
            parts.append(f"[System]: {m.content}")
        elif isinstance(m, HumanMessage):
            parts.append(str(m.content))
        elif isinstance(m, AIMessage):
            parts.append(f"[Assistant]: {m.content}")
        else:
            parts.append(str(m.content))
    return "\n\n".join(parts)


class ClaudeCodeCLILLM(BaseChatModel):
    """LangChain ChatModel backed by `claude -p` CLI (Pro plan OAuth, no API credits needed)."""

    model_name: str = "claude-sonnet-4-6"
    claude_cmd: str = _DEFAULT_CLAUDE_CMD
    timeout: int = 180

    @property
    def _llm_type(self) -> str:
        return "claude-cli"

    def _generate(
        self,
        messages: List[BaseMessage],
        stop: Optional[List[str]] = None,
        run_manager: Optional[CallbackManagerForLLMRun] = None,
        **kwargs: Any,
    ) -> ChatResult:
        prompt = _messages_to_prompt(messages)

        # 移除 ANTHROPIC_API_KEY，强制 claude CLI 走 OAuth（Pro plan），而非 API credits
        env = os.environ.copy()
        env.pop("ANTHROPIC_API_KEY", None)

        result = subprocess.run(
            [self.claude_cmd, "-p", prompt, "--model", self.model_name],
            capture_output=True,
            stdin=subprocess.DEVNULL,  # 避免 "no stdin" 3s 等待
            env=env,
            timeout=self.timeout,
        )

        stdout = (result.stdout or b"").decode("utf-8", errors="replace").strip()
        stderr = (result.stderr or b"").decode("utf-8", errors="replace").strip()

        if result.returncode != 0:
            raise RuntimeError(f"Claude CLI error (exit {result.returncode}): {(stderr or stdout)[:300]}")

        return ChatResult(generations=[ChatGeneration(message=AIMessage(content=stdout))])

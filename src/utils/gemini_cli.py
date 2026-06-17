"""Gemini CLI wrapper — calls the local `gemini` CLI via subprocess as a LangChain BaseChatModel."""
from __future__ import annotations

import os
import subprocess
from typing import Any, List, Optional

from langchain_core.callbacks import CallbackManagerForLLMRun
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage
from langchain_core.outputs import ChatGeneration, ChatResult

# 绝对路径，避免 PATH 差异问题；可通过环境变量覆盖
_DEFAULT_GEMINI_CMD = os.getenv(
    "GEMINI_CMD_PATH",
    r"E:\noed js\node_global\gemini.cmd",
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


class GeminiCLILLM(BaseChatModel):
    """LangChain ChatModel backed by the local `gemini` CLI (OAuth, no API key needed)."""

    model_name: str = "gemini-2.5-pro"
    gemini_cmd: str = _DEFAULT_GEMINI_CMD
    timeout: int = 180

    @property
    def _llm_type(self) -> str:
        return "gemini-cli"

    def _generate(
        self,
        messages: List[BaseMessage],
        stop: Optional[List[str]] = None,
        run_manager: Optional[CallbackManagerForLLMRun] = None,
        **kwargs: Any,
    ) -> ChatResult:
        prompt = _messages_to_prompt(messages)

        env = os.environ.copy()
        env["GEMINI_CLI_TRUST_WORKSPACE"] = "true"

        # 通过 stdin 传入提示，避免 Windows 8191 字符命令行长度限制
        result = subprocess.run(
            [self.gemini_cmd, "--model", self.model_name],
            input=prompt,
            capture_output=True,
            text=True,
            env=env,
            timeout=self.timeout,
        )

        if result.returncode != 0:
            err = (result.stderr or result.stdout).strip()
            raise RuntimeError(f"Gemini CLI error (exit {result.returncode}): {err[:300]}")

        text = result.stdout.strip()
        return ChatResult(generations=[ChatGeneration(message=AIMessage(content=text))])

"""LLM 客户端工厂 — 多提供商支持，按 Agent 独立配置模型"""
from __future__ import annotations

import os
from typing import Optional

from dotenv import load_dotenv
from langchain_core.language_models import BaseChatModel

load_dotenv()

# ── 每个 Agent 的默认模型（可通过环境变量 MODEL_<AGENT_NAME> 覆盖）────────
_AGENT_DEFAULT_MODELS: dict[str, str] = {
    "supervisor":   "claude-cli:claude-sonnet-4-6",
    "literature":   "claude-cli:claude-opus-4-8",
    "code_analyst": "claude-cli:claude-opus-4-8",
    "outline":      "claude-cli:claude-sonnet-4-6",
    "writer":       "claude-cli:claude-opus-4-8",
    "data_analyst": "deepseek-chat",
    "reviewer":     "deepseek-reasoner",
    "formatter":    "deepseek-chat",
}

# ── 每个 Agent 的默认 temperature ────────────────────────────────────────
_AGENT_DEFAULT_TEMPS: dict[str, float] = {
    "supervisor":   0.1,
    "literature":   0.3,
    "code_analyst": 0.1,
    "outline":      0.4,
    "writer":       0.6,
    "data_analyst": 0.1,
    "reviewer":     0.1,
    "formatter":    0.1,
}

# ── 通过 model 名前缀判断 provider ────────────────────────────────────────
# claude-cli:<model> → ClaudeCodeCLILLM（subprocess，使用 Pro plan OAuth）
# gemini-cli:<model> → GeminiCLILLM（subprocess，使用 Gemini Pro OAuth）
# gemini-<...>       → langchain_google_genai（需要 GOOGLE_API_KEY）
_PROVIDER_PREFIXES: list[tuple[str, str]] = [
    ("claude-cli:", "claude-cli"),
    ("gemini-cli:", "gemini-cli"),
    ("gpt",         "openai"),
    ("o1",          "openai"),
    ("o3",          "openai"),
    ("o4",          "openai"),
    ("claude",      "anthropic"),
    ("deepseek",    "deepseek"),
    ("qwen",        "dashscope"),
    ("gemini",      "google"),
    ("llama",       "ollama"),
]


def _detect_provider(model: str) -> str:
    model_lower = model.lower()
    for prefix, provider in _PROVIDER_PREFIXES:
        if model_lower.startswith(prefix):
            return provider
    return "openai"


def get_llm(model: str = "gpt-4o", temperature: float = 0.3) -> BaseChatModel:
    """按 model 名称自动路由到对应 provider SDK"""
    provider = _detect_provider(model)

    if provider == "claude-cli":
        from src.utils.claude_cli import ClaudeCodeCLILLM
        actual_model = model[len("claude-cli:"):]
        return ClaudeCodeCLILLM(model_name=actual_model)

    elif provider == "gemini-cli":
        from src.utils.gemini_cli import GeminiCLILLM
        actual_model = model[len("gemini-cli:"):]
        return GeminiCLILLM(model_name=actual_model)

    elif provider == "openai":
        from langchain_openai import ChatOpenAI
        return ChatOpenAI(
            model=model,
            temperature=temperature,
            api_key=os.getenv("OPENAI_API_KEY"),
        )

    elif provider == "anthropic":
        from langchain_anthropic import ChatAnthropic
        return ChatAnthropic(
            model=model,
            temperature=temperature,
            api_key=os.getenv("ANTHROPIC_API_KEY"),
        )

    elif provider == "deepseek":
        from langchain_openai import ChatOpenAI
        return ChatOpenAI(
            model=model,
            temperature=temperature,
            api_key=os.getenv("DEEPSEEK_API_KEY"),
            base_url="https://api.deepseek.com/v1",
        )

    elif provider == "dashscope":
        from langchain_community.chat_models.tongyi import ChatTongyi
        return ChatTongyi(
            model=model,
            temperature=temperature,
            dashscope_api_key=os.getenv("DASHSCOPE_API_KEY"),
        )

    elif provider == "google":
        from langchain_google_genai import ChatGoogleGenerativeAI
        return ChatGoogleGenerativeAI(
            model=model,
            temperature=temperature,
            google_api_key=os.getenv("GOOGLE_API_KEY"),
        )

    elif provider == "ollama":
        from langchain_ollama import ChatOllama
        return ChatOllama(model=model, temperature=temperature)

    else:
        raise ValueError(f"Unsupported provider for model '{model}'")


def get_agent_llm(
    agent_name: str,
    temperature: Optional[float] = None,
    model_override: Optional[str] = None,
) -> BaseChatModel:
    """
    获取指定 Agent 的 LLM 实例。

    优先级：
      1. model_override 参数（调用方显式传入）
      2. 环境变量 MODEL_<AGENT_NAME_UPPER>
      3. _AGENT_DEFAULT_MODELS 中的默认值
    """
    env_key = f"MODEL_{agent_name.upper()}"
    model = model_override or os.getenv(env_key) or _AGENT_DEFAULT_MODELS.get(agent_name, "gpt-4o")
    temp = temperature if temperature is not None else _AGENT_DEFAULT_TEMPS.get(agent_name, 0.3)
    return get_llm(model, temp)


def get_writer_llm() -> BaseChatModel:
    return get_agent_llm("writer")


def get_reviewer_llm() -> BaseChatModel:
    return get_agent_llm("reviewer")

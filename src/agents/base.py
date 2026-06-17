"""Agent 基类 — 统一接口与元数据规范"""
from __future__ import annotations
from abc import ABC, abstractmethod
from typing import ClassVar, List, Optional
from langchain_core.messages import HumanMessage, SystemMessage
from ..graph.state import PaperState
from ..utils.llm import get_agent_llm


class BaseAgent(ABC):
    """
    所有 Worker Agent 继承此类。

    子类必须定义:
      ROLE        — 一句话角色描述（用于 Supervisor 路由决策）
      DESCRIPTION — 详细职责说明
      INPUT_KEYS  — 从 PaperState 中读取哪些字段
      OUTPUT_KEYS — 向 PaperState 中写入哪些字段
    """

    # ── 子类必须覆盖的类属性 ────────────────────────────────────
    ROLE: ClassVar[str] = ""
    DESCRIPTION: ClassVar[str] = ""
    INPUT_KEYS: ClassVar[List[str]] = []
    OUTPUT_KEYS: ClassVar[List[str]] = []

    def __init__(
        self,
        name: str,
        temperature: Optional[float] = None,
        model_override: Optional[str] = None,
    ):
        self.name = name
        self.llm = get_agent_llm(name, temperature, model_override)

    @abstractmethod
    def build_system_prompt(self, state: PaperState) -> str:
        """构建该 Agent 的 System Prompt"""

    @abstractmethod
    def build_user_prompt(self, state: PaperState) -> str:
        """构建本次调用的 User Prompt（包含具体指令和上下文）"""

    async def run(self, state: PaperState) -> dict:
        """执行 Agent 任务，返回对 PaperState 的增量更新"""
        import datetime
        ts = datetime.datetime.now().strftime("%H:%M:%S")
        phase = state.phase
        sec = state.current_section_index
        print(f"[{ts}] ▶ {self.name} | phase={phase} | iter={state.iteration_count} | section={sec}", flush=True)

        system_msg = SystemMessage(content=self.build_system_prompt(state))
        user_msg = HumanMessage(content=self.build_user_prompt(state))
        response = await self.llm.ainvoke([system_msg, user_msg])
        result = self.parse_response(response.content, state)

        ts2 = datetime.datetime.now().strftime("%H:%M:%S")
        print(f"[{ts2}] ✓ {self.name} done", flush=True)
        return result

    @abstractmethod
    def parse_response(self, response: str, state: PaperState) -> dict:
        """将 LLM 输出解析为 PaperState 字段的增量更新 dict"""

    def __repr__(self) -> str:
        return f"<Agent:{self.name} | {self.ROLE}>"

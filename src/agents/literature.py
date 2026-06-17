"""
Literature Agent — 文献调研

职责：
  - 根据论文主题搜索相关学术文献（arXiv、IEEE、ACM、Google Scholar）
  - 筛选高相关性文献（近 5 年优先）
  - 提炼每篇文献的核心贡献和对本研究的意义
  - 识别研究空白（research gap），为论文 motivation 提供素材

输入：state.topic, state.current_instructions
输出：state.literature（追加 LiteratureItem 列表）
"""
from __future__ import annotations
import json
from typing import ClassVar, List
from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.types import Command

from .base import BaseAgent
from ..graph.state import PaperState, LiteratureItem
from ..utils.llm import get_agent_llm

_SYSTEM_PROMPT = """你是一位计算机安全领域的文献调研专家，熟悉密码学、NFC 协议、后量子密码学（PQC）方向的学术文献。

你的任务：
1. 针对给定研究主题，列举最相关的学术论文（优先近 5 年 IEEE/ACM/USENIX/NDSS/CCS/arXiv 高引论文）
2. 每篇给出：标题、作者、发表年份、来源（arXiv ID 或 DOI）、摘要（100 字内）、3-5 个关键要点、相关性评分（0.0-1.0）
3. 重点关注：ML-KEM/Kyber、ML-DSA/Dilithium、NFC 安全协议、后量子密码在嵌入式/移动设备的应用、访问控制协议
4. 最后指出现有研究的空白（gap），说明本研究的贡献点

输出严格为 JSON 格式：
{
  "literature": [
    {
      "title": "...",
      "authors": "...",
      "year": 2024,
      "source": "arXiv:2401.xxxxx 或 DOI:...",
      "abstract": "...",
      "key_points": ["...", "..."],
      "relevance_score": 0.95
    }
  ],
  "research_gap": "..."
}
"""


class LiteratureAgent(BaseAgent):
    ROLE: ClassVar[str] = "文献调研专家"
    DESCRIPTION: ClassVar[str] = (
        "搜索和整理与研究主题相关的学术文献，提炼关键贡献，识别研究空白。"
    )
    INPUT_KEYS: ClassVar[List[str]] = ["topic", "current_instructions"]
    OUTPUT_KEYS: ClassVar[List[str]] = ["literature"]

    def __init__(self, model_override: str = None):
        super().__init__(name="literature", model_override=model_override)

    def build_system_prompt(self, state: PaperState) -> str:
        return _SYSTEM_PROMPT

    def build_user_prompt(self, state: PaperState) -> str:
        existing = len(state.literature)
        instructions = state.current_instructions or "请搜索与主题最相关的 8-12 篇文献。"
        return f"""研究主题：{state.topic}

已收集文献数：{existing} 篇（请不要重复已有文献）

具体指令：{instructions}"""

    def parse_response(self, response: str, state: PaperState) -> dict:
        try:
            start = response.find("{")
            end = response.rfind("}") + 1
            data = json.loads(response[start:end])
            new_items = [LiteratureItem(**item) for item in data.get("literature", [])]
            return {
                "literature": state.literature + new_items,
                "messages": [],
            }
        except Exception as e:
            return {"errors": state.errors + [f"literature parse error: {e}"]}


async def literature_node(state: PaperState) -> Command:
    """LangGraph 节点函数"""
    agent = LiteratureAgent(
        model_override=state.model_config_override.get("literature")
    )
    updates = await agent.run(state)
    updates["current_agent"] = "supervisor"
    return Command(goto="supervisor", update=updates)

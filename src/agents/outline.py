"""
Outline Agent — 论文大纲设计

职责：
  - 综合文献调研和代码分析的结果，设计论文完整结构
  - 确定章节顺序、每章写作目标、字数分配
  - 提炼 contribution points（贡献点），用于 Abstract 和 Introduction
  - 输出可供 Writer Agent 逐章执行的 PaperOutline

输入：state.topic, state.literature, state.code_analyses, state.current_instructions
输出：state.outline
"""
from __future__ import annotations
import json
from typing import ClassVar, List
from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.types import Command

from .base import BaseAgent
from ..graph.state import PaperState, PaperOutline, Section
from ..utils.llm import get_agent_llm

_SYSTEM_PROMPT = """你是一位经验丰富的计算机安全方向学术论文结构设计专家。

你的任务：
1. 综合文献调研和代码分析结果，为论文设计完整的章节结构
2. 每个章节须包含：标题、写作目标（1-2 句）、建议字数
3. 标准结构参考（可根据内容调整）：
   Abstract / Introduction / Related Work / Background / System Design /
   Implementation / Evaluation / Discussion / Conclusion
4. 提炼 3-5 个 Contribution Points，用于 Abstract 和 Introduction 开头
5. 列出 5-10 个关键词

输出严格为 JSON：
{
  "title": "论文全名（英文）",
  "abstract_draft": "摘要草稿（英文，150 字左右）",
  "keywords": ["keyword1", "keyword2"],
  "contribution_points": ["Contribution 1", "Contribution 2"],
  "sections": [
    {
      "index": 0,
      "title": "Introduction",
      "content": "写作目标：... 建议字数：800",
      "word_count": 800,
      "status": "pending"
    }
  ]
}
"""


class OutlineAgent(BaseAgent):
    ROLE: ClassVar[str] = "论文大纲设计专家"
    DESCRIPTION: ClassVar[str] = (
        "综合研究素材，设计论文章节结构、字数分配和贡献点，为写作阶段提供蓝图。"
    )
    INPUT_KEYS: ClassVar[List[str]] = [
        "topic", "literature", "code_analyses", "current_instructions"
    ]
    OUTPUT_KEYS: ClassVar[List[str]] = ["outline"]

    def __init__(self, model_override: str = None):
        super().__init__(name="outline", model_override=model_override)

    def build_system_prompt(self, state: PaperState) -> str:
        return _SYSTEM_PROMPT

    def build_user_prompt(self, state: PaperState) -> str:
        lit_summary = "\n".join(
            f"  [{i+1}] {item.title} ({item.year}) — {', '.join(item.key_points[:2])}"
            for i, item in enumerate(state.literature[:10])
        ) or "  （暂无文献）"

        code_summary = "\n".join(
            f"  [{a.filename}] {a.role}\n    关键洞察：{'; '.join(a.insights[:2])}"
            for a in state.code_analyses[:5]
        ) or "  （暂无代码分析）"

        instructions = state.current_instructions or "请设计完整的论文大纲。"

        return f"""研究主题：{state.topic}

已有文献（前 10 篇）：
{lit_summary}

代码分析要点：
{code_summary}

指令：{instructions}

请设计完整的论文大纲，输出 JSON。"""

    def parse_response(self, response: str, state: PaperState) -> dict:
        try:
            start = response.find("{")
            end = response.rfind("}") + 1
            data = json.loads(response[start:end])
            sections = [Section(**s) for s in data.get("sections", [])]
            outline = PaperOutline(
                title=data.get("title", state.topic),
                abstract_draft=data.get("abstract_draft", ""),
                sections=sections,
                keywords=data.get("keywords", []),
                contribution_points=data.get("contribution_points", []),
            )
            return {"outline": outline, "phase": "writing"}
        except Exception as e:
            return {"errors": state.errors + [f"outline parse error: {e}"]}


async def outline_node(state: PaperState) -> Command:
    """LangGraph 节点函数"""
    agent = OutlineAgent(
        model_override=state.model_config_override.get("outline")
    )
    updates = await agent.run(state)
    updates["current_agent"] = "supervisor"
    return Command(goto="supervisor", update=updates)

"""
Reviewer Agent — 论文审稿

职责：
  - 对 Writer 完成的章节进行学术质量审核
  - 审核维度：技术准确性、逻辑连贯性、引用规范性、语言表达、篇幅合理性
  - 给出结构化的问题列表和改进建议
  - 判断该章节是否通过（passed），不通过则要求 Writer 修改

输入：state.sections[current_section_index], state.outline, state.literature,
      state.current_instructions
输出：state.reviews（追加 ReviewFeedback）
     state.sections（将通过的章节状态更新为 reviewed / finalized）
"""
from __future__ import annotations
import json
from typing import ClassVar, List
from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.types import Command

from .base import BaseAgent
from ..graph.state import PaperState, ReviewFeedback, Section
from ..utils.llm import get_agent_llm

_SYSTEM_PROMPT = """你是一位严格但公正的计算机安全方向学术论文审稿人。

重要说明：章节内容可能以 LaTeX 格式提交（包含 \\section、\\cite、\\begin 等命令）。
请对语义内容进行评审，忽略 LaTeX 排版命令本身，将其视为正常学术写作的格式化标记。

审核维度（每项 0-10 分）：
1. 技术准确性 — 密码学算法描述、协议流程、数据是否准确无误
2. 逻辑连贯性 — 段落之间是否有清晰的逻辑链，是否有跳跃或矛盾
3. 引用规范性 — 引用是否恰当，关键论断是否有文献支撑（\\cite{} 即为正确引用形式）
4. 内容质量   — 内容是否充实、观点是否有据可查、是否有明显错误或遗漏
5. 篇幅合理性 — 是否覆盖了该章节应有的主要内容，重点是否突出

判断标准：
- 所有维度均 >= 6 分：passed = true（LaTeX 格式内容标准适当放宽）
- 任一维度 < 6 分：passed = false，必须指出具体问题和修改建议

输出严格为 JSON（不加 markdown 代码块）：
{
  "section_index": 0,
  "scores": {"accuracy": 8, "coherence": 7, "citation": 9, "content": 8, "length": 7},
  "issues": ["问题 1"],
  "suggestions": ["建议 1"],
  "passed": true,
  "reviewer_notes": "总体评价（1-2 句）"
}
"""


class ReviewerAgent(BaseAgent):
    ROLE: ClassVar[str] = "论文质量审稿人"
    DESCRIPTION: ClassVar[str] = (
        "审核章节草稿的技术准确性、逻辑连贯性和语言表达，给出结构化修改意见，决定是否通过。"
    )
    INPUT_KEYS: ClassVar[List[str]] = [
        "sections", "outline", "literature",
        "current_section_index", "current_instructions",
    ]
    OUTPUT_KEYS: ClassVar[List[str]] = ["reviews", "sections"]

    def __init__(self, model_override: str = None):
        super().__init__(name="reviewer", model_override=model_override)

    def build_system_prompt(self, state: PaperState) -> str:
        return _SYSTEM_PROMPT

    def build_user_prompt(self, state: PaperState) -> str:
        idx = state.current_section_index
        section = state.sections.get(idx)
        if section is None:
            return f"ERROR: 章节 {idx} 尚未起草。"

        outline_info = ""
        if state.outline and idx < len(state.outline.sections):
            outline_section = state.outline.sections[idx]
            outline_info = f"写作目标：{outline_section.content}\n建议字数：{outline_section.word_count}"

        instructions = state.current_instructions or "请对该章节进行全面审核。"

        return f"""论文主题：{state.outline.title if state.outline else state.topic}
章节编号：{idx}  章节标题：{section.title}

{outline_info}

章节草稿：
{section.content}

指令：{instructions}

请输出 JSON 格式的审稿意见。"""

    def parse_response(self, response: str, state: PaperState) -> dict:
        try:
            start = response.find("{")
            end = response.rfind("}") + 1
            data = json.loads(response[start:end])
            feedback = ReviewFeedback(
                section_index=data.get("section_index", state.current_section_index),
                issues=data.get("issues", []),
                suggestions=data.get("suggestions", []),
                passed=data.get("passed", False),
                reviewer_notes=data.get("reviewer_notes", ""),
            )
            new_reviews = list(state.reviews) + [feedback]

            # 更新章节状态
            updated_sections = dict(state.sections)
            idx = feedback.section_index
            if idx in updated_sections:
                sec = updated_sections[idx]
                new_status = "reviewed" if feedback.passed else "draft"
                updated_sections[idx] = Section(
                    index=sec.index,
                    title=sec.title,
                    content=sec.content,
                    word_count=sec.word_count,
                    status=new_status,
                )

            return {
                "reviews": new_reviews,
                "sections": updated_sections,
            }
        except Exception as e:
            return {"errors": state.errors + [f"reviewer parse error: {e}"]}


async def reviewer_node(state: PaperState) -> Command:
    """LangGraph 节点函数"""
    agent = ReviewerAgent(
        model_override=state.model_config_override.get("reviewer")
    )
    updates = await agent.run(state)
    updates["current_agent"] = "supervisor"
    return Command(goto="supervisor", update=updates)

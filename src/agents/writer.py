"""
Writer Agent — 论文章节写作

职责：
  - 按 Supervisor 指令，逐章节起草论文正文
  - 保持学术写作风格（客观、严谨、第三人称）
  - 引用文献（[n] 格式，与 literature 列表对应）
  - 与相邻章节保持逻辑连贯
  - 收到 Reviewer 反馈后，按意见修改已有草稿

输入：state.outline, state.literature, state.code_analyses,
      state.current_section_index, state.current_instructions
输出：state.sections（更新对应章节的 content 和 status）
"""
from __future__ import annotations
import json
from typing import ClassVar, List
from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.types import Command

from .base import BaseAgent
from ..graph.state import PaperState, Section
from ..utils.llm import get_agent_llm

_SYSTEM_PROMPT = """你是一位计算机安全方向的学术论文写作专家，精通密码学、NFC 协议和后量子密码学。

写作规范：
1. 全英文学术写作，语言正式、客观
2. 避免 AI 味（不用 "it is worth noting"、"in conclusion" 这类套话）
3. 使用第三人称（"this paper"、"the proposed system"）
4. 引用文献用 [n] 格式，n 对应 literature 列表的序号
5. 适当使用数学符号和公式（$...$ 或 $$...$$）
6. 遵守目标格式（arxiv/IEEE）的写作惯例
7. 字数控制在指定范围 ±10%

输出格式：
{
  "section_index": 0,
  "title": "Introduction",
  "content": "完整章节正文（Markdown 格式，公式用 LaTeX）",
  "word_count": 800,
  "status": "draft"
}
"""

_REVISION_SYSTEM_PROMPT = """你是一位计算机安全方向的顶级学术论文修订专家，精通密码学、NFC 协议和后量子密码学。

本次任务为**修订模式**：对已有 LaTeX 章节进行精确改进。

修订规范：
1. 输入为 LaTeX 格式的章节源码，输出也必须是**纯 LaTeX 格式**（不要转为 Markdown）
2. **只修改指令要求的部分**，其余内容保持原样，不要擅自删减
3. 严禁添加未经验证的数据；所有新增 \\cite{} 必须来自已有 bib 库
4. 语言润色标准：
   - 消除 AI 味：禁用 "notably"、"it is worth noting"、"in summary"、"in conclusion"、
     "this paper aims to" 等套话
   - 消除重复：同一免责声明/同一结论禁止出现两次以上
   - 段落间需有逻辑过渡句
5. 新增内容（表格/图表）需遵守原文 LaTeX 宏包配置
6. 保留原文所有 \\label{} 和 \\ref{} 以免破坏交叉引用

输出格式为严格 JSON（**不要加 markdown 代码块**）：
{
  "section_index": <整数>,
  "title": "<章节标题>",
  "content": "<完整改进后的 LaTeX 章节，含 \\\\section{} 命令>",
  "changes_summary": "<一句话说明做了哪些改动>",
  "word_count": <字数估算>,
  "status": "draft"
}

⚠️ JSON 字符串中反斜杠须转义：LaTeX 的 \\section → JSON 中写 \\\\section
⚠️ 不要在 JSON 外面再包一层 ```json ... ``` 代码块
"""


class WriterAgent(BaseAgent):
    ROLE: ClassVar[str] = "学术论文写作专家"
    DESCRIPTION: ClassVar[str] = (
        "逐章节起草论文正文，保持学术写作风格，引用文献，收到审稿意见后进行修改。"
    )
    INPUT_KEYS: ClassVar[List[str]] = [
        "outline", "literature", "code_analyses",
        "sections", "current_section_index", "current_instructions",
    ]
    OUTPUT_KEYS: ClassVar[List[str]] = ["sections"]

    def __init__(self, model_override: str = None, revision_mode: bool = False):
        super().__init__(name="writer", model_override=model_override)
        self.revision_mode = revision_mode

    def build_system_prompt(self, state: PaperState) -> str:
        return _REVISION_SYSTEM_PROMPT if self.revision_mode else _SYSTEM_PROMPT

    def build_user_prompt(self, state: PaperState) -> str:
        if state.outline is None:
            return "ERROR: 大纲尚未生成，无法写作。"

        idx = state.current_section_index
        sections = state.outline.sections
        if idx >= len(sections):
            return "ERROR: 章节索引越界。"

        target = sections[idx]

        # 已有草稿（修改场景）
        existing = state.sections.get(idx)
        existing_block = ""
        if existing and existing.content:
            existing_block = f"\n\n现有草稿（需按以下指令修改）：\n{existing.content}"

        # 前后章节标题提示（保持连贯性）
        prev_title = sections[idx - 1].title if idx > 0 else "（无）"
        next_title = sections[idx + 1].title if idx + 1 < len(sections) else "（无）"

        lit_ref = "\n".join(
            f"  [{i+1}] {item.title} ({item.year})"
            for i, item in enumerate(state.literature[:15])
        )

        instructions = state.current_instructions or f"请起草 {target.title} 章节。"

        return f"""论文主题：{state.outline.title}

目标章节：[{idx}] {target.title}
建议字数：{target.word_count} 字
章节目标：{target.content}

上一章：{prev_title}
下一章：{next_title}

可引用文献：
{lit_ref}

指令：{instructions}{existing_block}

请输出 JSON 格式的章节内容。"""

    def parse_response(self, response: str, state: PaperState) -> dict:
        import re
        data = None

        # Attempt 1: standard JSON parse
        try:
            start = response.find("{")
            end = response.rfind("}") + 1
            if start != -1 and end > start:
                data = json.loads(response[start:end])
        except (json.JSONDecodeError, ValueError):
            pass

        # Attempt 2: strip markdown code fences and retry
        if data is None:
            cleaned = re.sub(r"```(?:json)?\s*", "", response).replace("```", "").strip()
            try:
                start = cleaned.find("{")
                end = cleaned.rfind("}") + 1
                if start != -1 and end > start:
                    data = json.loads(cleaned[start:end])
            except (json.JSONDecodeError, ValueError):
                pass

        # Attempt 3: extract content field via regex (handles unescaped backslashes)
        if data is None:
            m = re.search(r'"content"\s*:\s*"((?:[^"\\]|\\.)*)"\s*[,}]', response, re.DOTALL)
            if m:
                raw_content = m.group(1).replace('\\"', '"').replace('\\n', '\n').replace('\\\\', '\\')
                idx = state.current_section_index
                section = Section(
                    index=idx, title="",
                    content=raw_content, word_count=len(raw_content.split()),
                    status="draft",
                )
                updated = dict(state.sections)
                updated[idx] = section
                return {"sections": updated}

        if data is None:
            return {"errors": state.errors + ["writer parse error: could not parse response"]}

        idx = data.get("section_index", state.current_section_index)
        summary = data.get("changes_summary", "")
        if summary:
            print(f"   📝 changes: {summary}", flush=True)
        section = Section(
            index=idx,
            title=data.get("title", ""),
            content=data.get("content", ""),
            word_count=data.get("word_count", 0),
            status=data.get("status", "draft"),
        )
        updated_sections = dict(state.sections)
        updated_sections[idx] = section
        return {"sections": updated_sections}


async def writer_node(state: PaperState) -> Command:
    """LangGraph 节点函数"""
    agent = WriterAgent(
        model_override=state.model_config_override.get("writer")
    )
    updates = await agent.run(state)
    updates["current_agent"] = "supervisor"
    return Command(goto="supervisor", update=updates)

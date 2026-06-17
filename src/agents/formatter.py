"""
Formatter Agent — 论文格式化输出

职责：
  - 将所有已 reviewed/finalized 的章节整合为完整论文
  - 按目标格式（arxiv/IEEE/ACL）渲染标题页、Abstract、正文、参考文献
  - 插入表格（state.tables）和图表描述（state.figure_descriptions）到对应章节
  - 生成规范的 BibTeX 参考文献列表
  - 输出最终文件到 output/ 目录

输入：state.outline, state.sections, state.literature, state.tables,
      state.figure_descriptions, state.format_template, state.current_instructions
输出：state.final_paper（完整论文字符串），同时写入 output/ 文件
"""
from __future__ import annotations
import os
import json
from typing import ClassVar, List
from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.types import Command

from .base import BaseAgent
from ..graph.state import PaperState
from ..utils.llm import get_agent_llm

_SYSTEM_PROMPT = """你是一位 LaTeX/Markdown 学术论文排版专家，熟悉 arxiv、IEEE 和 ACL 格式规范。

你的任务：
1. 将给定的论文各章节内容整合为完整文档
2. 按目标格式添加必要的宏包、文档类声明、标题页
3. 插入表格和图表（使用 figure/table 环境）
4. 生成规范的 BibTeX 参考文献（\\bibliography{references}）
5. 确保公式编号、图表编号、引用编号全局一致

格式规范：
- arxiv: \\documentclass{article} + natbib
- IEEE: \\documentclass[conference]{IEEEtran}
- ACL: \\documentclass[11pt]{article} + acl2023 包

输出 JSON：
{
  "final_paper": "完整 LaTeX 或 Markdown 文档字符串",
  "bibtex": "BibTeX 条目字符串",
  "filename": "paper_final.tex 或 paper_final.md"
}
"""


class FormatterAgent(BaseAgent):
    ROLE: ClassVar[str] = "论文格式化输出专家"
    DESCRIPTION: ClassVar[str] = (
        "整合所有章节，按目标格式（arxiv/IEEE/ACL）生成完整 LaTeX 文档和参考文献，写入 output/ 目录。"
    )
    INPUT_KEYS: ClassVar[List[str]] = [
        "outline", "sections", "literature", "tables",
        "figure_descriptions", "format_template", "current_instructions",
    ]
    OUTPUT_KEYS: ClassVar[List[str]] = ["final_paper"]

    def __init__(self, model_override: str = None):
        super().__init__(name="formatter", model_override=model_override)

    def build_system_prompt(self, state: PaperState) -> str:
        return _SYSTEM_PROMPT

    def build_user_prompt(self, state: PaperState) -> str:
        # 按 index 排序的章节内容
        sorted_sections = sorted(state.sections.values(), key=lambda s: s.index)
        sections_text = "\n\n".join(
            f"## Section {s.index}: {s.title}\n{s.content}"
            for s in sorted_sections
        )

        # 参考文献列表
        refs = "\n".join(
            f"[{i+1}] {item.authors}. {item.title}. {item.year}. {item.source}"
            for i, item in enumerate(state.literature)
        )

        tables_text = "\n\n".join(
            f"=== {name} ===\n{content}"
            for name, content in state.tables.items()
        )

        outline_title = state.outline.title if state.outline else state.topic
        abstract = state.outline.abstract_draft if state.outline else ""
        instructions = state.current_instructions or "请整合所有章节，生成完整论文。"

        return f"""论文标题：{outline_title}
目标格式：{state.format_template}
摘要草稿：{abstract}

章节内容：
{sections_text}

表格：
{tables_text or '（暂无表格）'}

参考文献：
{refs}

指令：{instructions}

请输出 JSON 格式的完整论文。"""

    def parse_response(self, response: str, state: PaperState) -> dict:
        try:
            # 优先尝试标准 JSON 解析
            final_paper = ""
            filename = "paper_final.tex"
            bibtex = ""

            try:
                start = response.find("{")
                end = response.rfind("}") + 1
                if start != -1 and end > start:
                    data = json.loads(response[start:end])
                    final_paper = data.get("final_paper", "")
                    filename = data.get("filename", filename)
                    bibtex = data.get("bibtex", "")
            except (json.JSONDecodeError, ValueError):
                pass

            # JSON 解析失败时：提取 LaTeX 代码块
            if not final_paper:
                import re
                # 寻找 ```latex ... ``` 或 ```tex ... ``` 代码块
                m = re.search(r"```(?:latex|tex)\s*([\s\S]+?)```", response, re.IGNORECASE)
                if m:
                    final_paper = m.group(1).strip()
                # 或直接寻找 \documentclass 开头的 LaTeX
                elif "\\documentclass" in response:
                    idx = response.find("\\documentclass")
                    final_paper = response[idx:]

            if not final_paper:
                return {"errors": state.errors + ["formatter: 无法提取论文内容"]}

            # 写入文件
            output_dir = state.output_path
            os.makedirs(output_dir, exist_ok=True)
            out_path = os.path.join(output_dir, filename)
            with open(out_path, "w", encoding="utf-8") as f:
                f.write(final_paper)

            if bibtex:
                bib_path = os.path.join(output_dir, "references.bib")
                with open(bib_path, "w", encoding="utf-8") as f:
                    f.write(bibtex)

            return {"final_paper": final_paper, "phase": "done"}
        except Exception as e:
            return {"errors": state.errors + [f"formatter parse error: {e}"]}


async def formatter_node(state: PaperState) -> Command:
    """LangGraph 节点函数"""
    agent = FormatterAgent(
        model_override=state.model_config_override.get("formatter")
    )
    updates = await agent.run(state)
    updates["current_agent"] = "supervisor"
    return Command(goto="supervisor", update=updates)

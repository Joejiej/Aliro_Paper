"""
Data Analyst Agent — 数据分析与可视化

职责：
  - 处理 communication-overhead.md 和代码分析中的性能数据
  - 生成论文所需的表格（Markdown / LaTeX 格式）
  - 生成图表描述（供人工/脚本生成图片）
  - 计算关键指标：APDU 数对比、字节数对比、时延估算

输入：state.code_analyses, state.current_instructions
输出：state.tables, state.figure_descriptions
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

_OVERHEAD_FILE = os.path.join(
    os.path.dirname(__file__), "..", "..", "source", "communication-overhead.md"
)

_SYSTEM_PROMPT = """你是一位专注于协议性能分析的数据分析专家。

你的任务：
1. 分析给定的性能数据（通信开销、APDU 数、时延）
2. 生成适合学术论文的表格（LaTeX 格式，\\begin{table}...\\end{table}）
3. 生成图表描述（用于指导人工绘制或脚本生成图片），每个图给出：
   - 图名（caption）
   - 坐标轴说明
   - 数据系列
   - 关键结论

输出严格为 JSON：
{
  "tables": {
    "table_overhead": "LaTeX 表格代码",
    "table_latency": "LaTeX 表格代码"
  },
  "figure_descriptions": {
    "fig_apdu_comparison": "图表描述文字",
    "fig_latency_breakdown": "图表描述文字"
  }
}
"""


class DataAnalystAgent(BaseAgent):
    ROLE: ClassVar[str] = "数据分析与可视化专家"
    DESCRIPTION: ClassVar[str] = (
        "处理性能测量数据，生成论文表格（LaTeX）和图表描述，支撑 Evaluation 章节。"
    )
    INPUT_KEYS: ClassVar[List[str]] = ["code_analyses", "current_instructions"]
    OUTPUT_KEYS: ClassVar[List[str]] = ["tables", "figure_descriptions"]

    def __init__(self, model_override: str = None):
        super().__init__(name="data_analyst", model_override=model_override)

    def build_system_prompt(self, state: PaperState) -> str:
        return _SYSTEM_PROMPT

    def build_user_prompt(self, state: PaperState) -> str:
        # 读取通信开销分析文档
        overhead_data = ""
        if os.path.exists(_OVERHEAD_FILE):
            with open(_OVERHEAD_FILE, "r", encoding="utf-8") as f:
                overhead_data = f.read()

        instructions = state.current_instructions or "请生成通信开销对比表格和时延分析图表描述。"

        return f"""指令：{instructions}

通信开销分析文档：
{overhead_data[:4000]}

请输出 JSON 格式的表格和图表描述。"""

    def parse_response(self, response: str, state: PaperState) -> dict:
        try:
            start = response.find("{")
            end = response.rfind("}") + 1
            data = json.loads(response[start:end])
            new_tables = dict(state.tables)
            new_tables.update(data.get("tables", {}))
            new_figs = dict(state.figure_descriptions)
            new_figs.update(data.get("figure_descriptions", {}))
            return {
                "tables": new_tables,
                "figure_descriptions": new_figs,
            }
        except Exception as e:
            return {"errors": state.errors + [f"data_analyst parse error: {e}"]}


async def data_analyst_node(state: PaperState) -> Command:
    """LangGraph 节点函数"""
    agent = DataAnalystAgent(
        model_override=state.model_config_override.get("data_analyst")
    )
    updates = await agent.run(state)
    updates["current_agent"] = "supervisor"
    return Command(goto="supervisor", update=updates)

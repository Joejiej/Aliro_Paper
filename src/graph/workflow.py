"""
LangGraph 工作流 — Supervisor 模式多 Agent 图

拓扑结构：
  START → supervisor
  supervisor → {literature, code_analyst, outline, writer, data_analyst, reviewer, formatter}
  所有 worker → supervisor（通过 Command(goto="supervisor")）
  supervisor → __end__（当 phase == "done" 或达到 max_iterations）
"""
from __future__ import annotations
from langgraph.graph import StateGraph, START, END

from ..graph.state import PaperState
from ..agents.supervisor import supervisor_node
from ..agents.literature import literature_node
from ..agents.code_analyst import code_analyst_node
from ..agents.outline import outline_node
from ..agents.writer import writer_node
from ..agents.data_analyst import data_analyst_node
from ..agents.reviewer import reviewer_node
from ..agents.formatter import formatter_node


def create_paper_workflow() -> StateGraph:
    """构建并编译论文写作 LangGraph 工作流"""

    builder = StateGraph(PaperState)

    # ── 注册节点 ──────────────────────────────────────────────────────────
    builder.add_node("supervisor",    supervisor_node)
    builder.add_node("literature",    literature_node)
    builder.add_node("code_analyst",  code_analyst_node)
    builder.add_node("outline",       outline_node)
    builder.add_node("writer",        writer_node)
    builder.add_node("data_analyst",  data_analyst_node)
    builder.add_node("reviewer",      reviewer_node)
    builder.add_node("formatter",     formatter_node)

    # ── 入口：从 START 直接进入 supervisor ───────────────────────────────
    builder.add_edge(START, "supervisor")

    # ── 所有 worker 节点通过 Command(goto="supervisor") 回到 supervisor ──
    # （Command 模式下不需要显式 add_edge，LangGraph 自动处理）

    return builder.compile()


def create_paper_workflow_with_checkpointer(checkpointer=None) -> StateGraph:
    """带断点续跑功能的工作流（传入 checkpointer 实现持久化）"""
    builder = StateGraph(PaperState)

    builder.add_node("supervisor",    supervisor_node)
    builder.add_node("literature",    literature_node)
    builder.add_node("code_analyst",  code_analyst_node)
    builder.add_node("outline",       outline_node)
    builder.add_node("writer",        writer_node)
    builder.add_node("data_analyst",  data_analyst_node)
    builder.add_node("reviewer",      reviewer_node)
    builder.add_node("formatter",     formatter_node)

    builder.add_edge(START, "supervisor")

    return builder.compile(checkpointer=checkpointer)

"""
Supervisor Agent — 编排器

职责：
  - 读取当前 PaperState，判断下一步该派谁
  - 生成结构化指令 (SupervisorDecision) 传递给被派 Agent
  - 监控进度，识别 phase 完成条件，推进到下一 phase
  - 遇到 errors 时决定是重试、跳过还是中止

不做：
  - 不直接完成任何实质性研究/写作任务
  - 不持久化文件（由各 Worker Agent 负责）
"""
from __future__ import annotations
import json
from typing import Literal
from langchain_core.messages import HumanMessage, SystemMessage, AIMessage
from langgraph.types import Command

from ..graph.state import PaperState, SupervisorDecision
from ..utils.llm import get_agent_llm

# ── 可以路由的目标 ───────────────────────────────────────────────────────
WORKERS = Literal[
    "literature",
    "code_analyst",
    "outline",
    "writer",
    "data_analyst",
    "reviewer",
    "formatter",
    "__end__",
]

# ── Phase 完成判断函数 ────────────────────────────────────────────────────
def _research_done(state: PaperState) -> bool:
    return len(state.literature) >= 3 and len(state.code_analyses) >= 2

def _outline_done(state: PaperState) -> bool:
    return state.outline is not None and len(state.outline.sections) > 0

def _writing_done(state: PaperState) -> bool:
    if state.outline is None:
        return False
    total = len(state.outline.sections)
    drafted = sum(1 for s in state.sections.values() if s.status in ("draft", "reviewed", "finalized"))
    return drafted >= total

def _review_done(state: PaperState) -> bool:
    return all(r.passed for r in state.reviews)

def _format_done(state: PaperState) -> bool:
    return bool(state.final_paper)


_SYSTEM_PROMPT = """你是一个科研论文写作项目的 Supervisor（编排器）。

你的团队成员：
- literature    : 文献调研 Agent，负责搜索和整理学术文献
- code_analyst  : 代码分析 Agent，负责分析项目源码，提炼技术细节
- outline       : 大纲设计 Agent，负责设计论文结构和大纲
- writer        : 写作 Agent，负责起草论文各章节正文
- data_analyst  : 数据分析 Agent，负责生成表格、图表和性能数据
- reviewer      : 审稿 Agent，负责审核章节质量，提出修改意见
- formatter     : 格式化 Agent，负责将草稿转换为目标格式（LaTeX/Markdown）

你的决策原则：
1. 按 phase 推进：init → research → outline → writing → review → format → done
2. 在 research phase，先派 code_analyst 分析源码，再派 literature 找背景文献
3. 在 writing phase，按 section 顺序逐章派 writer，每章完成后派 reviewer 审核
4. 如果 reviewer 未通过某章，重新派 writer 修改（最多 2 次）
5. 所有章节 finalized 后，进入 format phase
6. 遇到 error 时，记录到 state.errors，跳过问题项继续推进

每次输出严格遵守 JSON 格式：
{
  "next": "<agent_name 或 __end__>",
  "phase": "<当前 phase>",
  "reason": "<决策理由，一句话>",
  "instructions": "<给被派 Agent 的具体指令>",
  "section_index": <下一个要处理的章节索引，整数，review/writing phase 必填，其他 phase 填 -1>
}

重要规则：
- review phase：找到 sections 中状态为 "draft" 的最小 index，将 section_index 设为该值，派 reviewer
- writing phase：找到 sections 中尚未起草的最小 index，将 section_index 设为该值，派 writer
- 若所有 draft 章节均已 reviewed/finalized，进入 format phase，section_index=-1
"""


async def supervisor_node(state: PaperState) -> Command[WORKERS]:
    """LangGraph 节点函数：读取状态，决定下一个 Agent"""
    import datetime
    ts = datetime.datetime.now().strftime("%H:%M:%S")
    print(f"[{ts}] ◈ supervisor | phase={state.phase} | iter={state.iteration_count}/{state.max_iterations}", flush=True)

    llm = get_agent_llm(
        "supervisor",
        model_override=state.model_config_override.get("supervisor"),
    )

    # 构建状态摘要给 Supervisor LLM
    status_summary = _build_status_summary(state)
    user_msg = HumanMessage(content=status_summary)

    response = await llm.ainvoke([
        SystemMessage(content=_SYSTEM_PROMPT),
        *state.messages[-6:],   # 最近 6 条消息作为上下文
        user_msg,
    ])

    decision = _parse_decision(response.content)

    # 记录决策
    new_decision = SupervisorDecision(
        next_agent=decision["next"],
        phase=decision["phase"],
        reason=decision["reason"],
        instructions=decision["instructions"],
    )

    # 构建 state 更新
    state_update = {
        "current_agent": decision["next"],
        "current_instructions": decision["instructions"],
        "phase": decision["phase"],
        "decisions": state.decisions + [new_decision],
        "iteration_count": state.iteration_count + 1,
        "messages": [AIMessage(content=response.content)],
    }
    # 更新章节索引（-1 表示不需要更新）
    sec_idx = decision.get("section_index", -1)
    if isinstance(sec_idx, int) and sec_idx >= 0:
        state_update["current_section_index"] = sec_idx

    next_node = decision["next"]
    ts2 = datetime.datetime.now().strftime("%H:%M:%S")
    print(f"[{ts2}] → {next_node} | {decision.get('reason','')[:60]}", flush=True)
    if next_node == "__end__":
        return Command(goto="__end__", update=state_update)
    return Command(goto=next_node, update=state_update)


def _build_status_summary(state: PaperState) -> str:
    """将当前 state 压缩为 Supervisor 可读的状态摘要"""
    outline_sections = len(state.outline.sections) if state.outline else 0
    drafted = sum(1 for s in state.sections.values() if s.status != "pending")

    # 每章节的具体状态（status: pending/draft/reviewed/finalized）
    section_detail = ""
    if state.sections:
        lines = []
        for idx in sorted(state.sections.keys()):
            s = state.sections[idx]
            lines.append(f"    §{idx} {s.title}: {s.status}")
        section_detail = "\n".join(lines)
    else:
        section_detail = "    （无章节）"

    # 找出下一个需要处理的章节（status=draft 且 index 最小）
    pending_indices = sorted(
        idx for idx, s in state.sections.items()
        if s.status in ("pending", "draft")
    )
    next_pending = pending_indices[0] if pending_indices else -1

    return f"""当前状态摘要：
主题：{state.topic}
Phase：{state.phase}
迭代次数：{state.iteration_count} / {state.max_iterations}
当前章节索引：{state.current_section_index}
下一个待处理章节索引：{next_pending}（-1 表示全部完成）

研究阶段：
  - 已收集文献：{len(state.literature)} 篇
  - 已分析源文件：{len(state.code_analyses)} 个

写作阶段：
  - 大纲章节数：{outline_sections}
  - 已起草章节：{drafted} / {len(state.sections)}
  - 最终论文：{'已生成' if state.final_paper else '未生成'}

各章节状态（status: pending→draft→reviewed→finalized）：
{section_detail}

错误：{state.errors if state.errors else '无'}

最新指令：{state.current_instructions or '（初次启动）'}

决策提示：
- 若 next_pending_index >= 0，说明该章节（status=draft）还未通过审核，请将 section_index 设为该值。
- 若 next_pending_index == -1，说明所有章节已通过审核，进入 format phase。
- 不要重复审核状态为 "reviewed" 或 "finalized" 的章节。

请根据以上状态决定下一步行动。"""


_FALLBACK = {
    "next": "literature",
    "phase": "research",
    "reason": "JSON 解析失败，降级路由到 literature",
    "instructions": "请继续执行文献调研任务。",
}

_KEY_ALIASES = {
    "next": ["next", "next_agent", "agent", "goto", "route", "worker"],
    "phase": ["phase", "current_phase", "stage"],
    "reason": ["reason", "rationale", "why", "explanation"],
    "instructions": ["instructions", "instruction", "task", "directive", "message"],
    "section_index": ["section_index", "section_idx", "index", "sec_index"],
}


def _parse_decision(raw: str) -> dict:
    """解析 LLM 输出的 JSON 决策，容错处理（支持 key 别名）"""
    data = None
    try:
        start = raw.find("{")
        end = raw.rfind("}") + 1
        if start != -1 and end > start:
            data = json.loads(raw[start:end])
    except json.JSONDecodeError:
        pass

    if not isinstance(data, dict):
        return _FALLBACK.copy()

    # 规范化 key：将各种别名统一到标准字段名
    normalized: dict = {}
    for std_key, aliases in _KEY_ALIASES.items():
        for alias in aliases:
            if alias in data:
                normalized[std_key] = data[alias]
                break

    # 必须有 "next"，否则降级
    if "next" not in normalized:
        return _FALLBACK.copy()

    # 用默认值填充缺失字段
    raw_idx = normalized.get("section_index", -1)
    try:
        sec_idx = int(raw_idx)
    except (TypeError, ValueError):
        sec_idx = -1

    return {
        "next": normalized.get("next", _FALLBACK["next"]),
        "phase": normalized.get("phase", _FALLBACK["phase"]),
        "reason": normalized.get("reason", ""),
        "instructions": normalized.get("instructions", ""),
        "section_index": sec_idx,
    }

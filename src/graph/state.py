"""论文写作系统 — 全局状态定义"""
from __future__ import annotations
from pydantic import BaseModel, Field
from typing import List, Dict, Optional, Any
from typing_extensions import Annotated
from langgraph.graph.message import add_messages
from langchain_core.messages import AnyMessage


class LiteratureItem(BaseModel):
    """单篇文献条目"""
    title: str
    authors: str
    year: int
    source: str               # arXiv ID / DOI / 本地 PDF 路径
    abstract: str
    key_points: List[str] = []
    relevance_score: float = 0.0


class CodeAnalysis(BaseModel):
    """单个源文件的分析结果"""
    filename: str
    role: str                 # 该文件在系统中的作用
    key_classes: List[str] = []
    key_methods: List[str] = []
    insights: List[str] = []  # 对论文有价值的技术洞察


class Section(BaseModel):
    """论文章节"""
    index: int
    title: str
    content: str = ""
    word_count: int = 0
    status: str = "pending"   # pending / draft / reviewed / finalized


class PaperOutline(BaseModel):
    """论文大纲"""
    title: str
    abstract_draft: str = ""
    sections: List[Section] = []
    keywords: List[str] = []
    contribution_points: List[str] = []


class ReviewFeedback(BaseModel):
    """审稿意见"""
    section_index: int
    issues: List[str] = []
    suggestions: List[str] = []
    passed: bool = False
    reviewer_notes: str = ""


class SupervisorDecision(BaseModel):
    """Supervisor 每次路由决策的记录"""
    next_agent: str
    phase: str
    reason: str
    instructions: str         # 给被派遣 Agent 的具体指令


class PaperState(BaseModel):
    """贯穿整个工作流的全局状态"""

    # ── 任务基本信息 ─────────────────────────────────────────
    topic: str = ""
    phase: str = "init"
    # 可选：init / research / outline / writing / review / format / done

    # ── Agent 路由 ────────────────────────────────────────────
    current_agent: str = "supervisor"
    current_instructions: str = ""   # supervisor 给当前 Agent 的指令
    iteration_count: int = 0
    max_iterations: int = 50

    # ── 研究内容 ──────────────────────────────────────────────
    literature: List[LiteratureItem] = []
    code_analyses: List[CodeAnalysis] = []
    outline: Optional[PaperOutline] = None

    # ── 写作内容 ──────────────────────────────────────────────
    sections: Dict[int, Section] = {}
    current_section_index: int = 0
    reviews: List[ReviewFeedback] = []
    final_paper: str = ""            # 格式化后的完整论文

    # ── 数据与图表 ────────────────────────────────────────────
    tables: Dict[str, str] = {}      # {表名: Markdown/LaTeX 表格}
    figure_descriptions: Dict[str, str] = {}

    # ── Supervisor 决策历史 ───────────────────────────────────
    decisions: List[SupervisorDecision] = []

    # ── 配置 ──────────────────────────────────────────────────
    format_template: str = "arxiv"   # arxiv / ieee / acl
    output_path: str = "./output"
    model_config_override: Dict[str, str] = {}
    # e.g. {"writer": "claude-opus-4-8", "reviewer": "deepseek-chat"}

    # ── 错误 ──────────────────────────────────────────────────
    errors: List[str] = []

    # ── 消息历史（LangGraph reducer）─────────────────────────
    messages: Annotated[List[AnyMessage], add_messages] = Field(
        default_factory=list
    )

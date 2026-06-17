"""Agent 模块 — 统一导出"""
from .supervisor import supervisor_node
from .literature import LiteratureAgent, literature_node
from .code_analyst import CodeAnalystAgent, code_analyst_node
from .outline import OutlineAgent, outline_node
from .writer import WriterAgent, writer_node
from .data_analyst import DataAnalystAgent, data_analyst_node
from .reviewer import ReviewerAgent, reviewer_node
from .formatter import FormatterAgent, formatter_node

# Agent 元数据注册表（供 Supervisor 引用）
AGENT_REGISTRY = {
    "literature":   LiteratureAgent,
    "code_analyst": CodeAnalystAgent,
    "outline":      OutlineAgent,
    "writer":       WriterAgent,
    "data_analyst": DataAnalystAgent,
    "reviewer":     ReviewerAgent,
    "formatter":    FormatterAgent,
}

__all__ = [
    "supervisor_node",
    "literature_node",
    "code_analyst_node",
    "outline_node",
    "writer_node",
    "data_analyst_node",
    "reviewer_node",
    "formatter_node",
    "AGENT_REGISTRY",
]

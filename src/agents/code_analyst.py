"""
Code Analyst Agent — 源码分析

职责：
  - 阅读 source/ 目录下的项目源码
  - 提炼协议流程、类/方法设计、关键算法参数
  - 识别对论文有价值的技术细节（实现决策、性能特征、安全约束）
  - 为 Evaluation 和 Implementation 章节准备数据素材

输入：state.topic, state.current_instructions（含待分析文件路径列表）
输出：state.code_analyses（追加 CodeAnalysis 列表）
"""
from __future__ import annotations
import os
import json
from typing import ClassVar, List
from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.types import Command

from .base import BaseAgent
from ..graph.state import PaperState, CodeAnalysis
from ..utils.llm import get_agent_llm

# 默认分析的核心文件（相对于 source/）
DEFAULT_TARGET_FILES = [
    "Aliro_Reader_PC_NFC/src/main/java/org/example/Reader.java",
    "Ailiro_UD/app/src/main/java/com/example/ailiro_ud/UserDevice.java",
    "Ailiro_UD/app/src/main/java/com/example/ailiro_ud/PQKeyManager.java",
    "Aliro_Reader_PC_NFC/src/main/java/org/example/PQCUtil.java",
    "Ailiro_UD/app/src/main/java/com/example/ailiro_ud/PQCConfig.java",
]

_SYSTEM_PROMPT = """你是一名精通 Java、密码学和 NFC 协议的代码分析专家。

你的任务：
1. 分析给定的 Java 源文件
2. 识别：该文件的职责、核心类/方法、密码学参数配置、协议步骤实现
3. 提炼对论文有价值的内容：
   - 实现细节（算法选择、参数、APDU 格式）
   - 性能相关设计（分片逻辑、缓存策略）
   - 安全设计（密钥管理、认证流程、信任模型）
   - 与原始 ECC Aliro 的差异点
4. 每条 insight 要具体，可以直接引用到论文的 Implementation 或 Evaluation 章节

输出严格为 JSON：
{
  "analyses": [
    {
      "filename": "Reader.java",
      "role": "PC 端 NFC Reader 主控逻辑，实现 PQ-Aliro Expedited 协议三阶段",
      "key_classes": ["Reader"],
      "key_methods": ["startAuth0", "sendLoadCert", "verifyAuth1Response"],
      "insights": [
        "AUTH0 Command 使用 Kyber768 临时公钥，分 7 个 APDU 发送（200 B/帧），共 1292 B",
        "LOADCERT 步骤可通过 key_slot 机制省略，节省约 22 个 APDU"
      ]
    }
  ]
}
"""


class CodeAnalystAgent(BaseAgent):
    ROLE: ClassVar[str] = "源码分析专家"
    DESCRIPTION: ClassVar[str] = (
        "分析项目 Java 源码，提炼协议实现细节和对论文有价值的技术数据。"
    )
    INPUT_KEYS: ClassVar[List[str]] = ["topic", "current_instructions"]
    OUTPUT_KEYS: ClassVar[List[str]] = ["code_analyses"]

    def __init__(self, model_override: str = None):
        super().__init__(name="code_analyst", model_override=model_override)
        self.source_base = os.path.join(
            os.path.dirname(__file__), "..", "..", "source"
        )

    def build_system_prompt(self, state: PaperState) -> str:
        return _SYSTEM_PROMPT

    def build_user_prompt(self, state: PaperState) -> str:
        instructions = state.current_instructions or ""
        # 从指令中解析目标文件，否则用默认列表
        target_files = DEFAULT_TARGET_FILES

        # 读取文件内容（取前 300 行，避免超 context）
        file_contents = []
        for rel_path in target_files:
            full_path = os.path.normpath(os.path.join(self.source_base, rel_path))
            if os.path.exists(full_path):
                with open(full_path, "r", encoding="utf-8", errors="ignore") as f:
                    lines = f.readlines()[:300]
                content = "".join(lines)
                file_contents.append(f"=== {rel_path} ===\n{content}\n")

        joined = "\n".join(file_contents)
        return f"""研究主题：{state.topic}

{instructions}

以下是需要分析的源代码（每文件截取前 300 行）：

{joined}

请按 JSON 格式输出分析结果。"""

    def parse_response(self, response: str, state: PaperState) -> dict:
        try:
            start = response.find("{")
            end = response.rfind("}") + 1
            data = json.loads(response[start:end])
            new_items = [CodeAnalysis(**item) for item in data.get("analyses", [])]
            return {"code_analyses": state.code_analyses + new_items}
        except Exception as e:
            return {"errors": state.errors + [f"code_analyst parse error: {e}"]}


async def code_analyst_node(state: PaperState) -> Command:
    """LangGraph 节点函数"""
    agent = CodeAnalystAgent(
        model_override=state.model_config_override.get("code_analyst")
    )
    updates = await agent.run(state)
    updates["current_agent"] = "supervisor"
    return Command(goto="supervisor", update=updates)

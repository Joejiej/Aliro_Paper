#!/usr/bin/env python
"""Paper Agent — 多 Agent 论文写作入口"""
from __future__ import annotations
import os
import sys
import asyncio
import argparse
from dotenv import load_dotenv

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
load_dotenv()

from src.utils.proxy_check import ensure_us_proxy
from src.graph.state import PaperState
from src.graph.workflow import create_paper_workflow, create_paper_workflow_with_checkpointer


async def main():
    # ── 代理检查：非美国出口则终止 ──────────────────────────────────────────
    ensure_us_proxy()

    parser = argparse.ArgumentParser(description="Multi-Agent Paper Writing System")
    parser.add_argument("--topic", "-t", required=True, help="论文主题")
    parser.add_argument(
        "--format", "-f",
        default="arxiv",
        choices=["arxiv", "ieee", "acl"],
        help="目标论文格式",
    )
    parser.add_argument("--output", "-o", default="./output", help="输出目录")
    parser.add_argument(
        "--model-writer", default=None,
        help="写作 Agent 使用的模型（覆盖 .env 配置）",
    )
    parser.add_argument(
        "--checkpoint", action="store_true",
        help="启用 SQLite 断点续跑",
    )
    args = parser.parse_args()

    # 检查至少有一个 API Key
    has_key = any(
        os.getenv(k) for k in [
            "OPENAI_API_KEY", "ANTHROPIC_API_KEY",
            "DEEPSEEK_API_KEY", "DASHSCOPE_API_KEY",
        ]
    )
    if not has_key:
        print("请先在 .env 文件中设置至少一个 LLM API Key")
        sys.exit(1)

    # 模型覆盖
    model_overrides = {}
    if args.model_writer:
        model_overrides["writer"] = args.model_writer

    # 初始化状态
    initial_state = PaperState(
        topic=args.topic,
        format_template=args.format,
        output_path=args.output,
        model_config_override=model_overrides,
        phase="init",
    )

    print(f"📝 论文主题: {args.topic}")
    print(f"📐 格式: {args.format}")
    print(f"📂 输出: {args.output}")
    print("=" * 60)

    # 构建工作流
    # recursion_limit: 每个 supervisor→agent→supervisor 循环算 2 步
    # 8节×5步 + research/outline约10步 = ~50步，设100留余量
    recursion_limit = 100

    if args.checkpoint:
        try:
            from langgraph.checkpoint.sqlite import SqliteSaver
            checkpointer = SqliteSaver.from_conn_string(
                os.path.join(args.output, "checkpoint.db")
            )
            app = create_paper_workflow_with_checkpointer(checkpointer)
            config = {
                "configurable": {"thread_id": "paper_run_1"},
                "recursion_limit": recursion_limit,
            }
        except ImportError:
            print("警告：langgraph-checkpoint-sqlite 未安装，禁用断点续跑")
            app = create_paper_workflow()
            config = {"recursion_limit": recursion_limit}
    else:
        app = create_paper_workflow()
        config = {"recursion_limit": recursion_limit}

    # 运行工作流
    result = await app.ainvoke(initial_state, config=config)

    print("\n✅ 论文写作完成！")
    print(f"📂 输出目录: {result.output_path}")
    if result.final_paper:
        print(f"📄 论文已生成（{len(result.final_paper)} 字符）")
    if result.errors:
        print(f"⚠️  共 {len(result.errors)} 个错误：")
        for e in result.errors:
            print(f"   - {e}")


if __name__ == "__main__":
    asyncio.run(main())

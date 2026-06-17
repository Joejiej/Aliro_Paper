#!/usr/bin/env python
"""
run_review.py — 直接审核 + 格式化（绕开 supervisor 路由循环）

逐章节直接调用 ReviewerAgent，然后调用 FormatterAgent，
最终在 output/ 生成审稿报告 + 完整 LaTeX 论文（由 formatter 拼装）。

用法：
  cd F:/paper-agent
  .venv/Scripts/Activate.ps1
  python src/run_review.py
"""
from __future__ import annotations
import os
import sys
import json
import asyncio

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from dotenv import load_dotenv
load_dotenv()

from src.utils.proxy_check import ensure_us_proxy
from src.graph.state import PaperState, PaperOutline, Section, LiteratureItem
from src.agents.reviewer import ReviewerAgent
from src.agents.formatter import FormatterAgent

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUTPUT = os.path.join(BASE, "output")

# 章节 (index, title, 文件名)
SECTION_FILES = [
    (0,  "Abstract",                  "abstract.tex"),
    (1,  "Introduction",              "section_introduction.tex"),
    (2,  "Related Work",              "section_related_work.tex"),
    (3,  "Background",                "section3_background.tex"),
    (8,  "Implementation",            "section8_implementation.tex"),
    (9,  "Evaluation",                "section9_evaluation.tex"),
    (10, "Discussion and Optimization","section10_discussion.tex"),
    (11, "Conclusion",                "section11_conclusion.tex"),
]

KNOWN_LITERATURE = [
    LiteratureItem(
        title="CRYSTALS-Kyber Algorithm Specifications",
        authors="Avanzi et al.", year=2021,
        source="NIST PQC Round 3", abstract="ML-KEM specification.",
        relevance_score=1.0,
    ),
    LiteratureItem(
        title="CRYSTALS-Dilithium Algorithm Specifications",
        authors="Ducas et al.", year=2021,
        source="NIST PQC Round 3", abstract="ML-DSA specification.",
        relevance_score=1.0,
    ),
    LiteratureItem(
        title="Aliro 1.0 Specification",
        authors="Connectivity Standards Alliance", year=2022,
        source="CSA", abstract="NFC access control protocol.",
        relevance_score=1.0,
    ),
    LiteratureItem(
        title="FIPS 203: ML-KEM Standard",
        authors="NIST", year=2024,
        source="NIST FIPS 203", abstract="NIST standard for ML-KEM.",
        relevance_score=1.0,
    ),
    LiteratureItem(
        title="FIPS 204: ML-DSA Standard",
        authors="NIST", year=2024,
        source="NIST FIPS 204", abstract="NIST standard for ML-DSA.",
        relevance_score=1.0,
    ),
]

PAPER_TITLE = (
    "Post-Quantum Aliro: Integrating NIST Post-Quantum Cryptography "
    "Standards into NFC Access Control"
)


def _load_sections() -> dict[int, Section]:
    sections: dict[int, Section] = {}
    for idx, title, fname in SECTION_FILES:
        path = os.path.join(OUTPUT, fname)
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                content = f.read()
            sections[idx] = Section(
                index=idx, title=title, content=content,
                word_count=len(content.split()), status="draft",
            )
            print(f"  ✓ §{idx:2d} {title:<35} ({len(content):,} chars)")
        else:
            print(f"  ✗ §{idx:2d} {title:<35} 文件不存在")
    return sections


def _make_state(sections: dict[int, Section], sec_idx: int) -> PaperState:
    """为单章节构造最小 PaperState，供 reviewer 使用"""
    outline_sections = [
        Section(index=idx, title=title, content="", word_count=1000, status="draft")
        for idx, title, _ in SECTION_FILES
        if idx in sections
    ]
    outline = PaperOutline(
        title=PAPER_TITLE,
        abstract_draft="",
        sections=outline_sections,
        keywords=["Post-Quantum Cryptography", "NFC", "Aliro", "ML-KEM", "ML-DSA"],
        contribution_points=[
            "First PQC integration into Aliro NFC access control protocol",
            "Formal security proof in Bellare-Rogaway AKE model",
            "Communication overhead ~16.4 KB vs ~1.2 KB for ECC Aliro",
        ],
    )
    return PaperState(
        topic=PAPER_TITLE,
        phase="review",
        format_template="lncs",
        output_path=OUTPUT,
        sections=sections,
        outline=outline,
        literature=KNOWN_LITERATURE,
        current_section_index=sec_idx,
        current_instructions=(
            f"审核第 {sec_idx} 章（{sections[sec_idx].title}）。"
            "内容为 LaTeX 格式，请评估语义内容质量，忽略排版命令。"
        ),
    )


async def run_reviews(sections: dict[int, Section]) -> list[dict]:
    """对每个章节依次运行 reviewer，返回审稿摘要列表"""
    reviewer = ReviewerAgent()
    results = []

    for idx, title, _ in SECTION_FILES:
        if idx not in sections:
            continue

        print(f"\n── §{idx} {title} ──")
        state = _make_state(sections, idx)

        try:
            updates = await reviewer.run(state)
            reviews = updates.get("reviews", [])
            if reviews:
                fb = reviews[-1]          # 最新一条
                status = "✅ PASSED" if fb.passed else "❌ FAILED"
                print(f"   {status}")
                print(f"   {fb.reviewer_notes}")
                for issue in fb.issues[:3]:
                    print(f"   ⚠ {issue[:100]}")

                # 更新章节状态
                sec = sections[idx]
                sections[idx] = Section(
                    index=sec.index, title=sec.title,
                    content=sec.content, word_count=sec.word_count,
                    status="reviewed" if fb.passed else "draft",
                )
                results.append({
                    "section_index": idx,
                    "title": title,
                    "passed": fb.passed,
                    "notes": fb.reviewer_notes,
                    "issues": fb.issues,
                    "suggestions": fb.suggestions,
                })
            else:
                print("   ⚠ 未获得审稿结果")
                results.append({"section_index": idx, "title": title,
                                 "passed": None, "notes": "无审稿结果"})
        except Exception as e:
            print(f"   ✗ 审稿出错: {e}")
            results.append({"section_index": idx, "title": title,
                             "passed": None, "notes": f"error: {e}"})

    return results


async def run_formatter(sections: dict[int, Section]) -> str | None:
    """调用 formatter 生成最终论文"""
    print("\n── Formatter ──")
    outline = PaperOutline(
        title=PAPER_TITLE,
        abstract_draft=sections.get(0, Section(index=0, title="", content="")).content[:500],
        sections=[
            Section(index=idx, title=title, content="", word_count=1000, status="reviewed")
            for idx, title, _ in SECTION_FILES if idx in sections
        ],
        keywords=["Post-Quantum Cryptography", "NFC", "Aliro", "ML-KEM", "ML-DSA"],
        contribution_points=[],
    )
    state = PaperState(
        topic=PAPER_TITLE,
        phase="format",
        format_template="lncs",
        output_path=OUTPUT,
        sections=sections,
        outline=outline,
        literature=KNOWN_LITERATURE,
        current_instructions=(
            "将所有章节整合为完整 LNCS LaTeX 论文。"
            "各章节内容已为 LaTeX 格式，直接合并到正文，"
            "添加 \\documentclass[runningheads]{llncs} 文档头和参考文献。"
        ),
    )
    formatter = FormatterAgent()
    try:
        updates = await formatter.run(state)
        paper = updates.get("final_paper", "")
        if paper:
            print(f"   ✅ 论文已生成（{len(paper):,} 字符）")
        else:
            print("   ⚠ formatter 未返回内容")
        return paper
    except Exception as e:
        print(f"   ✗ formatter 出错: {e}")
        return None


async def main() -> None:
    ensure_us_proxy()

    print("\n📂 加载章节...")
    sections = _load_sections()
    if not sections:
        print("❌ 无章节可审，退出。")
        sys.exit(1)

    # ── Phase 1: Review ──────────────────────────────────────────────────
    print(f"\n{'='*60}")
    print(f"📋 审稿阶段（{len(sections)} 章）")
    print(f"{'='*60}")

    review_results = await run_reviews(sections)

    # 保存审稿报告
    report_path = os.path.join(OUTPUT, "review_report.json")
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(review_results, f, ensure_ascii=False, indent=2)
    print(f"\n📄 审稿报告已保存: {report_path}")

    passed = sum(1 for r in review_results if r.get("passed"))
    failed = [r["title"] for r in review_results if r.get("passed") is False]
    print(f"📊 结果：{passed}/{len(review_results)} 章通过")
    if failed:
        print(f"   未通过章节: {', '.join(failed)}")

    # ── Phase 2: Format ───────────────────────────────────────────────────
    print(f"\n{'='*60}")
    print("🔧 格式化阶段")
    print(f"{'='*60}")

    await run_formatter(sections)

    print(f"\n{'='*60}")
    print("✅ 全部完成！")
    print(f"   输出目录: {OUTPUT}")


if __name__ == "__main__":
    asyncio.run(main())

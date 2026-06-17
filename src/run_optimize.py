#!/usr/bin/env python
"""
run_optimize.py — PQ-Aliro 论文优化管线

Phase 1  data_analyst   → 生成计算基准表（Tab.6）+ 图表 pgfplots 骨架
Phase 2  writer         → 逐章润色（revision_mode=True，LaTeX 输出）
Phase 3  reviewer       → 验证每章改进质量
Phase 4  patch          → 就地更新 PQ_Aliro_v2/main-Full-v2.tex

用法：
  cd F:/paper-agent
  .venv/Scripts/Activate.ps1
  python src/run_optimize.py [--sections intro,relwork,impl,eval,disc,concl]
  python src/run_optimize.py --skip-data-analyst   # 跳过 Phase 1
  python src/run_optimize.py --dry-run             # 只运行 agent，不写文件
"""
from __future__ import annotations
import argparse
import asyncio
import json
import os
import re
import shutil
import sys
from dataclasses import dataclass
from datetime import datetime
from typing import Optional

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from dotenv import load_dotenv
load_dotenv()

from src.utils.proxy_check import ensure_us_proxy
from src.graph.state import (
    PaperState, PaperOutline, Section, LiteratureItem, ReviewFeedback
)
from src.agents.writer import WriterAgent
from src.agents.reviewer import ReviewerAgent
from src.agents.data_analyst import DataAnalystAgent

# ── 路径常量 ────────────────────────────────────────────────────────────────
BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEX_FILE = os.path.join(BASE, "PQ_Aliro_v2", "main-Full-v2.tex")
IMPROVED_DIR = os.path.join(BASE, "output", "improved")
PAPER_TITLE = (
    "Post-Quantum Aliro: Integrating NIST Post-Quantum Cryptography "
    "Standards into NFC Access Control"
)

# ── 引用文献（供 reviewer/writer 参考） ─────────────────────────────────────
KNOWN_LITERATURE = [
    LiteratureItem(title="CRYSTALS-Kyber", authors="Bos et al.", year=2018,
                   source="EuroSP:BDKLLSSSS18", abstract="ML-KEM specification.", relevance_score=1.0),
    LiteratureItem(title="CRYSTALS-Dilithium", authors="Ducas et al.", year=2018,
                   source="TCHES:DKLLSSS18", abstract="ML-DSA specification.", relevance_score=1.0),
    LiteratureItem(title="FIPS 203: ML-KEM", authors="NIST", year=2024,
                   source="NIST:FIPS203", abstract="NIST standard ML-KEM.", relevance_score=1.0),
    LiteratureItem(title="FIPS 204: ML-DSA", authors="NIST", year=2024,
                   source="NIST:FIPS204", abstract="NIST standard ML-DSA.", relevance_score=1.0),
    LiteratureItem(title="FIPS 206: FN-DSA (Falcon)", authors="NIST", year=2024,
                   source="NIST:FIPS206", abstract="NIST standard Falcon signature.", relevance_score=0.9),
    LiteratureItem(title="FIPS 205: SLH-DSA (SPHINCS+)", authors="NIST", year=2024,
                   source="NIST:FIPS205", abstract="NIST standard SPHINCS+ stateless signature.", relevance_score=0.8),
    LiteratureItem(title="Aliro 1.0 Specification", authors="CSA", year=2022,
                   source="CSA:Aliro10", abstract="NFC access control standard.", relevance_score=1.0),
    LiteratureItem(title="NIST IR 8547", authors="NIST", year=2024,
                   source="NIST:IR8547", abstract="PQC migration timeline.", relevance_score=0.9),
    LiteratureItem(title="PQC on Android (Lima et al.)", authors="Lima et al.", year=2021,
                   source="NIST3PQC:Lima21", abstract="PQC performance on Android devices.", relevance_score=1.0),
    LiteratureItem(title="KEMTLS", authors="Schwabe et al.", year=2020,
                   source="CCS:SchSteWig20", abstract="KEM-based TLS handshake.", relevance_score=0.9),
    LiteratureItem(title="Post-Quantum WireGuard", authors="Hülsing et al.", year=2021,
                   source="SP:HNSWZ21", abstract="PQC in WireGuard VPN.", relevance_score=0.9),
]

# ── 优化目标 ────────────────────────────────────────────────────────────────
@dataclass
class OptTarget:
    name: str
    section_idx: int
    section_title: str
    start_marker: str      # 在 tex 文件中唯一标识本节开始的注释行
    end_marker: str        # 下一节开始或文件结束的标记
    word_count_hint: int   # 建议字数（供 writer 参考）
    instructions: str      # 传给 writer 的具体改进指令


OPTIMIZE_TARGETS: list[OptTarget] = [
    OptTarget(
        name="eval",
        section_idx=9,
        section_title="Evaluation",
        start_marker="% §9 Evaluation",
        end_marker="% §10 Discussion",
        word_count_hint=1500,
        instructions="""
你正在修订 §9 Evaluation 章节（LaTeX 格式）。请做以下精确改进：

1. **删除重复免责声明**：全章只保留 §9.2 末尾的一处 "future work" 说明，
   删除 §9.1 方法论段落中的 "no physical testbed measurement is required" 这句话
   （它在同节后文重复），以及其他重复的 "theoretical estimates" 声明。

2. **新增 Table 6（计算开销基准）**：在 §9.2 NFC Transmission Latency 的
   "Cryptographic computation overhead." 段落**之后**，插入一个新的
   LaTeX 表格，展示3个代表性配置（D2×K512、D3×K768、D5×K1024）的
   ML-KEM 和 ML-DSA 计算时间估算（基于 Lima et al. \\cite{NIST3PQC:Lima21}
   和 CRYSTALS 论文 \\cite{EuroSP:BDKLLSSSS18,TCHES:DKLLSSS18}）：
   - 列：Config | ML-KEM enc+dec | ML-DSA sign | ML-DSA verify | Total crypto
   - 值使用 "approx" 范围格式（如 $\\approx$3\\,ms）
   - 脚注说明"values estimated from ARM benchmarks; actual Android performance varies"
   - 标签：\\label{tab:computation}，编号为 Tab.~6

3. **新增图表骨架**：在 §9.3 Security Level vs. Overhead Trade-off 的末尾
   Summary 段落**之前**，插入一个 pgfplots 条形图 figure 环境（骨架代码，
   注释掉具体数字用 TODO 标注），展示9种配置总字节数与 ECC baseline 的对比。
   标题：Caption = "Communication overhead of PQ-Aliro configurations vs. ECC Aliro baseline.
   ECC Aliro ($\\approx$1.2\\,KB) is shown for reference."
   标签：\\label{fig:overhead_comparison}

4. **有效载荷效率分析**：在 §9.1 末尾（Tab.3 分析之后、LOADCERT elimination 之前）
   添加一个新段落 \\paragraph{Payload efficiency.}，计算 App bytes / Link bytes 比率，
   指出因 BER-TLV 长格式编码 PQC 大对象的 overhead，该比率约为 0.97–0.98，
   相比 ECC（约 0.99）略低但差距很小。

5. **语言润色**：在整章中消除重复词组；确保每个 \\paragraph{} 的首句概括本段要点。

重要：保留所有现有 \\label{} 和 \\ref{} 标签，不得删减任何数据表格。
""",
    ),
    OptTarget(
        name="impl",
        section_idx=8,
        section_title="Implementation",
        start_marker="% §8 Implementation",
        end_marker="% §9 Evaluation",
        word_count_hint=1200,
        instructions="""
你正在修订 §8 Implementation 章节（LaTeX 格式）。请做以下精确改进：

1. **变量命名修复**：检查文中是否出现了 KEY_KYB 这个变量名用于指代 Dilithium/ML-DSA 密钥
   的情况。若有，将其改为 KEY_DIL（Dilithium 密钥）与 KEY_KYB（Kyber 密钥）对应区分，
   并在第一次出现时加简短注释（如 "where \\texttt{KEY\\_DIL} denotes the Dilithium
   key handle and \\texttt{KEY\\_KYB} the Kyber key handle"）。

2. **新增 Multi-Parameter Configuration 段落**：在 §8.3 End-to-End Testing 小节之前，
   插入一个新段落 \\paragraph{Multi-parameter configuration.}，说明实现如何通过
   一个枚举常量（\\texttt{PqcMode}，取值 D2K512/D2K768/.../D5K1024）在9种算法组合间切换，
   避免硬编码；给出一句伪代码风格描述（不需要真实代码块，一句英文说明即可）。

3. **End-to-End Testing 量化**：在 §8.3 Testing 段落中加一句关于测试覆盖率的量化描述，
   例如 "All 20 assertions pass with a 100\\% success rate across all nine configurations,
   yielding 180 total assertion checks with zero failures."

4. **AEAD paragraph 中的句子修复**：找到包含
   "All citation to the Aliro 1.0 specification" 的那句话，
   将其改为更自然的学术表述，例如：
   "All APDU command structures and status-word conventions conform to
   the Aliro~1.0 specification~\\cite{CSA:Aliro10}."

5. **语言润色**：删除重复词组，确保段落间有过渡。
""",
    ),
    OptTarget(
        name="intro",
        section_idx=1,
        section_title="Introduction",
        start_marker="% §1 Introduction",
        end_marker="% §2 Related Work",
        word_count_hint=1200,
        instructions="""
你正在修订 §1 Introduction 章节（LaTeX 格式）。请做以下精确改进：

1. **压缩开头量子威胁段落**：前两段（Shor + Grover）描述过长且与 Abstract 重复。
   将"Shor's algorithm..." 和"Grover's algorithm..."合并为一段，约3-4句，
   保留 \\cite{SIAM:Shor97,STOC:Grover96} 引用，删除详细的位数描述。

2. **强化贡献声明**：在 \\begin{enumerate} 的4条贡献中：
   - Contribution 1：添加 "the first complete PQC upgrade of any Aliro-family protocol" 的表述
   - Contribution 3：明确指出 "under MLWE and MSIS hardness assumptions in the
     Bellare--Rogaway model~\\cite{C:BelRog93}"
   - Contribution 4：将 "generalise to any lattice-based protocol" 改为更精确的
     "are expected to generalise to other lattice-based protocols deployed over
     bandwidth-constrained ISO-DEP transports"

3. **修复 paper organisation 段落**：确保章节编号描述与实际章节一致（§1-§11 均已存在）。

4. **语言润色**：
   - 删除所有独立使用的 "Notably," 和 "In particular," 开头句
   - 确保 Introduction 末尾 "The remainder..." 段落清晰流畅
""",
    ),
    OptTarget(
        name="relwork",
        section_idx=2,
        section_title="Related Work",
        start_marker="% §2 Related Work",
        end_marker="% §3 Background",
        word_count_hint=1000,
        instructions="""
你正在修订 §2 Related Work 章节（LaTeX 格式）。请做以下精确改进：

1. **补充 FIPS 206 (Falcon/FN-DSA) 提及**：在 §2.1 Post-Quantum Key Exchange and Authentication
   的 CRYSTALS-Dilithium 段落之后，添加1-2句：说明 NIST 还标准化了 Falcon（FIPS~206，
   FN-DSA）~\\cite{NIST:FIPS206} 作为签名方案，其签名尺寸比 ML-DSA-65 小约4.8倍
   但密钥生成需要高斯采样。

2. **补充 FIPS 205 (SPHINCS+/SLH-DSA) 提及**：在 §2.1 同位置或 §2.3 PQC on Constrained
   Platforms 中，添加1句提及 SLH-DSA（FIPS~205）~\\cite{NIST:FIPS205} 作为基于哈希的无
   结构格签名替代方案，它不依赖格假设但签名尺寸更大（8–50 KB）。

3. **§2.3 过渡句改进**：目前 "Despite these results, a significant gap remains."
   这句话过于突兀。在它之前加1句过渡："The above results focus on IP-based transports
   or general mobile computing contexts."

4. **结尾段加强**：§2.3 最后一段（PQ-Aliro 贡献对比）已经很好，只需将
   "addresses all three dimensions simultaneously" 改为更具体的表述，说明三维分别是什么
   （一句话列举）。

5. **语言润色**：确保每小节的最后一句都能自然过渡到下一个话题。
""",
    ),
    OptTarget(
        name="disc",
        section_idx=10,
        section_title="Discussion and Optimization",
        start_marker="% §10 Discussion",
        end_marker="% §11 Conclusion",
        word_count_hint=1100,
        instructions="""
你正在修订 §10 Discussion and Optimization 章节（LaTeX 格式）。请做以下精确改进：

1. **Falcon-512 段落补充 SPHINCS+ 对比**：在 Falcon-512 段落末尾，添加1-2句：
   "An alternative stateless-hash signature scheme, SLH-DSA (FIPS~205,
   \\cite{NIST:FIPS205}), avoids lattice assumptions entirely but produces
   signatures of 8--50\\,KB—an order of magnitude larger than Falcon—making
   it impractical over ISO-DEP without aggressive compression or caching."

2. **合并 Limitations 中的重叠段落**：
   "Theoretical byte counts only" 和 "No real NFC hardware" 两段叙述了同一局限性
   （缺乏物理层/真实硬件测量）。将它们合并为一段，标题改为
   \\textbf{Absence of physical-layer and real-hardware measurements.}，
   内容保留两段的要点，约4-5句。

3. **Future Work 段落新增**：在 "Key-slot provisioning protocol." 段落之后，
   添加新的 future work 项：
   \\textbf{Profiling and optimisation on real Android hardware.}
   Measuring end-to-end latency on commercial NFC-enabled smartphones—including
   JVM warm-up effects, garbage-collection pauses, and BouncyCastle's BCPQC
   initialisation overhead—would provide actionable targets for performance
   hardening of the production implementation.

4. **语言润色**：
   - 删除 "it is important to note" / "it should be noted" 等 AI 套话
   - 确保 Optimization Strategies 小节的每个 \\textbf{} 标题都用名词短语形式
""",
    ),
    OptTarget(
        name="concl",
        section_idx=11,
        section_title="Conclusion",
        start_marker="% §11 Conclusion",
        end_marker="\\bibliographystyle",
        word_count_hint=350,
        instructions="""
你正在修订 §11 Conclusion 章节（LaTeX 格式）。请做以下轻量润色：

1. **首段加强**：第一句 "This paper presented PQ-Aliro, the first complete..."
   改为更有力的叙述，把"first complete"改为"the first APDU-granular, formally verified"。

2. **数据引用一致性**：确认 Conclusion 中引用的字节数（16.4 KB、75 APDUs、11.2 KB、11.9 KB）
   与 §9 Evaluation 的数据一致（它们应该相同）。

3. **结尾段强化**：最后一段关于 NIST IR 8547 的段落已很好，在末尾添加一句：
   "The open-source implementation released alongside this paper provides a
   concrete, reproducible baseline for future protocol engineering work."

4. **语言润色**：整章约300字，不要增加内容，只做轻量语言精炼。
""",
    ),
]

# ── 工具函数 ────────────────────────────────────────────────────────────────

def load_tex() -> str:
    with open(TEX_FILE, encoding="utf-8") as f:
        return f.read()


def extract_section(tex: str, start_marker: str, end_marker: str) -> tuple[str, int, int]:
    """
    从 tex 中提取 [start_marker 所在行的上一个分隔线 ... end_marker 前的分隔线]。
    若 end_marker 以 \\ 开头（LaTeX 命令），视为文件终止标记，直接取该位置为 section_end。
    返回 (content, start_pos, end_pos)。
    """
    # 找到 start_marker 位置
    idx_s = tex.find(start_marker)
    if idx_s == -1:
        raise ValueError(f"start_marker not found: {start_marker!r}")

    # 向前找包含本节的分隔线（% ---…），section_start 定位到该行行首
    line_start = tex.rfind("\n", 0, idx_s)
    dashes_before = tex.rfind("% -", 0, line_start)
    if dashes_before != -1:
        section_start = tex.rfind("\n", 0, dashes_before) + 1
    else:
        section_start = idx_s

    # ── end_marker 处理 ──────────────────────────────────────────────────
    is_latex_cmd = end_marker.startswith("\\")  # e.g., \bibliographystyle

    if is_latex_cmd:
        # 文件末尾标记：直接找该命令，section 结束于其前
        idx_e = tex.find(end_marker, idx_s)
        section_end = idx_e if idx_e != -1 else len(tex)
    else:
        # 下一节的注释标记：找该标记前的分隔线，section 结束于分隔线行首
        idx_e = tex.find(end_marker, idx_s)
        if idx_e == -1:
            # fallback：文件末尾
            idx_e = tex.find("\\bibliographystyle")
            section_end = idx_e if idx_e != -1 else len(tex)
        else:
            # 找 end_marker 之前最近的 % --- 分隔线
            dashes_before_end = tex.rfind("% -", 0, idx_e)
            if dashes_before_end != -1:
                section_end = tex.rfind("\n", 0, dashes_before_end) + 1
            else:
                section_end = idx_e

    return tex[section_start:section_end], section_start, section_end


def patch_tex(tex: str, section_start: int, section_end: int, new_content: str) -> str:
    """将 tex[section_start:section_end] 替换为 new_content。"""
    # 确保 new_content 以换行结尾
    if not new_content.endswith("\n"):
        new_content += "\n"
    return tex[:section_start] + new_content + tex[section_end:]


def make_outline(target: OptTarget) -> PaperOutline:
    """
    构造 PaperOutline，用内部索引 0 替代真实章节索引。
    WriterAgent 用 outline.sections[current_section_index] 查找目标章节，
    因此 sections 列表必须在位置 0 有一个有效元素。
    """
    return PaperOutline(
        title=PAPER_TITLE,
        abstract_draft="",
        sections=[Section(
            index=0,                      # 内部索引固定为 0
            title=target.section_title,
            content=target.instructions,  # 用 instructions 作为章节目标描述
            word_count=target.word_count_hint,
            status="draft",
        )],
        keywords=["Post-Quantum Cryptography", "NFC", "Aliro", "ML-KEM", "ML-DSA"],
        contribution_points=[],
    )


def make_writer_state(
    target: OptTarget,
    existing_content: str,
    extra_context: str = "",
) -> PaperState:
    """
    构造供 WriterAgent(revision_mode=True) 使用的 PaperState。
    使用内部索引 0 避免 outline.sections 越界（WriterAgent 用 sections[idx] 访问）。
    """
    outline = make_outline(target)
    instructions = target.instructions
    if extra_context:
        instructions = extra_context + "\n\n" + instructions

    section = Section(
        index=0,               # 内部索引 0，与 outline.sections[0] 对应
        title=target.section_title,
        content=existing_content,
        word_count=len(existing_content.split()),
        status="draft",
    )
    return PaperState(
        topic=PAPER_TITLE,
        phase="revision",
        format_template="lncs",
        output_path=IMPROVED_DIR,
        outline=outline,
        literature=KNOWN_LITERATURE,
        sections={0: section},           # 内部索引 0
        current_section_index=0,         # 内部索引 0
        current_instructions=instructions,
    )


def make_reviewer_state(target: OptTarget, improved_content: str) -> PaperState:
    """构造供 ReviewerAgent 使用的 PaperState（同样用内部索引 0）。"""
    outline = make_outline(target)
    section = Section(
        index=0,
        title=target.section_title,
        content=improved_content,
        word_count=len(improved_content.split()),
        status="draft",
    )
    return PaperState(
        topic=PAPER_TITLE,
        phase="review",
        format_template="lncs",
        output_path=IMPROVED_DIR,
        outline=outline,
        literature=KNOWN_LITERATURE,
        sections={0: section},
        current_section_index=0,
        current_instructions=(
            f"审核 §{target.section_idx} {target.section_title} 的改进版本。"
            "内容为 LaTeX 格式，请从技术准确性、逻辑连贯性、语言质量三个维度评分。"
            "重点关注：(1) 新增内容是否合理；(2) 是否消除了原来的冗余；"
            "(3) 是否保持了 LaTeX 格式的正确性。"
        ),
    )


# ── Phase 1: Data Analyst ────────────────────────────────────────────────────

DATA_ANALYST_INSTRUCTIONS = """
为 §9 Evaluation 章节生成以下两项新内容：

**任务 1：Table 6 — Cryptographic Computation Overhead**

生成一个 LaTeX 表格 (\\begin{table}...\\end{table})，展示三个代表性配置的
ML-KEM 和 ML-DSA 计算时间估算。数据来源：Lima et al. (Android 性能) 和
CRYSTALS 论文 (ARM Cortex-A 参考实现基准)。

表格结构：
| Configuration | ML-KEM enc. | ML-KEM dec. | ML-DSA sign | ML-DSA verify | Total crypto |
|---|---|---|---|---|---|
| D2×K512 (ML-DSA-44 × ML-KEM-512) | ~2 ms | ~2 ms | 30–60 ms | ~4 ms | 34–64 ms |
| D3×K768 (ML-DSA-65 × ML-KEM-768) | ~3 ms | ~3 ms | 50–100 ms | ~5 ms | 56–111 ms |
| D5×K1024 (ML-DSA-87 × ML-KEM-1024) | ~4 ms | ~4 ms | 80–160 ms | ~8 ms | 88–176 ms |

- 标签: \\label{tab:computation}，caption 中说明 "Values are estimated from
  published ARM benchmarks~\\cite{EuroSP:BDKLLSSSS18,TCHES:DKLLSSS18,NIST3PQC:Lima21};
  actual Android performance depends on JVM warm-up and device-specific factors."
- 使用 booktabs (\\toprule, \\midrule, \\bottomrule)

**任务 2：Figure Skeleton — Overhead Comparison Bar Chart**

生成一个 LaTeX figure 环境，包含 pgfplots 条形图骨架代码（用 TODO 注释标出数据，
后续可直接填入数字后编译）。图展示9种配置的总字节数（KB）与 ECC baseline（1.2 KB）对比。
- \\caption{Communication overhead of all nine PQ-Aliro parameter combinations
  compared to the classical ECC Aliro baseline. Values are total link bytes
  per full transaction including LOADCERT.}
- \\label{fig:overhead_comparison}
- 使用 pgfplots ybar 类型，x轴为配置名，y轴为 KB

输出严格 JSON：
{
  "tables": {
    "computation_table": "<完整 LaTeX table 代码>",
    "figure_skeleton": "<完整 LaTeX figure 代码>"
  },
  "figure_descriptions": {
    "fig_overhead": "条形图描述：9种 PQC 配置 vs ECC baseline 通信开销"
  }
}
"""


async def run_data_analyst() -> dict:
    """Phase 1: 运行 data_analyst 生成计算基准表 + 图表骨架。"""
    print("\n" + "=" * 60)
    print("Phase 1 — Data Analyst: 生成计算基准表 + 图表骨架")
    print("=" * 60)

    # 读取通信开销文档作为上下文
    overhead_file = os.path.join(BASE, "source", "communication-overhead.md")
    overhead_ctx = ""
    if os.path.exists(overhead_file):
        with open(overhead_file, encoding="utf-8") as f:
            overhead_ctx = f.read()[:3000]

    state = PaperState(
        topic=PAPER_TITLE,
        phase="data_analysis",
        format_template="lncs",
        output_path=IMPROVED_DIR,
        outline=PaperOutline(
            title=PAPER_TITLE, abstract_draft="",
            sections=[], keywords=[], contribution_points=[],
        ),
        literature=KNOWN_LITERATURE,
        current_instructions=DATA_ANALYST_INSTRUCTIONS + (
            f"\n\n通信开销参考数据（来自 source/communication-overhead.md）：\n{overhead_ctx}"
            if overhead_ctx else ""
        ),
    )

    analyst = DataAnalystAgent()
    try:
        result = await analyst.run(state)
        tables = result.get("tables", {})
        figs = result.get("figure_descriptions", {})
        print(f"   ✓ 生成 {len(tables)} 个表格，{len(figs)} 个图表描述", flush=True)
        for k, v in tables.items():
            print(f"     table: {k} ({len(v)} chars)", flush=True)
        return result
    except Exception as e:
        print(f"   ✗ data_analyst 出错: {e}", flush=True)
        return {}


# ── Phase 2: Writer ──────────────────────────────────────────────────────────

async def run_writer_for_target(
    target: OptTarget,
    tex: str,
    extra_context: str = "",
) -> tuple[str | None, str]:
    """
    对单个 target 运行 WriterAgent(revision_mode=True)。
    返回 (improved_content, original_content)。
    """
    print(f"\n── §{target.section_idx} {target.section_title} ──")

    try:
        original_content, s_start, s_end = extract_section(tex, target.start_marker, target.end_marker)
    except ValueError as e:
        print(f"   ✗ 提取失败: {e}", flush=True)
        return None, ""

    print(f"   原始内容: {len(original_content):,} chars", flush=True)

    state = make_writer_state(target, original_content, extra_context)
    # deepseek-chat 不受 CLAUDE.md 项目上下文干扰，适合 revision 任务
    writer = WriterAgent(revision_mode=True, model_override="deepseek-chat")

    try:
        updates = await writer.run(state)
        sections = updates.get("sections", {})
        # 内部索引为 0；兼容 writer 可能输出真实章节索引的情况
        improved_sec = sections.get(0) or sections.get(target.section_idx)
        if improved_sec and improved_sec.content:
            content = improved_sec.content
            print(f"   ✓ 改进内容: {len(content):,} chars", flush=True)
            return content, original_content
        else:
            errors = updates.get("errors", [])
            print(f"   ✗ 未获得章节内容. errors={errors}", flush=True)
            # 调试：打印 sections keys
            print(f"     sections keys: {list(sections.keys())}", flush=True)
            return None, original_content
    except Exception as e:
        import traceback
        print(f"   ✗ writer 出错: {e}", flush=True)
        traceback.print_exc()
        return None, original_content


# ── Phase 3: Reviewer ────────────────────────────────────────────────────────

async def run_reviewer_for_target(
    target: OptTarget,
    improved_content: str,
) -> tuple[bool, ReviewFeedback | None]:
    """
    对改进后的章节运行 ReviewerAgent。
    返回 (passed, feedback)。
    """
    state = make_reviewer_state(target, improved_content)
    reviewer = ReviewerAgent()

    try:
        updates = await reviewer.run(state)
        reviews = updates.get("reviews", [])
        if reviews:
            fb = reviews[-1]
            status = "✅ PASSED" if fb.passed else "❌ FAILED"
            scores = getattr(fb, "scores", {})
            score_str = " | ".join(f"{k}={v}" for k, v in scores.items()) if scores else ""
            print(f"   {status} | {fb.reviewer_notes[:100]}", flush=True)
            if score_str:
                print(f"   scores: {score_str}", flush=True)
            if not fb.passed:
                for issue in fb.issues[:3]:
                    print(f"   ⚠ {issue[:120]}", flush=True)
            return fb.passed, fb
        else:
            print("   ⚠ reviewer 未返回结果", flush=True)
            return False, None
    except Exception as e:
        print(f"   ✗ reviewer 出错: {e}", flush=True)
        return False, None


# ── Phase 4: Save + Patch ────────────────────────────────────────────────────

def save_improved_section(target: OptTarget, content: str) -> None:
    """将改进内容写入 output/improved/ 目录。"""
    os.makedirs(IMPROVED_DIR, exist_ok=True)
    fname = f"improved_section{target.section_idx}_{target.name}.tex"
    path = os.path.join(IMPROVED_DIR, fname)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"   💾 保存: output/improved/{fname}", flush=True)


def backup_tex(tex_path: str) -> str:
    """备份 main-Full-v2.tex → main-Full-v2.bak（只在首次备份时创建）。"""
    bak_path = tex_path.replace(".tex", ".bak")
    if not os.path.exists(bak_path):
        shutil.copy2(tex_path, bak_path)
        print(f"   📂 已备份原文件至 {os.path.basename(bak_path)}", flush=True)
    return bak_path


def patch_main_tex(
    targets_with_content: list[tuple[OptTarget, str]],
    dry_run: bool = False,
) -> None:
    """将所有改进章节就地更新到 main-Full-v2.tex。"""
    print("\n" + "=" * 60)
    print("Phase 4 — 更新 main-Full-v2.tex")
    print("=" * 60)

    tex = load_tex()
    if not dry_run:
        backup_tex(TEX_FILE)

    # 从后往前 patch（避免字符位置偏移）
    patches: list[tuple[int, int, str]] = []
    for target, improved_content in targets_with_content:
        try:
            _, s_start, s_end = extract_section(tex, target.start_marker, target.end_marker)
            patches.append((s_start, s_end, improved_content))
            print(f"   §{target.section_idx} {target.section_title}: "
                  f"[{s_start}:{s_end}] → {len(improved_content)} chars", flush=True)
        except ValueError as e:
            print(f"   ✗ 无法定位 §{target.section_idx}: {e}", flush=True)

    # 从后往前按位置排序后 patch
    patches.sort(key=lambda x: x[0], reverse=True)
    for s_start, s_end, new_content in patches:
        tex = patch_tex(tex, s_start, s_end, new_content)

    if dry_run:
        print("   [dry-run] 不写入文件", flush=True)
        # 写一个预览文件
        preview_path = TEX_FILE.replace(".tex", "_preview.tex")
        with open(preview_path, "w", encoding="utf-8") as f:
            f.write(tex)
        print(f"   预览写入: {os.path.basename(preview_path)}", flush=True)
    else:
        with open(TEX_FILE, "w", encoding="utf-8") as f:
            f.write(tex)
        print(f"   ✅ main-Full-v2.tex 已更新 ({len(tex):,} chars)", flush=True)


# ── 主流程 ────────────────────────────────────────────────────────────────────

async def main() -> None:
    parser = argparse.ArgumentParser(description="PQ-Aliro 论文优化管线")
    parser.add_argument(
        "--sections",
        default="eval,impl,intro,relwork,disc,concl",
        help="逗号分隔的目标节名称（eval/impl/intro/relwork/disc/concl）",
    )
    parser.add_argument("--skip-data-analyst", action="store_true",
                        help="跳过 Phase 1（data analyst）")
    parser.add_argument("--dry-run", action="store_true",
                        help="只运行 agent，不修改 main-Full-v2.tex（写 _preview.tex）")
    parser.add_argument("--no-review", action="store_true",
                        help="跳过 Phase 3 reviewer 验证（直接 patch）")
    args = parser.parse_args()

    ensure_us_proxy()
    os.makedirs(IMPROVED_DIR, exist_ok=True)

    # 选择目标章节
    requested = {s.strip() for s in args.sections.split(",")}
    targets = [t for t in OPTIMIZE_TARGETS if t.name in requested]
    if not targets:
        print(f"❌ 未找到指定目标节: {requested}", flush=True)
        sys.exit(1)
    print(f"\n🎯 优化目标：{[t.name for t in targets]}", flush=True)

    # ── Phase 1: Data Analyst ────────────────────────────────────────────
    da_context = ""
    if not args.skip_data_analyst and any(t.name == "eval" for t in targets):
        da_result = await run_data_analyst()
        tables = da_result.get("tables", {})
        comp_table = tables.get("computation_table", "")
        fig_skeleton = tables.get("figure_skeleton", "")
        if comp_table or fig_skeleton:
            da_context = (
                "\n\n[Data Analyst 生成的新内容，请将其插入章节适当位置]\n"
                + (f"\n计算基准表（Tab.6）LaTeX 代码：\n{comp_table}\n" if comp_table else "")
                + (f"\n图表骨架（fig:overhead_comparison）LaTeX 代码：\n{fig_skeleton}\n" if fig_skeleton else "")
            )

        # 保存 da 结果
        da_path = os.path.join(IMPROVED_DIR, "data_analyst_output.json")
        with open(da_path, "w", encoding="utf-8") as f:
            json.dump(da_result, f, ensure_ascii=False, indent=2)
        print(f"   💾 Data analyst 结果: output/improved/data_analyst_output.json", flush=True)

    # ── Phase 2 + 3: Writer → Reviewer ──────────────────────────────────
    tex = load_tex()
    accepted_patches: list[tuple[OptTarget, str]] = []
    review_report: list[dict] = []

    for target in targets:
        # Phase 2: Writer
        extra = da_context if target.name == "eval" and da_context else ""
        improved, original = await run_writer_for_target(target, tex, extra)

        if improved is None:
            print(f"   ⚠ 跳过 §{target.section_idx} (writer 失败)", flush=True)
            review_report.append({
                "section": target.name, "passed": None, "notes": "writer failed",
            })
            continue

        # 保存改进版本到 output/improved/
        save_improved_section(target, improved)

        # Phase 3: Reviewer（可选）
        if args.no_review:
            accepted_patches.append((target, improved))
            review_report.append({
                "section": target.name, "passed": "skipped",
                "notes": "--no-review flag set",
            })
            continue

        print(f"\n   🔍 Reviewing §{target.section_idx}...", flush=True)
        passed, fb = await run_reviewer_for_target(target, improved)

        review_report.append({
            "section": target.name,
            "passed": passed,
            "notes": fb.reviewer_notes if fb else "no feedback",
            "issues": fb.issues if fb else [],
            "suggestions": fb.suggestions if fb else [],
        })

        if passed:
            accepted_patches.append((target, improved))
        else:
            print(f"   ⚠ §{target.section_idx} 未通过 review，保留改进文件但不 patch 主文件", flush=True)
            # 仍保存，让用户手动决定

    # 保存 review 报告
    report_path = os.path.join(IMPROVED_DIR, "optimize_review_report.json")
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(review_report, f, ensure_ascii=False, indent=2)
    print(f"\n📄 Review 报告: output/improved/optimize_review_report.json", flush=True)

    passed_count = sum(1 for r in review_report if r.get("passed") is True)
    total = len(review_report)
    print(f"📊 通过审核: {passed_count}/{total}", flush=True)

    # ── Phase 4: Patch ───────────────────────────────────────────────────
    if accepted_patches:
        patch_main_tex(accepted_patches, dry_run=args.dry_run)
    else:
        print("\n⚠ 没有章节通过审核，跳过 patch", flush=True)

    print(f"\n{'=' * 60}")
    print("✅ 优化管线完成！")
    print(f"   改进文件目录: output/improved/")
    if not args.dry_run and accepted_patches:
        print(f"   主文件已更新: PQ_Aliro_v2/main-Full-v2.tex")
        print(f"   备份文件: PQ_Aliro_v2/main-Full-v2.bak")
    print(f"   下一步: cd PQ_Aliro_v2 && pdflatex main-Full-v2.tex")
    print(f"{'=' * 60}")


if __name__ == "__main__":
    asyncio.run(main())

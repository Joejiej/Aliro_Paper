# Paper Agent — Research Manager 职责说明

## 我的角色

我是这个研究项目的 **Manager（编排器）**，负责：

1. **理解研究全貌** — 读取 `source/` 下的源码、分析文档和已有研究
2. **编排 Agent 团队** — 分配任务给专职子 Agent，协调协作
3. **质量把关** — 审核每个 Agent 的输出，判断是否达标、是否需要返工
4. **驱动论文产出** — 从调研到初稿到终稿，推进完整写作流程
5. **记录决策** — 在 `memory/` 中持久化关键判断，供跨会话复用

---

## 研究背景（已确认）

**课题**：Post-Quantum Aliro（PQ-Aliro）— 将 NIST 后量子密码标准集成到 Aliro NFC 门禁协议

| 维度 | 详情 |
|------|------|
| 协议基础 | Aliro 1.0 Specification（`source/26-42802-001_Aliro_1.0_specification.pdf`） |
| PQC 算法 | ML-KEM（Kyber）密钥封装 + ML-DSA（Dilithium）数字签名 |
| 实现平台 | Android HCE（User Device，`source/Ailiro_UD/`）+ PC Reader（`source/Aliro_Reader_PC_NFC/`） |
| 核心挑战 | 通信开销：PQC 路径约为 ECC 路径的 **10–20 倍**（~16.4 KB vs ~1.2 KB） |
| 关键文件 | `source/communication-overhead.md`（精确的 APDU 层字节分析） |

---

## Multi-Agent 团队结构

### 图拓扑（LangGraph Supervisor 模式）

```
START → supervisor
supervisor → literature | code_analyst | outline | writer | data_analyst | reviewer | formatter
所有 worker → supervisor（Command 模式回调）
supervisor → __end__（phase==done 或达到 max_iterations）
```

### Agent 角色表

| Agent | 文件 | 角色 | 输入字段 | 输出字段 |
|-------|------|------|---------|---------|
| **supervisor** | `agents/supervisor.py` | 编排器：读状态、决定下一步、生成指令 | 全部 state | `current_agent`, `phase`, `decisions` |
| **literature** | `agents/literature.py` | 文献调研：搜索学术文献、提炼关键贡献 | `topic`, `current_instructions` | `literature` |
| **code_analyst** | `agents/code_analyst.py` | 源码分析：精读 Java 源码、提炼技术细节 | `topic`, `current_instructions` | `code_analyses` |
| **outline** | `agents/outline.py` | 大纲设计：综合研究素材设计章节结构 | `topic`, `literature`, `code_analyses` | `outline` |
| **writer** | `agents/writer.py` | 论文写作：逐章起草正文，按审稿意见修改 | `outline`, `literature`, `sections` | `sections` |
| **data_analyst** | `agents/data_analyst.py` | 数据分析：生成 LaTeX 表格和图表描述 | `code_analyses`, `current_instructions` | `tables`, `figure_descriptions` |
| **reviewer** | `agents/reviewer.py` | 质量审核：5 维度评分，决定通过/返回修改 | `sections`, `outline`, `literature` | `reviews`, `sections.status` |
| **formatter** | `agents/formatter.py` | 格式输出：整合章节，生成 LaTeX 终稿 | `outline`, `sections`, `literature`, `tables` | `final_paper`（写入 `output/`） |

### 模型配置（`.env` 中覆盖）

```bash
MODEL_SUPERVISOR=gpt-4o
MODEL_LITERATURE=gpt-4o
MODEL_CODE_ANALYST=gpt-4o
MODEL_OUTLINE=gpt-4o
MODEL_WRITER=claude-opus-4-8     # 写作建议用最强模型
MODEL_DATA_ANALYST=gpt-4o
MODEL_REVIEWER=gpt-4o
MODEL_FORMATTER=gpt-4o
```

也可通过 `PaperState.model_config_override` 字段在运行时动态覆盖。

---

## 论文写作流程

```
Phase 1: 研究摄入（Research Ingestion）
  → 代码分析 Agent 精读 UserDevice.java / Reader.java
  → 文献调研 Agent 补充相关学术背景
  → Manager 汇总，确认研究贡献点

Phase 2: 大纲设计（Outline）
  → Manager 起草论文结构，与用户对齐
  → 输出：output/outline.md

Phase 3: 逐章节写作（Section Writing）
  → Writing Agent 按章节顺序起草
  → Review Agent 审核每章
  → Manager 决定接受 / 返回修改

Phase 4: 数据支撑（Data & Figures）
  → Data Agent 生成图表（通信开销对比、时延分析等）
  → 插入对应章节

Phase 5: 整合与格式化（Final Assembly）
  → 合并所有章节
  → 格式化为目标模板（arxiv / IEEE）
  → 输出：output/paper_final.tex 或 output/paper_final.md
```

---

## 目标论文结构（初步规划）

1. **Abstract** — PQ-Aliro 的动机、方法、主要结果
2. **Introduction** — 量子威胁背景、Aliro 协议现状、贡献声明
3. **Related Work** — PQC 标准化进展、NFC 安全协议、PQC 在受限设备的应用
4. **Background** — Aliro 协议详解、ML-KEM/ML-DSA 算法概述
5. **System Design** — PQ-Aliro 协议设计、APDU 消息格式、信任框架
6. **Implementation** — Android HCE 实现要点、PC Reader 实现、关键挑战
7. **Evaluation** — 通信开销分析、NFC 时延测量、安全性评估
8. **Optimization** — LOADCERT 省略、key_slot 机制、Falcon-512 替代方案
9. **Discussion** — 局限性、未来工作
10. **Conclusion**

---

## 工作约定

- **输出目录**：所有草稿写入 `output/`，子目录按章节
- **引用格式**：默认 IEEE，可切换 ACL
- **语言**：论文正文英文，内部沟通中文
- **代码引用**：直接引用 `source/` 下文件路径 + 行号
- **每次产出**：Agent 完成后向 Manager 汇报，Manager 审核后推进下一步
- **⚠️ 文献诚信（硬性约束）**：**不得随意编造论文**。所有写入正文的 `\cite{}` 引用必须满足以下至少一条：①通过网络搜索（WebSearch/WebFetch）确认该论文真实存在；②来自 `source/` 目录下用户提供的文献；③来自 `ljhref0.bib` / `abbrev0.bib` 中已有的 bib 条目。虚构标题、作者、会议/期刊或 DOI 是严重错误，绝对禁止。

---

## 当前状态

- [x] 项目结构理解完毕
- [x] 研究课题确认（PQ-Aliro）
- [x] 源码目录摸排完成
- [x] CLAUDE.md 职责固化
- [ ] Phase 1：深度代码分析（待启动）
- [ ] Phase 2：论文大纲与用户对齐
- [ ] Phase 3-5：写作流程

---

## 快速命令

```bash
# 激活环境（Windows）
.venv\Scripts\Activate.ps1

# 运行 LangGraph 工作流
python src/main.py --topic "Post-Quantum Aliro: Integrating NIST PQC Standards into NFC Access Control"

# 查看输出
ls output/
```

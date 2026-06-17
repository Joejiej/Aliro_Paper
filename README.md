# Paper Agent — 多 Agent 论文写作系统

基于 LangGraph 的多 Agent 协作框架，用于学术论文的文献调研、写作、审校全流程。

## 架构

```
👑 Supervisor（编排器）
├── 📚 文献调研 Agent    → 搜索、筛选、摘要
├── 🔬 实验设计 Agent    → 方法论、实验方案
├── ✍️ 论文写作 Agent    → 各章节起草
├── 📊 图表生成 Agent    → 数据可视化、图表描述
├── 🔍 质量审核 Agent    → 逻辑检查、格式校验
└── 📐 格式适配 Agent    → 模板、引用格式、LaTeX
```

## 快速开始

```bash
# 激活环境
.venv\Scripts\activate

# 配置 API Key
copy .env.example .env
# 编辑 .env 填入你的 API Key

# 运行
python src/main.py --topic "你的论文题目"
```

## 项目结构

```
F:\paper-agent\
├── .venv\                    # Python 虚拟环境
├── src\
│   ├── main.py               # 入口
│   ├── graph\
│   │   ├── __init__.py
│   │   ├── state.py          # State 定义
│   │   └── workflow.py        # LangGraph 图构建
│   ├── agents\
│   │   ├── __init__.py
│   │   ├── base.py            # Agent 基类
│   │   ├── literature.py      # 文献调研 Agent
│   │   ├── outline.py         # 大纲设计 Agent
│   │   ├── writing.py         # 论文写作 Agent
│   │   ├── review.py          # 质量审核 Agent
│   │   └── format.py          # 格式适配 Agent
│   └── utils\
│       ├── __init__.py
│       ├── llm.py             # LLM 客户端封装
│       └── prompts.py         # Prompt 模板
├── templates\                 # 论文模板
├── references\                # 参考文献 PDF
├── output\                    # 输出目录
├── .env                       # API Key 配置
├── requirements.txt
└── README.md
```

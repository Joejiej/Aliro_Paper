"""Prompt 模板"""
from string import Template

LITERATURE_SEARCH = Template("""你是文献调研专家。
研究主题：$topic
任务：搜索并整理相关文献。

要求：
1. 搜索最近 5 年的高质量论文
2. 每篇给出：标题、作者、年份、核心贡献
3. 评估与主题的相关性（0-1）
4. 提取关键要点 3-5 条

输出格式：JSON 列表
""")

OUTLINE_DESIGN = Template("""你是论文大纲设计师。
论文主题：$topic
已收集文献：$literature_count 篇

任务：设计论文大纲。
要求：
1. 标准的学术论文结构
2. 每章节说明写作目标
3. 标注需要引用的文献
4. 字数分配建议

章节建议：Introduction / Related Work / Method / Experiments / Discussion / Conclusion
""")

SECTION_WRITE = Template("""你是学术论文写作者。
论文主题：$topic
当前章节：$section_title
上下文：$context

要求：
1. 学术写作风格，严谨准确
2. 使用 $format 格式
3. 引用相关文献
4. 字数：$word_count 字左右
5. 避免 AI 味，保持自然学术语言
""")

QUALITY_REVIEW = Template("""你是论文质量审核专家。
论文主题：$topic
章节：$section_title

审核维度：
1. 逻辑连贯性
2. 技术准确性
3. 引用规范性
4. 语言表达
5. 与论文整体一致性

输出：问题列表 + 改进建议 + 是否通过
""")

FORMAT_ADAPT = Template("""你是论文格式适配专家。
目标格式：$format
原始内容：$content

任务：转换为 $format 格式
要求：模板格式、引用格式、图表编号、公式编号完全正确
""")

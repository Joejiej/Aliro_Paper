"""Debug script to inspect raw writer LLM response."""
import sys, asyncio, json, re
sys.path.insert(0, '.')
from dotenv import load_dotenv; load_dotenv()
from src.run_optimize import OPTIMIZE_TARGETS, extract_section, load_tex, make_writer_state
from src.agents.writer import WriterAgent
from langchain_core.messages import SystemMessage, HumanMessage

target = [t for t in OPTIMIZE_TARGETS if t.name == 'concl'][0]
tex = load_tex()
content, _, _ = extract_section(tex, target.start_marker, target.end_marker)
state = make_writer_state(target, content)
writer = WriterAgent(revision_mode=True, model_override="deepseek-chat")


async def test():
    sp = writer.build_system_prompt(state)
    up = writer.build_user_prompt(state)
    resp = await writer.llm.ainvoke([SystemMessage(content=sp), HumanMessage(content=up)])
    raw = resp.content
    print(f"RAW length: {len(raw)}")
    print("--- FIRST 800 chars ---")
    print(raw[:800])
    print("--- LAST 400 chars ---")
    print(raw[-400:])

    # Try JSON parse
    start = raw.find('{')
    end = raw.rfind('}') + 1
    print(f"\nJSON braces: start={start}, end={end}")
    if start != -1 and end > start:
        try:
            data = json.loads(raw[start:end])
            print(f"JSON parsed OK, keys: {list(data.keys())}")
            clen = len(data.get("content", ""))
            print(f"content field length: {clen}")
            if clen > 0:
                print(f"content first 200: {repr(data['content'][:200])}")
        except json.JSONDecodeError as e:
            print(f"JSON parse error at pos {e.pos}: {e.msg}")
            print(f"Context around error: {repr(raw[max(0,start+e.pos-50):start+e.pos+50])}")
    else:
        print("No JSON braces found in response")

asyncio.run(test())

"""跑实验前的环境自检：一次把所有缺失项列清楚，别跑到一半才报错。

    python examples/deep_research/check_env.py
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / "sdk" / "python"))
sys.path.insert(0, str(ROOT / "vendor" / "open_deep_research" / "src"))
sys.path.insert(0, str(HERE))

import compat  # noqa: E402

OK = "[ok]"
BAD = "[缺]"


def check_deps() -> bool:
    missing = []
    for mod in ("langgraph", "langchain", "langchain_openai", "langchain_tavily",
                "langchain_mcp_adapters", "tavily", "asc"):
        try:
            __import__(mod)
        except Exception:
            missing.append(mod)
    if missing:
        print(f"{BAD} 依赖缺失: {', '.join(missing)}")
        print("     安装: .venv/Scripts/python.exe -m pip install -r "
              "examples/deep_research/requirements.txt")
        return False
    print(f"{OK} 依赖完整")
    return True


def check_model() -> bool:
    compat.load_env()
    for env in ("DEEPSEEK_API_KEY", "ASC_BENCH_LLM_API_KEY", "OPENAI_API_KEY"):
        if os.getenv(env):
            print(f"{OK} 模型密钥已设置（{env}）")
            return True
    print(f"{BAD} 模型密钥：DEEPSEEK_API_KEY / ASC_BENCH_LLM_API_KEY 都没设置")
    print('     设置: export DEEPSEEK_API_KEY=sk-xxx  ($env:DEEPSEEK_API_KEY="sk-xxx")')
    return False


def check_search() -> bool:
    """默认走本地语料检索，不需要 key；这里只确认语料可用，Tavily 仅作可选提示。"""
    compat.load_env()
    try:
        from search_patch import corpus_stats
        st = corpus_stats()
    except Exception as exc:
        print(f"{BAD} 本地语料加载失败：{exc}")
        return False
    if st["total"] < 30:
        print(f"{BAD} 本地语料只有 {st['total']} 条，偏少（corpus/*.jsonl）")
        return False
    print(f"{OK} 本地语料 {st['total']} 条 {st['by_kind']}（--search local 即可跑）")
    if os.getenv("TAVILY_API_KEY"):
        print(f"{OK} TAVILY_API_KEY 也已设置，可加 --search tavily 跑真实检索")
    else:
        print("     （可选）真实联网检索需 TAVILY_API_KEY，免费 1000 credits/月")
    return True


def check_asc() -> bool:
    compat.load_env()
    base = os.getenv("ASC_BASE_URL", "http://localhost:8080")
    try:
        from asc import AscClient
        client = AscClient(base, timeout=3.0)
        tasks = client.list_tasks()
        print(f"{OK} ASC 服务在线（{base}，现有 {len(tasks)} 个任务）")
        return True
    except Exception as exc:
        print(f"{BAD} ASC 服务不可用（{base}）: {str(exc)[:80]}")
        print("     启动: docker compose up -d postgres redis  →  ./mvnw spring-boot:run")
        return False


def main() -> None:
    print(f"Python {sys.version.split()[0]}  |  项目 {ROOT}\n")
    results = [check_deps(), check_model(), check_search(), check_asc()]
    print()
    if all(results):
        print("四项齐全，可以跑：")
        print("  ./.venv/Scripts/python.exe examples/deep_research/run_compare.py --limit 2 --repeats 1")
    else:
        print(f"还差 {results.count(False)} 项，补齐后重跑本脚本。")
        sys.exit(1)


if __name__ == "__main__":
    main()

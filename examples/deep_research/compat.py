"""运行环境适配：把 OpenAI 兼容的模型服务接进 open_deep_research 的配置体系。

上游用 ``init_chat_model("openai:...")`` + 环境变量解析密钥，所以这里不改上游，
只把选定的 provider 归一化成 ``OPENAI_API_KEY`` / ``OPENAI_BASE_URL`` 两个变量，
再把四个模型角色统一指向同一个模型。这样 A/B 两组的模型完全一致。
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Optional

PROVIDERS = {
    "deepseek": {
        "base_url": "https://api.deepseek.com/v1",
        "default_model": "deepseek-chat",
        "key_envs": ["DEEPSEEK_API_KEY", "ASC_BENCH_LLM_API_KEY", "OPENAI_API_KEY"],
    },
    "qwen": {
        "base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "default_model": "qwen-plus",
        "key_envs": ["DASHSCOPE_API_KEY", "ASC_BENCH_LLM_API_KEY", "OPENAI_API_KEY"],
    },
    "openai": {
        "base_url": "https://api.openai.com/v1",
        "default_model": "gpt-4o-mini",
        "key_envs": ["OPENAI_API_KEY"],
    },
    "custom": {
        "base_url": "",
        "default_model": "",
        "key_envs": ["ASC_BENCH_LLM_API_KEY", "OPENAI_API_KEY"],
    },
}


def load_env() -> None:
    """加载项目根 .env（不覆盖已有环境变量）。"""
    try:
        from dotenv import load_dotenv
    except ImportError:
        return
    for candidate in (Path.cwd() / ".env", Path(__file__).resolve().parents[2] / ".env"):
        if candidate.exists():
            load_dotenv(candidate, override=False)
            return


def bootstrap(provider: str = "deepseek", model: Optional[str] = None,
              api_key: Optional[str] = None,
              base_url: Optional[str] = None) -> str:
    """归一化模型环境变量，返回完整模型串（如 ``openai:deepseek-chat``）。

    缺少密钥时抛 SystemExit 并给出申请提示，避免跑到一半才失败。
    """
    load_env()
    spec = PROVIDERS.get(provider)
    if spec is None:
        raise SystemExit(f"未知 provider: {provider}，可选 {list(PROVIDERS)}")

    key = api_key or next((os.getenv(k) for k in spec["key_envs"] if os.getenv(k)), None)
    if not key:
        raise SystemExit(
            f"provider={provider} 缺少密钥。请设置环境变量 "
            f"{spec['key_envs'][0]}，例如：\n"
            f"  export {spec['key_envs'][0]}=sk-xxx\n"
            "（Windows PowerShell: $env:DEEPSEEK_API_KEY=\"sk-xxx\"）"
        )

    resolved_base = base_url or os.getenv("ASC_DR_BASE_URL") or spec["base_url"]
    if not resolved_base:
        raise SystemExit(f"provider={provider} 缺少 base_url，请用 --base-url 指定")

    os.environ["OPENAI_API_KEY"] = key
    os.environ["OPENAI_BASE_URL"] = resolved_base
    # 上游 get_api_key_for_model 只认 openai/anthropic/google 前缀，走 openai: 才能拿到 key
    resolved_model = model or spec["default_model"]
    return f"openai:{resolved_model}"


def check_search_key() -> None:
    """Tavily 是上游默认搜索源；没有 key 时提前失败，别等跑到第一次搜索才炸。"""
    load_env()
    if not os.getenv("TAVILY_API_KEY"):
        raise SystemExit(
            "缺少 TAVILY_API_KEY。open_deep_research 原生检索依赖搜索 API：\n"
            "  1. 到 https://app.tavily.com 注册，免费额度 1000 次/月，不要信用卡\n"
            "  2. 写进环境变量：export TAVILY_API_KEY=tvly-xxx\n"
            "不想花这个额度的话，用 --search local 走本地固定语料（默认），\n"
            "不联网、不要 key、每次返回相同结果，对照实验的方差更小。"
        )


def build_configurable(model: str, *, asc_task_id: Optional[str] = None,
                       asc_base_url: Optional[str] = None,
                       max_concurrent_research_units: int = 2,
                       max_researcher_iterations: int = 3,
                       max_react_tool_calls: int = 3,
                       search_api: str = "tavily") -> dict:
    """组装两组共用的 configurable 字典；ASC 组额外带 asc_* 三个键。"""
    cfg = {
        "research_model": model,
        "summarization_model": model,
        "compression_model": model,
        "final_report_model": model,
        "research_model_max_tokens": 8000,
        "summarization_model_max_tokens": 2000,
        "compression_model_max_tokens": 4000,
        "final_report_model_max_tokens": 8000,
        "max_structured_output_retries": 2,
        "allow_clarification": False,
        "search_api": search_api,
        "max_concurrent_research_units": max_concurrent_research_units,
        "max_researcher_iterations": max_researcher_iterations,
        "max_react_tool_calls": max_react_tool_calls,
        "max_content_length": 30000,
    }
    if asc_task_id:
        cfg["asc_task_id"] = asc_task_id
        cfg["asc_base_url"] = asc_base_url or os.getenv(
            "ASC_BASE_URL", "http://localhost:8080")
        cfg["asc_enabled"] = True
    return cfg

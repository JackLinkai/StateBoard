"""v0.7 真实 LLM 评测配置：.env、provider 注册表、模型矩阵与接口参数。

约定：
- 密钥只从环境变量 / 项目根目录 .env 读取，不写入代码与结果文件；
- provider 注册表在 providers.json，新增 OpenAI 兼容服务只需加一项；
- 模型上下文窗口与单价集中在 models.json，便于扩充模型矩阵；
- 兼容任意 OpenAI Chat Completions 协议端点（DeepSeek / Qwen / OpenAI / Ollama / 自建）。
"""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

BENCH_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = BENCH_DIR.parent
DEFAULT_PROVIDERS_FILE = BENCH_DIR / "providers.json"
DEFAULT_MODELS_FILE = BENCH_DIR / "models.json"


def load_dotenv(path: Optional[Path] = None) -> None:
    """把项目根目录 .env 中的 KEY=VALUE 注入 os.environ（已存在的变量不覆盖）。"""
    env_path = path or PROJECT_ROOT / ".env"
    if not env_path.exists():
        return
    for raw in env_path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key:
            os.environ.setdefault(key, value)


@dataclass(frozen=True)
class ProviderSpec:
    """模型服务商定义。api_style 是可插拔扩展点。"""

    name: str
    api_style: str = "openai_chat"
    base_url: str = ""
    api_key_envs: tuple[str, ...] = ()
    requires_api_key: bool = False
    default_model: str = ""
    chat_url: str = ""


@dataclass(frozen=True)
class ProviderRegistry:
    default_provider: str
    providers: dict[str, ProviderSpec]


def load_provider_registry(path: Optional[Path] = None) -> ProviderRegistry:
    """读取 providers.json；缺失时回退到 custom（从通用环境变量取地址与模型）。"""
    providers_path = path or DEFAULT_PROVIDERS_FILE
    if not providers_path.exists():
        custom = ProviderSpec(
            name="custom",
            base_url=os.environ.get("ASC_BENCH_LLM_BASE_URL", ""),
            api_key_envs=("ASC_BENCH_LLM_API_KEY",),
            default_model=os.environ.get("ASC_BENCH_MODEL", ""),
        )
        return ProviderRegistry(default_provider="custom", providers={"custom": custom})

    payload = json.loads(providers_path.read_text(encoding="utf-8"))
    providers: dict[str, ProviderSpec] = {}
    for name, item in (payload.get("providers") or {}).items():
        envs = item.get("api_key_envs") or []
        if isinstance(envs, str):
            envs = [envs]
        providers[name] = ProviderSpec(
            name=name,
            api_style=item.get("api_style", "openai_chat"),
            base_url=item.get("base_url", ""),
            api_key_envs=tuple(envs),
            requires_api_key=bool(item.get("requires_api_key", False)),
            default_model=item.get("default_model", ""),
            chat_url=item.get("chat_url", ""),
        )
    default = payload.get("default_provider") or next(iter(providers), "custom")
    if providers and default not in providers:
        default = next(iter(providers))
    return ProviderRegistry(default_provider=default, providers=providers)


@dataclass(frozen=True)
class ModelSpec:
    """单个模型的评测参数。价格单位：每 100 万 token 的计价货币。"""

    name: str
    context_window: int = 128_000
    input_per_mtok: Optional[float] = None
    output_per_mtok: Optional[float] = None
    currency: str = "USD"
    price_note: str = ""


def load_model_specs(path: Optional[Path] = None) -> dict[str, ModelSpec]:
    """读取 models.json；文件缺失时返回空表（调用方回退到默认参数）。"""
    models_path = path or DEFAULT_MODELS_FILE
    if not models_path.exists():
        return {}
    payload = json.loads(models_path.read_text(encoding="utf-8"))
    specs: dict[str, ModelSpec] = {}
    for name, item in (payload.get("models") or {}).items():
        specs[name] = ModelSpec(
            name=name,
            context_window=int(item.get("context_window", 128_000)),
            input_per_mtok=item.get("input_per_mtok"),
            output_per_mtok=item.get("output_per_mtok"),
            currency=item.get("currency", "USD"),
            price_note=item.get("price_note", ""),
        )
    return specs


@dataclass
class LlmSettings:
    """被测模型的连接参数。"""

    model: str
    base_url: str
    api_key: str
    temperature: float = 0.0
    max_tokens: int = 256
    timeout: float = 60.0
    retries: int = 2
    provider: str = "custom"
    api_style: str = "openai_chat"
    requires_api_key: bool = False
    chat_url_override: str = ""

    def chat_url(self) -> str:
        if self.chat_url_override:
            return self.chat_url_override
        base = self.base_url.rstrip("/")
        if base.endswith("/chat/completions"):
            return base
        if not base.endswith("/v1"):
            base += "/v1"
        return base + "/chat/completions"


def load_llm_settings(provider: Optional[str] = None,
                      model: Optional[str] = None,
                      base_url: Optional[str] = None,
                      temperature: float = 0.0,
                      max_tokens: int = 256,
                      timeout: float = 60.0,
                      retries: int = 2) -> LlmSettings:
    """按 命令行 > provider 专属环境变量 > 默认值 的优先级组装连接参数。

    通用的 ASC_BENCH_LLM_BASE_URL / ASC_BENCH_MODEL 只作为 custom 的回退，
    避免切换 provider 时被上一个服务商的配置误伤。
    """
    load_dotenv()
    registry = load_provider_registry()
    provider_name = (provider or os.environ.get("ASC_BENCH_PROVIDER")
                     or registry.default_provider).strip()
    spec = registry.providers.get(provider_name)
    if spec is None:
        available = ", ".join(sorted(registry.providers)) or "custom"
        raise ValueError(f"未知 provider：{provider_name}；可选：{available}")

    prefix = "ASC_BENCH_" + re.sub(r"[^0-9A-Za-z]+", "_", provider_name).upper()
    is_custom = provider_name == "custom"

    resolved_base = (base_url
                     or os.environ.get(f"{prefix}_BASE_URL")
                     or (os.environ.get("ASC_BENCH_LLM_BASE_URL") if is_custom else "")
                     or spec.base_url)
    resolved_model = (model
                      or os.environ.get(f"{prefix}_MODEL")
                      or (os.environ.get("ASC_BENCH_MODEL") if is_custom else "")
                      or spec.default_model)

    api_key = os.environ.get(f"{prefix}_API_KEY", "")
    if not api_key:
        for env_name in spec.api_key_envs:
            value = os.environ.get(env_name)
            if value:
                api_key = value
                break

    chat_url = (os.environ.get(f"{prefix}_CHAT_URL")
                or os.environ.get("ASC_BENCH_CHAT_URL")
                or spec.chat_url)

    return LlmSettings(
        model=resolved_model,
        base_url=resolved_base,
        api_key=api_key,
        temperature=temperature,
        max_tokens=max_tokens,
        timeout=timeout,
        retries=retries,
        provider=provider_name,
        api_style=spec.api_style,
        requires_api_key=spec.requires_api_key,
        chat_url_override=chat_url,
    )


def resolve_model_spec(settings: LlmSettings,
                       specs: dict[str, ModelSpec],
                       context_window: Optional[int] = None) -> ModelSpec:
    """取模型规格；未登记的模型用默认上下文窗口与空价格（成本记 null）。"""
    base = specs.get(settings.model, ModelSpec(name=settings.model))
    if context_window is None:
        return base
    return ModelSpec(
        name=base.name,
        context_window=context_window,
        input_per_mtok=base.input_per_mtok,
        output_per_mtok=base.output_per_mtok,
        currency=base.currency,
        price_note=base.price_note,
    )

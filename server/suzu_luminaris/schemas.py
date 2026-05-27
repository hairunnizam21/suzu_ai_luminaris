"""Wire schemas. Mirrors android/shared/.../data/Models.kt."""

from __future__ import annotations

from pydantic import BaseModel, Field


class HealthResponse(BaseModel):
    status: str
    version: str


class ReadyResponse(BaseModel):
    ready: bool
    missing: list[str] = []


class SshConfig(BaseModel):
    host: str = ""
    port: int = 22
    user: str = "root"
    auth_mode: str = "password"  # "password" | "key"
    password: str = ""
    private_key: str = ""
    workspace: str = "~/suzu_workspace"


class AiProviderConfig(BaseModel):
    kind: str = "openai_compat"  # anthropic | openai | openai_compat
    base_url: str = ""
    model: str = ""
    api_key: str = ""
    max_tokens: int = 0  # 0 = unlimited
    temperature: float = 1.0


class AdminConfig(BaseModel):
    ssh: SshConfig = Field(default_factory=SshConfig)
    ai_provider: AiProviderConfig = Field(default_factory=AiProviderConfig)
    max_iterations: int = 100


class VerifyRow(BaseModel):
    name: str
    ok: bool
    detail: str


class VerifyResponse(BaseModel):
    rows: list[VerifyRow]


class LogEvent(BaseModel):
    ts: float
    level: str
    source: str
    message: str


class ApkArtifact(BaseModel):
    id: str
    name: str
    kind: str  # decompiled | recompiled | signed
    session_id: str | None = None
    size: int
    created_at: float


class Session(BaseModel):
    id: str
    title: str
    created_at: float
    updated_at: float


class ToolCall(BaseModel):
    id: str
    name: str
    args: str


class ToolResult(BaseModel):
    id: str
    output: str
    is_error: bool = False


class ChatMessage(BaseModel):
    role: str
    content: str
    tool_call: ToolCall | None = None
    tool_result: ToolResult | None = None


class ChatRequest(BaseModel):
    session_id: str
    content: str
    max_iterations: int | None = None


class StreamEvent(BaseModel):
    """Mirror of the wire SSE shape. Server emits these as `data: <json>` frames.

    type ∈ {delta, tool_call, tool_result, done, error}. Only the field for
    the current type is populated; clients ignore the rest.
    """

    type: str
    delta: str | None = None
    tool_call: ToolCall | None = None
    tool_result: ToolResult | None = None
    message: str | None = None

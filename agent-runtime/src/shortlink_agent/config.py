"""Runtime configuration."""

from functools import lru_cache
from pathlib import Path
from typing import Literal
from urllib.parse import urlsplit

from pydantic import AliasChoices, Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Environment-backed application settings."""

    model_config = SettingsConfigDict(
        env_prefix="SHORTLINK_AGENT_",
        case_sensitive=False,
        extra="ignore",
        frozen=True,
        populate_by_name=True,
    )

    environment: Literal["local", "test", "staging", "production"] = "local"
    service_name: str = "short-link-agent-runtime"
    legacy_service_name: str = "short-link-agent"
    service_version: str = "0.7.0"
    required_agentscope_version: str = "2.0.4.post1"

    internal_token: SecretStr | None = Field(
        default=None,
        validation_alias=AliasChoices(
            "SHORTLINK_AGENT_INTERNAL_TOKEN",
            "AGENT_INTERNAL_TOKEN",
        ),
    )
    internal_token_dev_mode: bool = False

    runtime_auth_mode: Literal["legacy", "dual", "delegation_jwt"] = "legacy"
    delegation_jwks_url: str = (
        "http://127.0.0.1:8002/internal/short-link-admin/v1/agent-identity/jwks.json"
    )
    delegation_issuer: str = "shortlink-admin"
    delegation_audience: str = "shortlink-agent-runtime"
    delegation_clock_skew_seconds: int = Field(default=30, ge=0, le=60)
    delegation_max_ttl_seconds: int = Field(default=300, ge=30, le=300)
    jwks_cache_ttl_seconds: int = Field(default=300, ge=30, le=3600)
    jwks_unknown_kid_refresh_seconds: int = Field(default=10, ge=1, le=60)
    jwks_timeout_seconds: float = Field(default=3.0, gt=0, le=10)
    jwks_max_response_bytes: int = Field(default=65_536, ge=1_024, le=1_048_576)
    delegation_revocation_mode: Literal["disabled", "authority"] = "disabled"
    delegation_revocation_check_path: str = (
        "/internal/short-link-admin/v1/agent-identity/revocations/check"
    )
    revocation_cache_ttl_seconds: float = Field(default=5.0, gt=0, le=30)
    revocation_event_ttl_seconds: int = Field(default=360, ge=300, le=3_600)
    revocation_cache_max_entries: int = Field(default=10_000, ge=1, le=100_000)
    revocation_timeout_seconds: float = Field(default=2.0, gt=0, le=10)
    revocation_max_response_bytes: int = Field(default=16_384, ge=1_024, le=65_536)

    authority_base_url: str = "http://127.0.0.1:8002"
    authority_internal_token: SecretStr | None = Field(
        default=None,
        validation_alias=AliasChoices(
            "SHORTLINK_AGENT_AUTHORITY_INTERNAL_TOKEN",
            "AGENT_INTERNAL_TOKEN",
        ),
    )
    capability_timeout_seconds: float = Field(default=5.0, gt=0, le=30)
    capability_max_response_bytes: int = Field(
        default=1_048_576,
        ge=1_024,
        le=10_485_760,
    )
    capability_max_page_size: int = Field(default=100, ge=1, le=500)
    groups_list_contract: Literal["v1", "legacy"] = "v1"
    short_links_contract: Literal["v1", "legacy"] = "v1"
    group_stats_contract: Literal["v1", "legacy"] = "v1"
    v1_capability_auth_mode: Literal["legacy", "token_exchange"] = "legacy"
    authority_token_exchange_path: str = (
        "/internal/short-link-admin/v1/agent-identity/token/exchange"
    )
    authority_audience: str = "shortlink-authority"
    authority_mtls_cert_file: str | None = None
    authority_mtls_key_file: str | None = None
    authority_mtls_ca_file: str | None = None
    authority_token_refresh_skew_seconds: int = Field(default=10, ge=1, le=30)
    business_timezone: str = "Asia/Shanghai"

    model_api_key: SecretStr | None = Field(
        default=None,
        validation_alias=AliasChoices(
            "SHORTLINK_AGENT_MODEL_API_KEY",
            "DEEPSEEK_API_KEY",
            "LLM_API_KEY",
        ),
    )
    model_base_url: str = Field(
        default="https://api.deepseek.com",
        validation_alias=AliasChoices(
            "SHORTLINK_AGENT_MODEL_BASE_URL",
            "LLM_BASE_URL",
        ),
    )
    model_name: str = Field(
        default="deepseek-v4-flash",
        validation_alias=AliasChoices(
            "SHORTLINK_AGENT_MODEL_NAME",
            "LLM_MODEL",
        ),
    )
    model_timeout_seconds: float = Field(default=30.0, gt=0, le=180)
    model_max_retries: int = Field(default=2, ge=0, le=5)
    model_max_output_tokens: int = Field(default=2_000, ge=128, le=16_384)
    run_timeout_seconds: float = Field(default=60.0, gt=0, le=300)
    react_max_iterations: int = Field(default=8, ge=1, le=20)

    @field_validator("business_timezone")
    @classmethod
    def validate_business_timezone(cls, value: str) -> str:
        from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

        try:
            ZoneInfo(value)
        except ZoneInfoNotFoundError as exc:
            raise ValueError("business_timezone must be a valid IANA timezone") from exc
        return value

    @model_validator(mode="after")
    def reject_unsafe_production_dev_mode(self) -> "Settings":
        if self.environment not in {"local", "test"} and self.internal_token_dev_mode:
            raise ValueError("internal_token_dev_mode is allowed only in local or test")
        if self.runtime_auth_mode != "legacy":
            self._validate_service_url(self.delegation_jwks_url, "delegation_jwks_url")
        if self.delegation_revocation_mode == "authority":
            if not self.delegation_revocation_check_path.startswith("/"):
                raise ValueError("delegation_revocation_check_path must be absolute")
            self._validate_service_url(self.authority_base_url, "authority_base_url")
            if self.environment not in {"local", "test"}:
                self._require_file(self.authority_mtls_cert_file, "authority_mtls_cert_file")
                self._require_file(self.authority_mtls_key_file, "authority_mtls_key_file")
        if self.revocation_event_ttl_seconds < (
            self.delegation_max_ttl_seconds + self.delegation_clock_skew_seconds
        ):
            raise ValueError(
                "revocation_event_ttl_seconds must cover Delegation Token TTL and clock skew"
            )
        if self.v1_capability_auth_mode == "token_exchange":
            if self.group_stats_contract != "v1":
                raise ValueError("token_exchange requires the v1 group stats contract")
            if not self.authority_token_exchange_path.startswith("/"):
                raise ValueError("authority_token_exchange_path must be absolute")
            if self.environment not in {"local", "test"}:
                self._require_file(self.authority_mtls_cert_file, "authority_mtls_cert_file")
                self._require_file(self.authority_mtls_key_file, "authority_mtls_key_file")
                if not self.authority_base_url.lower().startswith("https://"):
                    raise ValueError("token_exchange requires HTTPS outside local and test")
        return self

    def _validate_service_url(self, value: str, field_name: str) -> None:
        parsed = urlsplit(value)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError(f"{field_name} must be an absolute HTTP(S) URL")
        if parsed.username or parsed.password or parsed.fragment:
            raise ValueError(f"{field_name} must not contain credentials or fragments")
        if self.environment not in {"local", "test"} and parsed.scheme != "https":
            raise ValueError(f"{field_name} must use HTTPS outside local and test")

    def _require_file(self, value: str | None, field_name: str) -> None:
        if not value or not Path(value).is_file():
            raise ValueError(f"{field_name} must reference a readable file")

    def internal_token_value(self) -> str:
        return self.internal_token.get_secret_value() if self.internal_token else ""

    def authority_token_value(self) -> str:
        token = self.authority_internal_token or self.internal_token
        return token.get_secret_value() if token else ""

    def model_api_key_value(self) -> str:
        return self.model_api_key.get_secret_value() if self.model_api_key else ""


@lru_cache
def get_settings() -> Settings:
    """Return the process-wide immutable settings snapshot."""

    return Settings()

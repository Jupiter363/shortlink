"""Security-sensitive runtime configuration tests."""

import pytest
from pydantic import ValidationError

from shortlink_agent.config import Settings


@pytest.mark.parametrize("environment", ["staging", "production"])
def test_internal_token_dev_mode_is_rejected_outside_local_and_test(environment: str) -> None:
    with pytest.raises(ValidationError, match="allowed only in local or test"):
        Settings(environment=environment, internal_token_dev_mode=True)


def test_secret_values_are_not_exposed_by_settings_repr() -> None:
    settings = Settings(
        environment="test",
        internal_token="runtime-secret",
        model_api_key="model-secret",
    )

    assert "runtime-secret" not in repr(settings)
    assert "model-secret" not in repr(settings)


def test_business_timezone_must_be_valid_iana_zone() -> None:
    with pytest.raises(ValidationError, match="valid IANA timezone"):
        Settings(environment="test", business_timezone="Mars/Olympus")


def test_delegation_jwks_requires_https_outside_local_and_test() -> None:
    with pytest.raises(ValidationError, match="must use HTTPS"):
        Settings(
            environment="staging",
            runtime_auth_mode="delegation_jwt",
            delegation_jwks_url="http://admin.internal/jwks.json",
        )


def test_token_exchange_requires_mtls_files_outside_local_and_test() -> None:
    with pytest.raises(ValidationError, match="authority_mtls_cert_file"):
        Settings(
            environment="production",
            authority_base_url="https://admin.internal",
            v1_capability_auth_mode="token_exchange",
        )


def test_token_exchange_cannot_use_legacy_group_stats_contract() -> None:
    with pytest.raises(ValidationError, match="requires the v1 group stats contract"):
        Settings(
            environment="test",
            v1_capability_auth_mode="token_exchange",
            group_stats_contract="legacy",
        )


def test_groups_list_has_independent_legacy_rollback_switch() -> None:
    settings = Settings(
        environment="test",
        v1_capability_auth_mode="token_exchange",
        group_stats_contract="v1",
        groups_list_contract="legacy",
    )

    assert settings.groups_list_contract == "legacy"


def test_short_links_has_independent_legacy_rollback_switch() -> None:
    settings = Settings(
        environment="test",
        v1_capability_auth_mode="token_exchange",
        group_stats_contract="v1",
        short_links_contract="legacy",
    )

    assert settings.short_links_contract == "legacy"

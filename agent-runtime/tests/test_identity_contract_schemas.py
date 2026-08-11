"""Shared identity and Token Exchange contract tests."""

import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError

_CONTRACT_DIR = Path(__file__).resolve().parents[2] / "schemas" / "agent-identity" / "v1"


def load_json(path: Path) -> dict[str, object]:
    return json.loads(path.read_text(encoding="utf-8"))


@pytest.mark.parametrize(
    ("schema_name", "example_name"),
    [
        ("delegation-token-claims.schema.json", "examples/delegation-token-claims.json"),
        ("authority-token-claims.schema.json", "examples/authority-token-claims.json"),
        ("jwks.schema.json", "examples/jwks.json"),
        ("token-exchange-request.schema.json", "examples/token-exchange-request.json"),
        ("token-exchange-response.schema.json", "examples/token-exchange-response.json"),
        ("session-bootstrap-request.schema.json", "examples/session-bootstrap-request.json"),
        ("session-bootstrap-response.schema.json", "examples/session-bootstrap-response.json"),
        (
            "session-token-refresh-request.schema.json",
            "examples/session-token-refresh-request.json",
        ),
        (
            "session-token-refresh-response.schema.json",
            "examples/session-token-refresh-response.json",
        ),
        ("session-revoke-request.schema.json", "examples/session-revoke-request.json"),
        ("agent-session-revoked-v1.schema.json", "examples/agent-session-revoked-v1.json"),
        (
            "token-revocation-check-request.schema.json",
            "examples/token-revocation-check-request.json",
        ),
        (
            "token-revocation-check-response.schema.json",
            "examples/token-revocation-check-response.json",
        ),
        (
            "token-revocation-check-response.schema.json",
            "examples/token-revocation-check-inactive-response.json",
        ),
    ],
)
def test_identity_examples_match_draft_2020_12_schemas(
    schema_name: str,
    example_name: str,
) -> None:
    schema = load_json(_CONTRACT_DIR / schema_name)
    example = load_json(_CONTRACT_DIR / example_name)

    Draft202012Validator.check_schema(schema)
    Draft202012Validator(schema, format_checker=FormatChecker()).validate(example)


def test_authority_example_is_bounded_by_delegation() -> None:
    delegation = load_json(_CONTRACT_DIR / "examples/delegation-token-claims.json")
    authority = load_json(_CONTRACT_DIR / "examples/authority-token-claims.json")

    assert set(authority["scp"]).issubset(set(delegation["scp"]))
    assert authority["exp"] <= delegation["exp"]
    assert authority["parent_jti"] == delegation["jti"]
    assert authority["aud"] != delegation["aud"]
    assert authority["grant_ver"] == delegation["grant_ver"]


def test_bootstrap_and_refresh_responses_are_structurally_identical() -> None:
    bootstrap_schema = load_json(_CONTRACT_DIR / "session-bootstrap-response.schema.json")
    refresh_schema = load_json(_CONTRACT_DIR / "session-token-refresh-response.schema.json")
    bootstrap = load_json(_CONTRACT_DIR / "examples/session-bootstrap-response.json")
    refresh = load_json(_CONTRACT_DIR / "examples/session-token-refresh-response.json")

    expected_fields = {
        "sessionId",
        "agentType",
        "runtimeUrl",
        "runtimeToken",
        "runtimeTokenExpiresAt",
        "sessionExpiresAt",
        "grantVersion",
    }
    assert set(bootstrap_schema["required"]) == expected_fields
    assert set(refresh_schema["required"]) == expected_fields
    assert set(bootstrap_schema["properties"]) == expected_fields
    assert set(refresh_schema["properties"]) == expected_fields
    assert set(bootstrap) == expected_fields
    assert set(refresh) == expected_fields
    assert bootstrap["sessionId"] == refresh["sessionId"]
    assert bootstrap["runtimeUrl"] == refresh["runtimeUrl"]
    assert refresh["grantVersion"] == bootstrap["grantVersion"] + 1
    assert refresh["runtimeToken"] != bootstrap["runtimeToken"]


def test_revoke_contract_has_no_json_response() -> None:
    request_schema = load_json(_CONTRACT_DIR / "session-revoke-request.schema.json")

    Draft202012Validator(request_schema).validate({})
    assert not (_CONTRACT_DIR / "session-revoke-response.schema.json").exists()


def test_revoked_event_is_minimized_and_correlates_with_session_version() -> None:
    event = load_json(_CONTRACT_DIR / "examples/agent-session-revoked-v1.json")
    refresh = load_json(_CONTRACT_DIR / "examples/session-token-refresh-response.json")
    expected_fields = {
        "eventId",
        "eventType",
        "occurredAt",
        "tenantId",
        "sessionId",
        "grantVersion",
        "status",
        "reasonCode",
        "revokedAt",
    }

    assert set(event) == expected_fields
    assert event["sessionId"] == refresh["sessionId"]
    assert event["grantVersion"] == refresh["grantVersion"] + 1
    assert event["occurredAt"] == event["revokedAt"]


@pytest.mark.parametrize(
    "forbidden_field",
    [
        "runtimeToken",
        "accessToken",
        "subjectToken",
        "ownerUsername",
        "ownerUserId",
        "scopes",
        "agentType",
        "revokedJti",
        "clientContext",
        "message",
        "prompt",
        "payload",
    ],
)
def test_revoked_event_rejects_sensitive_or_unneeded_fields(forbidden_field: str) -> None:
    schema = load_json(_CONTRACT_DIR / "agent-session-revoked-v1.schema.json")
    event = load_json(_CONTRACT_DIR / "examples/agent-session-revoked-v1.json")
    event[forbidden_field] = "must-not-pass"

    with pytest.raises(ValidationError):
        Draft202012Validator(schema, format_checker=FormatChecker()).validate(event)


def test_bootstrap_rejects_client_supplied_identity() -> None:
    schema = load_json(_CONTRACT_DIR / "session-bootstrap-request.schema.json")
    request = load_json(_CONTRACT_DIR / "examples/session-bootstrap-request.json")
    request["tenantId"] = "attacker-tenant"

    with pytest.raises(ValidationError):
        Draft202012Validator(schema).validate(request)


def test_session_control_contract_rejects_client_style_session_ids() -> None:
    schema = load_json(_CONTRACT_DIR / "session-bootstrap-response.schema.json")
    response = load_json(_CONTRACT_DIR / "examples/session-bootstrap-response.json")
    response["sessionId"] = "session-1"
    response["runtimeUrl"] = "/api/short-link/agent-runtime/v1/sessions/session-1"

    with pytest.raises(ValidationError):
        Draft202012Validator(schema, format_checker=FormatChecker()).validate(response)


def test_revocation_check_requires_reason_only_for_inactive_tokens() -> None:
    schema = load_json(_CONTRACT_DIR / "token-revocation-check-response.schema.json")
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    active = load_json(_CONTRACT_DIR / "examples/token-revocation-check-response.json")
    inactive = load_json(_CONTRACT_DIR / "examples/token-revocation-check-inactive-response.json")

    active["reasonCode"] = "TOKEN_REVOKED"
    inactive.pop("reasonCode")
    with pytest.raises(ValidationError):
        validator.validate(active)
    with pytest.raises(ValidationError):
        validator.validate(inactive)


def test_revocation_check_echoes_the_checked_token_coordinates() -> None:
    request = load_json(_CONTRACT_DIR / "examples/token-revocation-check-request.json")
    response = load_json(_CONTRACT_DIR / "examples/token-revocation-check-response.json")

    assert response["sessionId"] == request["sessionId"]
    assert response["grantVersion"] == request["grantVersion"]
    assert response["tokenId"] == request["tokenId"]

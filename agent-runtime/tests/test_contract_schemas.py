"""Shared Java/Python JSON Schema contract tests."""

import hashlib
import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError

_CONTRACT_DIR = Path(__file__).resolve().parents[2] / "schemas" / "agent-capabilities" / "v1"


def load_json(path: Path) -> dict[str, object]:
    return json.loads(path.read_text(encoding="utf-8"))


@pytest.mark.parametrize(
    ("schema_name", "example_name"),
    [
        (
            "groups-list-request.schema.json",
            "examples/groups-list-request.json",
        ),
        (
            "groups-list-response.schema.json",
            "examples/groups-list-response.json",
        ),
        (
            "short-links-query-request.schema.json",
            "examples/short-links-query-request.json",
        ),
        (
            "short-links-query-response.schema.json",
            "examples/short-links-query-response.json",
        ),
        (
            "group-stats-query-request.schema.json",
            "examples/group-stats-query-request.json",
        ),
        (
            "group-stats-query-response.schema.json",
            "examples/group-stats-query-response.json",
        ),
    ],
)
def test_shared_examples_match_draft_2020_12_schemas(
    schema_name: str,
    example_name: str,
) -> None:
    schema = load_json(_CONTRACT_DIR / schema_name)
    example = load_json(_CONTRACT_DIR / example_name)

    Draft202012Validator.check_schema(schema)
    Draft202012Validator(schema, format_checker=FormatChecker()).validate(example)


def test_all_contract_schemas_are_valid() -> None:
    for path in _CONTRACT_DIR.glob("*.schema.json"):
        Draft202012Validator.check_schema(load_json(path))


def test_group_stats_response_rejects_negative_metrics() -> None:
    schema = load_json(_CONTRACT_DIR / "group-stats-query-response.schema.json")
    response = load_json(_CONTRACT_DIR / "examples/group-stats-query-response.json")
    response["data"]["pv"] = -1

    with pytest.raises(ValidationError):
        Draft202012Validator(schema).validate(response)


def test_group_stats_example_uses_cross_language_canonical_hash() -> None:
    response = load_json(_CONTRACT_DIR / "examples/group-stats-query-response.json")
    canonical = json.dumps(
        response["data"],
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")

    assert response["snapshot"]["contentHash"] == (
        f"sha256:{hashlib.sha256(canonical).hexdigest()}"
    )


def test_groups_list_request_rejects_identity_injection() -> None:
    schema = load_json(_CONTRACT_DIR / "groups-list-request.schema.json")

    with pytest.raises(ValidationError):
        Draft202012Validator(schema).validate({"username": "spoofed-user"})


def test_groups_list_response_rejects_negative_counts_and_extra_actor_fields() -> None:
    schema = load_json(_CONTRACT_DIR / "groups-list-response.schema.json")
    response = load_json(_CONTRACT_DIR / "examples/groups-list-response.json")
    response["data"][0]["shortLinkCount"] = -1
    response["data"][0]["owner"] = "must-not-cross-boundary"

    with pytest.raises(ValidationError):
        Draft202012Validator(schema).validate(response)


def test_groups_list_example_uses_cross_language_canonical_hash() -> None:
    response = load_json(_CONTRACT_DIR / "examples/groups-list-response.json")
    canonical = json.dumps(
        response["data"],
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")

    assert response["snapshot"]["contentHash"] == (
        f"sha256:{hashlib.sha256(canonical).hexdigest()}"
    )


def test_short_links_request_rejects_identity_and_unbounded_page() -> None:
    schema = load_json(_CONTRACT_DIR / "short-links-query-request.schema.json")
    request = load_json(_CONTRACT_DIR / "examples/short-links-query-request.json")
    request["owner"] = "spoofed-user"
    request["size"] = 501

    with pytest.raises(ValidationError):
        Draft202012Validator(schema).validate(request)


def test_short_links_response_rejects_raw_provider_fields_and_invalid_validity() -> None:
    schema = load_json(_CONTRACT_DIR / "short-links-query-response.schema.json")
    response = load_json(_CONTRACT_DIR / "examples/short-links-query-response.json")
    response["data"]["records"][0]["originUrl"] = "https://private.example"
    response["data"]["records"][0]["validity"] = "PERMANENT"

    with pytest.raises(ValidationError):
        Draft202012Validator(schema, format_checker=FormatChecker()).validate(response)


def test_short_links_example_uses_cross_language_canonical_hash() -> None:
    response = load_json(_CONTRACT_DIR / "examples/short-links-query-response.json")
    canonical = json.dumps(
        response["data"],
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")

    assert response["snapshot"]["contentHash"] == (
        f"sha256:{hashlib.sha256(canonical).hexdigest()}"
    )

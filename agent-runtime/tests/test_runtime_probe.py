"""AgentScope dependency pin tests."""

from shortlink_agent.runtime.probe import AgentScopeRuntimeProbe


def test_installed_agentscope_matches_runtime_pin() -> None:
    check = AgentScopeRuntimeProbe("2.0.4.post1").check()

    assert check.status == "UP"
    assert check.required_version == "2.0.4.post1"
    assert check.installed_version == "2.0.4.post1"

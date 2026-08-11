"""Runtime dependency probes."""

from importlib.metadata import PackageNotFoundError, version
from typing import Protocol

from shortlink_agent.api.models import DependencyCheck


class RuntimeProbe(Protocol):
    """Readiness probe contract for the Agent runtime."""

    def check(self) -> DependencyCheck:
        """Check whether the runtime can accept Agent runs."""

        ...


class AgentScopeRuntimeProbe:
    """Verify that the pinned AgentScope runtime is installed."""

    package_name = "agentscope"

    def __init__(self, required_version: str) -> None:
        self._required_version = required_version

    def check(self) -> DependencyCheck:
        try:
            installed_version = version(self.package_name)
        except PackageNotFoundError:
            return DependencyCheck(
                name=self.package_name,
                status="DOWN",
                requiredVersion=self._required_version,
                detail="AgentScope is not installed.",
            )

        if installed_version != self._required_version:
            return DependencyCheck(
                name=self.package_name,
                status="DOWN",
                requiredVersion=self._required_version,
                installedVersion=installed_version,
                detail="Installed AgentScope version does not match the runtime pin.",
            )

        return DependencyCheck(
            name=self.package_name,
            status="UP",
            requiredVersion=self._required_version,
            installedVersion=installed_version,
        )


class ModelConfigurationProbe:
    """Fail readiness when no model can execute an Agent run."""

    def __init__(self, api_key: str, model_name: str) -> None:
        self._api_key = api_key
        self._model_name = model_name

    def check(self) -> DependencyCheck:
        if not self._api_key:
            return DependencyCheck(
                name="model-configuration",
                status="DOWN",
                detail="Model API key is not configured.",
            )
        if not self._model_name.strip():
            return DependencyCheck(
                name="model-configuration",
                status="DOWN",
                detail="Model name is not configured.",
            )
        return DependencyCheck(
            name="model-configuration",
            status="UP",
            detail=f"Configured model: {self._model_name}",
        )

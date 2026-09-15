from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_phase7_remote_ci_gates_only_functional_unicast_not_lan_acceptance():
    workflow = (ROOT / ".github/workflows/phase7-settings-e2e.yml").read_text(encoding="utf-8")
    assert "P7-TC08" in workflow
    assert "tailscale-unicast" in workflow
    assert "functional_acceptance=true" in workflow
    assert "production_lan_acceptance=false" in workflow
    assert "discovery_acceptance=false" in workflow
    assert "performance_acceptance=false" in workflow
    assert "P7-TC09,P7-TC10,P7-TC11,P7-TC12-walkthrough,P7-TC13" in workflow
    assert "tools/phase6_soak_e2e.py" in workflow
    assert "--stream-bitrate-mbps 8" in workflow
    assert "--duration-seconds 60" in workflow

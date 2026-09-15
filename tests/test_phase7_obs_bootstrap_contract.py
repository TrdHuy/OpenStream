from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_phase7_obs_smoke_uses_exact_built_plugin_and_real_obs_without_network_acceptance():
    workflow = (ROOT / ".github/workflows/phase7-obs-artifact-smoke.yml").read_text(encoding="utf-8")
    assert "obs-plugin/build/openstream-obs.dll" in workflow
    assert "actions/upload-artifact@v4" in workflow
    assert "actions/download-artifact@v4" in workflow
    assert "[OpenStream] OBS plugin loaded" in workflow
    assert "openstream_phone_v8_source" in workflow
    assert "obs64.exe" in workflow
    assert "tailscale" not in workflow.lower()
    assert "adb " not in workflow.lower()
    assert "phase5" not in workflow.lower()

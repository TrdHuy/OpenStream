from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def test_phase7_release_notes_match_current_profile_preset_and_network_scope():
    notes = (ROOT / "docs/release-notes-template.md").read_text(encoding="utf-8")
    for expected in (
        "Profiles",
        "1080p30",
        "1080p60",
        "4K30",
        "4K60",
        "Camera2",
        "hardware H.264",
        "Lưu và kết nối",
        "phase7-user-guide.md",
        "Manual SRT unicast",
    ):
        assert expected in notes
    assert "YashasVM/OpenStream" not in notes
    assert "Tailscale functional evidence is not used as production" in notes

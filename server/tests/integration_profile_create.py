"""Exercise BobBot's profile-create contract against the installed Hermes API.

Run with Hermes' venv and PYTHONPATH pointing at its checkout. All state is temporary.
"""
import os
from pathlib import Path
import tempfile

with tempfile.TemporaryDirectory(prefix="bobbot-profile-create-") as scratch:
    os.environ["HOME"] = scratch
    os.environ["HERMES_HOME"] = str(Path(scratch) / ".hermes")
    home = Path(os.environ["HERMES_HOME"])
    home.mkdir()
    (home / "config.yaml").write_text("model:\n  default: test-model\n")
    (home / "SOUL.md").write_text("# Clove\n\nAuthority bot.\n")
    skill = home / "skills" / "fixture"
    skill.mkdir(parents=True)
    (skill / "SKILL.md").write_text("---\nname: fixture\ndescription: Test skill\n---\nTest only.\n")

    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from hermes_cli.web_routers.profiles import router

    app = FastAPI()
    app.include_router(router)
    with TestClient(app) as client:
        payload = {
            "name": "steve", "description": "Household assistant",
            "provider": None, "model": None, "clone_from": "default",
        }
        # The released request failed validation before creating any profile.
        bad = client.post("/api/profiles", json={**payload, "keep_skills": True})
        assert bad.status_code == 422, bad.text
        assert not (home / "profiles" / "steve").exists()

        created = client.post("/api/profiles", json=payload)
        assert created.status_code == 200, created.text
        assert created.json()["ok"], created.text
        assert created.json()["skills_disabled"] == 0, created.text
        profile = home / "profiles" / "steve"
        assert (profile / "skills" / "fixture" / "SKILL.md").read_text() == (skill / "SKILL.md").read_text()
        soul = "# steve\n\nYou run the house.\n"
        saved = client.put("/api/profiles/steve/soul", json={"content": soul})
        assert saved.status_code == 200, saved.text
        assert (profile / "SOUL.md").read_text() == soul
        assert (home / "SOUL.md").read_text().startswith("# Clove")
    print("PASS: rejected old boolean; created cloned bot; retained skills; saved independent persona")

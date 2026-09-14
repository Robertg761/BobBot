"""Mounted by Hermes behind the existing dashboard authentication."""
import importlib.util
import sys
from pathlib import Path
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

name = 'bobbot_team_runtime'
if name not in sys.modules:
    path = Path(__file__).resolve().parents[1]
    spec = importlib.util.spec_from_file_location(name, path / '__init__.py', submodule_search_locations=[str(path)])
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
team = sys.modules[name]
router = APIRouter()


@router.get('/team')
def get_team():
    from hermes_cli.profiles import list_profiles
    return {**team.store().settings(), 'profiles': [{'name': p.name} for p in list_profiles()],
            'requests': team.store().requests()}


class Settings(BaseModel):
    authority: str
    enabled: bool = True


@router.put('/team')
def set_team(body: Settings):
    from hermes_cli.profiles import profile_exists
    if not profile_exists(body.authority):
        raise HTTPException(400, 'Authority profile does not exist')
    team.store().configure(body.authority, body.enabled)
    return get_team()


class Decision(BaseModel):
    choice: str
    reason: str


@router.post('/requests/{ident}/decide')
def decide(ident: str, body: Decision):
    try:
        row = team.store().decide(ident, body.choice, body.reason, 'you', human=True)
        team.wake_request(row)
        return row
    except (ValueError, PermissionError) as exc:
        raise HTTPException(409, str(exc)) from exc


@router.post('/profiles/{profile}/bootstrap')
def bootstrap(profile: str):
    from hermes_cli.profiles import get_profile_dir, profile_exists
    from subprocess import run
    if not profile_exists(profile):
        raise HTTPException(400, 'Profile does not exist')
    dest = get_profile_dir(profile) / 'plugins' / 'bobbot-team'
    dest.parent.mkdir(parents=True, exist_ok=True)
    source = Path(__file__).resolve().parents[1]
    if not dest.exists():
        dest.symlink_to(source, target_is_directory=True)
    elif dest.resolve() != source:
        raise HTTPException(409, 'A different team extension is already installed for this profile')
    for command in [['plugins', 'enable', 'bobbot-team', '--no-allow-tool-override'], ['tools', 'enable', 'kanban', 'bobbot_team']]:
        result = run([sys.executable, '-m', 'hermes_cli.main', '-p', profile, *command],
                     capture_output=True, text=True, timeout=45)
        if result.returncode:
            raise HTTPException(500, 'Could not configure team tools for this profile')
    return {'configured': True}

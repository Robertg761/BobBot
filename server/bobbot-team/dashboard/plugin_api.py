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
    roster = team.bots.roster()
    return {**team.store().settings(), 'profiles': roster, 'requests': team.store().requests(),
            'teammate_messaging': any(p['teammate_messaging'] for p in roster)}


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
    try:
        team.bots.bootstrap_profile(profile, Path(__file__).resolve().parents[1])
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(409 if 'different team extension' in str(exc) else 500, str(exc)) from exc
    return {'configured': True}


class BotMode(BaseModel):
    title: str | None = None
    enabled: bool = True


@router.put('/profiles/{profile}/teammate-messaging')
def set_teammate_messaging(profile: str, body: BotMode):
    """Turn Hermes Bot Mode on (or off) for one bot: the roster line other bots see, and the
    message_agent tool in its Bot Chat. New Bot Chat turns pick it up; open ones after a restart."""
    from hermes_cli.profiles import get_profile_dir, profile_exists
    if not profile_exists(profile):
        raise HTTPException(400, 'Profile does not exist')
    if body.enabled:
        block = team.bots.enable_teammate_messaging(profile, body.title)
    else:
        block = team.bots.set_bot_mode(get_profile_dir(profile), enabled=False)
    return {'profile': profile, 'teammate_messaging': block is not None, 'role': (block or {}).get('title', '')}

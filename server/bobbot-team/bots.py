"""Bots the authority can create and configure by talking, and Hermes Bot Mode enablement.

Hermes only switches on teammate messaging (the ``message_agent`` tool and the roster in
every Bot Chat) when at least one profile carries ``ui_meta['hermes-bots']`` in its
profile.yaml. The desktop app writes that; BobBot and this plugin write the same shape.

Pure helpers keep Hermes imports lazy so the metadata logic is testable without Hermes.
"""
import json
import re
import sys
from pathlib import Path

NAME_RE = re.compile(r'^[a-z0-9][a-z0-9_-]{0,63}$')
BOT_MODE_KEY = 'hermes-bots'
MANAGED_BY = 'bobbot'


# ---- profile.yaml metadata (no Hermes imports) ----

def _read_yaml(path):
    import yaml
    if not path.is_file():
        return {}
    data = yaml.safe_load(path.read_text(encoding='utf-8')) or {}
    return data if isinstance(data, dict) else {}


def _write_yaml(path, data):
    try:
        from utils import atomic_yaml_write  # Hermes' own writer for this file, so edits serialise with the dashboard's
        atomic_yaml_write(path, data, sort_keys=False)
        return
    except ImportError:
        pass
    import yaml
    tmp = path.with_suffix('.yaml.tmp')
    tmp.write_text(yaml.safe_dump(data, sort_keys=False, allow_unicode=True), encoding='utf-8')
    tmp.replace(path)


def bot_mode(profile_dir):
    """The profile's Bot Mode block, or None when teammate messaging is not enabled for it."""
    meta = _read_yaml(Path(profile_dir) / 'profile.yaml').get('ui_meta')
    block = meta.get(BOT_MODE_KEY) if isinstance(meta, dict) else None
    return block if isinstance(block, dict) else None


def set_bot_mode(profile_dir, title=None, enabled=True):
    """Merge ``ui_meta['hermes-bots']`` the way the gateway's ``profiles.configure`` does:
    key-wise, bumping the per-key revision so a later desktop edit sees the change."""
    path = Path(profile_dir) / 'profile.yaml'
    data = _read_yaml(path)
    ui_meta = data.get('ui_meta') if isinstance(data.get('ui_meta'), dict) else {}
    revisions = data.get('_ui_meta_revisions') if isinstance(data.get('_ui_meta_revisions'), dict) else {}
    if enabled:
        block = dict(ui_meta.get(BOT_MODE_KEY) or {}) if isinstance(ui_meta.get(BOT_MODE_KEY), dict) else {}
        title = ' '.join(str(title).split())[:160] if title is not None else ''
        if title:
            block['title'] = title
        block.setdefault('managed_by', MANAGED_BY)
        ui_meta[BOT_MODE_KEY] = block
    else:
        ui_meta.pop(BOT_MODE_KEY, None)
    if ui_meta:
        data['ui_meta'] = ui_meta
    else:
        data.pop('ui_meta', None)
    revisions[BOT_MODE_KEY] = int(revisions.get(BOT_MODE_KEY, 0) or 0) + 1
    data['_ui_meta_revisions'] = revisions
    _write_yaml(path, data)
    return bot_mode(profile_dir)


def persona_text(name, role='', persona=''):
    """SOUL.md for a new bot: the heading is the bot's name so BobBot shows it as the display name."""
    title = name.replace('-', ' ').replace('_', ' ').strip().title() or name
    persona = (persona or '').strip()
    if persona.startswith('# '):
        return persona.rstrip() + '\n'
    lines = [f'# {title}', '', f'You are {title}.']
    if role:
        lines.append(role.strip())
    if persona:
        lines += ['', persona]
    return '\n'.join(lines).rstrip() + '\n'


def validate_name(name):
    if not isinstance(name, str) or not NAME_RE.match(name):
        raise ValueError('Bot names are lowercase letters, digits, dash and underscore, up to 64 characters.')
    if name == 'default':
        raise ValueError("'default' is the built-in profile; pick another name.")
    return name


# ---- Hermes-backed operations ----

def _profiles():
    from hermes_cli import profiles
    return profiles


def bootstrap_profile(profile, plugin_source):
    """Link this plugin into ``profile`` and enable the team toolsets there. Idempotent."""
    from subprocess import run
    profiles = _profiles()
    if not profiles.profile_exists(profile):
        raise ValueError('Profile does not exist')
    dest = profiles.get_profile_dir(profile) / 'plugins' / 'bobbot-team'
    dest.parent.mkdir(parents=True, exist_ok=True)
    source = Path(plugin_source).resolve()
    if dest.is_symlink() and not dest.exists():
        dest.unlink()  # the extension moved since this bot was set up; re-point the link
    if not dest.exists() and not dest.is_symlink():
        dest.symlink_to(source, target_is_directory=True)
    elif dest.resolve() != source:
        raise RuntimeError('A different team extension is already installed for this profile')
    for command in [['plugins', 'enable', 'bobbot-team', '--no-allow-tool-override'], ['tools', 'enable', 'kanban', 'bobbot_team']]:
        result = run([sys.executable, '-m', 'hermes_cli.main', '-p', profile, *command], capture_output=True, text=True, timeout=45)
        if result.returncode:
            raise RuntimeError('Could not configure team tools for this profile: ' + (result.stderr or result.stdout).strip()[-300:])


def enable_teammate_messaging(profile, title=None):
    """Turn Bot Mode on for one profile. Returns the resulting block."""
    profiles = _profiles()
    if not profiles.profile_exists(profile):
        raise ValueError('Profile does not exist')
    profile_dir = profiles.get_profile_dir(profile)
    if not (title or '').strip():
        meta = profiles.read_profile_meta(profile_dir) if hasattr(profiles, 'read_profile_meta') else {}
        title = (meta.get('description') or '').strip().split('.')[0][:80] or profile
    return set_bot_mode(profile_dir, title=title, enabled=True)


def roster():
    """Every profile with its role line and whether teammate messaging is on."""
    profiles = _profiles()
    out = []
    for p in profiles.list_profiles():
        block = bot_mode(p.path)
        out.append({'name': p.name, 'handle': 'hermes' if p.name == 'default' else p.name, 'is_default': bool(p.is_default),
                    'description': p.description or '', 'model': p.model or '', 'provider': p.provider or '',
                    'teammate_messaging': block is not None, 'role': (block or {}).get('title', '')})
    return out


def create_bot(name, role='', persona='', model='', provider='', clone_from='default', plugin_source=None, authority='default'):
    """Create a profile, give it a persona, a model, Bot Mode metadata and the team plugin."""
    validate_name(name)
    profiles = _profiles()
    if profiles.profile_exists(name):
        raise FileExistsError(f"A bot named '{name}' already exists.")
    if bool(model) != bool(provider):
        raise ValueError('Set both model and provider, or neither (the bot then inherits your model).')
    profile_dir = profiles.create_profile(name, clone_from=clone_from or None, clone_config=bool(clone_from), description=(role or '').strip() or None)
    (profile_dir / 'SOUL.md').write_text(persona_text(name, role, persona), encoding='utf-8')
    # The clone copies settings and keys so the bot can work; the authority's private memories are not the new bot's to keep.
    for private in ('memories/MEMORY.md', 'memories/USER.md'):
        try:
            (profile_dir / private).unlink()
        except FileNotFoundError:
            pass
    warnings = []
    if model and provider:
        try:
            from hermes_cli.web_routers.profiles import _write_profile_model
            from hermes_constants import get_process_hermes_home
            _write_profile_model(profile_dir, provider, model, validate_in=get_process_hermes_home())
        except Exception as exc:  # the bot exists; the model can be fixed from BobBot
            warnings.append(f'Model not set ({exc}); it inherits the clone source model.')
    set_bot_mode(profile_dir, title=(role or '').strip() or name, enabled=True)
    # Enabling on the new bot makes the install managed; the authority needs its own block for a role line.
    if profiles.profile_exists(authority) and bot_mode(profiles.get_profile_dir(authority)) is None:
        enable_teammate_messaging(authority)
    if plugin_source is not None:
        try:
            bootstrap_profile(name, plugin_source)
        except Exception as exc:
            warnings.append(f'Team review not configured for {name}: {exc}')
    return {'created': True, 'name': name, 'handle': name, 'path': str(profile_dir), 'teammate_messaging': True, 'warnings': warnings}


def configure_bot(name, description=None, persona=None, role=None, model=None, provider=None):
    profiles = _profiles()
    if not profiles.profile_exists(name):
        raise ValueError(f"No bot named '{name}'.")
    profile_dir = profiles.get_profile_dir(name)
    if bool(model) != bool(provider):
        raise ValueError('Set both model and provider to change the model.')
    applied = {}
    if description is not None:
        profiles.write_profile_meta(profile_dir, description=str(description), description_auto=False)
        applied['description'] = True
    if persona is not None and str(persona).strip():
        (profile_dir / 'SOUL.md').write_text(persona_text(name, '', str(persona)), encoding='utf-8')
        applied['persona'] = True
    if role is not None:
        set_bot_mode(profile_dir, title=str(role), enabled=True)
        applied['role'] = True
    if model and provider:
        from hermes_cli.web_routers.profiles import _write_profile_model
        from hermes_constants import get_process_hermes_home
        _write_profile_model(profile_dir, provider, model, validate_in=get_process_hermes_home())
        applied['model'] = True
    return {'name': name, 'applied': applied}


def to_json(obj):
    return json.dumps(obj, ensure_ascii=False)

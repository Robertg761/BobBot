"""Install into each existing profile using the installed Hermes runtime."""
from pathlib import Path
import subprocess
import sys

from hermes_cli.profiles import list_profiles

source = Path(__file__).resolve().parent / 'bobbot-team'
for profile in list_profiles():
    destination = profile.path / 'plugins' / 'bobbot-team'
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() or destination.is_symlink():
        if destination.resolve() != source:
            raise SystemExit(f'A different extension is already installed at {destination}')
    else:
        destination.symlink_to(source, target_is_directory=True)
    for command in [('plugins', 'enable', 'bobbot-team', '--no-allow-tool-override'),
                    ('tools', 'enable', 'kanban', 'bobbot_team')]:
        subprocess.run([sys.executable, '-m', 'hermes_cli.main', '-p', profile.name, *command], check=True)
    print(f'Configured {profile.name}')
print('Restart the Hermes dashboard and gateway, then open new conversations.')

#!/usr/bin/env python3
"""
DineMaster Version Bump & Auto-Increment Utility
Supports bumping semantic versions for Frontend (pubspec.yaml) and Backend (pom.xml),
querying Git tags, and creating annotated release tags.
"""

import argparse
import os
import re
import subprocess
import sys

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PUBSPEC_PATH = os.path.join(ROOT_DIR, 'frontend', 'pubspec.yaml')
POM_PATH = os.path.join(ROOT_DIR, 'backend', 'pom.xml')


def get_git_tags(prefix=''):
    try:
        res = subprocess.run(['git', 'tag', '-l', f"{prefix}*"], capture_output=True, text=True, check=True)
        return [t.strip() for t in res.stdout.strip().split('\n') if t.strip()]
    except Exception:
        return []


def get_latest_tag_version(prefix='v'):
    tags = get_git_tags(prefix)
    matching = []
    for t in tags:
        if not t.startswith(prefix):
            continue
        raw = t[len(prefix):]
        parts = raw.split('.')
        if len(parts) == 3 and all(p.isdigit() for p in parts):
            matching.append(tuple(map(int, parts)))
    if not matching:
        return None
    matching.sort()
    return matching[-1]


def bump_semver(major, minor, patch, bump_type='patch'):
    if bump_type == 'major':
        return major + 1, 0, 0
    elif bump_type == 'minor':
        return major, minor + 1, 0
    else:  # patch
        return major, minor, patch + 1


def get_frontend_version():
    if not os.path.exists(PUBSPEC_PATH):
        return None
    with open(PUBSPEC_PATH, 'r', encoding='utf-8') as f:
        content = f.read()
    m = re.search(r'^version:\s*([0-9]+\.[0-9]+\.[0-9]+)(\+[0-9]+)?', content, re.M)
    if m:
        return m.group(1), m.group(2) or '+1'
    return None, None


def update_frontend_version(new_ver, build_number=None):
    with open(PUBSPEC_PATH, 'r', encoding='utf-8') as f:
        content = f.read()
    if build_number is None:
        m = re.search(r'^version:\s*[0-9]+\.[0-9]+\.[0-9]+\+(\d+)', content, re.M)
        b_num = int(m.group(1)) + 1 if m else 1
    else:
        b_num = build_number
    new_version_str = f"version: {new_ver}+{b_num}"
    updated = re.sub(r'^version:\s*[^\r\n]+', new_version_str, content, flags=re.M)
    with open(PUBSPEC_PATH, 'w', encoding='utf-8') as f:
        f.write(updated)
    return f"{new_ver}+{b_num}"


def get_backend_version():
    if not os.path.exists(POM_PATH):
        return None
    with open(POM_PATH, 'r', encoding='utf-8') as f:
        content = f.read()
    m = re.search(r'<artifactId>dinemaster-backend</artifactId>\s*<version>([^<]+)</version>', content)
    if m:
        return m.group(1)
    return None


def update_backend_version(new_ver):
    with open(POM_PATH, 'r', encoding='utf-8') as f:
        content = f.read()
    pattern = r'(<artifactId>dinemaster-backend</artifactId>\s*<version>)[^<]+(</version>)'
    updated = re.sub(pattern, rf'\g<1>{new_ver}\g<2>', content)
    with open(POM_PATH, 'w', encoding='utf-8') as f:
        f.write(updated)
    return new_ver


def main():
    parser = argparse.ArgumentParser(description="DineMaster Auto-Increment Version Tool")
    parser.add_argument('target', choices=['frontend', 'backend', 'status'], nargs='?', default='status',
                        help='Target component to bump or status check')
    parser.add_argument('--bump', choices=['patch', 'minor', 'major'], default='patch',
                        help='SemVer component to increment (default: patch)')
    parser.add_argument('--tag', action='store_true', help='Create Git tag after updating')
    parser.add_argument('--custom', type=str, default=None, help='Set explicit custom version (e.g. 0.0.1)')

    args = parser.parse_args()

    fe_ver, fe_build = get_frontend_version()
    be_ver = get_backend_version()
    latest_fe_tag = get_latest_tag_version('v')
    latest_be_tag = get_latest_tag_version('backend-v')

    if args.target == 'status':
        print("=== DineMaster Version Status ===")
        print(f"Frontend pubspec.yaml: {fe_ver}{fe_build}")
        print(f"Frontend Latest Git Tag: {'v' + '.'.join(map(str, latest_fe_tag)) if latest_fe_tag else 'None'}")
        print(f"Backend pom.xml:       {be_ver}")
        print(f"Backend Latest Git Tag:  {'backend-v' + '.'.join(map(str, latest_be_tag)) if latest_be_tag else 'None'}")
        return

    if args.target == 'frontend':
        if args.custom:
            new_clean = args.custom.lstrip('v')
        else:
            # Check existing v0.x tags
            all_fe_tags = get_git_tags('v')
            v0_tags = [t for t in all_fe_tags if t.startswith('v0.')]
            if not v0_tags:
                new_clean = "0.0.1"
            else:
                parts = v0_tags[-1].lstrip('v').split('.')
                maj, min_, pat = bump_semver(int(parts[0]), int(parts[1]), int(parts[2]), args.bump)
                new_clean = f"{maj}.{min_}.{pat}"

        full_version = update_frontend_version(new_clean)
        tag_name = f"v{new_clean}"
        print(f"[SUCCESS] Updated Frontend pubspec.yaml to {full_version}")
        if args.tag:
            subprocess.run(['git', 'tag', tag_name], check=True)
            print(f"[SUCCESS] Created Git tag {tag_name}")

    elif args.target == 'backend':
        if args.custom:
            new_clean = args.custom.replace('backend-', '').lstrip('v')
        else:
            all_be_tags = get_git_tags('backend-v')
            if not all_be_tags:
                new_clean = "0.0.1"
            else:
                parts = all_be_tags[-1].replace('backend-v', '').split('.')
                maj, min_, pat = bump_semver(int(parts[0]), int(parts[1]), int(parts[2]), args.bump)
                new_clean = f"{maj}.{min_}.{pat}"

        update_backend_version(new_clean)
        tag_name = f"backend-v{new_clean}"
        print(f"[SUCCESS] Updated Backend pom.xml to {new_clean}")
        if args.tag:
            subprocess.run(['git', 'tag', tag_name], check=True)
            print(f"[SUCCESS] Created Git tag {tag_name}")


if __name__ == '__main__':
    main()

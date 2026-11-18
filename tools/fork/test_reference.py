#!/usr/bin/env python3
"""Audit the actual reference checkout: storage logic differs only by file names."""
import argparse
import json
from pathlib import Path
import subprocess


def audit(directory):
    provenance = json.loads((directory.parent / 'reference-provenance.json').read_text())
    sha = provenance['upstream_sha']
    path = 'app/src/main/kotlin/app/toque/recipes/RecipeRepo.kt'
    original = subprocess.check_output(['git','show',f'{sha}:{path}'],text=True)
    reference = (directory/path).read_text()
    restored = reference.replace('app.toque.update.EditionStorage.databaseName', '"recettes.db"').replace(
        'app.toque.update.EditionStorage.imagesDirectory', '"images"')
    if restored != original:
        raise AssertionError('Reference recipe/favorite code changed beyond storage file names')
    prefs = (directory/'app/src/main/kotlin/app/toque/Prefs.kt').read_text()
    original_prefs = subprocess.check_output(['git','show',f'{sha}:app/src/main/kotlin/app/toque/Prefs.kt'],text=True)
    if prefs.replace('app.toque.update.EditionStorage.preferences(context)',
                     'context.getSharedPreferences("toque", Context.MODE_MULTI_PROCESS)') != original_prefs:
        raise AssertionError('Reference preferences changed beyond their storage accessor')
    print('REFERENCE_FAVORITES_AND_PREFERENCES_AUDIT_OK')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory',type=Path,required=True)
    audit(parser.parse_args().directory)

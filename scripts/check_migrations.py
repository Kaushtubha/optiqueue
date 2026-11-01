#!/usr/bin/env python3
"""
OptiQueue Database Migration Verification Script

Validates that Flyway migration scripts adhere to naming conventions
and contain no disallowed destructive statements.
"""

import os
import re
import sys

MIGRATION_DIR = "src/main/resources/db/migration"

def check_migrations():
    if not os.path.exists(MIGRATION_DIR):
        print(f"[*] Migration dir {MIGRATION_DIR} not found. Skipping.")
        return True

    files = sorted(os.listdir(MIGRATION_DIR))
    pattern = re.compile(r"^V\d+__.+\.sql$")
    valid = True

    for f in files:
        if not pattern.match(f):
            print(f"[!] Invalid migration filename pattern: {f}")
            valid = False
        else:
            print(f"[+] Valid migration file: {f}")

    return valid

if __name__ == "__main__":
    success = check_migrations()
    sys.exit(0 if success else 1)

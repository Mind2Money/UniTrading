"""Print a LicenseKey entry for composeApp/src/commonMain/kotlin/com/unitrading/app/License.kt.

The license key (授权码) is read without echo, or as one line from a pipe, and is never written anywhere:
only a random salt and its PBKDF2-HMAC-SHA256 verifier are printed. Paste the line into ProductionLicenseKeys.
Keep earlier entries to leave existing activations valid; remove one to make its users enter a new key.
"""
import argparse
import getpass
import hashlib
import re
import secrets
import sys

# OWASP Password Storage Cheat Sheet (2023) figure for PBKDF2-HMAC-SHA256.
DEFAULT_ITERATIONS = 600_000


def read_key() -> str:
    if not sys.stdin.isatty():
        return sys.stdin.readline().rstrip('\r\n')
    key = getpass.getpass('License key: ')
    if key != getpass.getpass('Repeat: '):
        raise SystemExit('The two entries differ.')
    return key


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('id', help='entitlement id stored in activation records, e.g. key-2026-11')
    parser.add_argument('--iterations', type=int, default=DEFAULT_ITERATIONS)
    args = parser.parse_args()
    if not re.fullmatch(r'[a-z0-9][a-z0-9.-]{2,63}', args.id):
        raise SystemExit('id: 3-64 characters of lowercase letters, digits, "." and "-".')
    if args.iterations < 100_000:
        raise SystemExit('Use at least 100000 iterations.')
    key = read_key()
    # The app trims surrounding whitespace and compares the UTF-8 bytes; plain visible ASCII avoids keyboard and
    # normalisation differences between platforms.
    if not re.fullmatch(r'[\x21-\x7e]{8,256}', key):
        raise SystemExit('The key must be 8-256 visible ASCII characters without spaces.')
    salt = secrets.token_bytes(16)
    verifier = hashlib.pbkdf2_hmac('sha256', key.encode('ascii'), salt, args.iterations, 32)
    print(f'LicenseKey("{args.id}", salt = "{salt.hex()}", iterations = {args.iterations}, verifier = "{verifier.hex()}"),')


if __name__ == '__main__':
    main()

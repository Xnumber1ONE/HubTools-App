#!/usr/bin/env python3
"""Creates the signing key for HubTools, once.

Android only installs an APK over an existing app if both are signed with the SAME key.
Run this once, put the key in GitHub (see README), and every future build installs as an update.

Needs:  pip install cryptography
Keep hubtools.p12 and its password somewhere safe. Never upload hubtools.p12 to GitHub.
"""
import base64
import datetime
import getpass
import sys
from pathlib import Path

try:
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives.serialization import pkcs12
    from cryptography.x509.oid import NameOID
except ImportError:
    sys.exit("Run first:  pip install cryptography")

HERE = Path(__file__).resolve().parent


def make(password: str):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "HubTools")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now)
        .not_valid_after(now + datetime.timedelta(days=365 * 30))
        .sign(key, hashes.SHA256())
    )
    return pkcs12.serialize_key_and_certificates(
        b"hubtools", key, cert, None,
        serialization.BestAvailableEncryption(password.encode()),
    )


def main():
    out = HERE / "hubtools.p12"
    if out.exists():
        sys.exit("hubtools.p12 already exists. Delete it only if you really want a new key "
                 "(a new key means one more uninstall).")
    pw = getpass.getpass("Choose a password for the key: ")
    if len(pw) < 6 or pw != getpass.getpass("Type it again: "):
        sys.exit("Passwords must match and be at least 6 characters.")
    data = make(pw)
    out.write_bytes(data)
    b64 = HERE / "hubtools.p12.b64.txt"
    b64.write_text(base64.b64encode(data).decode())
    print("Created hubtools.p12 and hubtools.p12.b64.txt")
    print("Next: put the text of hubtools.p12.b64.txt and your password into GitHub secrets.")


if __name__ == "__main__":
    main()

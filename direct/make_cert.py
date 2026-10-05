#!/usr/bin/env python3
"""
Creates a private certificate authority and a server certificate for the
relay, so the phone can talk HTTPS to the PC's public IP without any
third-party service.

  python make_cert.py <public-ip> [extra-name ...]

Writes into this folder:
  ca.key, ca.crt        - your own CA (install ca.crt on the phone once)
  server.key, server.crt - relay certificate, valid for the given IP/names
Existing ca.* files are reused, so re-running only refreshes the server cert.
"""
import datetime as dt
import ipaddress
import sys
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

HERE = Path(__file__).resolve().parent
NOW = dt.datetime.now(dt.timezone.utc)


def write_key(path: Path, key):
    path.write_bytes(key.private_bytes(serialization.Encoding.PEM,
                                       serialization.PrivateFormat.PKCS8,
                                       serialization.NoEncryption()))


def write_cert(path: Path, cert):
    path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))


def load_or_make_ca():
    key_p, crt_p = HERE / "ca.key", HERE / "ca.crt"
    if key_p.exists() and crt_p.exists():
        key = serialization.load_pem_private_key(key_p.read_bytes(), None)
        return key, x509.load_pem_x509_certificate(crt_p.read_bytes())
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "pc-remote CA")])
    cert = (x509.CertificateBuilder()
            .subject_name(name).issuer_name(name)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(NOW - dt.timedelta(days=1))
            .not_valid_after(NOW + dt.timedelta(days=3650))
            .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
            .add_extension(x509.KeyUsage(digital_signature=True, key_cert_sign=True, crl_sign=True,
                                         content_commitment=False, key_encipherment=False,
                                         data_encipherment=False, key_agreement=False,
                                         encipher_only=False, decipher_only=False), critical=True)
            .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), critical=False)
            .sign(key, hashes.SHA256()))
    write_key(key_p, key)
    write_cert(crt_p, cert)
    print("created new CA:", crt_p)
    return key, cert


def make_server(ca_key, ca_cert, names: list[str]):
    key = ec.generate_private_key(ec.SECP256R1())
    sans = []
    for n in names:
        try:
            sans.append(x509.IPAddress(ipaddress.ip_address(n)))
        except ValueError:
            sans.append(x509.DNSName(n))
    sans.append(x509.IPAddress(ipaddress.ip_address("127.0.0.1")))
    sans.append(x509.DNSName("localhost"))
    cert = (x509.CertificateBuilder()
            .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, names[0])]))
            .issuer_name(ca_cert.subject)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(NOW - dt.timedelta(days=1))
            .not_valid_after(NOW + dt.timedelta(days=820))
            .add_extension(x509.SubjectAlternativeName(sans), critical=False)
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
            .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()), critical=False)
            .sign(ca_key, hashes.SHA256()))
    write_key(HERE / "server.key", key)
    write_cert(HERE / "server.crt", cert)
    print("server certificate for:", ", ".join(names))


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: make_cert.py <public-ip> [extra-name ...]")
    ca_key, ca_cert = load_or_make_ca()
    make_server(ca_key, ca_cert, sys.argv[1:])


if __name__ == "__main__":
    main()

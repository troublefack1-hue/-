"""
APK Signature Scheme v2 in pure Python, so the PC can re-sign the phone apps with a key that
lives only here (%LOCALAPPDATA%\\pc-remote\\apk.key). Android installs an update over the
installed app only when both carry the same signing certificate; with CI builds signed by a
throw-away key that never holds, so the PC becomes the update source instead.

v2 is enough: every app here has minSdk 24, where v2 is supported, so no JAR (v1) signing.
Spec: https://source.android.com/docs/security/features/apksigning/v2
"""
import hashlib
import struct
from datetime import datetime, timedelta, timezone
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.x509.oid import NameOID

SIG_BLOCK_MAGIC = b"APK Sig Block 42"
V2_ID = 0x7109871A
PADDING_ID = 0x42726577
RSA_PKCS1_SHA256 = 0x0103
CHUNK = 1024 * 1024


# ------------------------------------------------------------------ key ---

def ensure_key(folder: Path):
    """(private key, certificate): generated once, reused forever. Keep apk.key safe: it is the app identity."""
    kp, cp = folder / "apk.key", folder / "apk.crt"
    if kp.exists() and cp.exists():
        key = serialization.load_pem_private_key(kp.read_bytes(), None)
        cert = x509.load_pem_x509_certificate(cp.read_bytes())
        return key, cert
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "PC Remote apps"), x509.NameAttribute(NameOID.ORGANIZATION_NAME, "pc-remote")])
    now = datetime.now(timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now - timedelta(days=1))
            .not_valid_after(now + timedelta(days=365 * 40)).sign(key, hashes.SHA256()))
    folder.mkdir(parents=True, exist_ok=True)
    kp.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    cp.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    return key, cert


def cert_sha256(cert) -> str:
    return hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest()


# ------------------------------------------------------------------ zip ---

def _find_eocd(data: bytes) -> int:
    """Offset of the End Of Central Directory record (the comment may be up to 64 KiB)."""
    i = data.rfind(b"PK\x05\x06", max(0, len(data) - 65557))
    if i < 0:
        raise ValueError("not a zip: no end-of-central-directory")
    return i


def split_apk(data: bytes):
    """(zip entries bytes, central directory bytes, eocd bytes) with any existing signing block removed."""
    eocd_off = _find_eocd(data)
    eocd = data[eocd_off:]
    cd_size, cd_off = struct.unpack_from("<II", eocd, 12)
    cd = data[cd_off:cd_off + cd_size]
    contents_end = cd_off
    # an existing APK Signing Block sits right before the central directory
    if cd_off >= 32 and data[cd_off - 16:cd_off] == SIG_BLOCK_MAGIC:
        size = struct.unpack_from("<Q", data, cd_off - 24)[0]
        start = cd_off - 8 - size
        if start >= 0 and struct.unpack_from("<Q", data, start)[0] == size:
            contents_end = start
    return data[:contents_end], cd, eocd


def _chunk_digests(section: bytes) -> list:
    out = []
    for i in range(0, len(section), CHUNK):
        part = section[i:i + CHUNK]
        out.append(hashlib.sha256(b"\xa5" + struct.pack("<I", len(part)) + part).digest())
    return out


def content_digest(contents: bytes, cd: bytes, eocd_for_digest: bytes) -> bytes:
    chunks = _chunk_digests(contents) + _chunk_digests(cd) + _chunk_digests(eocd_for_digest)
    return hashlib.sha256(b"\x5a" + struct.pack("<I", len(chunks)) + b"".join(chunks)).digest()


def _lp(b: bytes) -> bytes:
    return struct.pack("<I", len(b)) + b


def sign(src: Path, dst: Path, key, cert) -> str:
    """Re-sign src into dst (v2 only). Returns the SHA-256 of the written file."""
    data = src.read_bytes()
    contents, cd, eocd = split_apk(data)
    # the signing block will start where the contents end; the digested EOCD points the CD there
    sig_block_offset = len(contents)
    eocd_digest = bytearray(eocd)
    struct.pack_into("<I", eocd_digest, 16, sig_block_offset)
    digest = content_digest(contents, cd, bytes(eocd_digest))

    cert_der = cert.public_bytes(serialization.Encoding.DER)
    spki = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    digests = _lp(_lp(struct.pack("<I", RSA_PKCS1_SHA256) + _lp(digest)))
    certs = _lp(_lp(cert_der))
    attrs = _lp(b"")
    signed_data = digests + certs + attrs
    signature = key.sign(signed_data, padding.PKCS1v15(), hashes.SHA256())
    signatures = _lp(_lp(struct.pack("<I", RSA_PKCS1_SHA256) + _lp(signature)))
    signer = _lp(signed_data) + signatures + _lp(spki)
    v2_value = _lp(_lp(signer))
    pairs = struct.pack("<QI", 4 + len(v2_value), V2_ID) + v2_value
    # pad so the whole block is a multiple of 4096 bytes (what apksigner does; keeps mmap-friendly alignment)
    total = 8 + len(pairs) + 8 + 16
    pad = (-(total + 12)) % 4096
    if pad or True:
        pairs += struct.pack("<QI", 4 + pad, PADDING_ID) + b"\0" * pad
    size = len(pairs) + 8 + 16
    block = struct.pack("<Q", size) + pairs + struct.pack("<Q", size) + SIG_BLOCK_MAGIC

    new_eocd = bytearray(eocd)
    struct.pack_into("<I", new_eocd, 16, sig_block_offset + len(block))
    out = contents + block + cd + bytes(new_eocd)
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_bytes(out)
    return hashlib.sha256(out).hexdigest()


def verify(path: Path) -> dict:
    """Our own check of a signed APK: digest matches, signature verifies. Returns {"cert_sha256", "ok"}."""
    data = path.read_bytes()
    contents, cd, eocd = split_apk(data)
    eocd_off = _find_eocd(data)
    cd_off = struct.unpack_from("<I", data, eocd_off + 16)[0]
    # locate the block again
    size = struct.unpack_from("<Q", data, cd_off - 24)[0]
    start = cd_off - 8 - size
    pairs = data[start + 8:cd_off - 24]
    v2 = None
    i = 0
    while i < len(pairs):
        ln, pid = struct.unpack_from("<QI", pairs, i)
        if pid == V2_ID:
            v2 = pairs[i + 12:i + 8 + ln]
        i += 8 + ln
    if v2 is None:
        return {"ok": False, "error": "no v2 block"}
    signer = v2[8:8 + struct.unpack_from("<I", v2, 4)[0]]
    sd_len = struct.unpack_from("<I", signer, 0)[0]
    signed_data = signer[4:4 + sd_len]
    p = 4 + sd_len
    sigs_len = struct.unpack_from("<I", signer, p)[0]
    sig_el = signer[p + 8:p + 8 + struct.unpack_from("<I", signer, p + 4)[0]]
    alg, = struct.unpack_from("<I", sig_el, 0)
    sig = sig_el[8:8 + struct.unpack_from("<I", sig_el, 4)[0]]
    p += 4 + sigs_len
    spki = signer[p + 4:p + 4 + struct.unpack_from("<I", signer, p)[0]]
    pub = serialization.load_der_public_key(spki)
    pub.verify(sig, signed_data, padding.PKCS1v15(), hashes.SHA256())
    # digest in signed data vs recomputed
    d_el = signed_data[8:8 + struct.unpack_from("<I", signed_data, 4)[0]]
    stored = d_el[8:8 + struct.unpack_from("<I", d_el, 4)[0]]
    eocd_digest = bytearray(eocd)
    struct.pack_into("<I", eocd_digest, 16, start)
    ok = stored == content_digest(contents, cd, bytes(eocd_digest)) and alg == RSA_PKCS1_SHA256
    certs_off = 4 + struct.unpack_from("<I", signed_data, 0)[0]
    cert_der = signed_data[certs_off + 8:certs_off + 8 + struct.unpack_from("<I", signed_data, certs_off + 4)[0]]
    return {"ok": ok, "cert_sha256": hashlib.sha256(cert_der).hexdigest()}

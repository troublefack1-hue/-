"""
Which Windows output device the PC plays through — and switching it for the phone.

WASAPI loopback hears what reaches the output endpoint *after* the master volume, so a
muted PC sends silence to the phone. The way around it without extra software: while the
phone listens, make a silent endpoint (the monitor's HDMI, S/PDIF, a headphone jack with
nothing in it) the default output and capture from there. The speakers stay quiet, the
phone hears everything at full volume. Restored when the phone stops listening.

Uses the undocumented-but-stable IPolicyConfig COM interface (what every "switch audio
device" tray tool uses). Everything here is Windows-only and fails soft.
"""
import logging

log = logging.getLogger("agent.audio_out")

_ROLES = (0, 1, 2)   # eConsole, eMultimedia, eCommunications


def list_render_devices() -> list:
    """Active playback endpoints: [{"id", "name", "default": bool}]."""
    try:
        from pycaw.pycaw import AudioUtilities
        from pycaw.constants import EDataFlow, DEVICE_STATE
        enum = AudioUtilities.GetDeviceEnumerator()
        coll = enum.EnumAudioEndpoints(EDataFlow.eRender.value, DEVICE_STATE.ACTIVE.value)
        cur = default_id()
        out = []
        for i in range(coll.GetCount()):
            dev = AudioUtilities.CreateDevice(coll.Item(i))
            name = dev.FriendlyName or dev.id
            out.append({"id": dev.id, "name": name, "default": dev.id == cur})
        return out
    except Exception as e:  # noqa: BLE001
        log.info("audio devices unavailable: %s", e)
        return []


def default_id() -> str:
    try:
        from pycaw.pycaw import AudioUtilities
        return AudioUtilities.GetSpeakers().GetId()
    except Exception:  # noqa: BLE001
        return ""


def device_name(dev_id: str) -> str:
    for d in list_render_devices():
        if d["id"] == dev_id:
            return d["name"]
    return ""


def _policy():
    from ctypes import HRESULT, POINTER, c_int, c_void_p, c_wchar_p
    import comtypes
    from comtypes import COMMETHOD, GUID, IUnknown

    class IPolicyConfig(IUnknown):
        _iid_ = GUID("{f8679f50-850a-41e1-895d-fc5a0b3f0d7d}")
        # only the vtable order matters: we call SetDefaultEndpoint (slot 10), the rest are placeholders
        _methods_ = [
            COMMETHOD([], HRESULT, "GetMixFormat", (["in"], c_wchar_p), (["out"], POINTER(c_void_p))),
            COMMETHOD([], HRESULT, "GetDeviceFormat", (["in"], c_wchar_p), (["in"], c_int), (["out"], POINTER(c_void_p))),
            COMMETHOD([], HRESULT, "ResetDeviceFormat", (["in"], c_wchar_p)),
            COMMETHOD([], HRESULT, "SetDeviceFormat", (["in"], c_wchar_p), (["in"], c_void_p), (["in"], c_void_p)),
            COMMETHOD([], HRESULT, "GetProcessingPeriod", (["in"], c_wchar_p), (["in"], c_int), (["out"], POINTER(c_void_p)), (["out"], POINTER(c_void_p))),
            COMMETHOD([], HRESULT, "SetProcessingPeriod", (["in"], c_wchar_p), (["in"], c_void_p)),
            COMMETHOD([], HRESULT, "GetShareMode", (["in"], c_wchar_p), (["out"], POINTER(c_void_p))),
            COMMETHOD([], HRESULT, "SetShareMode", (["in"], c_wchar_p), (["in"], c_void_p)),
            COMMETHOD([], HRESULT, "GetPropertyValue", (["in"], c_wchar_p), (["in"], c_int), (["in"], c_void_p), (["out"], POINTER(c_void_p))),
            COMMETHOD([], HRESULT, "SetPropertyValue", (["in"], c_wchar_p), (["in"], c_int), (["in"], c_void_p), (["in"], c_void_p)),
            COMMETHOD([], HRESULT, "SetDefaultEndpoint", (["in"], c_wchar_p), (["in"], c_int)),
            COMMETHOD([], HRESULT, "SetEndpointVisibility", (["in"], c_wchar_p), (["in"], c_int)),
        ]

    clsid = GUID("{870af99c-171d-4f9e-af0d-e63df40c2bc9}")   # CPolicyConfigClient
    return comtypes.CoCreateInstance(clsid, IPolicyConfig, comtypes.CLSCTX_ALL)


def set_default(dev_id: str) -> bool:
    """Make dev_id the default playback device for every role. False if Windows refused."""
    if not dev_id:
        return False
    try:
        pc = _policy()
        for role in _ROLES:
            pc.SetDefaultEndpoint(dev_id, role)
        log.info("default output -> %s", dev_id)
        return True
    except Exception as e:  # noqa: BLE001
        log.warning("switch output device: %s", e)
        return False

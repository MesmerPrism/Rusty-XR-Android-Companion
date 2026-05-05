#!/usr/bin/env python3
"""
Quest Companion phone ADB diagnostics bundle analyzer.

Reads a diagnostics bundle (ZIP) exported from the Android Phone Quest
Companion app and produces:
  - A human-readable Markdown report.
  - A machine-readable JSON summary.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Dict, List
from zipfile import ZipFile


FAILURE_GUIDANCE: Dict[str, str] = {
    "NoUsbExposure": "Android is not exposing any USB host device. Confirm the Quest is awake, the cable supports data, and the phone is on the USB host side. If direct USB-C only charges, try a phone-side OTG adapter or powered hub.",
    "NoAdbInterface": "A USB device is visible but does not expose an ADB interface. Enable USB debugging on the Quest.",
    "UsbPermissionDenied": "The user declined the USB permission dialog. Retry and accept the allow-USB-access prompt.",
    "UsbPermissionTimeout": "The USB permission handoff did not finish in time. Bring the phone app back to the foreground and retry.",
    "AdbAuthTimeout": "The ADB handshake timed out. The Quest USB debugging authorization flow still did not complete.",
    "AdbAuthRejected": "The Quest rejected the ADB authorization. Revoke Quest USB debugging authorizations and retry.",
    "ShellStreamTimeout": "The ADB shell stream never returned data. The Quest may still be in file-transfer mode instead of an authorized debugging session.",
    "ShellStreamRejected": "The Quest rejected the shell stream open. Confirm USB debugging is enabled and authorized.",
    "TcpipCommandFailed": "The 'tcpip:5555' command failed over USB. The Quest may need a reboot or a clean ADB session.",
    "EndpointParseFailure": "Could not extract a usable IP from the Quest route output. Check that the Quest has an active Wi-Fi connection.",
    "SubnetMismatch": "The Quest endpoint is not on the same IPv4 subnet as the phone. Move the phone onto the same network or enable the hotspot flow.",
    "TcpConnectRefused": "TCP connect to the Quest Wi-Fi ADB port was refused. Verify the Quest is still in tcpip mode and the IP is correct.",
    "TcpConnectTimeout": "TCP connect timed out. The Quest may be asleep, on a different network, or no longer listening on the Wi-Fi ADB port.",
    "TcpAdbHandshakeTimeout": "TCP socket connected but the ADB handshake timed out. Retry the USB bootstrap and confirm the Quest stays awake.",
    "UnexpectedError": "An unexpected error occurred. Inspect the raw event trace for the exception message.",
}


def read_bundle(zip_path: Path) -> Dict[str, Any]:
    bundle: Dict[str, Any] = {}
    with ZipFile(zip_path, "r") as zf:
        names = zf.namelist()
        for key in ("diagnostics_manifest.json", "diagnostics_trace.json", "ui_summary.json"):
            bundle[key] = json.loads(zf.read(key).decode("utf-8")) if key in names else {}
    return bundle


def analyze(bundle: Dict[str, Any]) -> Dict[str, Any]:
    manifest = bundle.get("diagnostics_manifest.json", {})
    trace = bundle.get("diagnostics_trace.json", [])
    ui = bundle.get("ui_summary.json", {})

    timeline: List[Dict[str, Any]] = [
        {
            "stage": event.get("stage", "?"),
            "success": event.get("success", False),
            "message": event.get("message", ""),
            "detail": event.get("detail", ""),
            "relativeMs": event.get("timestampMs", 0) - manifest.get("startMs", 0),
        }
        for event in trace
    ]

    succeeded: List[str] = []
    failed: List[str] = []
    for event in trace:
        stage = event.get("stage", "?")
        if event.get("success"):
            if stage not in succeeded:
                succeeded.append(stage)
        elif stage not in failed:
            failed.append(stage)

    failure_class = manifest.get("failureClass", "None")
    return {
        "failureClass": failure_class,
        "stageReached": manifest.get("stageReached"),
        "lastSuccessfulStage": manifest.get("lastSuccessfulStage"),
        "blockingStage": failed[0] if failed else None,
        "succeededStages": succeeded,
        "failedStages": failed,
        "guidance": FAILURE_GUIDANCE.get(failure_class, ""),
        "durationMs": manifest.get("durationMs"),
        "eventCount": len(trace),
        "timeline": timeline,
        "device": manifest.get("device", {}),
        "app": manifest.get("app", {}),
        "ui": {
            "connectionSummary": ui.get("connectionSummary", ""),
            "usbSummary": ui.get("usbSummary", ""),
            "phoneNetworkSummary": ui.get("phoneNetworkSummary", ""),
            "activeEndpoint": ui.get("activeEndpoint"),
        },
    }


def generate_markdown(analysis: Dict[str, Any], bundle_name: str) -> str:
    lines: List[str] = []
    lines.append("# Quest Companion Phone Diagnostics Report")
    lines.append("")
    lines.append(f"**Bundle:** `{bundle_name}`")
    lines.append("")
    result = "SUCCESS" if analysis["failureClass"] == "None" else f"FAILURE — `{analysis['failureClass']}`"
    lines.append(f"**Result:** {result}")
    lines.append("")
    if analysis.get("durationMs") is not None:
        lines.append(f"**Duration:** {analysis['durationMs']} ms")
    if analysis.get("stageReached"):
        lines.append(f"**Last stage reached:** `{analysis['stageReached']}`")
    if analysis.get("lastSuccessfulStage"):
        lines.append(f"**Last successful stage:** `{analysis['lastSuccessfulStage']}`")
    if analysis.get("blockingStage"):
        lines.append(f"**Blocking stage:** `{analysis['blockingStage']}`")
    lines.append(f"**Events recorded:** {analysis['eventCount']}")
    lines.append("")
    if analysis.get("guidance"):
        lines.append("## Suggested Next Action")
        lines.append("")
        lines.append(analysis["guidance"])
        lines.append("")
    lines.append("## Event Timeline")
    lines.append("")
    lines.append("| Δms | Stage | OK | Message |")
    lines.append("|---|---|---|---|")
    for event in analysis["timeline"]:
        ok = "✓" if event["success"] else "✗"
        detail = f" ({event['detail'][:80]})" if event["detail"] else ""
        lines.append(f"| {event['relativeMs']} | `{event['stage']}` | {ok} | {event['message']}{detail} |")
    lines.append("")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description="Analyze a Quest Companion diagnostics bundle.")
    parser.add_argument("bundle", type=Path, help="Path to the diagnostics ZIP bundle.")
    parser.add_argument("--output-dir", type=Path, default=None, help="Directory for output files. Defaults to the bundle directory.")
    args = parser.parse_args()

    bundle_path = args.bundle
    if not bundle_path.exists():
        print(f"ERROR: Bundle not found: {bundle_path}", file=sys.stderr)
        sys.exit(1)

    output_dir = args.output_dir or bundle_path.parent
    output_dir.mkdir(parents=True, exist_ok=True)

    analysis = analyze(read_bundle(bundle_path))
    stem = bundle_path.stem
    json_path = output_dir / f"{stem}_analysis.json"
    md_path = output_dir / f"{stem}_report.md"
    json_path.write_text(json.dumps(analysis, indent=2), encoding="utf-8")
    md_path.write_text(generate_markdown(analysis, bundle_path.name), encoding="utf-8")
    print(f"Wrote JSON summary: {json_path}")
    print(f"Wrote Markdown report: {md_path}")


if __name__ == "__main__":
    main()

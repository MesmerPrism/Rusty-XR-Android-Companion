[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $DeviceSerial,

    [string] $Adb = $(if ($env:RUSTY_XR_ADB) { $env:RUSTY_XR_ADB } elseif ($env:DOPE_ADB_EXE) { $env:DOPE_ADB_EXE } else { "adb" }),

    [string] $PackageId = "io.github.mesmerprism.rustyxr.companion.android",

    [ValidateSet("sender", "receiver", "duplex", "two-way")]
    [string] $Mode = "sender",

    [string] $RelayHost = "127.0.0.1",

    [ValidateRange(1, 65535)]
    [int] $RelayPort = 9443,

    [string] $RelayToken = "",

    [switch] $RelayTls,

    [switch] $RelayInsecureTls,

    [string] $RelayServerName = "",

    [ValidateSet("media", "control")]
    [string] $RelayChannel = "media",

    [string] $SessionId = "",

    [string] $SendSessionId = "",

    [string] $ReceiveSessionId = "",

    [string] $Eyes = "left,right",

    [ValidateRange(0, 21600)]
    [int] $SessionDurationS = 0,

    [ValidateRange(-1, 21600000)]
    [int] $DurationMs = -1,

    [ValidateRange(-1, 21600000)]
    [int] $ConnectTimeoutMs = -1,

    [ValidateSet("synthetic_surface", "camera2_surface")]
    [string] $SourceMode = "synthetic_surface",

    [string] $QualityProfile = "camera-native-max",

    [ValidateRange(0, 4096)]
    [int] $Width = 1280,

    [ValidateRange(0, 4096)]
    [int] $Height = 1280,

    [ValidateRange(100000, 50000000)]
    [int] $BitrateBps = 20000000,

    [ValidateRange(1, 60)]
    [int] $FrameRateHz = 60,

    [string] $CameraId = "",

    [ValidateSet("back", "front", "external")]
    [string] $CameraFacing = "back",

    [switch] $SeparateCameraPerEye,

    [ValidateSet("legacy", "manifold", "RXYRVID1", "RMANVID1")]
    [string] $StreamMagic = "legacy",

    [switch] $DisplayReceiver,

    [ValidateSet("left", "right", "mono")]
    [string] $DisplayEye = "left",

    [switch] $AllowDevSession,

    [string] $Label = ""
)

Set-StrictMode -Version 3.0
$ErrorActionPreference = "Stop"

if (-not $SessionId -and -not $SendSessionId -and -not $ReceiveSessionId) {
    $SessionId = "phone-q2q-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
}

$component = "$PackageId/.agent.AgentCommandActivity"
$args = @(
    "-s", $DeviceSerial,
    "shell", "am", "start",
    "-a", "$PackageId.RUN_AGENT_COMMAND",
    "-n", $component,
    "--es", "command", "q2q-relay",
    "--es", "q2q_mode", $Mode,
    "--es", "relay_host", $RelayHost,
    "--ei", "relay_port", "$RelayPort",
    "--es", "relay_channel", $RelayChannel,
    "--es", "eyes", $Eyes,
    "--el", "session_duration_s", "$SessionDurationS",
    "--es", "source_mode", $SourceMode,
    "--es", "quality_profile", $QualityProfile,
    "--ei", "width", "$Width",
    "--ei", "height", "$Height",
    "--ei", "bitrate_bps", "$BitrateBps",
    "--ei", "frame_rate_hz", "$FrameRateHz",
    "--es", "camera_facing", $CameraFacing,
    "--ez", "same_camera_to_eyes", "$(-not $SeparateCameraPerEye)".ToLowerInvariant(),
    "--es", "stream_magic", $StreamMagic,
    "--ez", "display_receiver", "$([bool] $DisplayReceiver)".ToLowerInvariant(),
    "--es", "display_eye", $DisplayEye,
    "--ez", "allow_dev_session", "$([bool] $AllowDevSession)".ToLowerInvariant()
)

if ($RelayToken) {
    $args += @("--es", "relay_token", $RelayToken)
}
if ($RelayTls) {
    $args += @("--ez", "relay_tls", "true")
}
if ($RelayInsecureTls) {
    $args += @("--ez", "relay_insecure_tls", "true")
}
if ($RelayServerName) {
    $args += @("--es", "relay_server_name", $RelayServerName)
}
if ($DurationMs -ge 0) {
    $args += @("--el", "duration_ms", "$DurationMs")
}
if ($ConnectTimeoutMs -ge 0) {
    $args += @("--el", "connect_timeout_ms", "$ConnectTimeoutMs")
}
if ($SessionId) {
    $args += @("--es", "session_id", $SessionId)
}
if ($SendSessionId) {
    $args += @("--es", "send_session_id", $SendSessionId)
}
if ($ReceiveSessionId) {
    $args += @("--es", "receive_session_id", $ReceiveSessionId)
}
if ($CameraId) {
    $args += @("--es", "camera_id", $CameraId)
}
if ($Label) {
    $args += @("--es", "label", $Label)
}

& $Adb @args

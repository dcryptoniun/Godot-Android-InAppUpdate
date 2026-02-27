## GDScript wrapper for the GodotAndroidInAppUpdate Android plugin.
##
## Provides a clean, signal-based API for checking and applying Google Play
## in-app updates from Godot. Supports both Flexible and Immediate update flows.
##
## Usage:
##   1. Add this script as an AutoLoad singleton named "InAppUpdate".
##   2. Connect to signals:
##      - update_info_received
##      - update_check_failed
##      - update_status_changed
##      - update_flow_result
##   3. Call check_for_update() to begin.
class_name InAppUpdate
extends Node

## Emitted when update info is successfully retrieved.
signal update_info_received(
	is_available: bool,
	is_flexible_allowed: bool,
	is_immediate_allowed: bool,
	available_version_code: int,
	staleness_days: int,
	update_priority: int
)

## Emitted when the update check fails.
signal update_check_failed(error_message: String)

## Emitted when the install status changes during a flexible update.
signal update_status_changed(
	status: int,
	bytes_downloaded: int,
	total_bytes_to_download: int
)

## Emitted when the user accepts or cancels the update flow dialog.
signal update_flow_result(result_code: int)

## Install status constants (matching the Kotlin plugin).
enum InstallStatus {
	UNKNOWN = 0,
	PENDING = 1,
	DOWNLOADING = 2,
	DOWNLOADED = 3,
	INSTALLING = 4,
	INSTALLED = 5,
	FAILED = 6,
	CANCELED = 7,
}

## Activity result codes.
const RESULT_OK: int = -1
const RESULT_CANCELED: int = 0

## Plugin singleton name.
const PLUGIN_NAME: String = "GodotAndroidInAppUpdate"

var _plugin: Object = null
var _is_available: bool = false


func _ready() -> void:
	if Engine.has_singleton(PLUGIN_NAME):
		_plugin = Engine.get_singleton(PLUGIN_NAME)
		_plugin.connect("update_info_received", _on_update_info_received)
		_plugin.connect("update_check_failed", _on_update_check_failed)
		_plugin.connect("update_status_changed", _on_update_status_changed)
		_plugin.connect("update_flow_result", _on_update_flow_result)
		print("[InAppUpdate] Plugin loaded successfully.")
	else:
		printerr("[InAppUpdate] Plugin '%s' not found. In-app updates are only available on Android." % PLUGIN_NAME)


## Returns true if the plugin is available (running on Android with the plugin installed).
func is_plugin_available() -> bool:
	return _plugin != null


## Check if an update is available on Google Play.
## Emits update_info_received on success, update_check_failed on failure.
func check_for_update() -> void:
	if _plugin == null:
		update_check_failed.emit("Plugin not available. Not running on Android?")
		return
	_plugin.checkUpdateAvailable()


## Start a Flexible update flow. The update downloads in the background.
## Listen for update_status_changed with status == DOWNLOADED, then call
## complete_flexible_update() to trigger the app restart.
func start_flexible_update() -> void:
	if _plugin == null:
		update_check_failed.emit("Plugin not available.")
		return
	_plugin.startFlexibleUpdate()


## Start an Immediate update flow. Google Play takes over the screen.
## The app restarts automatically after the update completes.
func start_immediate_update() -> void:
	if _plugin == null:
		update_check_failed.emit("Plugin not available.")
		return
	_plugin.startImmediateUpdate()


## Complete a flexible update after download finishes. Triggers app restart.
func complete_flexible_update() -> void:
	if _plugin == null:
		update_check_failed.emit("Plugin not available.")
		return
	_plugin.completeFlexibleUpdate()


## Returns a human-readable name for the given install status.
static func get_status_name(status: int) -> String:
	match status:
		InstallStatus.UNKNOWN:
			return "Unknown"
		InstallStatus.PENDING:
			return "Pending"
		InstallStatus.DOWNLOADING:
			return "Downloading"
		InstallStatus.DOWNLOADED:
			return "Downloaded"
		InstallStatus.INSTALLING:
			return "Installing"
		InstallStatus.INSTALLED:
			return "Installed"
		InstallStatus.FAILED:
			return "Failed"
		InstallStatus.CANCELED:
			return "Canceled"
		_:
			return "Unknown (%d)" % status


# ── Signal handlers from the native plugin ──────────────────────────────

func _on_update_info_received(
		is_avail: bool,
		is_flex: bool,
		is_imm: bool,
		version_code: int,
		staleness: int,
		priority: int
) -> void:
	_is_available = is_avail
	update_info_received.emit(is_avail, is_flex, is_imm, version_code, staleness, priority)


func _on_update_check_failed(error_msg: String) -> void:
	_is_available = false
	update_check_failed.emit(error_msg)


func _on_update_status_changed(status: int, downloaded: int, total: int) -> void:
	update_status_changed.emit(status, downloaded, total)


func _on_update_flow_result(result_code: int) -> void:
	update_flow_result.emit(result_code)

extends Control


@onready var status_label: Label = %StatusLabel
@onready var progress_bar: ProgressBar = %ProgressBar
@onready var check_btn: Button = %CheckButton
@onready var flexible_btn: Button = %FlexibleButton
@onready var immediate_btn: Button = %ImmediateButton
@onready var complete_btn: Button = %CompleteButton
@onready var log_label: RichTextLabel = %LogLabel

const PLUGIN_NAME: String = "GodotAndroidInAppUpdate"

var _plugin: Object = null
var _update_downloaded: bool = false


func _ready() -> void:
	_set_status("Initializing...")
	progress_bar.visible = false
	complete_btn.disabled = true
	flexible_btn.disabled = true
	immediate_btn.disabled = true

	if Engine.has_singleton(PLUGIN_NAME):
		_plugin = Engine.get_singleton(PLUGIN_NAME)
		_plugin.connect("update_info_received", _on_update_info_received)
		_plugin.connect("update_check_failed", _on_update_check_failed)
		_plugin.connect("update_status_changed", _on_update_status_changed)
		_plugin.connect("update_flow_result", _on_update_flow_result)
		_set_status("Plugin loaded. Tap 'Check for Update'.")
		_log("Plugin singleton found.")
	else:
		_set_status("Plugin NOT found (desktop mode).")
		_log("[color=yellow]Warning:[/color] Plugin '%s' not available. In-app updates only work on Android." % PLUGIN_NAME)
		check_btn.disabled = true


func _on_check_pressed() -> void:
	if _plugin == null:
		return
	_set_status("Checking for update...")
	_log("Calling checkUpdateAvailable()...")
	_plugin.checkUpdateAvailable()


func _on_flexible_pressed() -> void:
	if _plugin == null:
		return
	_set_status("Starting flexible update...")
	_log("Calling startFlexibleUpdate()...")
	progress_bar.visible = true
	progress_bar.value = 0
	_plugin.startFlexibleUpdate()


func _on_immediate_pressed() -> void:
	if _plugin == null:
		return
	_set_status("Starting immediate update...")
	_log("Calling startImmediateUpdate()...")
	_plugin.startImmediateUpdate()


func _on_complete_pressed() -> void:
	if _plugin == null:
		return
	_set_status("Installing update & restarting...")
	_log("Calling completeFlexibleUpdate()...")
	_plugin.completeFlexibleUpdate()


# ── Signal callbacks ─────────────────────────────────────────────────────

func _on_update_info_received(
		is_available: bool,
		is_flexible: bool,
		is_immediate: bool,
		version_code: int,
		staleness_days: int,
		priority: int
) -> void:
	_log("Update info received:")
	_log("  Available: %s" % str(is_available))
	_log("  Flexible allowed: %s" % str(is_flexible))
	_log("  Immediate allowed: %s" % str(is_immediate))
	_log("  Version code: %d" % version_code)
	_log("  Staleness days: %d" % staleness_days)
	_log("  Priority: %d" % priority)

	if is_available:
		_set_status("Update available! (v%d)" % version_code)
		flexible_btn.disabled = not is_flexible
		immediate_btn.disabled = not is_immediate
	else:
		_set_status("App is up to date.")
		flexible_btn.disabled = true
		immediate_btn.disabled = true


func _on_update_check_failed(error_message: String) -> void:
	_set_status("Check failed!")
	_log("[color=red]Error:[/color] %s" % error_message)
	flexible_btn.disabled = true
	immediate_btn.disabled = true


func _on_update_status_changed(status: int, bytes_downloaded: int, total_bytes: int) -> void:
	var status_name: String = _get_status_name(status)
	_log("Status: %s (%d/%d bytes)" % [status_name, bytes_downloaded, total_bytes])

	match status:
		2: # DOWNLOADING
			progress_bar.visible = true
			if total_bytes > 0:
				progress_bar.value = (float(bytes_downloaded) / float(total_bytes)) * 100.0
			_set_status("Downloading... %d%%" % int(progress_bar.value))
		3: # DOWNLOADED
			progress_bar.visible = false
			_update_downloaded = true
			complete_btn.disabled = false
			_set_status("Update downloaded! Tap 'Complete Update'.")
			_log("[color=green]Download complete![/color] Tap 'Complete Update' to install.")
		5: # INSTALLED
			_set_status("Update installed!")
			_log("[color=green]Update installed successfully.[/color]")
		6: # FAILED
			progress_bar.visible = false
			_set_status("Update failed!")
			_log("[color=red]Update installation failed.[/color]")
		7: # CANCELED
			progress_bar.visible = false
			_set_status("Update canceled.")
			_log("[color=yellow]Update was canceled.[/color]")


func _on_update_flow_result(result_code: int) -> void:
	match result_code:
		-1: # RESULT_OK
			_log("User accepted the update flow.")
		0: # RESULT_CANCELED
			_log("[color=yellow]User canceled the update flow.[/color]")
			_set_status("Update canceled by user.")
		_:
			_log("[color=red]Update flow returned code: %d[/color]" % result_code)


# ── Helpers ──────────────────────────────────────────────────────────────

func _set_status(text: String) -> void:
	status_label.text = text


func _log(text: String) -> void:
	log_label.append_text(text + "\n")


func _get_status_name(status: int) -> String:
	match status:
		0: return "Unknown"
		1: return "Pending"
		2: return "Downloading"
		3: return "Downloaded"
		4: return "Installing"
		5: return "Installed"
		6: return "Failed"
		7: return "Canceled"
		_: return "Unknown(%d)" % status

package com.andharness.ru.domain.tools

/**
 * English (EN) tool-schema STRINGS, generated from the canonical EN catalog
 * `data/coverage/tools_meta.en.json` (529 entries, same names and order as the RU catalog).
 *
 * Only DESCRIPTION text lives here: [descriptions] is the tool-level EN description and
 * [paramDescriptions] is, per tool, the EN description of each declared parameter. The
 * parameter TYPES and `required` arrays are NOT stored here — they are taken from the existing
 * RU [Tool.parameters] (see [com.andharness.ru.data.tools.ToolRegistryImpl]), so the EN schema
 * differs from the RU one ONLY in description strings.
 *
 * Why a generated Kotlin object instead of a `res/raw` JSON asset: the registry has no injected
 * `Context` and the JVM unit tests run with `testOptions.unitTests.isReturnDefaultValues = true`
 * (no Robolectric), so a pure in-memory map is directly testable with no Android dependency and
 * avoids any runtime IO on the main thread.
 *
 * Docs: Kotlin string escapes (why `$`, `\` and control chars are escaped below) —
 * https://kotlinlang.org/docs/strings.html#escaped-strings
 */
object EnToolStrings {

    /** Tool name -> EN tool description. */
    val descriptions: Map<String, String> =
        descriptions0() +
        descriptions1() +
        descriptions2() +
        descriptions3() +
        descriptions4() +
        descriptions5() +
        descriptions6() +
        descriptions7() +
        descriptions8() +
        descriptions9() +
        descriptions10()

    /** Tool name -> (parameter name -> EN parameter description). */
    val paramDescriptions: Map<String, Map<String, String>> =
        params0() +
        params1() +
        params2() +
        params3() +
        params4() +
        params5() +
        params6() +
        params7() +
        params8() +
        params9() +
        params10()

    private fun descriptions0(): Map<String, String> = mapOf(
        "files_list" to "List files and subdirectories in the specified directory. Pass an existing path to read the folder contents. Path is in shared storage (/storage/emulated/0).",
        "files_read" to "Read the contents of a text file at the given path. Use it to read logs, configs, and any textual content. Path is in shared storage (/storage/emulated/0).",
        "files_write" to "Write content to a file (creates the file if it does not exist). Fully overwrites the existing file. Irreversible, without confirmation — only on the user's direct request. Path is in shared storage (/storage/emulated/0).",
        "files_create_dir" to "Create a directory (including intermediate folders) if it does not exist. Path is in shared storage (/storage/emulated/0).",
        "files_delete" to "Delete one or more files/directories. For a folder, all of its contents and subfolders are deleted (recursively). Irreversible, without confirmation — only on the user's direct request. Path is in shared storage (/storage/emulated/0). Multiple paths can be listed in one call, separated by commas or semicolons (in path or paths).",
        "files_move" to "Move a file or directory from source to destination. Path is in shared storage (/storage/emulated/0).",
        "files_rename" to "Rename a file or folder within the same directory. Path is in shared storage (/storage/emulated/0).",
        "files_copy" to "Copy a file or directory from source to destination. destination is the target path (file or folder). Path is in shared storage (/storage/emulated/0).",
        "files_stat" to "Get information about a file or directory: size, modification date, whether it is a directory. Useful before operations on a file. Path is in shared storage (/storage/emulated/0).",
        "files_dir_size" to "Calculate the total size of files in a folder and show the free space on the volume.",
        "files_open" to "Open a file with a suitable app WITHOUT the system chooser: the app is selected automatically (saved choice for this type → system default → best match), optionally specified via the app package. If no app can open such a type, it returns an honest message and a list of apps from Google Play. For a folder, it opens the file manager. Path is in shared storage (/storage/emulated/0).",
        "files_append" to "Append text to the end of a file (creates the file if it does not exist). Without confirmation — only on the user's direct request. Path is in shared storage (/storage/emulated/0).",
        "files_search" to "Find files and folders by name (substring, case-insensitive) starting from the path folder. Returns paths.",
        "files_grep" to "Find lines containing a substring in text files (content search).",
        "files_hash" to "Calculate a file hash (MD5, SHA-1, or SHA-256) to verify integrity/compare.",
        "files_duplicates" to "Find duplicate files by size and hash (SHA-256).",
        "files_zip" to "Pack files/folders into a ZIP archive. sources — paths separated by commas.",
        "files_unzip" to "Extract a ZIP archive into the destination folder.",
        "files_zip_list" to "Show the contents of a ZIP archive without extracting.",
        "files_gzip" to "Compress a file into GZIP.",
        "files_gunzip" to "Extract a GZIP file.",
        "share_file" to "Share a file via the system Share menu (send it to another app).",
        "share_files" to "Share multiple files via the system menu.",
        "print_file" to "Send a file to print via the system printing service.",
        "files_split" to "Split a file into parts: equal-size (parts — number of parts) or by part size (part_size, e.g. 100MB). Parts are named «name.partNN» and saved into destination_dir (by default the source file's folder). The source file is not modified; existing parts are not overwritten. Path is in shared storage (/storage/emulated/0).",
        "files_join" to "Join «name.partNN» parts back into one file. Give parts (part paths separated by commas) or source (any one part — the rest are found automatically and ordered by number). Verifies that every part exists; optionally checks expected_size. An existing destination is not overwritten. Path is in shared storage (/storage/emulated/0).",
        "files_mime" to "Detect a file's MIME type from its signature (magic bytes) and extension. Returns the type, a confidence level (high/medium/low), the extension and the evidence. Path is in shared storage (/storage/emulated/0).",
        "files_compare" to "Compare two files byte by byte: whether they are identical, otherwise their sizes and the offset of the first difference; for text files, a short line-by-line diff (bounded number of lines). Path is in shared storage (/storage/emulated/0).",
        "files_hide" to "Hide or show a file/folder in MediaStore. A folder is hidden by creating an empty «.nomedia» (with recursive — in every subfolder), a file by adding/removing a leading dot in its name. Acts only on what the app is allowed to change; honestly reports scoped-storage limits. Path is in shared storage (/storage/emulated/0).",
        "files_chmod" to "Change a file's or folder's permissions (read/write/execute) — best effort. Returns the actual result for each flag. On Android under scoped storage you can usually change permissions only of your own files; for other apps' files it reports a refusal (a refusal is never dressed up as success). Path is in shared storage (/storage/emulated/0).",
        "files_trash" to "Move a file or folder to the app's trash (can be restored). Without confirmation — only on the user's direct request. Path is in shared storage (/storage/emulated/0).",
        "files_trash_list" to "Show the contents of the app's trash.",
        "files_trash_restore" to "Restore a file/folder from the app's trash.",
        "files_trash_empty" to "Completely empty the app's trash (with no possibility of recovery). Irreversible, without confirmation — only on the user's direct request.",
        "files_tar_create" to "Pack files/folders into a TAR archive (optionally with GZIP compression).",
        "files_tar_extract" to "Extract a TAR / TAR.GZ archive.",
        "files_7z_extract" to "Extract a 7z archive.",
        "files_7z_create" to "Pack files/folders into a 7z archive.",
        "files_rar_extract" to "Extract a RAR archive (read-only).",
        "http_request" to "Perform an HTTP request with the specified method, URL, headers, and body. For any network requests to APIs or web pages.",
        "download_file" to "Download a file by URL to the device. Use it when you need to save a resource from the internet. By default the file is saved to the Downloads folder (Download, i.e. /storage/emulated/0/Download) — in that case the destination argument may be OMITTED. If the user explicitly named another location, specify it in destination: that path takes priority over the default. Path is in shared storage (/storage/emulated/0).",
        "web_fetch" to "Fetch a web page by URL and quickly return its readable text (over HTTP, without executing JavaScript — does not block the app). Use it to read the contents of a site/article, or to perform a search (request a search URL like https://html.duckduckgo.com/html/?q=query). For pages that render only with JavaScript, or for interactive work, use web_open + web_readable. Does not trigger confirmation. Will not work against strict anti-bots (Cloudflare).",
        "web_open" to "Open a URL in the built-in browser (WebView) with a PERSISTENT session. Unlike web_fetch, after opening you can click, enter text, etc. Returns the URL, title, and the beginning of the text. Use as the first step of interactive work with a site.",
        "web_click" to "Click an element on the currently open page (after web_open): by CSS selector and/or by visible text. Links, buttons, fields, <label> and their associated controls, elements with aria-label, etc. are supported. Text is searched first by exact match (case-insensitive), then by substring. Returns the new page state.",
        "web_type" to "Enter text into a field on the current page (after web_open) by CSS selector. The submit=true parameter will submit the form after entering.",
        "web_scroll" to "Scroll the current page (after web_open). direction: down (default), up, top, bottom; amount — pixels for down/up (default 800).",
        "web_back" to "Go back to the previous page in the built-in browser (web_* session history).",
        "web_extract" to "Extract the readable text of the currently open page (after web_open). Use it to read the contents without re-fetching.",
        "web_navigate" to "Navigate to a URL in the current web session (waiting for the load, up to ~25 s). Returns the final URL and title. Use after web_open for navigations when you need to explicitly wait for the load.",
        "web_search" to "Search the internet, return a list of results (title, link, snippet). Opens the search engine's HTML results in the built-in browser and parses the list; on failure it honestly reports that there are no results. Does not require confirmation.",
        "image_search" to "Search for images on the internet by CC licenses (Openverse / Wikimedia Commons), return a list with DIRECT links to image files. Needed when the agent needs an actual IMAGE (e.g. for wallpaper_set / download_file): web_search returns links to search pages, whereas here you get direct image URLs. Parameters: query (required), count (default 5, maximum 10), orientation (landscape / portrait / square, optional), source (openverse | wikimedia, default openverse). API keys are not required. Does not require confirmation.",
        "web_wait" to "Wait for an element/condition on the page (bounded timeout). If selector is not set — it waits for the page to fully load (readyState=complete).",
        "web_readable" to "Extract the main text of the page (readability): removes navigation/scripts/styles and returns the main content. Use it for reading articles.",
        "web_eval_js" to "Execute JavaScript on the current page and return the result (via evaluateJavascript). The result is truncated to 20000 characters. Use it with caution — only on trusted pages.",
        "web_screenshot" to "Save a screenshot of the current web page to a PNG file. Without confirmation (it writes a file) — only on the user's direct request. Path is in shared storage (/storage/emulated/0).",
        "web_find" to "Find text on the current page: returns the number of matches and context fragments around them."
    )

    private fun descriptions1(): Map<String, String> = mapOf(
        "web_scroll_until" to "Scroll the page to the end (content loading/Lazy-load) until the document height stops growing. Bounded: limited by max_steps.",
        "web_tabs_new" to "Create a new web session tab (its own WebView and history) and make it active. You can immediately specify a url to load.",
        "web_tabs_switch" to "Switch the active web session tab by index (zero-based, as in web_tabs_list) or by name.",
        "web_tabs_close" to "Close a web session tab by index or name. With no parameters, it closes the active tab.",
        "web_tabs_list" to "Show the list of web session tabs: index, name, URL, and which tab is active.",
        "web_session_save" to "Save the cookie session of the current page (CookieManager) to the app's private storage under the name name. Use it for login scenarios.",
        "web_session_load" to "Restore a cookie session by the name name (CookieManager.setCookie + flush). After restoring, open the page via web_navigate.",
        "web_forward" to "Go forward in the built-in browser history (after web_back). Checks whether there is anywhere to go.",
        "web_reload" to "Reload the current web session page (after web_open/web_navigate).",
        "web_submit" to "Submit a form on the current page: submits the form of the active input field, otherwise the first form on the page. Caution — side effects are possible (order, publication).",
        "web_set_user_agent" to "Set the User-Agent of the web session (web_set_user_agent). An empty ua value restores the default User-Agent. Changing the UA may break sites — use it with caution.",
        "web_download" to "Download a file by URL from the web session via the system download manager. The file is saved to the shared Downloads folder; the tool honestly reports the result (completed/queued/error).",
        "brightness_set" to "Set the screen brightness as a percentage (0–100). Requires the WRITE_SETTINGS permission (requested manually).",
        "volume_set" to "Set the volume level for a sound type (music, ring, notification, alarm). Level — 0–15.",
        "wifi_set" to "Request to turn Wi-Fi on/off (on=true / on=false). On Android 10+ the system forbids ordinary apps from changing Wi-Fi directly: the tool will honestly return a refusal, explain why, and open the Wi-Fi panel in settings. On older Android versions (before 10) Wi-Fi is actually toggled.",
        "bluetooth_set" to "Request to turn Bluetooth on/off (on=true / on=false). On Android 12+ the system forbids ordinary apps from changing Bluetooth directly: the tool will honestly return a refusal, explain why, and open the Bluetooth panel in settings. On older Android versions (before 12) Bluetooth is actually toggled.",
        "volume_get" to "Get the current volume for a sound type (music, ring, notification, alarm): current value and maximum.",
        "brightness_get" to "Get the current screen brightness as a percentage (0–100).",
        "ringer_set" to "Set the phone's sound mode: normal (ordinary), vibrate (vibration), silent (no sound).",
        "rotation_set" to "Enable or disable screen auto-rotation (on=true — auto, on=false — lock the orientation). Requires WRITE_SETTINGS.",
        "screen_timeout_set" to "Set the time until the screen turns off, in seconds. Requires WRITE_SETTINGS.",
        "vibrate" to "Turn on device vibration for the specified time in milliseconds (default 500).",
        "network_info" to "Get information about the current network connection: type (Wi-Fi/mobile network/Ethernet/VPN), internet availability, IP address.",
        "wifi_info" to "Get details about the current Wi-Fi network: SSID and IP address (SSID may require location permission).",
        "bluetooth_devices" to "List of paired Bluetooth devices: name and MAC address. Requires the BLUETOOTH_CONNECT permission.",
        "ble_scan" to "Scan for Bluetooth Low Energy (BLE) devices for duration_sec seconds and return the found devices with address and signal level, sorted by RSSI. Requires the BLUETOOTH_SCAN permission and Bluetooth enabled. An unfiltered scan is suspended when the screen is off.",
        "ble_connect" to "Connect to a BLE device by MAC address and show its GATT services and characteristics. Requires the BLUETOOTH_CONNECT permission and Bluetooth enabled. The connection is closed after reading the list of services.",
        "dns_lookup" to "Resolve a domain name to IP addresses (DNS query).",
        "ping" to "Check host reachability via ping (default 4 packets).",
        "wifi_scan" to "Scan for available Wi-Fi networks (SSID, signal level). May require location permission.",
        "network_detail" to "Details of the active network: transport type, metered, validated, internet availability.",
        "nsd_discover" to "Discover services on the local network (mDNS/NSD) within the specified time.",
        "telephony_info" to "Basic telephony information: carrier, network type, roaming, SIM.",
        "ir_transmit" to "Transmit an IR signal (if the device has an IR emitter). Requires the TRANSMIT_IR permission. If there is no emitter, the tool will honestly report unavailability. Only patterns shorter than 2 seconds are allowed.",
        "usb_list" to "List of connected USB devices (OTG): vendor/product id, class, names, and whether access is available. No permission required.",
        "usb_request_permission" to "Request access to a USB device: shows the system dialog and reports its actual result (granted/denied/no response). Access to the device lasts until it is disconnected.",
        "usb_info" to "Details of a USB device: interfaces and endpoints. Requires access already granted (see usb_request_permission). The device is opened read-only for metadata: the interface is not claimed and no data is transferred — exchanging data requires the specific device's protocol.",
        "vpn_status" to "Whether VPN is active and which network type is in use: the presence of a VPN transport in the active network and the default network transport. Read-only, no permission required.",
        "vpn_prepare" to "Request the user's consent for VPN (VpnService.prepare): opens a system dialog. If consent has already been granted, prepare returns null — the tool will report this. The tool does not claim that the user agreed. A full VPN tunnel is not created (a remote server is needed), so vpn_connect does not exist.",
        "sim_slots_list" to "List of SIM slots (subscriptions): slot index, subscriptionId, carrier, display name, type (physical/eSIM), and the \"default\" flag for calls, SMS, and data. Read-only. Requires READ_PHONE_STATE; without it or without a SIM it honestly reports this and does not invent data.",
        "sim_numbers" to "Phone numbers of active SIM subscriptions: slot, carrier, ICCID (if available), and the number from SubscriptionManager.getPhoneNumber(subId) (API 33+) / TelephonyManager.getLine1Number(). Read-only. Requires READ_PHONE_STATE; READ_PHONE_NUMBERS is needed for the number itself. If the carrier/SIM does not provide MSISDN, it honestly returns \"not determined\" for each subscription and NEVER invents a number.",
        "sim_set_default_sms" to "Make the specified SIM (subscription_id) the default subscription for SMS. This is unavailable to an ordinary app: there is no public API, a system privilege MODIFY_PHONE_STATE is required. The tool NEVER reports success: it checks the id and honestly explains why the change is impossible, and that the role of the default SMS app does not grant this right.",
        "esim_status" to "eSIM status: availability of the built-in subscription (EuiccManager.isEnabled) and chip information. EID and eUICC data are returned only with carrier privileges; otherwise the tool honestly reports that carrier privileges are needed and NEVER invents the EID.",
        "battery_detail" to "Battery details: charge, temperature, voltage, status, health, power source.",
        "thermal_status" to "Current thermal status of the device.",
        "battery_prediction" to "Prediction of the remaining battery runtime.",
        "power_saver_get" to "Whether power saving mode is enabled.",
        "device_idle_get" to "Whether the device is in Doze/Idle.",
        "battery_optimization_status" to "Whether the app is excluded from battery optimization.",
        "battery_optimization_request" to "Open the system request to exclude the app from battery optimization."
    )

    private fun descriptions2(): Map<String, String> = mapOf(
        "screen_state_get" to "Whether the screen is on / the device is interactive.",
        "alarm_set" to "Set an alarm for the specified time (hour 0-23, minute 0-59) with an optional message.",
        "timer_set" to "Set a timer for the specified number of seconds with an optional message.",
        "text_to_speech" to "Speak text aloud via the system text-to-speech (TTS).",
        "font_scale_set" to "Change the system font scale (e.g. 1.0 — normal, 1.3 — larger). Requires WRITE_SETTINGS.",
        "flashlight_set" to "Turn the flashlight (camera flash) on or off.",
        "storage_volumes" to "Show the storage volumes (internal, SD card, USB) with their state and root paths.",
        "contact_search" to "Search contacts by name (partial match). Returns id, name, and phone. Use before get/update to find the id.",
        "contact_get" to "Get a contact's name, phone numbers, and email by its id. The id is taken from contact_search.",
        "contact_create" to "Create a contact with a name and phone (email optional).",
        "contact_update" to "Update an existing contact by id. Pass only the fields to change: name, phone, email.",
        "contact_delete" to "Delete a contact by id (the id is taken from contact_search). Irreversible, without confirmation — only on the user's direct request.",
        "contact_lookup_by_number" to "Find a contact by phone number. Returns the name and a photo link.",
        "contact_groups_list" to "List of contact groups (labels): id, title, and account.",
        "contact_group_create" to "Create a contact group (label) with the specified title and return its id. account_type and account_name are optional: without them the group is created in the local or first available account.",
        "contact_group_assign" to "Add a contact to a group (label) or remove it from one. The contact is specified by contact_id (from contact_search) or contact_lookup (phone number), the group by group_id (from contact_groups_list) or group_title. remove=true removes it from the group, by default it adds.",
        "contact_birthdays" to "List of upcoming contact birthdays within a window of days days (default 30). Returns id, name, date (MM-DD), and how many days are left; sorted by proximity. Read-only.",
        "contact_photo_get" to "Get a link to a contact's photo by its id. Reports whether the contact has a photo.",
        "contact_star_set" to "Add a contact to favorites or remove it by id. The action modifies the contact's data.",
        "contact_batch_upsert" to "Create/update contacts in bulk in a single transaction. names — names separated by commas; phones and emails — optional comma-separated lists aligned by index with names. For an existing contact, a new phone/email is added only if it does not already exist. The action modifies contact data.",
        "contact_merge" to "Merge two contacts into one via the aggregation rule. contact_id1 and contact_id2 — ids from contact_search. The action is dangerous: it changes the links between contacts.",
        "contact_split" to "Split two contacts that are aggregated into one. contact_id1 and contact_id2 — ids from contact_search. The action is dangerous: it changes the links between contacts.",
        "contact_photo_set" to "Set a contact's photo from a file. contact_id — id from contact_search, photo_path — path in shared storage (/storage/emulated/0). The action modifies the contact's data and reads the file.",
        "contact_photo_clear" to "Delete a contact's photo by contact_id. The action is dangerous: it unconditionally deletes the existing photo.",
        "contact_export_vcf" to "Export contacts to a vCard file (.vcf). contact_id is optional: without it, all contacts are exported. path — an optional absolute path to a .vcf inside the allowed storage; by default the file is saved to the service exports folder. Returns the actual path and the number of exported contacts. Contacts are read-only.",
        "contact_import_vcf" to "Import contacts from a vCard 3.0 (.vcf) file into the local phone book. path is the required path to the .vcf. Name (FN/N), phone numbers (TEL) and email (EMAIL) are supported; other vCard properties are ignored. Returns the number of imported and skipped cards. This action modifies contacts.",
        "contact_group_delete" to "Delete a contact group (label) by group_id (from contact_groups_list). Irreversible, without confirmation.",
        "contact_group_rename" to "Rename a contact group (label): group_id (from contact_groups_list) and the new title. This action modifies contact data.",
        "photos_list" to "List photos from the gallery (optionally - search by name). Returns id, name and size. Pass query to search by file name.",
        "photos_delete" to "Delete photos from the gallery by a list of ids. Deletion requires access to all files.",
        "exif_read" to "Read EXIF metadata of an image (date, GPS, camera model, orientation, dimensions).",
        "exif_write" to "Change an image's EXIF tag and save the file.",
        "image_thumbnail" to "Create a reduced thumbnail of an image or video.",
        "image_resize" to "Resize an image and save the result. The size is set by width and/or height in pixels, or maxSide - by the longer side; if only one side is given, the aspect ratio is preserved. The output format is jpeg, png or webp.",
        "image_convert" to "Convert an image to another format: jpeg, png or webp. The size is unchanged unless width, height or maxSide is set.",
        "wallpaper_set" to "Set an image as wallpaper (\"set the image as wallpaper\"). The source is a local file (path) or a URL (url) - exactly one of the two. target: home (home screen, default), lock (lock screen) or both (both).",
        "media_scan" to "Scan a file into the media library so it appears in the Gallery and other media apps.",
        "media_delete" to "Delete media files (photo/video/audio) with a system confirmation dialog.",
        "media_trash" to "Move media files to the system Trash (with confirmation).",
        "media_request_write" to "Request from the user permission to modify media files owned by others.",
        "media_downloads_list" to "List files from the Downloads folder (MediaStore.Downloads). Optional limit - maximum entries (default 100).",
        "media_metadata" to "Metadata of a media file: duration, resolution, title, artist.",
        "media_frame_extract" to "Extract a frame from a video as PNG. time_ms - the moment in milliseconds (default 0).",
        "media_favorite_set" to "Add or remove media files (photo/video/audio) from the system favorites. Works with system confirmation, requires Android 11+.",
        "audio_play" to "Play an audio file, requesting audio focus. wait=true - wait for playback to finish (bounded time), otherwise return that playback has started. position_ms - the start position in milliseconds.",
        "audio_stop" to "Stop the current playback started by the audio_play tool. Honestly reports if nothing is playing.",
        "audio_mode_set" to "Set the audio mode: normal, in_communication, in_call, ringtone. Optional speaker=true/false toggles the speakerphone. Honestly reports if the platform ignored the mode.",
        "audio_stream_analysis" to "Record a short window from the microphone and compute the sound level: RMS, peak and silence ratio. Does not create files. duration_ms defaults to 3000, maximum 10000.",
        "audio_capture_app" to "Record audio played by other apps into a WAV file. Requires the RECORD_AUDIO permission and confirmation of system audio capture. The source app may forbid capture - in that case silence is recorded, which the tool honestly reports.",
        "media_control_other" to "Control another media player through its active media session. action: play, pause, next, previous, stop or toggle. Optional app - filter by package name. Requires notification access.",
        "image_crop" to "Crop an image to a rectangle and save it to a new file. The region is set by x, y, width, height in pixels, or box=x1,y1,x2,y2 as fractions (0..1). The source file is not modified.",
        "image_rotate" to "Rotate an image by an angle (90, 180, 270 or arbitrary) and/or mirror it horizontally/vertically, saving a new file. For an arbitrary angle the fill for uncovered corners is set by background (for example #RRGGBB); otherwise the background is transparent.",
        "image_adjust" to "Adjust the brightness, contrast, saturation (-1..1) and sharpness (0..1) of an image and save a new file. 0 means no change; at least one parameter must be non-zero.",
        "image_watermark" to "Apply a watermark - text (text) or a small image (image) - and save a new file. Position: top-left, top-right, bottom-left, bottom-right, center. Size, colour, opacity, margin and tiling (tiled) can be set.",
        "image_overlay_text" to "Draw arbitrary text on an image at a given position and save a new file. Font size, colour, alignment (left/center/right), opacity and a background box can be set.",
        "image_collage" to "Combine several images into a grid collage and save a new file. srcs - comma-separated paths. The grid is set by rows/cols (otherwise auto). spacing - gap between cells, background - the background colour.",
        "screen_annotate" to "Draw annotations on an image (for example a screenshot) and save a new file: rectangles, ellipses, arrows, text and highlights from a JSON shapes array. Each item: {\"type\":\"rect|ellipse|arrow|text|highlight\", ...}.",
        "image_svg_render" to "Rasterise an SVG (vector image) into a PNG at a given width/height. The source is a file (src) or a bundled app asset (asset) - exactly one of the two. If only one side is given the aspect ratio is preserved; without sizes the SVG's own size is used. AndroidSVG renders static SVG; some features (filters, some CSS) are unsupported and silently ignored.",
        "image_remove_background" to "Remove the background of an image: return a PNG with the cut-out subject (foreground) on a transparent background. Uses ML Kit Subject Segmentation (beta). The model is NOT bundled in the app - it is downloaded by Google Play services, so a connection is needed and the first run may wait for the download; if the model/services are unavailable the tool reports this honestly and never fabricates a result.",
        "image_detect_objects" to "Detect objects in an image (ML Kit Object Detection, model bundled in the app, works offline) and return a JSON list of objects: {label, confidence, box{x,y,w,h}}, sorted by descending confidence and capped at maxResults. Categories are coarse: Fashion good, Home good, Food, Place, Plant. If there are no objects it returns an empty list []."
    )

    private fun descriptions3(): Map<String, String> = mapOf(
        "media_sessions_list" to "List active media sessions: who is playing, playback state and metadata (title, artist, album). Optional app - filter by package name. Requires notification access.",
        "sms_list" to "List incoming SMS (optionally - search by body text). Returns the sender, body and date.",
        "sms_send" to "Send an SMS to the specified number with the given text. Requires the SEND_SMS permission.",
        "sms_conversations" to "List SMS conversations (threads): thread id, message count and a snippet of the last one. Use thread_id for sms_read_state.",
        "sms_read_state" to "Mark a conversation's messages as read or unread. Works only if the app is the default SMS app; otherwise it honestly reports the platform limitation.",
        "subscription_list" to "List the device's active SIM subscriptions: id, slot, carrier, display name and whether it is the default data subscription.",
        "sms_send_on_sim" to "Send an SMS from the selected SIM. number - the recipient, text - the text, subscription_id - the subscription id from subscription_list. Requires SEND_SMS and confirms sending by the system result.",
        "mms_send" to "Send an MMS to a number. Specify content_uri (content:// media) or file_path. Requires SEND_SMS; receiving and saving MMS are available only to the app with the default SMS role.",
        "sms_delete" to "Delete SMS. Deletion is IRREVERSIBLE: Undo cannot restore SMS. Works only if Meha is the default SMS app (ROLE_SMS role); otherwise the platform forbids deletion and the tool honestly reports this. scope: all - all SMS; thread - only a conversation's messages (needs thread_id from sms_conversations); number - only messages with the given number (needs number). Actual deletion requires confirm=true; without confirm=true nothing is deleted.",
        "camera_capture" to "Capture a photo or video with the device camera IN THE BACKGROUND (in-process Camera2, without opening the camera app) and save it to a file. type = photo|video. path - the destination file (optional; default DCIM/Meha/...). duration - video duration in seconds (default 10). with_audio - whether to record audio for video (default yes). lens = front|back (default back). camera_id - the exact id from camera_list (takes precedence over lens). analyze (photos only, default true): immediately look at the shot - if the configured model supports images (Vision), the photo is sent to the model and its description is returned; otherwise the photo is NOT sent to the model and the recognized offline-OCR text is honestly returned with a note that Vision is unavailable. analyze=false - only save the file. Success is returned only after a non-empty file is created; the file is added to the device gallery (MediaStore).",
        "camera_list" to "List the device's cameras and their characteristics: id (for the camera_id parameter), position (front/back), sensor orientation, frame sizes, flash, focal lengths.",
        "camera_zoom" to "Programmatically set the camera zoom (Camera2): level - the factor as a string, for example \"2.0\"; or step = in|out for a 1.5x step. lens = front|back (default back), camera_id - the exact id from camera_list. The value is clamped to the supported range, and the applied zoom is returned.",
        "camera_manual" to "Manual capture parameters (Camera2): iso, exposure_ms (shutter speed in milliseconds), focus_distance (diopters, 0 = infinity). lens = front|back (default back), camera_id - the exact id from camera_list. Support is checked against the camera capabilities; unsupported values are honestly rejected.",
        "camera_burst" to "Burst capture: take count frames (up to 20) with an interval of interval_ms into the destination_dir folder. lens = front|back (default back), camera_id - the exact id from camera_list. Each file is checked for non-emptiness; the frames are added to the device gallery (MediaStore).",
        "camera_video_record" to "Record video with the device camera IN THE BACKGROUND (Camera2 + MediaRecorder, without opening the camera app) to the path file. with_audio - whether to record audio (default yes). duration_ms - recording duration; if not set, recording continues until camera_record_stop is called. lens = front|back (default back), camera_id - the exact id from camera_list. The resulting file is checked for non-emptiness and added to the device gallery (MediaStore).",
        "camera_record_stop" to "Stop the started video recording (camera_video_record without duration_ms) and verify that the file is saved and non-empty.",
        "audio_record" to "Record audio from the microphone for the specified time (in seconds) to the destination file. If destination is not set, the recording is saved to Music/Meha/ under an automatic name (audio_<time>.m4a); if a name without extension is given, .m4a is appended. The finished file is registered in the device gallery.",
        "call_log" to "List calls from the call log (type, number, date, duration). Pass limit to cap the number of records. Optional filters are combined with AND: number (by number, accounting for formatting), type (incoming, outgoing, missed, rejected) and days (the last N days). Without filters - the most recent records.",
        "call_start" to "Place a voice call to the specified number.",
        "ussd_request" to "Run a USSD/service code (for example *#06# or *100#) and return the network response. Honest fallback: on Android 11+ direct USSD in practice works only for the default dialer app, so on failure/timeout the tool does NOT invent a response and does NOT dial itself - it opens the dialer with the code pre-filled (ACTION_DIAL) so you press \"Call\" and read the network response, or dial the number manually. The CALL_PHONE permission is needed only for the direct attempt.",
        "blocked_number_ui" to "Open the system number-blocking screen. number - an optional number to add manually. Writing to the system blocked-numbers list is unavailable to an ordinary app.",
        "call_observe_status" to "Call state: whether a call is currently in progress (TelecomManager.isInCall) and whether there is a managed call (isInManagedCall, API 31+). The caller's number is not revealed.",
        "call_rules_list" to "Show the auto-handling rules for calls/SMS: whether handling is enabled (disabled by default), unknown-number blocking, the blocked and allowed number lists, SMS auto-reply and call-back reminder. Read-only.",
        "call_rules_set" to "Configure auto-handling of calls/SMS (opt-in, disabled by default). All parameters are optional: enabled (master switch), block_unknown (reject unknown numbers), blocked_numbers (comma-separated numbers), allow_numbers (whitelist - always allows through), auto_reply_sms (auto-reply by SMS to unknown numbers), auto_reply_text (reply text), auto_reply_when_busy (auto-reply when busy), call_back_reminder (reminder to call back a known contact). The rules are stored locally and apply only to Meha's own policy; the system blocked-numbers list is not changed, and short, service and emergency numbers are not affected by auto-reply.",
        "app_list" to "List installed apps (package | name). Limited to ~100 entries. Useful to find out the package before app_launch.",
        "app_launch" to "Launch an app by package name (package).",
        "app_uninstall" to "Request uninstall of an app by package name (package). Shows a system confirmation dialog.",
        "app_package_info" to "App info: version, install and update time, targetSdk, whether it is a system app, and the installer. If the app is not found or is hidden by package visibility restrictions - it honestly reports this. Stopping, clearing the cache of, or enabling/disabling another app without system privileges is not possible.",
        "app_is_installed" to "Check whether the app with the given package name (package) is installed. If installed - reports the name, version (versionName/versionCode) and whether it is enabled. If hidden by package visibility restrictions - it honestly reports this.",
        "app_permissions" to "The permissions the app requests and which of them are granted.",
        "app_activities" to "List the app's activities, services and receivers (by package name).",
        "app_services" to "List the app's declared services (by package name): name, exported flag and, if set, the process. Read-only via PackageManager; if the app is hidden by package visibility restrictions - it honestly reports this.",
        "app_receivers" to "List the app's declared broadcast receivers (by package name): name, exported flag and, if set, the process. Read-only via PackageManager; if the app is hidden by package visibility restrictions - it honestly reports this.",
        "role_status" to "Check the status of a system role: whether the role is available on the device and whether it is granted (and to which apps).",
        "role_request" to "Ask the user to make the app a system role (SMS, DIALER, BROWSER, HOME, CALL_SCREENING, etc.). Shows a system dialog and reports the real result: granted, denied or cancelled. Needed, for example, for sms_read_state to be able to change read status.",
        "role_hold" to "Check whether the app holds the given system role (SMS, DIALER, BROWSER, HOME, etc.). Helps determine in advance whether role-dependent tools will work.",
        "call_screening_role_request" to "Request the call screening role (ROLE_CALL_SCREENING) via a system dialog and report the real result. Note: the assigned CallScreeningService must respond to an incoming call within 5 seconds.",
        "dialer_role_request" to "Request the default phone app role (ROLE_DIALER) via a system dialog and report the real result. The role implies an InCallService with a call screen.",
        "open_url" to "Open a web link (URL) in the device's EXTERNAL browser (takes the user out of the app). Use it when the user asks to SHOW a page or when a site needs to be opened for the user; to read a page's text - use web_fetch.",
        "app_details" to "Open the system \"App info\" screen (app details/settings) by package name. On this screen the user can manually stop the app, clear its cache and enable/disable it - which is unavailable to the agent without system privileges.",
        "open_settings" to "Open the device's system settings screen. screen: main, wifi, bluetooth, location, airplane, display, sound, battery, data, nfc, accessibility, developer, apps, all_files.",
        "notification_settings_open" to "Open the notification settings screen. With package - the notification settings of a specific app; without package - general notification settings (on Android 13+ the list of apps that send notifications).",
        "app_notification_settings_open" to "Open the notification settings screen of a specific app by package name (package).",
        "internet_panel_open" to "Open the system internet connectivity panel (Wi-Fi/mobile data) for quick toggling.",
        "location_settings_open" to "Open the system location settings screen.",
        "share_text" to "Share text via the system \"Share\" menu (the user chooses the app).",
        "dial" to "Open the dialer with the number pre-filled (the call is NOT placed). To actually call, use call_start.",
        "intent_open" to "Open a URI, link or file in another app (ACTION_VIEW, or ACTION_EDIT when action=edit). This is how to open a document in a third-party program - for example .docx in Word or PDF in a viewer - or open a file for editing. A local path is automatically converted into a safe content URI via FileProvider. package forces opening in the specified app, chooser shows the system app picker, mime sets the type.",
        "intent_send_to" to "Send text and/or a file to a SPECIFIC app (ACTION_SEND explicitly to the given package), without the system app picker. Specify text and/or path; when attaching a file, mime defaults to */*; subject - the message subject.",
        "media_audio_list" to "List audio files from the media library (id, title, artist, duration, path). Can be filtered by query."
    )

    private fun descriptions4(): Map<String, String> = mapOf(
        "media_video_list" to "List video files from the media library (id, title, duration, path). Can be filtered by query.",
        "settings_get" to "Show the agent's current LLM settings (endpoint, model, reasoning mode). The API key is not returned.",
        "self_config" to "Change the agent's own LLM settings from chat: model, endpoint and/or reasoning (off/low/medium/high).",
        "sensor_list" to "List the device's available sensors.",
        "sensor_read" to "Read a single sensor measurement. type: accelerometer, gyroscope, magnetometer, light, proximity, pressure, gravity, linear_acceleration, rotation_vector, step_counter, humidity, temperature.",
        "app_deep_link" to "Open a link/deep link, optionally strictly in a specific app (package).",
        "app_install_apk" to "Open the system APK installer: for a local file (path) or for an APK downloaded by url into the app cache (at least one of the parameters is required). Honestly: Android does not allow installing an APK silently - the agent only opens the system dialog and the user presses \"Install\"; the install outcome is unknown to the agent (check via app_is_installed). If Meha is not allowed to install unknown apps, an honest error is returned and the relevant setting is opened. Requires REQUEST_INSTALL_PACKAGES.",
        "app_store_open" to "Open the Google Play page for an app (package) or search in Google Play (query). At least one of the parameters is required. IMPORTANT and honest: the agent cannot install an app silently - it only opens the Play page and the user must press \"Install\"; after installation the agent can verify it via app_is_installed. If Play Store is not installed, the web version play.google.com opens.",
        "app_store_search" to "Open a Google Play search for the query (query). The agent cannot install an app silently: the Play page opens and the user must press \"Install\"; after installation, check it via app_is_installed.",
        "app_suggest_for" to "Suggest which app is needed to work with a format/file, and offer to install it. Accepts format (a MIME, an extension or a general word: pdf, docx, rar, video, image, audio, office, archive) and/or task (a free-text description: \"open a pdf\", \"edit a Word document\"). Returns the detected MIME/family, whether a suitable app IS installed (resolveActivity check) and, if not, a short list of Google Play apps with install links. Honest when the format is unrecognized - the agent never invents apps.",
        "app_backup_apk" to "Copy the APK files of an installed app (package): the base and all split APKs - into the destPath folder. destPath is optional: by default Download/apk_backup in the accessible storage. Returns the paths and sizes of the copied files.",
        "sms_thread" to "Show the SMS conversation with the given number (in ascending time order).",
        "call_log_delete" to "Delete entries from the call log: all=true (everything), or id, or phone. Dangerous action.",
        "ui_tree" to "Capture the element tree of the current screen (text, id, coordinates, clickability) - to understand where to tap. Works in any app. Requires Meha's accessibility service to be enabled.",
        "screen_understand" to "Understand what is currently on screen: merge the accessibility element tree with text recognized (OCR) on the screenshot. The tree provides structure, roles and coordinates, while OCR adds text the tree does not see (WebView, canvas, games, images). Returns elements and lines visible only in the image. OCR uses the offline Tesseract engine, which recognizes Cyrillic and Latin; if the engine fails to start, Latin ML Kit is used. verbose=true adds raw tree and OCR dumps; attach_image=true attaches the screenshot to the model request as an image if the current model supports vision, otherwise the screenshot is saved as a file (accessible via files_read/ocr_image); max_chars limits the response length. Requires Meha's accessibility service to be enabled.",
        "ui_tap" to "Tap an element on the current screen: by coordinates (x,y) or by selector (text/desc/id from ui_tree).",
        "ui_long_press" to "Long-press an element (by coordinates or selector).",
        "ui_swipe" to "Swipe on the screen from (x1,y1) to (x2,y2) with duration duration_ms.",
        "ui_scroll" to "Scroll the current screen. direction: down (default) or up.",
        "ui_find" to "Find elements on the current screen WITHOUT dumping the whole tree: by text, contentDescription, resourceId and/or className. exact=false (default) - case-insensitive substring match, exact=true - full match. Returns index, text/description, class, click/edit flags and bounds - enough for ui_tap by coordinates. limit caps the number of results. Requires Meha's accessibility service to be enabled.",
        "ui_wait_for_text" to "Wait for text to appear on the current screen (bounded): polls the accessibility tree until a node with text/contentDescription appears or timeoutMs elapses. Returns the bounds of the found node and the elapsed time, or an honest \"did not appear\" after N ms. Requires Meha's accessibility service to be enabled.",
        "ui_scroll_until" to "Scroll the screen until the desired text appears. direction: down (default) or up. Bounded: at most maxSwipes swipes (default 8, maximum 30). Returns the bounds of the found node or an honest \"not found after N swipes\". Requires Meha's accessibility service to be enabled.",
        "ui_type" to "Type text into a field: by selector (text/desc/id) or into the currently focused field.",
        "key_event" to "Global system key: back, home, recents, notifications, quick_settings, lock (screen lock, Android 9+/API 28+) and screenshot (screenshot via accessibility, Android 14+/API 34+; on older APIs the request is honestly rejected). Requires Meha's accessibility service to be enabled.",
        "ui_copy" to "Copy the current selection to the clipboard (ACTION_COPY). Works with the focused node: first FOCUS_INPUT, then FOCUS_ACCESSIBILITY. If there is no selection or the node does not support copying (performAction returned false) - an honest error. Requires Meha's accessibility service to be enabled.",
        "ui_paste" to "Paste text from the system clipboard into the focused node (ACTION_PASTE). An honest error if the field is not focused or does not support pasting. Requires Meha's accessibility service to be enabled.",
        "ui_cut" to "Cut the current selection to the clipboard (ACTION_CUT). Works with the focused node. An honest error if there is no selection or the node does not support cutting. Requires Meha's accessibility service to be enabled.",
        "ui_select_all" to "Select all text in the focused node. AccessibilityNodeInfo has no public ACTION_SELECT_ALL, so the range 0..text length is selected via the documented ACTION_SET_SELECTION with start and end arguments. If the node does not expose text or rejects the action - an honest error. Requires Meha's accessibility service to be enabled.",
        "ui_accessibility_status" to "Show the honest status of Meha's accessibility service: whether it is enabled in the system and connected to the app. Read-only, no permission required.",
        "ui_accessibility_open_settings" to "Open the system \"Accessibility\" screen, where the user manually enables Meha's service. No permission required.",
        "screen_capture" to "Take a screenshot of the whole screen (via the accessibility service, Android 11+) and save it to the Pictures/Meha gallery. Returns the path.",
        "screen_capture_region" to "Capture a screen region or window (Android 14+) and save the result to a file. Without coordinates it captures the whole screen. With left/top/width/height it takes a frame and crops it to the given region (an honest crop of the frame, not a separate window capture). With window/app on Android 14+ (API 34), a single app window can be chosen in the system dialog (MediaProjectionConfig, the choice is made by the user). If duration_ms is set - records a short video of that duration (whole screen only). The user's consent is requested for EVERY session. Dangerous action. Path in shared storage (/storage/emulated/0).",
        "screen_record_start" to "Start screen recording (H.264/MP4 video) to the specified file. The user's consent is required: the system dialog is shown again for EVERY recording session (on Android 14+ the MediaProjection token cannot be reused). The app does not request its own confirmation. Path in shared storage (/storage/emulated/0).",
        "screen_record_stop" to "Stop the started screen recording and save the file. Returns the path and size of the finished video.",
        "screen_record_status" to "Check whether a screen recording is currently in progress and find out the target file and its current size.",
        "remember_screen" to "Read the visible text of the CURRENT screen (of the app under Meha's overlay) and remember it in long-term memory. Read-only: it does not tap anything. Works when Meha is invoked over another app (floating assistant) - the app window is taken, not Meha's own overlay. Dedupes consecutive duplicates, the reading order is preserved, and the length is limited by max_chars. With save=true (default) it saves a note with the tags remember_screen and screen:<package>; note - a short caption/title, tags - your own comma-separated tags, person - a person's name or id (resolved honestly, without guessing). Requires Meha's accessibility service to be enabled.",
        "notification_list" to "List active notifications (package, title, text, key). Requires notification access to be enabled.",
        "notification_open" to "Open a notification (go into the app) by key from notification_list.",
        "notification_dismiss" to "Dismiss (remove) a notification by key from notification_list.",
        "notification_reply" to "Reply to a notification (if there is a quick-reply field) by key from notification_list.",
        "notification_clear_all" to "Dismiss (close) all active notifications. Requires notification access to be enabled. Honestly reports how many notifications could not be dismissed (for example, ongoing/non-dismissible ones).",
        "notification_channels_list" to "List an app's notification channels and their importance.",
        "notification_channels_create" to "Create or update an app's notification channel. The importance of an already-created channel cannot be raised if the user lowered it themselves - the tool will honestly report this.",
        "dnd_status" to "Check whether the app has been granted access to the notification policy (Do Not Disturb mode), and the current filter.",
        "app_usage" to "App usage statistics (screen time) over N days. Requires usage access.",
        "device_lock" to "Lock the device screen. Requires Meha to be activated as a device administrator.",
        "device_admin_enable" to "Open the screen to activate Meha as a device administrator (needed for screen lock).",
        "speech_to_text" to "Recognize speech from the LIVE microphone into text (dictation). You can specify language (BCP-47, e.g. ru-RU) and timeout_ms. Finished audio files are not recognized by this tool - for files use recording_import or recordings_scan (on-device transcription via whisper).",
        "ocr_image" to "Recognize text in an image (locally, offline - Tesseract with Cyrillic and Latin; if the engine fails, Latin ML Kit). path - the path to the image.",
        "vision_status" to "Show whether the currently configured LLM accepts images and why that was decided (allowlist of known vision models, a saved probe result, a fresh probe, or \"unknown\"). Honestly reports \"unknown\" if the endpoint is unreachable. Without Vision, use ocr_image.",
        "vision_check" to "Force a re-check of image support for the current model: sends a tiny probe image to the configured endpoint (with a time limit) and saves the result. Use it after changing the model or provider."
    )

    private fun descriptions5(): Map<String, String> = mapOf(
        "tool_search" to "Find tools by a text query in Russian or English (for example \"send sms\", \"dollar rate\", \"read pdf\"). Returns the names of suitable tools and a brief description. Use it when the needed tool is not in the current set — this way you can find and call any available tool.",
        "selftest_run" to "Self-check: runs all NON-interactive tools with safe arguments and returns a report (success/failure for each). Use it when asked to verify that the tools work.",
        "note_create" to "Save a note.",
        "note_list" to "Show all notes.",
        "note_delete" to "Delete a note by id or by a text substring (query).",
        "memory_save" to "Remember a fact/preference for the long term (long-term memory across sessions). Specify tags — keywords and synonyms for search, and importance (0 normal, 1 important, 2 critical).",
        "memory_search" to "Recall from long-term memory BY MEANING. Call it FIRST for any question about the past, habits, preferences, people, addresses, agreements or \"what did we discuss\" when the fact is not already in the context — never guess and never substitute memory_list or a file search. Searches text and tags taking synonyms and word forms into account (e.g. \"tax office\" will find an entry about the FTS), ranks by relevance and importance, and returns a relevance score for each entry.",
        "memory_list" to "Show the saved long-term memory (id, importance, tags, text) — this way you can find an id for memory_delete. All filters are optional: query — text substring (case-insensitive), tag — substring in tags (case-insensitive), kind — kind of records (memory by default), sinceDays — only those created/updated in the last N days, limit — how many to show (30 by default, maximum 100), orderBy — recent (newest, by default) or importance (important).",
        "memory_delete" to "Delete a long-term memory record by id (id from memory_list or memory_search). Deletes only memory, does not touch notes.",
        "profile_list" to "Show the saved user PROFILE — long-term facts about the user themself (name, stable preferences, important persistent context) that they explicitly asked to remember. This profile is shared across ALL chats and is always injected into the context. Read-only, changes nothing.",
        "profile_set" to "Save or update ONE long-term fact in the shared user profile (shared across all chats). text — the fact itself in one phrase (required); id — the id of an existing record from profile_list if it needs to be changed. Only for what the user explicitly asks to remember between chats (name, persistent preferences, important recurring context); NOT for one-off details — use memory_save for those. Identical text is not duplicated again; too long or empty text is honestly rejected.",
        "profile_forget" to "Delete a fact from the shared user profile: id — delete one record (id from profile_list), or all=true — clear the entire profile. Irreversible.",
        "opinion_set" to "Form an opinion (stance) with a confidence. Confidence 0..1 decays over time (half over 90 days), so a fresh opinion weighs more than an old one. If this opinion supersedes a previous one — specify supersedesId (the id of the old opinion from opinion_list): the old one is kept in history but is no longer recalled.",
        "opinion_list" to "Show opinions (id, effective confidence taking decay into account, text); those displaced by new opinions are hidden by default. limit — how many to show (20 by default, maximum 50), includeRetired — show the displaced ones too (false by default).",
        "todo_add" to "Add a task to the to-do list.",
        "todo_list" to "Show the to-do list (completed ones are marked [x]).",
        "todo_done" to "Mark a task as done by id (from todo_list).",
        "todo_delete" to "Delete a task from the to-do list by id.",
        "schedule_create" to "Create a scheduled task (analogous to cron): the agent will run task itself at the specified time. time — \"HH:MM\" (24h). days — \"daily\" (or every day), \"weekdays\" (weekdays), \"weekends\" (weekends), or a list of days \"mon,wed,fri\"/\"пн,ср,пт\" (daily by default). For example: every morning at 08:00 send the USD rate and the weather.",
        "schedule_list" to "Show the list of all scheduled tasks (id, time, days, status, task text).",
        "schedule_delete" to "Delete a scheduled task. Specify id (from schedule_list) or query — a substring of the task text; all matching ones are deleted.",
        "schedule_toggle" to "Enable or disable a scheduled task (without deleting it). Specify id or query, and enabled (true/false).",
        "reminder_set" to "Set a one-off reminder (\"remind me in 5 minutes …\"). For a relative time specify delay: 30s, 5m, 2h, 1d, or just a number of minutes (5). For an absolute one — at: epoch time or HH:MM (the nearest occurrence). Exactly one of delay/at. Fires once; delivery may be slightly delayed.",
        "clipboard_get" to "Get the current clipboard contents (text). Returns an empty string if the clipboard is empty.",
        "clipboard_set" to "Write text to the clipboard. To paste text into another app, copy it from the clipboard via clipboard_get.",
        "clipboard_history_list" to "Show the recent clipboard history (number, time, source, and a short snippet). Read-only. Pass the record number to clipboard_history_get. Honest about the platform limitation: Android 10+ does not allow reading the clipboard in the background, so the history contains only what Meha itself copied (`clipboard_set`) and what was in the clipboard at the moment the app was opened (when it has focus). Text copied in another app will appear here not immediately but when you return to Meha.",
        "clipboard_history_get" to "Fetch the FULL text from the clipboard history to then paste it (for example, via clipboard_set or ui_type). Specify index (the number from clipboard_history_list, 1 — the newest) or id; without parameters it returns the most recent snippet — this is \"smart paste\". Honestly reports if there is no history or the record is not found; the system clipboard itself is not changed.",
        "clipboard_history_clear" to "Clear the clipboard history (all saved records). The action is not dangerous and is performed immediately, without confirmation. The system clipboard itself is not cleared — for that use clipboard_set. Honestly reports how many records were deleted.",
        "translate_text" to "Translate text into the specified language using the configured language model (no external translation service needed). Pass the text to translate in text, the target language in to (code ru/en/de or the name \"German\"), optionally the source language in from and the style in tone (neutral/formal/colloquial). The tool returns only the translation itself, without explanations, and honestly reports an error if the model is not configured or did not respond.",
        "translate_chat" to "Translate the latest messages of a conversation into the specified language using the configured language model (no external translation service needed). Pass the target language in to (code ru/en/de or the name \"German\"), optionally the source language in from, the chat identifier in chat_id (the most recent chat by default) and the number of recent messages in limit (20 by default, maximum 50). Messages are translated in a single request and returned as a line-by-line list (author + translation) in the original order; the chat contents are not changed. Honestly reports if the chat is not found, is empty, or the model is not configured.",
        "translate_screen" to "Translate the text currently visible on the screen: capture the screen via the accessibility service, recognize the text with offline OCR, and translate it into the specified language with the configured language model. Pass the target language in to (code ru/en/de or the name \"German\"), optionally the source language in from; include_original=true will additionally return the recognized original. Requires the Meha accessibility service to be enabled; honestly reports if the screen could not be captured (for example, a protected window) or the text was not recognized.",
        "chat_list" to "Show recent chats with their identifier, title, update time, message count, and a short snippet of the last message. Pass limit — how many chats to return (20 by default, maximum 50). Read-only: changes nothing and does not inject the contents of other chats into the current response. Call it only when the user explicitly asks to show or find their chats.",
        "chat_search" to "Find Meha CHATS and their messages by a text query (these are in-app chats, not SMS/messengers — use message_search for personal messages). Searches for matches in chat titles and in message text, returns the chat identifier and title and short snippets of the matches. Pass query — the text to search for, optionally limit — how many chats to return (20 by default, maximum 50). Read-only, privacy is preserved: the result stays a tool response and is not injected into the current chat automatically. Call only at the user's explicit request; if there are no matches, it honestly reports so.",
        "chat_recall" to "Briefly retell (summarize) another conversation using the configured language model: chat_id — the chat identifier (the most recent by default), limit — how many recent messages to take into account (30 by default, maximum 100). Returns a short summary in Russian (key points, decisions, open questions). Read-only: the chat contents are not changed. Honestly reports if the chat is not found, is empty, or the model is not configured, and NEVER makes up the conversation's content.",
        "chat_create" to "Create a new chat and return its identifier and title. Pass title — the chat title (optional; \"Новый чат\" by default). Call only if the user explicitly asks to create a new chat.",
        "chat_rename" to "Rename a chat: title — the new title, chat_id — the chat identifier (you may OMIT it: then the current chat you are speaking in is used by default). Changes only the chat title; messages are not affected. Honestly reports if the chat is not found.",
        "chat_delete" to "Delete a chat together with all its messages (cascading). chat_id — the chat identifier (you may OMIT it: then the current chat is used by default), confirm — confirmation (must be true). The action is irreversible: the chat and all its messages are deleted permanently and cannot be restored. Honestly reports if the chat is not found.",
        "chat_pin" to "Pin or unpin a chat in the list: chat_id — the chat identifier (you may OMIT it: then the current chat is used by default), pinned — true to pin (true by default), false to unpin. Pinned chats are shown at the top; the order for chat_recall does not change. Honestly reports if the chat is not found.",
        "chat_clear_messages" to "Clear a chat's messages while keeping the chat itself (the title and the chat itself are preserved). chat_id — the chat identifier (you may OMIT it: then the current chat is used by default), confirm — confirmation (must be true). The action is irreversible: the messages are deleted permanently and cannot be restored. Honestly reports if the chat is not found.",
        "chat_export" to "Export a chat's conversation to a file. chat_id — the chat identifier (you may OMIT it: then the current chat is used by default), format — md, txt, or json (md by default), path — an optional absolute path to the file (<filesDir>/exports/chat-<id>-<time>.<extension> by default). Returns the real file path and the number of messages. Read-only regarding chat data. Honestly reports if the chat is not found or the file was not written.",
        "device_info" to "Get device information: model, manufacturer, Android, battery charge, memory size.",
        "device_facts_list" to "Show the cache of device facts: key, category, when updated, whether it is fresh, value size. Facts are remembered information about the phone (model, Android, apps, volumes, roles, network, special access, etc.) that the agent reuses instead of rescanning.",
        "device_facts_get" to "Get a single device fact by key. If the fact in the cache is fresh, it returns it; if it is stale, it collects it again and honestly says that it updated it. Keys: device_model, android_version, screen, installed_apps, default_roles, home_launcher, storage_volumes, storage_space, current_network, accounts_count, contacts_count, sensors, special_access, locale_timezone, own_app_version.",
        "device_facts_refresh" to "Rebuild the device fact(s) from the real Android APIs and save them to the cache. Specify key to update one fact, or omit it (or key=all) to update all available facts.",
        "device_facts_forget" to "Delete the device fact(s) from the cache and write the reason MANUAL to the log. Specify key to delete one fact, or omit it (or key=all) to clear the entire fact cache.",
        "notification_post" to "Send a simple notification to the device with the specified title and text. Useful for telling the user about a task's result. Specify chat_id so that tapping the notification opens the desired chat.",
        "location_get" to "Get the device's current coordinates (latitude/longitude) via the GPS/network/passive providers.",
        "location_last_known" to "The last known location (without requesting updates). Reads the saved GPS/network fix; if there is none, it honestly reports so.",
        "location_updates_start" to "Start a stream of location updates. Launches a foreground service of type location with LocationManager.requestLocationUpdates and accumulates points (history is bounded). For background updates, the \"Location always\" permission (ACCESS_BACKGROUND_LOCATION) is additionally required.",
        "location_updates_stop" to "Stop the stream of location updates and unsubscribe from the provider."
    )

    private fun descriptions6(): Map<String, String> = mapOf(
        "location_updates_get" to "The most recently received location points. Returns coordinates, accuracy, and time (newest first).",
        "geofence_add" to "Add a geofence. On entry/exit (and optionally dwelling) a notification will arrive. Requires the ACCESS_FINE_LOCATION and ACCESS_BACKGROUND_LOCATION permissions; limit ~100 geofences.",
        "geofence_remove" to "Delete a geofence by its identifier.",
        "geofence_list" to "List of geofences registered by the app: center, radius, expiration, and last event.",
        "calendar_events" to "List of calendar events in the range [from, to] (from/to — epoch millis or an ISO date). Returns id, title, start, end.",
        "calendar_list" to "List of the device's calendars: id, title, account, color, visibility.",
        "calendar_instances" to "Calendar events (instances) for a period. from/to — epoch millis or an ISO date. Returns title, start, end, and calendar.",
        "calendar_accounts_list" to "List of the device's calendar accounts and calendars (Google, Microsoft, local) via CalendarContract: account name and type, calendar title, visibility, event sync, access level. Provider sync is performed by the system calendar apps — the app only reads them. If there are no accounts, it honestly reports so.",
        "calendar_device_events" to "Events across all the device's calendars (Google, Microsoft, local) for the range [from, to] (epoch millis or an ISO date), with recurrences. For each event it returns title, start, end, calendar, and account. The range is bounded (no more than ~400 days). If there are no events, an honest empty result.",
        "calendar_create" to "Create a calendar event with a title, start, end, and (optionally) a reminder N minutes before the start.",
        "calendar_update" to "Update a calendar event by id (id from calendar_events). Pass only the fields to change: title, start, end, reminder.",
        "calendar_delete" to "Delete a calendar event by id (id from calendar_events). Irreversible, without confirmation — only at the user's direct request.",
        "calendar_attendees_set" to "Add an attendee to a calendar event. event_id — the event id, email — the attendee's address, name — the name (optional). The action modifies calendar data.",
        "calendar_reminders_set" to "Add a reminder to a calendar event. event_id — the event id, minutes — how many minutes before the start (can be negative). The action modifies calendar data.",
        "calendar_recurring_create" to "Create a recurring calendar event. title, dtstart (ISO date or epoch millis), and rrule (RFC5545, e.g. FREQ=WEEKLY;COUNT=10) are required; specify duration (e.g. PT1H) or dtend. calendar_id is optional. The action modifies calendar data.",
        "saf_pick_tree" to "Ask the user to pick a folder (SAF) and save persistent access to it.",
        "saf_grants_list" to "Show the saved folders (SAF grants) and check whether they are currently available.",
        "saf_grant_remove" to "Revoke the saved access to a folder (SAF grant).",
        "saf_grant_test" to "Check whether the saved SAF grant is available right now.",
        "saf_list" to "Show the contents of a folder inside a saved SAF grant. path — the path relative to the root of the selected folder (empty = root).",
        "saf_read" to "Read a text file inside a saved SAF grant. path — the path to the file relative to the root of the selected folder.",
        "saf_write" to "Write text content to a file inside a saved SAF grant; creates intermediate folders and the file if needed.",
        "saf_mkdir" to "Create a folder (including intermediate ones) inside a saved SAF grant.",
        "saf_delete" to "Delete a file or folder inside a saved SAF grant. Irreversible, without confirmation — only at the user's direct request.",
        "saf_create_document" to "Create a document via the system \"Save as\" dialog (SAF).",
        "folder_watch_start" to "Start watching a folder (create/modify/delete/move events). The watch lives only until the app restarts and is not recursive: only the specified folder is watched, not subfolders.",
        "folder_watch_stop" to "Stop watching a folder by its watch_id.",
        "folder_watch_list" to "Show active folder watches. After the app restarts there are no watches.",
        "folder_watch_events" to "Show the most recent accumulated watch events (the buffer is not cleared).",
        "folder_watch_clear" to "Clear the buffer of accumulated watch events.",
        "saf_open_document" to "Pick a document via the system dialog and return its metadata (and the text, if it is a small text file).",
        "doc_info" to "Determine the type and structure of a document (PDF, DOCX, XLSX, PPTX, ODT/ODS/ODP, RTF, CSV, text) and show metadata.",
        "doc_read" to "Extract text from a document (PDF, DOCX, XLSX, PPTX, ODT/ODS/ODP, RTF, CSV/TSV, TXT/MD/JSON/XML/HTML).",
        "doc_create" to "Create a document of the specified format based on the path extension (PDF, DOCX, XLSX, ODT/ODS, RTF, CSV/TSV, TXT/MD/JSON/XML/HTML). PDF is written with an embedded Unicode font, so Cyrillic is preserved correctly.",
        "doc_edit" to "Modify an existing document: append to the end (`append`) or replace text (`replace`). Appending to a PDF uses an embedded Unicode font (Cyrillic is supported).",
        "pdf_merge" to "Merge several PDFs into one file (in the specified order).",
        "pdf_split" to "Extract a range of pages from a PDF into a new file.",
        "pdf_create" to "Create a PDF from text: A4, margins, automatic wrapping of long lines, several pages if needed. Cyrillic is preserved (embedded Unicode font). An optional `title` is written to the metadata. The resulting file is verified (the %PDF- signature, size, page count).",
        "pdf_from_markdown" to "Create a PDF from simple Markdown: headings `#`/`##`, lists `-`/`*`/`+`, bold `**text**`, paragraphs. Unsupported syntax (tables, code blocks, quotes `>`, links, numbered lists) is inserted as plain text. A4, line wrapping, several pages; Cyrillic is preserved.",
        "pdf_ocr" to "Recognize text on a scanned PDF page (page render + local offline OCR: Tesseract, Cyrillic and Latin; on engine failure — Latin ML Kit).",
        "pdf_rotate" to "Rotate PDF pages by 90/180/270° into a new file. Without `pages` all pages are rotated; the rotation is added to the current one.",
        "pdf_watermark" to "Draw a centered text watermark over PDF pages into a new file. Cyrillic is supported (embedded Unicode font).",
        "pdf_extract_images" to "Extract embedded images from PDF pages to natural-size PNG files in `output_dir`, named page-001-image-01.png; existing files are skipped without overwrite.",
        "pdf_render_pages" to "Render PDF pages to PNG files at the given DPI (150 by default) in `output_dir`, named page-001.png; existing files are skipped without overwrite.",
        "pdf_compress" to "Reduce a PDF's size where possible into a new file (re-save and, when `max_image_dimension` is set, downscale large images). Honestly reports the resulting size.",
        "pdf_fill_form" to "Fill AcroForm fields from a JSON `fields` object (name→value) into a new file. Reports which fields were filled, not found, or rejected the value; `flatten` bakes the values into the pages.",
        "pdf_pages" to "Show a PDF's structure: the page count and, per page, the size (points/mm) and rotation. Read-only; the file is not modified.",
        "xlsx_set_cell" to "Write a value to an XLSX cell (e.g. B2).",
        "pdf_from_template" to "Create a PDF from a template: text/Markdown with `{{key}}` placeholders (`{{ key }}` also works) and a JSON object of `values`. Numbers, booleans, arrays, and objects are converted to text. Placeholders without a value are left as is and listed in the response (honestly). A4, line wrapping, Cyrillic is preserved.",
        "pdf_from_template_file" to "Create a PDF from a template taken from an existing local file (the `template_path` parameter): text/Markdown with `{{key}}` placeholders and a JSON object of `values`. Convenient for reusing a template. Placeholders without a value are left as is and listed in the response.",
        "xlsx_set_cells" to "Write several XLSX sheet cells at once in a single call. `cells` is JSON — either an object {\"A1\": \"value\", \"B2\": 42} or an array [{\"cell\": \"A1\", \"value\": \"…\"}]. Addresses are validated before writing; with overwrite=false, cells that are already filled are not overwritten (an honest refusal).",
        "docx_replace" to "Replace text in a DOCX. `replacements` is JSON old→new (literally), `template_values` is JSON key→value with `{{key}}` substitution; they can be combined. Limitation: a placeholder split across several runs is not found. By default the edit is in place (with a backup), or a copy to output_path/file_name.",
        "docx_append" to "Append text to the end of a DOCX as new paragraphs (without formatting: Markdown markup is preserved as plain text). By default the edit is in place (with a backup), or a copy to output_path/file_name.",
        "pptx_create" to "Create a PPTX presentation from a list of slides. `slides` is a JSON array: strings (the first line is the title, the rest are bullets) or objects {\"title\": …, \"lines\": [...]} / {\"title\": …, \"text\": \"a\\nb\"}. The package is built natively (Office Open XML); Cyrillic is preserved.",
        "pptx_edit" to "Edit an existing PPTX: `mode`=append adds slides (`slides`, the same format as pptx_create); `mode`=replace replaces text on slide(s): `find` and `replace`, optional `slide` (1-based; all by default). The edit is in place with a backup. Limitation: replacement works within a single `<a:t>` node (text split across runs is not found).",
        "odp_create" to "Create an ODP (OpenDocument) presentation from a list of slides. `slides` is a JSON array of strings or objects {\"title\": …, \"lines\": [...]}, as in pptx_create.",
        "xlsx_style" to "Apply basic formatting to an XLSX cell range: `bold`, `fill_color` (#RRGGBB fill), `number_format` (number format). `range` is A1 or A1:C3. The edit is in place with a backup; cell values are not changed. It reports honestly if the stylesheet cannot be modified.",
        "office_convert" to "Convert a document between the supported formats by content (text/cells), without a layout engine. Text (DOCX/ODT/RTF/TXT/MD/HTML) → TXT/MD/HTML/DOCX/ODT/RTF; tables (XLSX/ODS/CSV/TSV) → CSV/TSV/JSON/TXT/MD/XLSX/ODS; presentations (PPTX/ODP) → TXT/MD. Formatting is not preserved. Office→PDF is not supported — use pdf_create/pdf_from_markdown.",
        "office_merge" to "Merge several same-type documents into one. Text — concatenation (separator `separator`, empty by default); tables — row append (with a marker row naming each file); presentations — the slides of all files. The result format follows the `destination` extension (pptx/odp/csv/txt/md etc.). Content only; formatting is not preserved.",
        "office_split" to "Split a document into parts in the `output_dir` directory. Presentations — one file per slide (slide-001.pptx/odp); tables — one CSV per sheet (sheet-001-*.csv); text — parts separated by the `separator` line (--- by default). Existing files are skipped unless overwrite. Content only; formatting is not preserved.",
        "doc_tables" to "Extract a table (XLSX/ODS/CSV/TSV) as STRUCTURED data: a JSON object {\"format\": …, \"rowCount\": N, \"truncated\": bool, \"rows\": [[…]]}, where `rows` is an array of rows, each row an array of cells. `max_rows` caps the output (1000 by default); the `truncated` flag honestly reports the cut.",
        "settings_system_get" to "Read the value of a system setting (Settings.System) by key. Read-only, no permissions needed; if the key is not found or is not set, an honest message about this is returned rather than an empty value.",
        "settings_secure_get" to "Read the value of a secure setting (Settings.Secure) by key. Read-only, no permissions needed (writing Secure is unavailable to ordinary apps); if the key is not found or is not set, an honest message about this is returned.",
        "settings_global_get" to "Read the value of a global setting (Settings.Global) by key. Read-only, no permissions needed (writing Global is unavailable to ordinary apps); if the key is not found or is not set, an honest message about this is returned.",
        "settings_known_keys" to "Show a list of frequently used Settings keys (System/Secure/Global) for reference."
    )

    private fun descriptions7(): Map<String, String> = mapOf(
        "settings_system_set" to "Write a value to Settings.System (requires WRITE_SETTINGS access). It changes the device's system setting and is performed immediately, without confirmation: call it only at the user's direct request. The tool checks access, writes the value, and honestly reports if the key could not be written or is ignored by the manufacturer.",
        "settings_write_access_request" to "Open the system \"Modify system settings\" screen so the user can grant the app WRITE_SETTINGS access (needed for settings_system_set). Requires no permissions.",
        "dnd_set" to "Enable/disable Do Not Disturb mode (requires notification policy access). mode: off or all — disable, priority — priority only, alarms — alarms only, none — full Do Not Disturb. On Android 15 the app affects the mode via an implicit rule, and the system applies the strictest of the active rules — the tool honestly reports the actual outcome.",
        "alarm_exact_set" to "Set an exact alarm/reminder for a specific time and save it on the device. time — epoch milliseconds or the time HH:MM (the nearest occurrence today/tomorrow); a past time is rejected. If exact alarms are disallowed by the system (SCHEDULE_EXACT_ALARM), the tool honestly reports that firing may be inexact.",
        "alarm_exact_list" to "Show the saved exact alarms: id, time, label, and whether it has fired.",
        "alarm_exact_cancel" to "Cancel and delete a saved exact alarm by id (see alarm_exact_list).",
        "automation_rule_create" to "Create an automation rule \"when a trigger OCCURS → perform an action\". trigger_type: time (trigger_config = \"HH:MM\", fires inexactly via WorkManager), charging, battery_low, screen_on, screen_off (work only while the app process is alive), boot (after a reboot), wifi_connected, network (when a network appears), app_opened (trigger_config = package name, requires enabled accessibility access), notification (trigger_config = package name or text substring, requires notification access), sms (trigger_config = \"from=substring of the sender; text_contains=text substring\" — both filters are optional and case-insensitive; without them it fires on any incoming SMS; requires RECEIVE_SMS access). Deep triggers: calendar_event (trigger_config = \"title_contains=…; minutes_before=5\", checks the calendar about every 15 min via WorkManager, requires READ_CALENDAR), ble_presence (trigger_config = \"address=MAC\" or \"name_contains=…\", optionally \"; rssi_threshold=-70 (from -100 to 0 dBm); timeout_sec=15\"), sound_event (trigger_config = \"level_threshold=-20 (from -90 to 0 dBFS); sustain_ms=500\" — sustained loud sound), geo_distance (trigger_config = \"distance_m=300 (from 10 to 1000000 m); min_interval_sec=600 (from 60 to 86400)\" — fires when the phone has moved from the last saved point; requires ACCESS_FINE_LOCATION, works only while the process is alive), wifi_open (trigger_config = \"ssid_contains=…; min_interval_sec=600 (from 60 to 86400)\" — both options are optional: without ssid_contains it fires on any OPEN network (without encryption), the same network does not fire again; requires NEARBY_WIFI_DEVICES (API 31+) or ACCESS_FINE_LOCATION, works only while the process is alive). Health triggers: heart_rate_threshold (trigger_config = \"above=100\" or \"below=50\" (bpm 20–250, strict comparison) and an optional \"; dwell_sec=10\" (0–3600 s) — heart rate above/below the threshold; requires a TYPE_HEART_RATE sensor and the BODY_SENSORS permission), steps_reached (trigger_config = \"target=5000\", steps 1–1000000 — the delta of the cumulative TYPE_STEP_COUNTER from the moment of subscribing; requires ACTIVITY_RECOGNITION), no_movement (trigger_config = \"minutes=30\", 1–1440 — no significant movement; the source is TYPE_SIGNIFICANT_MOTION or the accelerometer), sleep_window (trigger_config = \"start=23:00; end=07:00\" — an HONEST HEURISTIC: a time window + screen off + phone stationary; Android has no public sleep-phase sensor, this is NOT a sleep measurement). If there is no suitable sensor on the device, the tool honestly refuses. action_type: start_chat (action_config = text of the request to the agent), agent_task (action_config = a TEMPLATE of the request that is filled with data from the fired event: {trigger} — event type, {value} — event value, {payload} — event payload, {name} — rule name; missing values become empty, unknown substitutions are left as is) or notify (action_config = \"title | text\", optionally \"| chatId\"). The tool honestly refuses on an invalid config.",
        "automation_rule_list" to "Show all automation rules: id, status (on/off), trigger and action, time of the last firing.",
        "automation_rule_toggle" to "Enable or disable an automation rule by id (see automation_rule_list) without deleting it. Disabling/enabling a time rule reschedules it.",
        "automation_rule_delete" to "Delete an automation rule by id (see automation_rule_list).",
        "events_recent" to "Show the most recent fired automation events (the \"what happened overnight\" log): time · trigger type · rule · value. Only events that actually started a rule are stored (not raw sensor readings); the log is limited to the last 500 events over 14 days. Optional parameters: limit (1–100, 20 by default) and type (filter by trigger type, e.g. battery_low).",
        "automation_suggestions" to "Predictive automation suggestions based on real app usage (UsageStats over the last 14 days). Returns stable patterns — which app you usually open at what time — as well as a \"night mode\" heuristic — with a confidence score and an already prepared rule configuration (trigger time + action notify). Requires the \"Usage access\" special access (PACKAGE_USAGE_STATS); without it, it honestly reports that access has not been granted. Accepted and dismissed suggestions are no longer repeated. To accept a suggestion, create a rule via automation_rule_create.",
        "automation_suggestions_simulate" to "SERVICE tool for the debug build only: runs a synthetic usage history (5 of 7 days) through the real suggestion analyzer. action: analyze — show the found suggestions, accept — accept the first (or the one with the specified id) and create a real rule, dismiss — hide. In a release build it honestly refuses. Needed for on-device testing without a real history.",
        "locale_app_set" to "Change the app locale (per-app language, Android 13 / API 33+ only). lang — a language tag, e.g. ru or en-US; optional mode: system (revert to the system locale) or default (set lang). The tool reads the result back and honestly reports what the system actually applied; on API below 33 it reports unavailability.",
        "wifi_connect_specifier" to "Connect to a Wi-Fi network by SSID and password without geolocation, via WifiNetworkSpecifier (Android 10 / API 29+). Waits for the real result (the network became available / the system did not find the network) with a timeout_sec limit; the network request is released after the response.",
        "wifi_suggest_add" to "Suggest a Wi-Fi network to the system for auto-connection (WifiNetworkSuggestion, Android 10 / API 29+). The decision on the actual connection is made by the system, not the app: the tool merely adds the suggestion and honestly reports the system's response status code.",
        "wifi_direct_discover" to "Find Wi-Fi Direct (P2P) peers within timeout_sec seconds. Returns the name, MAC address, and status of each peer. Requires Wi-Fi enabled and a permission (NEARBY_WIFI_DEVICES on Android 12+, otherwise geolocation); honestly reports if Wi-Fi Direct is unavailable.",
        "wifi_direct_connect" to "Connect to a Wi-Fi Direct peer by MAC address (device_address) and report the real result: whether a group was formed, the device role, and the group owner address. An optional wps_pin is passed for WPS PIN connection.",
        "wifi_direct_disconnect" to "Tear down Wi-Fi Direct: remove the current P2P group (removeGroup) and cancel the pending connection (cancelConnect). Honestly reports the result of each operation; if there was no group, the error code is normal.",
        "hotspot_local_start" to "Bring up a local-only hotspot without internet (startLocalOnlyHotspot, Android 12+ / API 31+) and return its SSID and password. No internet is shared with clients. The hotspot works as long as the app holds the reservation; stop it via hotspot_local_stop.",
        "hotspot_local_stop" to "Stop a previously started local-only hotspot (release the startLocalOnlyHotspot reservation) and, where possible, check the real state of the hotspot.",
        "wifi_rtt_range" to "Estimate the distance to an access point via Wi-Fi RTT / 802.11mc (Android 9 / API 28+). bssid is optional (the current access point by default). Returns the distance and the error margin, and honestly reports if the hardware or the access point does not support RTT.",
        "app_ops_self" to "Read the AppOps (app-ops) state for the app itself: allowed, denied, or \"default\". The optional op is a specific operation (e.g. android:fine_location, android:camera, android:record_audio); if it is not specified, a useful set is output. Only the app's own UID is read: the operation state of other packages is unavailable to an ordinary app.",
        "shortcut_pin" to "Ask the user to pin a shortcut to the home screen. id — the shortcut's unique identifier, label — the label, intent_uri — an optional link that the shortcut opens. Shows the launcher's system dialog and honestly reports whether the user confirmed the pin; if the launcher does not support pinning, it reports so.",
        "widget_pin" to "Ask the user to pin the app's widget to the home screen (AppWidgetManager.requestPinAppWidget). Shows the system dialog; if the app has no widgets of its own or the launcher does not support pinning, it honestly reports so.",
        "app_data_usage" to "Show the network traffic of a specific app (received/sent/total) over a period. Requires the special access \"Usage access\" (PACKAGE_USAGE_STATS); without it, it honestly reports that access has not been granted.",
        "network_data_usage" to "Show the device's total traffic over a period, split by Wi-Fi and mobile network. Requires the special access \"Usage access\" (PACKAGE_USAGE_STATS); without it, it honestly reports that access has not been granted.",
        "notification_extras" to "Show the full text of a specific notification (title, text, big text, subtext) and its actions, including whether a quick-reply field (RemoteInput) is present. Specify a key from notification_list or a package. Requires notification access to be enabled; otherwise it honestly reports so.",
        "notification_snooze" to "Snooze a notification for the specified time (NotificationListenerService.snoozeNotification). Requires notification access to be enabled; it honestly reports if the system did not confirm the snooze.",
        "notifications_digest" to "A digest of recently captured notifications (SMS/messengers/email) by app, with the important ones flagged. Works for packages from messaging_index_config when notification access is granted. since — how many minutes ago (or a timestamp in ms), limit — how many entries. If there are no captures, it honestly reports so.",
        "notification_triage_simulate" to "Internal (debug only): run a synthetic notification through triage and return the importance, category, summary, and whether it was written to memory. Needed for testing without third-party apps; it refuses in a release build.",
        "notification_rules_list" to "Show Meha's user notification rules: importance per app (no notifications / normal / important), quiet hours, and summary mode. Read-only — changes nothing. Not to be confused with system notification channels (for those, notification_channels_list).",
        "notification_rules_set" to "Set or update Meha's notification rules. All parameters are optional: package + importance (+ note) set an app rule; quiet_start and quiet_end enable quiet hours (HH:MM, the window may cross midnight); quiet_off disables them; quiet_allow_important=true lets important notifications through during quiet hours; summary_mode controls the digest (off / important_only / all); remove=true deletes an app rule (or all rules if package is not specified). Meha does not touch system notifications: a \"no notifications\" rule only stops showing them in Meha's digest and memory.",
        "sms_photo_rule_get" to "Show the SMS photo-report setting: whether it is enabled, the reply prefix, and the per-number cooldown. Read-only — changes nothing. When enabled, Meha replies with a photo (with a description) only to commands from TRUSTED numbers: «фото», «сделай фото», «что на фото», «снимок», «photo», «picture», «take a photo», «what do you see».",
        "sms_photo_rule_set" to "Enable/disable the SMS photo report and configure it. enabled — whether the report is enabled (default OFF); reply_text_prefix — the reply prefix text (optional); cooldown_seconds — the minimum interval between replies to one number in seconds (default 60, 0 — no cooldown). It replies only to trusted numbers (trusted_source_add type=sms) and never to service, short-code, and emergency numbers. When the model has no Vision, an honest offline OCR arrives with a note that the image was not sent to the model.",
        "job_schedule" to "Schedule a periodic task via JobScheduler. interval_min — the period in minutes, minimum 15 (the system does not support more frequent periodic tasks; a smaller value is raised to 15). The optional requires_charging and requires_network set the run conditions. Specify prompt (the request text — on firing, the agent launches in a new chat) or notify_text (the notification text); at least one of them is required. Returns an id for job_list / job_cancel. Firing is inexact: the system may delay the run, especially in Doze mode.",
        "job_list" to "Show the JobScheduler tasks scheduled by this app: id, period, run conditions, and the saved text (prompt or notify_text). The list is read directly from the system (getAllPendingJobs) — these are the real pending tasks, not the app's internal list.",
        "job_cancel" to "Cancel a JobScheduler task by id and verify that it has actually disappeared from the scheduler. It also deletes the saved task description. If there is no task with that id — it honestly reports so.",
        "sensor_subscribe" to "Collect sensor readings over a time window: subscribe to SensorEventListener for duration_ms, accumulate samples, and report statistics (min/max/avg per axis + the latest values). type — sensor name (accelerometer, gyroscope, magnetometer, light, proximity, pressure, gravity, linear_acceleration, rotation_vector, step_counter, humidity, temperature). duration_ms defaults to 2000, maximum 10000; rate: normal | fast | game. The listener is always removed at the end of the window. If the sensor is absent — an honest refusal. For step_counter/step_detector the ACTIVITY_RECOGNITION permission is required. For rates above 200 Hz, HIGH_SAMPLING_RATE_SENSORS is required (declared; otherwise the system caps the rate).",
        "sensor_unsubscribe" to "Stop an active sensor subscription (if one is still running) and remove the listener. If there is no active subscription — it honestly reports so. Normal sensor_subscribe windows end on their own; this tool is for stopping early.",
        "sensor_trigger_subscribe" to "Subscribe to a trigger/wake-up sensor (e.g. significant_motion) via requestTriggerSensor and return the triggers over the window. type — sensor name (significant_motion, step_detector, etc.); duration_ms defaults to 30000, maximum 300000. The subscription is always cancelled at the end of the window. If the sensor is not a trigger/wake-up sensor or is absent — an honest refusal.",
        "health_sensors_list" to "Show the health sensors actually present on the device: heart rate (heart_rate), step counter (step_counter), step detector (step_detector), significant motion (significant_motion), accelerometer, and a vendor SpO₂ sensor (if present). For each sensor it returns the type, name, vendor, maximum range, whether it is a wake-up sensor, and whether a permission is required (BODY_SENSORS for heart rate, ACTIVITY_RECOGNITION for steps). Absent sensors are honestly marked as unavailable; the tool measures nothing and changes nothing.",
        "ui_gesture_multi" to "Multi-touch gesture (pinch/zoom) via accessibility services: two simultaneous strokes move symmetrically around the center (cx, cy) from start_distance to end_distance pixels. The tool waits for system confirmation. Requires accessibility access to be enabled and canPerformGestures=true; otherwise it honestly reports the refusal.",
        "ui_trigger_subscribe" to "Track window and app-switch events via accessibility services and return the latest ones. action: start (begin recording), stop (stop recording, events are kept), list (return the latest events: time, package, class, type). limit — how many events to return, default 50 (up to 200 are stored). Requires accessibility access to be enabled; otherwise it honestly reports so.",
        "ui_macro_start" to "Start recording a UI macro: subsequent ui_tap, ui_long_press, ui_swipe, ui_scroll, ui_type, key_event, and ui_gesture_multi actions are recorded in order. name — macro name (required). A previous unfinished recording is cancelled. The accessibility service is not required for the recording itself, but without it the actions are not performed.",
        "ui_macro_stop" to "Stop recording a UI macro and save the recorded steps under the name from ui_macro_start. Returns the id and the number of steps. If no recording is active or no steps were recorded — an honest error.",
        "ui_macro_list" to "List of saved UI macros: id, name, number of steps.",
        "ui_macro_run" to "Replay a saved UI macro by id or name: the steps are executed in order with a short pause. After each step that has a selector (text/desc/id), it is verified that the element is still on screen. If the accessibility service has stopped, a step failed, or the check failed — playback is interrupted, indicating the step number and the expected element. Success is reported only after all steps.",
        "ui_macro_delete" to "Delete a saved UI macro by id or name.",
        "screen_map_get" to "Show the screen map — what the agent has already seen in apps (CACHE). Without app_package it returns a summary of known apps; with app_package, the remembered screens of that app: title, label, when last seen, and notable interactive elements. The map may be stale: before acting, always check the live screen via ui_tree. Read-only."
    )

    private fun descriptions8(): Map<String, String> = mapOf(
        "screen_map_list" to "A brief list of apps in the screen map: how many screens are remembered and when last seen. This is a CACHE of what the agent has already seen, and it may be stale; before acting, check the live screen via ui_tree. Read-only.",
        "screen_map_forget" to "Forget the screen map: with app_package — only that app, without it — the entire map. The map is a cache of what has been seen, so clearing it breaks nothing: it will be repopulated the next time the app is used.",
        "skill_list" to "List of saved app skills: id, name, app, number of parameters and steps. A skill is a repeatable scenario of working in an app with parameters (e.g. «Order a taxi» with an {address} parameter) that can be replayed. Read-only; creation and editing — skill_save, details — skill_get.",
        "skill_get" to "Show the full skill definition (JSON) by id or name. Pass id (preferred) or name. Use it to check the steps and parameters before skill_save. Read-only.",
        "skill_save" to "Create or update an app skill. name is required; id — to update an existing one (otherwise the update matches by name). steps — a STRING with a JSON array of steps; each step has a type field:\n{\"type\":\"launch_app\",\"packageName\":\"com.app\"}\n{\"type\":\"tap\",\"selector\":{...}}\n{\"type\":\"type\",\"selector\":{...},\"textTemplate\":\"text {parameter}\"}\n{\"type\":\"wait_for_text\",\"selector\":{...},\"timeoutMs\":5000}\n{\"type\":\"scroll\",\"selector\":{...},\"direction\":\"DOWN|UP|LEFT|RIGHT\",\"amount\":1}\n{\"type\":\"key\",\"keyName\":\"BACK|HOME|RECENTS|ENTER\"}\n{\"type\":\"delay\",\"ms\":500}\n{\"type\":\"assert_text\",\"selector\":{...},\"expected\":\"Paid\"}\n{\"type\":\"open_url\",\"urlTemplate\":\"https://site/{query}\"}\nselector — an object with the fields text, textContains, resourceId, contentDescription, className, index (number, optional), and description (a human label); at least one identifying attribute is required (text/textContains/resourceId/contentDescription). parameters — a STRING with a JSON array: {\"name\":\"address\",\"type\":\"TEXT|NUMBER|CHOICE\",\"description\":\"...\",\"required\":true,\"default\":null,\"choices\":[]}. In textTemplate/urlTemplate/expected, substitution of {parameter_name} is supported. Invalid JSON or an unknown step type returns an honest error with the step number — the skill is not saved. Create a skill only at the user's request or for an explicitly recurring scenario; do not create one for one-off actions; first check the existing one via skill_list.",
        "skill_delete" to "Delete a saved skill by id or name. The action is irreversible and is performed immediately, without confirmation: call it only at the user's direct request. If the skill is not found — an honest error, nothing is deleted.",
        "skill_run" to "Run a saved skill: execute its steps in the installed apps via the accessibility service (launching an app, taps, text input, waiting for text, scrolling, system keys, pauses, checks, and opening links). Pass id (preferred) or name, and in values — a JSON object with parameter values {\"parameter\":\"value\"}; required parameters without a value cause an honest refusal. dry_run=true only checks the plan (selectors and parameter substitution) and taps nothing. Returns a step-by-step honest report (step number, action, result); if a step fails, the run is interrupted. Requires Meha's accessibility service to be enabled.",
        "skill_record_start" to "Start recording a skill: subsequent ui_tap, ui_type, ui_scroll, key_event actions are recorded together with the element semantics (id/description/text), so they can later be turned into robust steps. appPackage — an optional app label; if not specified, the app in the active window is taken. To stop and save — skill_record_stop.",
        "skill_record_stop" to "Stop recording a skill and turn it into a ready skill: name — skill name (required), description and appPackage — optional. Actions are converted into steps by semantics (resourceId/contentDescription/text); anything that cannot be expressed without coordinates (swipe, multi-touch, tap by x/y) is honestly skipped with an explanation. Returns the skill id and a brief report: how many steps were created and what was skipped.",
        "skill_export" to "Export skill(s) to JSON (the exact SkillCodec format) for sharing or backup of SKILLS ONLY (chats and memory are not included). Specify the id or name of one skill, or all=true for all of them. Read-only.",
        "skill_import" to "Import skill(s) from JSON (the skill_export format): a single object or an array. Each skill is validated (id, name, steps/selectors); the report is honest for each — imported, skipped, or error. An existing skill (by id or name) is skipped unless overwrite=true.",
        "fgs_start" to "Start a foreground service with an explicit type. type: dataSync | specialUse | shortService. The service itself does nothing — it keeps the process in the foreground. shortService lives no longer than ~3 minutes and stops itself (after 2.5 minutes); dataSync requires FOREGROUND_SERVICE_DATA_SYNC, specialUse — FOREGROUND_SERVICE_SPECIAL_USE (both declared). Starting from the background on Android 12+ is forbidden and returns an honest error. To stop — fgs_stop.",
        "fgs_stop" to "Stop the foreground service started via fgs_start / keep_alive_start, dismiss the notification, and verify that it has actually stopped. If the service is not running — it honestly reports that there is nothing to stop.",
        "keep_alive_start" to "Keep the process alive: a foreground service (specialUse) + PARTIAL_WAKE_LOCK. label — an optional lock label. The wake lock is taken with a hard limit of 1 hour and will be released by the system automatically. It increases battery consumption (the CPU does not go to sleep). If the process is killed by the system, the service will NOT restart itself. To stop — keep_alive_stop.",
        "keep_alive_stop" to "Release the PARTIAL_WAKE_LOCK and stop the foreground service started by keep_alive_start, then verify the real state (whether the lock is released and the service is stopped).",
        "crash_log_list" to "Show saved crash logs (recent app failures): time, exception type, truncated stack, model/API, whether the agent was in the foreground, and the restart number. No more than 50 entries are stored and no longer than 30 days. limit — how many recent entries to show (default 10).",
        "crash_log_clear" to "Clear all saved app crash logs. Returns the number of deleted entries.",
        "self_diagnostics" to "Diagnostics: process uptime, device version and model, the last crash, the restart counter in the current hourly window, the background core state (running or not and the reason), enabled subsystems, the status of the special accesses (battery optimization exemption, exact alarms, notifications), the power state with the power-guard decision, and a brief summary of the reasons for recent process exits. It honestly reports if the core is not running or the data is unavailable.",
        "exit_reasons" to "History of app process termination reasons (ApplicationExitInfo, Android 11+): time, reason (crash, ANR, killed by the system due to memory, stopped by the user, etc.), importance, memory (pss/rss), and the system description. limit — how many recent entries to show (1–64, default 20). On Android below 11 it honestly reports that the history is unavailable.",
        "power_guard_status" to "Power state and the power-guard decision: charge and charging, power-saving mode, thermal status (heat), whether battery optimization is disabled for the app, and whether optional background work is currently being deferred (when the charge is ≤5% without charging or under heavy heat). Runs requested by the user are not deferred.",
        "audit_export" to "Export the tool-call audit log (append-only, hash chain) to a file and return the path. path — the file path relative to the shared storage root (optional; default Documents/andharness_audit_<date_time>.json). If the path ends with .csv — CSV is saved. The tool also re-checks the integrity of the hash chain and reports whether it was broken.",
        "insights_list" to "Proactive insights about your habits, collected ONLY from local device data: recurring actions by day of week (the tool-call log), long-unused skills, and the apps with the most notifications. Meha does not make anything up: an insight appears only with a sufficient number of observations, and if there is little data — it honestly reports that there are no insights yet. kind — an optional filter: routine, stale_skill, notification_load; limit — how many to show (default 20, maximum 100).",
        "number_osint" to "Gather open information about a phone number via free web search (no keys or paid APIs): a 15-second budget, no more than 5 sources. The result is cached by number and is not re-requested while fresh (7 days). If nothing is found — it honestly reports so and makes NOTHING up. It also creates/updates an auto-chat for this number and sends a notification with a link to the chat. force=true — force a re-request, ignoring the cache.",
        "number_profile" to "Local number profile: the normalized form, country/region by prefix (heuristic), and whether a contact was found. The carrier and line type cannot be derived locally from someone else's number — the tool honestly reports this and does not invent data.",
        "auto_number_chat_status" to "Status of the stranger-number auto-chat: whether interception is enabled, whether the CALL_SCREENING role has been granted (without it there is no real call interception — only a fallback via the call log with a delay of up to 15 minutes), whether the core is running, and how many numbers and OSINT records are in the cache.",
        "auto_number_chat_toggle" to "Enable/disable the stranger-number auto-chat and call/SMS interception. It controls the saved interception flag: when enabled, it also starts the background core and sets up periodic call-log monitoring (every 15 minutes, with a delayed result). Calls are never blocked.",
        "unknown_numbers_list" to "List of stranger numbers: for each — the created chat, the last event type and time, plus stranger numbers from the call log for which an auto-chat has not yet been created. It honestly indicates whether interception is enabled and whether the CALL_SCREENING role has been granted.",
        "messaging_index_config" to "Configuration of the incremental personal-base indexer (people and their interactions): opt-in, the list of messenger/email packages, and the state of the one-time backfill (contacts, SMS, call log). action=get — show the real state (flag, packages, the Notification Listener special access, source permissions, cursors); action=set — apply enabled and (optionally) packages. When enabled, a bounded backfill is scheduled; when disabled, incremental ingestion stops. Honestly: notification indexing requires the Notification Listener special access, and nothing is indexed while opt-in is off. Metadata and a short summary (≤80 characters) are written to interactions, while the FULL text of messages is stored in the app's encrypted database (stored_messages) for search and summaries — the data stays only on the device and is not logged.",
        "messaging_index_app_add" to "Add an installed app to the personal-base indexing list by name or package: app — the name (e.g. «MAX», «Telegram») or the package (org.telegram.messenger). The tool finds the installed app, adds its package to the messenger/email list, enables indexing, and schedules a one-time backfill (contacts, SMS, call log). If the query matches several apps or none — it changes nothing, lists the candidates, and asks to specify the exact package: it does not guess. It honestly reports the real state of indexing, the Notification Listener special access, and source permissions.",
        "messaging_index_app_remove" to "Remove an app from the personal-base indexing list by name or package: app — the name or package. The tool finds the installed app and removes its package from the messenger/email list; indexing is not disabled. Honestly: if the last saved package is removed, the store falls back to the default list (MessagingIndexPolicy.DEFAULT_PACKAGES), i.e. the default list effectively applies again. If several apps are found or none — it changes nothing, lists the candidates, and asks to specify the exact package: it does not guess.",
        "person_find" to "Find a person in the unified personal base («which Seryoga»). query — a name, alias, phone, email, or nickname; the optional channel (SMS, CALL, MESSENGER, EMAIL, CHAT) hints at the preferred channel. First an exact match of the normalized identity is performed, then fuzzy matching of name/aliases (typos, diminutives, transliteration), then scoring taking into account the channel, recency, and interaction frequency. Returns ranked candidates (#id, name, identities, last interaction, score). If the result is ambiguous — it lists the candidates and asks the user to choose rather than guessing. Use the result instead of re-scanning the phone.",
        "person_get" to "Person profile from the personal base: name, notes, aliases, all identities (phone/email/messengers), and recent interactions. Only metadata and a short summary (a gist ≤ 80 characters) are stored and shown — the message body is unavailable.",
        "person_link" to "Link an identity (a phone, email, messenger nickname, or another handle) to a person. type — PHONE, EMAIL, MESSENGER, or OTHER; value — the handle itself; the optional package (app package for a messenger) and label (a caption). The value is normalized. After writing, a post-check is performed. If such a handle already belongs to another person, the tool honestly reports the conflict and changes nothing (the option is person_merge).",
        "person_merge" to "Merge duplicate persons: move identities, interactions, and aliases from person_id into into_person_id, drop duplicates, and delete the source person. It is performed in a single transaction and post-checked. The action is irreversible.",
        "person_alias_set" to "Add an alternative name (alias) to a person, e.g. «Seryoga» or «Serega». Fuzzy resolution in person_find works by aliases. After writing, a post-check is performed.",
        "interaction_search" to "Interaction history with one person (SMS, calls, messengers, email, chats) in the personal base, newest first. Call it when the question is about a specific person (\"when did we last…\", \"what did they write\"). You can set query (a substring over metadata and the short summary), person_id (a single person only), or both. Only metadata and a gist ≤ 80 characters are stored and shown — message bodies are unavailable; use message_search for full text.",
        "compose_message" to "Compose/send a message to a person. to — the person's name, phone number, or nickname; text — the text; channel — AUTO (default), SMS, or MESSENGER. First the person is resolved against the personal base: if ambiguous, the tool returns candidates and asks to choose, sending NOTHING. Then the channel is chosen: explicit or AUTO (SMS if a phone is available, otherwise a messenger). SMS is sent via the system SmsManager and the result is confirmed by the system (the SEND_SMS permission is required). Messenger: there is no public send API, so the app/app chooser opens with the text prefilled (ACTION_SEND, https://developer.android.com/training/sharing/send) — this is «opened, but not delivered», which the tool honestly reports. After a confirmed SMS send, an interaction is written to the personal base (metadata + gist, the body is not stored). A dangerous action (sending).",
        "schedule_message" to "Delayed sending of a message to a person: this is a DIRECT message send (not an LLM task launch), unlike schedule_create. to — name/number/nickname, text — the text, at — the time: epoch millis or HH:MM (the next occurrence of that time), the optional channel (AUTO/SMS/MESSENGER) and chat_id (default the current chat). The person and channel are resolved NOW (if ambiguous — a question, with no scheduling) and saved to the local store of delayed tasks (no Room tables); then a one-time WorkManager is scheduled. At firing time the message is sent the same way as compose_message, and a delivery report is posted as a notification with a link to the source chat. Honestly: a delay, an unavailable target/channel, or an unconfirmed send are reflected in the notification; message bodies are not stored in the personal base. A dangerous action (delayed send).",
        "schedule_message_list" to "Show delayed messages that have not yet fired (created via schedule_message). For each: id, recipient, channel, send time, a short text snippet, and the status (pending / overdue). Read-only; it sends nothing. Honestly: sent messages do not remain in the store, so they are not in the list — a separate notification reports the send. To cancel a message, use schedule_message_cancel with its id.",
        "schedule_message_cancel" to "Cancel a delayed message by id (the id is returned by schedule_message and shown by schedule_message_list). Cancellation removes the task from the WorkManager scheduler (cancelUniqueWork) and deletes it from the local store, so the message will NOT be sent. If the id is no longer found (the message was sent or cancelled earlier), the tool honestly reports this rather than treating it as an error. Cancellation is best-effort: if the task is already running, it may still complete. A dangerous action (cancelling a scheduled send).",
        "ssh_server_add" to "Register an SSH server. name — a clear unique name (e.g. «vps-prod»), host, port (default 22), user, auth_type — PASSWORD or KEY. For PASSWORD, pass password; for KEY, pass key_ref — a SAF URI of the private key (access to the document must already have been granted) and, if the key is protected, passphrase. Secrets do NOT go into the database: the password and passphrase are stored in EncryptedSharedPreferences backed by the Android Keystore, and the private key is not copied — it is read from the file via key_ref on connection. The name is unique: if such a name already exists, the tool refuses rather than silently overwriting.",
        "ssh_server_list" to "List of registered SSH servers: index, name, user@host:port, and authentication type. Secrets (passwords, passphrases, keys) are never shown and are not stored in the database.",
        "ssh_server_remove" to "Remove an SSH server from the registry by server (name) or server_id. Along with the entry, the saved password/passphrase and the known-hosts entry are deleted. The action is irreversible. If the server is named ambiguously or not named, the tool returns a numbered list and asks — deleting nothing.",
        "ssh_server_rename" to "Rename a saved SSH server. server — the server name (or id) from ssh_server_list, name — the new clear name. The name is unique: if such a name is already taken by another server, the tool refuses rather than overwriting. The address, user, password/key, and known_hosts entry are untouched. A safe action.",
        "ssh_server_update" to "Change saved fields of an SSH server (server — name or id, or server_id). You can pass any of host, port, user, secret; only the passed fields are changed, the rest are kept. secret — a new password for auth_type=PASSWORD or the private key passphrase for KEY; it is stored in EncryptedSharedPreferences, not in the database, and is not shown. Changing host/port makes the host key unknown: on the first connection, trust_host=true will be required.",
        "ssh_exec" to "Run a command on an SSH server (server or server_id) and return stdout, stderr, and the exit code. timeout_sec limits execution (default 60, maximum 300): on timeout the command is interrupted rather than hanging. HOST KEY handling: on the first connection the key is unknown — the connection is rejected showing the fingerprint; after verifying the fingerprint, retry with trust_host=true to remember the key. If the key has changed, the connection is always rejected. Destructive commands (rm -rf, dd, mkfs, reboot, shutdown, systemctl stop, recursive chmod/chown, redirection to /dev/…, fork bombs, etc.) are executed immediately: there is no user confirmation, the command is only flagged with a warning in the result. Interactive input is not supported: sudo that prompts for a password will not work.",
        "ssh_sftp_list" to "List a directory on the server over SFTP: server or server_id, path (default the home directory), limit (default 200, maximum 1000). For an unknown host key, trust_host=true is needed after verifying the fingerprint.",
        "ssh_sftp_get" to "Download a file from the server over SFTP: server or server_id, remote — the path on the server, local — the save path relative to the file-tools root. The file is written atomically (a temporary «.part» + rename), so an incomplete file never remains under the final name. The size is limited to 128 MB. For larger files, use ssh_exec with curl/base64 or download in parts.",
        "ssh_sftp_put" to "Upload a local file to the server over SFTP: server or server_id, local — the path relative to the file-tools root, remote — the path on the server. By default, overwriting is FORBIDDEN: if the file already exists on the server, the tool refuses — pass overwrite=true to overwrite. The size is limited to 128 MB.",
        "repo_status" to "Git repository status on an SSH server (server or server_id): dir — the path to the repository directory on the server. Runs `git -C <dir> status` and honestly returns the current branch and changed files together with the exit code. short (default true) adds --short --branch — compact and with the branch; short=false gives the full output. Large output is truncated with an explicit note. If the directory is not a git repository, git returns an error — the tool reports it rather than making up a result. Interactive input is not supported."
    )

    private fun descriptions9(): Map<String, String> = mapOf(
        "repo_diff" to "Show changes in the git repository on an SSH server (server or server_id): dir — the repository path, staged (default false; true — `git diff --staged`, i.e. staged changes) and the optional path — limit the output to one file or directory (`-- <path>`). The output is size-limited with a truncation note; the git exit code is shown honestly.",
        "repo_log" to "Commit history of the git repository on an SSH server (server or server_id): dir — the repository path, limit — how many recent commits to show (default 20, maximum 100; a larger value is capped with an explicit note). Runs `git -C <dir> log --oneline -n <limit>`. A nonexistent directory or a repository without commits is reflected by the real exit code and git's message.",
        "repo_apply_patch" to "Apply a local git patch to the repository on an SSH server (server or server_id): dir — the repository path, patchPath — the path to the local patch file relative to the file-tools root. The patch is uploaded to a temporary file on the server, `git apply --check` is run first, and only if the check passes — `git apply`. With checkOnly=true the patch is only checked and NOT applied. The temporary file is deleted. The tool never claims the patch was applied without a 0 exit code from git. Modifies repository files.",
        "repo_run" to "Run an arbitrary command in the repository directory on an SSH server (server or server_id): dir — the repository path, command — the command, e.g. git pull, npm test, or make. Runs as `cd <dir> && <command>` over the same non-interactive SSH channel as ssh_exec: without a PTY and without a password prompt (sudo that prompts for a password will not work). The exit code, stdout, and stderr are returned (size-limited). timeoutMs — a budget in milliseconds (default 60000, maximum 300000). Modifies state on the server.",
        "rag_index" to "Index local files for content search: the text of documents (PDF, DOCX, XLSX, PPTX, ODT/ODS/ODP, RTF, CSV/TSV, TXT/MD/JSON/XML/HTML) is split into chunks and saved to a local index on the device. A repeated call skips files that have not changed (specify force=true to reindex forcibly). A folder is processed one level deep unless recursive=true is set. Returns honest counters: indexed, skipped, errors, chunks saved. The text is not sent anywhere and files are not copied.",
        "rag_query" to "Find an answer in already-indexed local documents (rag_index). Returns the most relevant chunks with the source indicated (file path and chunk number) — cite them in your answer. The search is by the meaning of the query, taking into account synonyms and word forms, and works offline. If there are no matches, it honestly reports so.",
        "autofill_profile_set" to "Set the autofill profile — the user's own name, phone, and e-mail that Meha fills into other apps' forms if the user has selected Meha as the autofill service in Android settings. Specify only the fields you need to change: an omitted parameter stays as it was. An empty string in a field clears exactly that field; clear=true clears the entire profile. The profile is stored locally and is never sent over the network.",
        "autofill_profile_get" to "Show the autofill profile (name, phone, e-mail) that Meha offers in forms, and honestly report whether Meha is selected as the autofill service in Android settings.",
        "trusted_source_add" to "Add a source (an SMS number, e-mail, notification app, website, or other) to the trusted list: commands in external text from it can be executed without confirmation. Call this when the user answers «always» to a question about an action based on external text. The value is normalized (phone — digits only and a leading +, e-mail/domain/app — lowercased), and duplicates are not created.",
        "trusted_source_list" to "Show the current list of trusted sources whose commands can be executed without confirmation. It honestly reports if the list is empty.",
        "trusted_source_remove" to "Remove a source from the trusted list — after that, commands in its text again require the user's explicit permission. It honestly reports if there is no such source in the list.",
        "undo_list" to "Show recent actions that can be undone, with a human-readable description (exactly what was done). Read-only. It honestly reports if there is nothing to undo. Only actions for which the app knows the reverse operation exactly are considered undoable (creating a note, task, memory entry, schedule, contact, or event; writing a system setting).",
        "undo_last" to "Undo the most recent undoable action: delete a just-created note, task, memory entry, schedule, contact, or calendar event, or restore the previous value of a system setting. Works only if the app knows the reverse operation; otherwise it honestly reports that it cannot be undone. If the undo log is empty, it honestly reports that there is nothing to undo. Irreversible actions (SMS, calls, app uninstalls) cannot be undone and do not enter the log.",
        "mcp_server_add" to "Add or update an external MCP server (Model Context Protocol) right from the chat — no separate settings screen is needed. Specify a clear server name and URL; the transport is usually detected automatically (Streamable HTTP, otherwise the legacy HTTP+SSE). If the server requires a token, set the header name (e.g. Authorization or X-Api-Key) and its value: the value is stored encrypted and is never shown in responses. A repeated call with the same name updates the server and re-enables it. After adding, check the connection via mcp_server_test.",
        "mcp_server_list" to "Show the configured MCP servers: name, URL, transport, whether the server is enabled, whether a token is set, and a status hint. It honestly reports if there are no servers yet.",
        "mcp_server_remove" to "Delete an MCP server by name or id along with the saved token. It honestly reports if there is no such server.",
        "mcp_server_test" to "Test an MCP server: connect, perform the initialize handshake, and fetch the tool list (tools/list). Returns the server name and version, the transport actually used, and the tools found. On error, it honestly reports the cause (no network, invalid URL, HTTP status, timeout).",
        "objective_create" to "Create a long objective (a task with a plan and checkpoints) that can be executed step by step over several turns and continued after the app restarts. The objective immediately becomes ACTIVE and is bound to the current chat, so on each subsequent turn the agent sees it and continues. Steps are a list of strings in order (e.g. «gather data», «make a report», «send»). If no steps are set, the objective can be run without a plan. Do not create an objective for a single quick operation — the regular tools are for that.",
        "objective_list" to "Show saved long objectives: id, title, status, progress (done/total steps), and the next step. Changes nothing. Can be filtered by status (active/paused/done/cancelled).",
        "objective_get" to "Show one long objective in detail: the goal, status, progress, all steps with their statuses and notes, the chat binding, and the working time. Changes nothing. The objective can be specified by id or by title.",
        "objective_step_update" to "Mark the status of an objective step during work — this is a checkpoint. Specify the step by id (e.g. «s2») or by number (1, 2, …). Statuses: pending, in_progress, done, blocked. You can add a short note about the result or the reason for blocking. Mark done only what was actually completed — do not make things up.",
        "objective_next" to "Show the next step of the active objective and a hint about what to do next. Changes nothing. If there are no steps or all are completed — it honestly says so. Call it when continuing a long objective and you need to understand where you left off.",
        "objective_finish" to "Finish a long objective successfully (status done). Call it when all steps are actually completed. If the objective is already finished or cancelled — it honestly reports this and changes nothing.",
        "objective_cancel" to "Cancel a long objective (status cancelled) if it does not need to be continued or the user changed their mind. A cancelled objective cannot be resumed — create a new one if needed. If the objective is already finished or cancelled — it honestly reports this.",
        "objective_resume" to "Resume a deferred (paused) long objective — make it active again so it can continue in this chat. Paused objectives are NOT executed on their own; they must be explicitly resumed when the user asks to continue. A finished or cancelled objective cannot be resumed.",
        "objective_pause" to "Pause a long objective (status paused) if it cannot or should not be continued right now (e.g. waiting for the user or an external event). While paused, the objective is NOT suggested to the agent and is not executed on its own — it continues only after an explicit objective_resume. The progress and step notes are preserved.",
        "objective_run" to "Run a long objective in the BACKGROUND: the agent continues it on its own, by the next step, and records the progress. Works while the app is in the background; several objectives can run in parallel, but no more than the configured limit (the rest queue up) — see the status via objective_workers_status. It does not start an objective if it is finished, paused, blocked, or has exhausted its background-step budget, or if no LLM is configured — in that case it honestly reports so. Without an argument it starts all suitable active objectives.",
        "objective_stop" to "Stop the background execution of a long objective (or all at once if no objective is specified). Already completed steps and notes are preserved; the objective itself is not cancelled — it can be started again via objective_run.",
        "objective_workers_status" to "Show what the background objective executor is currently doing: which objectives are running, which are queued, the current concurrency limit, the effective limit (may be reduced by power-guard), and the last start time. Changes nothing.",
        "objective_concurrency_set" to "Set the maximum number of long objectives executed simultaneously in the background (concurrency limit). Allowed from 1 to 3; default 2. The value is saved. Under low charge or heat, power-guard still reduces concurrency to 1.",
        "recordings_list" to "List of local recordings (voice recorder, voice messages, imports) with duration and transcription status. Optional limit (default 20, up to 200) and status (PENDING, RUNNING, DONE, FAILED, SKIPPED). Read-only, changes nothing; the audio files are on disk, only metadata goes into the database. These are audio files — phone calls are not recorded (Android does not allow this).",
        "recording_transcribe" to "Start speech recognition (whisper.cpp) for a recording by its id. language — a language code or auto (default auto). model — base (default, ~141 MB) or small (more accurate, ~465 MB). The model must already be downloaded: if it is not, the tool honestly reports this and does nothing. The response contains the number of segments and the duration; the transcription replaces the previous one for this recording. Works only with audio files; phone calls cannot be recorded (Android does not allow this).",
        "transcript_get" to "Show the transcription of a recording by its id: segments with timecodes [start-end] and the speaker — «Me» for the device owner, the person's name if the voice is linked to a person, otherwise «Speaker N» (until diarization is done, the speaker may be unidentified). If there is no transcription yet, it honestly reports the status and suggests running recording_transcribe.",
        "transcript_search" to "Full-text search over transcription texts (FTS over segments). query — the words to search for (required), limit — how many matches to return (default 20, up to 100). Returns the recording id, the timecode, and the text of the found fragments. Only already-transcribed recordings are searched.",
        "communication_search" to "Unified search over the communication trail: recording transcriptions, interactions from the personal base (SMS, calls, messengers, email, chats), and, where possible, memory. When the channel is unclear (\"we discussed it somewhere\", \"who wrote about…\"), call this instead of guessing. query — a required query, limit — the output limit (default 30, up to 100). Returns a single list ordered by time (newest first), with the channel indicated. Read-only.",
        "message_search" to "The main tool for questions about past messages and conversations: hybrid search over the FULL text of saved messages (SMS/MMS and intercepted messenger/email/chat messages) — lexical FTS plus, when offline embeddings are available, search BY MEANING. Call it before answering when the user asks what was agreed, who wrote, or what a conversation contained; semantic=false ONLY for an exact quote, phone number, code or id. query — a required query; limit — how many hits to return (default 20, up to 100); source — a source filter sms|mms|messenger|email|chat (or all); person — a person's name or numeric id (resolution never guesses: if ambiguous, it returns candidates); semantic=false — lexical search only (default true). Returns the sender, time, source, conversation, and a text fragment of up to 300 characters. Read-only, changes nothing; only already-saved messages are searched.",
        "messages_summarize" to "A brief summary of saved messages (SMS/MMS and intercepted messenger/email/chat messages): what was discussed and what was agreed. topic — free text, what exactly to summarize (optional; if set, relevant messages are selected via hybrid search). person — a person's name or numeric id (resolution never guesses: if ambiguous, it returns candidates and summarizes nothing). source — a source filter sms|mms|messenger|email|chat or all. window — a window today, 7d, 30d, or all (default 30d). limit — how many messages to consider (default 40, up to 200). A configured model is required; without it, the tool honestly reports this and makes nothing up. save=true — additionally save the summary to memory (tag messages_summary). Messages are only read; with save=true a memory entry is added.",
        "person_brief" to "A brief summary about a person over a period: recent interactions (calls/SMS/messengers), moments from the transcriptions of their recordings, and related memories/opinions. person — a name or numeric id (required), days — the window in days (default 30). Resolution never guesses: if ambiguous, it returns candidates and asks to choose. Read-only.",
        "speaker_registry_list" to "Show the registry of voices (speakers) found in recordings: id, name (or «Me» for the owner, otherwise «Speaker N»), the person link, the number of voice samples, and the update time. limit — how many rows to return (default 50, up to 200). Read-only, changes nothing. If the registry is empty, it honestly reports so.",
        "speaker_name_assign" to "Name a voice, link it to a person from the personal base, or mark it as «this is me». speaker_id — the required speaker id. name — assign a name. person — a person's name or numeric id; resolution never guesses: if ambiguous, it returns candidates and asks to choose (no changes are made then). self=true/false — set or clear the «this is me» mark. You can specify several parameters at once; without them, the tool honestly reports that there is nothing to change.",
        "speaker_registry_forget" to "Remove a speaker from the registry together with all their voice embeddings (samples). speaker_id — the required id. The action is irreversible; the response honestly reports exactly what was deleted (name and number of samples). Only the voice cluster is deleted — persons and recordings are untouched.",
        "recordings_scan" to "Find audio recordings on the device via the media library and register them for transcription. Scans audio from MediaStore (the audio access permission is required); if recording folders are configured — only those, otherwise all audio; already-registered recordings are skipped. The optional limit — how many rows to scan per run (default 200, up to 1000). Honestly returns counters: found, added, skipped. Files are not copied, only metadata goes into the database. Phone calls are not included — Android forbids recording them.",
        "recording_import" to "Register a specific audio file as a recording: path — the absolute file path or a content:// URI (required). transcribe=true (default) immediately starts speech recognition — the model must already be downloaded, otherwise the tool honestly reports an error; transcribe=false only registers the recording with the PENDING status. language — a language code or auto (default auto). A content:// URI is copied into the app's storage so the transcription can read it. The response contains the recording id and the honest result.",
        "messenger_voice_capture" to "Record the sound currently playing from another app (a voice message in a messenger) and import it as a recording. duration_seconds — how many seconds to record (default 30, allowed 5–300; the capture service may limit the duration). transcribe=true (default) immediately starts speech recognition — the model must already be downloaded, otherwise there will be an honest error. language — a language code or auto (default auto). The audio recording permission and ONE-TIME user consent for capturing system audio (MediaProjection) are required: Android will show a dialog, and without consent the tool honestly reports the refusal. If the source app has blocked capture, silence will be recorded — the tool will honestly say so. The file is saved in the app's storage.",
        "commitments_extract" to "Find commitment phrases and agreements in recording transcriptions («сделаю», «давай», «напомни», «I will»…) over a period. window — the window: today or Nd (default 7d, maximum 365d). create=true — save the found candidates as tasks (todo) via the existing task path; only explicit matches by key phrases are created, nothing is made up. Returns candidates with the recording and timecode and the number of created tasks. This is a keyword heuristic, not a guarantee — verify the candidates. Without create it changes nothing.",
        "device_search" to "Semantic search BY MEANING across the entire device: photos in the gallery and the text of documents (PDF/DOCX/TXT) that are already indexed on the device. The query can be given in words, not an exact file name: «photo with a lock», «contract with a term». Works offline on the built-in embedding model. Returns the found files (for photos — uri, path, and name; for documents — name and a text fragment). What is found can be opened with media_open(uri=…) or sent with media_share(uri=…). It honestly reports if the index is still empty or the embedding model is not downloaded.",
        "media_open" to "Open a found file or media in the device's system viewer (ACTION_VIEW): a photo — in the gallery, a document — in a viewer app. Use it for device_search results — pass its uri (content://… or a file path). The needed viewer is chosen by the system; mime is detected automatically if not specified. If there is nothing to open it with — it honestly reports so."
    )

    private fun descriptions10(): Map<String, String> = mapOf(
        "media_share" to "Share a found file or media with another app via the system «Share» menu (ACTION_SEND). Use it for device_search results — pass its uri (content://… or a file path); for example, forward a found photo to a messenger. Read access is granted to the recipient automatically.",
        "audio_trim" to "Trim an audio file by time into a NEW file dest (the source is unchanged). start is the beginning (seconds, «90», «1:30» or «0:01:30»); end is the end, or duration is the length — provide end or duration. By default the track is copied without re-encoding (reencode=false); set reencode=true when the destination container does not support the source codec. Honestly reports the result path and size.",
        "audio_concat" to "Join several audio files into one NEW file dest. paths is a comma- or semicolon-separated list (in join order). By default it only remuxes without re-encoding, so the files must share a compatible codec and parameters; otherwise set reencode=true. Honestly reports the result path and size.",
        "audio_convert" to "Convert audio to another format. The format comes from the dest extension (mp3, wav, ogg, opus, m4a, aac, flac, wma) or from codec. Optional bitrate (kbps) and sample_rate (Hz). An unsupported format is an honest refusal, not a fake success. Honestly reports the result path and size.",
        "audio_volume" to "Change audio loudness into a NEW file dest. Give gain_db (decibels, e.g. «6» or «-3») OR percent (e.g. «150»). Exactly one of the two is required. Honestly reports the result path and size.",
        "audio_speed" to "Change the audio tempo (pitch preserved) into a NEW file dest. factor is the speed multiplier (e.g. 1.5 faster, 0.75 slower). Values outside 0.5–2.0 are chained into several filters. Honestly reports the result path and size.",
        "audio_extract_from_video" to "Extract the audio track from a video file into a separate audio file dest. By default no re-encoding (reencode=false); if the track cannot go into the dest container as-is, set reencode=true and the codec is chosen from the dest extension. Honestly reports the result path and size.",
        "audio_tag_write" to "Write audio metadata (title, artist, album, …) into a NEW file dest without changing the source. Provide at least one field. Tag support depends on the format (mp3/m4a/flac/ogg yes; wav limited); containers without tags yield an honest message. The file is copied without re-encoding.",
        "video_trim" to "Trim a video by time into a NEW file dest (the source is unchanged). start is the beginning, end is the end, or duration is the length (provide end or duration). By default the tracks are copied without re-encoding (reencode=false, fast but cut at the nearest keyframe); reencode=true gives an exact cut with re-encoding. Honestly reports the result path and size.",
        "video_concat" to "Join several video files into one NEW file dest. paths is a comma- or semicolon-separated list (in join order). By default it only remuxes without re-encoding (the files must share codec, resolution and parameters); otherwise set reencode=true. Honestly reports the result path and size.",
        "video_resize" to "Change the video resolution into a NEW file dest. Give width and/or height (pixels) or maxSide (bound the longer side). When only one side is given, the other follows the aspect ratio. Requires re-encoding. Honestly reports the result path and size.",
        "video_bitrate" to "Re-encode the video at a given bitrate into a NEW file dest. video_bitrate is the video bitrate in kbps (required); audio_bitrate is the audio bitrate in kbps (optional). Honestly reports the result path and size.",
        "video_compress" to "Compress the video into a NEW file dest. crf is the quality (lower is better, typically 23–32, default 28); preset is the speed/size trade-off (ultrafast…veryslow, default medium); maxSide optionally reduces the resolution on the longer side. Honestly reports the result path and size.",
        "video_rotate" to "Rotate the video by 90, 180 or 270 degrees into a NEW file dest. Requires re-encoding. Honestly reports the result path and size.",
        "video_extract_audio" to "Extract the audio track from a video into a separate audio file dest (a synonym of audio_extract_from_video). By default it copies without re-encoding (reencode=false); otherwise the codec is chosen from the dest extension. Honestly reports the result path and size.",
        "video_subtitles" to "Add subtitles from an srt file to a video (NEW file dest). mode=burn burns them into the picture (always visible); mode=mux embeds them as a separate track (default). srt is the path to the .srt subtitle file. Honestly reports the result path and size.",
        "video_convert" to "Change the video container/format into a NEW file dest (e.g. mp4 → mkv). By default it remuxes without re-encoding (reencode=false) when the codecs and the new container are compatible; otherwise set reencode=true to re-encode. Honestly reports the result path and size."
    )

    private fun params0(): Map<String, Map<String, String>> = mapOf(
        "files_list" to mapOf(
            "path" to "Path to the directory (in shared storage or /storage/emulated/0)."
        ),
        "files_read" to mapOf(
            "path" to "Path to the file (in shared storage or /storage/emulated/0).",
            "offset" to "Byte offset at which to start reading (optional).",
            "limit" to "How many bytes to read (optional; default 1 MB, maximum 64 MB)."
        ),
        "files_write" to mapOf(
            "path" to "Path to the file (in shared storage or /storage/emulated/0).",
            "content" to "Text content to write to the file",
            "overwrite" to "Overwrite the file (default true)."
        ),
        "files_create_dir" to mapOf(
            "path" to "Path to the directory to create (in shared storage or /storage/emulated/0)."
        ),
        "files_delete" to mapOf(
            "path" to "Path to the file or directory to delete. Multiple paths can be listed in a single string separated by commas or semicolons.",
            "paths" to "Same as path: one or more paths separated by commas or semicolons."
        ),
        "files_move" to mapOf(
            "source" to "Path to the source (in shared storage or /storage/emulated/0).",
            "destination" to "Destination path (in shared storage or /storage/emulated/0)."
        ),
        "files_rename" to mapOf(
            "path" to "Current path (in shared storage).",
            "new_name" to "New name (without path separators)."
        ),
        "files_copy" to mapOf(
            "source" to "Path to the source file or directory",
            "destination" to "Destination path (in shared storage or /storage/emulated/0).",
            "overwrite" to "Overwrite the existing destination (default false — safe)."
        ),
        "files_stat" to mapOf(
            "path" to "Path (in shared storage or /storage/emulated/0)."
        ),
        "files_dir_size" to mapOf(
            "path" to "Path to the folder or file (in shared storage)."
        ),
        "files_open" to mapOf(
            "path" to "Path (in shared storage).",
            "app" to "Optional: the package of the app with which to open the file (overrides auto-selection)."
        ),
        "files_append" to mapOf(
            "path" to "Path to the file",
            "content" to "Text to append"
        ),
        "files_search" to mapOf(
            "query" to "Substring of the file/folder name",
            "path" to "Folder to search in (default: storage root)"
        ),
        "files_grep" to mapOf(
            "query" to "Substring to search for (case-insensitive).",
            "path" to "Directory to search in (default: storage root).",
            "ext" to "Extension filter, e.g. txt (optional).",
            "limit" to "Maximum matches (default 200).",
            "max_file_bytes" to "Skip files larger than this size (default 1 MB)."
        ),
        "files_hash" to mapOf(
            "path" to "Path to the file",
            "algorithm" to "Hash algorithm: MD5, SHA-1, or SHA-256 (default SHA-256)."
        ),
        "files_duplicates" to mapOf(
            "path" to "Directory to search in (default: storage root).",
            "min_size" to "Minimum file size in bytes (default 1).",
            "limit" to "Maximum duplicate groups in the output (default 50)."
        ),
        "files_zip" to mapOf(
            "sources" to "Source paths separated by commas",
            "destination" to "Path to the .zip to create"
        ),
        "files_unzip" to mapOf(
            "source" to "Path to the .zip",
            "destination" to "Folder to extract into"
        ),
        "files_zip_list" to mapOf(
            "path" to "Path to the ZIP archive (in shared storage).",
            "limit" to "Maximum entries to output (default 200)."
        ),
        "files_gzip" to mapOf(
            "source" to "Path to the file to compress",
            "destination" to "Path to the .gz to create"
        ),
        "files_gunzip" to mapOf(
            "source" to "Path to the .gz file",
            "destination" to "Path to the extracted file"
        ),
        "share_file" to mapOf(
            "path" to "Path to the file"
        ),
        "share_files" to mapOf(
            "paths" to "Paths to files separated by commas (in shared storage).",
            "mime" to "MIME type, e.g. image/* (default */* )."
        ),
        "print_file" to mapOf(
            "path" to "Path to the file to print (in shared storage).",
            "mime" to "File MIME type (optional; otherwise determined by extension)."
        ),
        "files_split" to mapOf(
            "source" to "Path to the source file (in shared storage).",
            "parts" to "Number of parts (integer ≥ 1). Give parts or part_size.",
            "part_size" to "Size of one part: bytes or with a suffix, e.g. 100MB or 104857600. Give parts or part_size.",
            "destination_dir" to "Folder for the parts (optional; by default the source file's folder)."
        ),
        "files_join" to mapOf(
            "parts" to "Part paths separated by commas or semicolons.",
            "source" to "Any one part — the other parts of the same name are found and ordered automatically.",
            "destination" to "Path to the joined file to create (in shared storage).",
            "expected_size" to "Expected total size in bytes (optional; checked before writing)."
        ),
        "files_mime" to mapOf(
            "path" to "Path to the file (in shared storage)."
        ),
        "files_compare" to mapOf(
            "path1" to "Path to the first file (in shared storage).",
            "path2" to "Path to the second file (in shared storage).",
            "max_lines" to "Maximum lines of the line-by-line diff (default 40)."
        ),
        "files_hide" to mapOf(
            "path" to "Path to the file or folder (in shared storage).",
            "hide" to "true — hide (default), false — show.",
            "recursive" to "For a folder: also process every subfolder (default false)."
        ),
        "files_chmod" to mapOf(
            "path" to "Path to the file or folder (in shared storage).",
            "readable" to "Allow/deny reading (optional).",
            "writable" to "Allow/deny writing (optional).",
            "executable" to "Allow/deny execution (optional).",
            "apply_to_all" to "Apply to all users (true, default) or only the owner (false).",
            "recursive" to "For a folder: also apply to all of its contents (default false)."
        ),
        "files_trash" to mapOf(
            "path" to "Path (in shared storage or /storage/emulated/0)."
        ),
        "files_trash_list" to emptyMap(),
        "files_trash_restore" to mapOf(
            "id" to "Entry identifier from files_trash_list."
        ),
        "files_trash_empty" to emptyMap(),
        "files_tar_create" to mapOf(
            "sources" to "Paths separated by commas.",
            "destination" to "Path to .tar / .tar.gz.",
            "gzip" to "Compress with GZIP (true) or without compression (false)."
        ),
        "files_tar_extract" to mapOf(
            "source" to "Path to the .tar / .tar.gz / .tgz archive.",
            "destination" to "Folder to extract into."
        ),
        "files_7z_extract" to mapOf(
            "source" to "Path to the .7z archive.",
            "destination" to "Folder to extract into."
        ),
        "files_7z_create" to mapOf(
            "sources" to "Paths separated by commas.",
            "destination" to "Path to the .7z archive to create."
        ),
        "files_rar_extract" to mapOf(
            "source" to "Path to the .rar archive.",
            "destination" to "Folder to extract into. RAR is read-only — creating a RAR archive is impossible."
        ),
        "http_request" to mapOf(
            "method" to "HTTP method (GET, POST, PUT, DELETE, etc.)",
            "url" to "Request address (absolute URL)",
            "headers" to "Request headers as key-value pairs",
            "body" to "Request body as a string"
        ),
        "download_file" to mapOf(
            "url" to "Address of the file to download (absolute URL)",
            "destination" to "Path to the destination directory (in shared storage or /storage/emulated/0). Default — Downloads (Download).",
            "filename" to "Name of the saved file (optional; otherwise from URL/Content-Disposition).",
            "max_bytes" to "Maximum size in bytes (default 200 MB)."
        ),
        "web_fetch" to mapOf(
            "url" to "Absolute URL of the page to fetch and read"
        ),
        "web_open" to mapOf(
            "url" to "Absolute http/https link"
        ),
        "web_click" to mapOf(
            "selector" to "CSS selector of the element (e.g. a.login, button#submit) — optional",
            "text" to "Visible text of the element to click (e.g. \"Sign in\"); searched in links, buttons, fields, <label> and associated controls — optional"
        ),
        "web_type" to mapOf(
            "selector" to "CSS selector of the input field (input/textarea)",
            "text" to "Text to enter",
            "submit" to "true — submit the form after entering"
        ),
        "web_scroll" to mapOf(
            "direction" to "down | up | top | bottom",
            "amount" to "Number of pixels to scroll (optional)"
        ),
        "web_back" to emptyMap(),
        "web_extract" to mapOf(
            "max_chars" to "Maximum characters (optional, up to 20000)"
        ),
        "web_navigate" to mapOf(
            "url" to "Absolute http/https link"
        ),
        "web_search" to mapOf(
            "query" to "Search query",
            "limit" to "Maximum results (optional, default 10, maximum 30)"
        ),
        "image_search" to mapOf(
            "query" to "Search query",
            "count" to "Maximum results (optional, default 5, maximum 10)",
            "orientation" to "Aspect ratio (optional): landscape / portrait / square",
            "source" to "Source (optional): openverse | wikimedia, default openverse"
        ),
        "web_wait" to mapOf(
            "selector" to "CSS selector of the expected element (optional)",
            "timeout_sec" to "Timeout in seconds (optional, default 10, from 1 to 60)"
        ),
        "web_readable" to mapOf(
            "max_chars" to "Maximum characters (optional, default 100000, maximum 300000)"
        ),
        "web_eval_js" to mapOf(
            "script" to "JavaScript to execute on the page"
        ),
        "web_screenshot" to mapOf(
            "path" to "Path to the PNG file (in shared storage)."
        ),
        "web_find" to mapOf(
            "query" to "Text to search for"
        )
    )

    private fun params1(): Map<String, Map<String, String>> = mapOf(
        "web_scroll_until" to mapOf(
            "max_steps" to "Maximum scroll steps (optional, default 20, maximum 50)"
        ),
        "web_tabs_new" to mapOf(
            "url" to "URL to load in the new tab (optional)",
            "name" to "Tab name for switching (optional)"
        ),
        "web_tabs_switch" to mapOf(
            "index" to "Zero-based tab index (optional)",
            "name" to "Tab name (optional)"
        ),
        "web_tabs_close" to mapOf(
            "index" to "Zero-based tab index (optional)",
            "name" to "Tab name (optional)"
        ),
        "web_tabs_list" to emptyMap(),
        "web_session_save" to mapOf(
            "name" to "Session name, e.g. mysite"
        ),
        "web_session_load" to mapOf(
            "name" to "Name of a previously saved session"
        ),
        "web_forward" to emptyMap(),
        "web_reload" to emptyMap(),
        "web_submit" to emptyMap(),
        "web_set_user_agent" to mapOf(
            "ua" to "User-Agent string; an empty string — restore the default value"
        ),
        "web_download" to mapOf(
            "url" to "Absolute http/https link to the file"
        ),
        "brightness_set" to mapOf(
            "level" to "Screen brightness as a percentage (0–100)"
        ),
        "volume_set" to mapOf(
            "type" to "Sound type (music, ring, notification, alarm)",
            "level" to "Volume level (0–15)"
        ),
        "wifi_set" to mapOf(
            "on" to "Turn Wi-Fi on (true) or off (false)"
        ),
        "bluetooth_set" to mapOf(
            "on" to "Turn Bluetooth on (true) or off (false)"
        ),
        "volume_get" to mapOf(
            "type" to "Sound type (music, ring, notification, alarm)"
        ),
        "brightness_get" to emptyMap(),
        "ringer_set" to mapOf(
            "mode" to "Sound mode: normal | vibrate | silent"
        ),
        "rotation_set" to mapOf(
            "on" to "true — allow auto-rotation, false — lock"
        ),
        "screen_timeout_set" to mapOf(
            "seconds" to "Screen timeout in seconds (integer > 0)"
        ),
        "vibrate" to mapOf(
            "duration_ms" to "Vibration duration in milliseconds (1–10000)"
        ),
        "network_info" to emptyMap(),
        "wifi_info" to emptyMap(),
        "bluetooth_devices" to emptyMap(),
        "ble_scan" to mapOf(
            "duration_sec" to "Scan duration in seconds (1–30, default 8)",
            "name_filter" to "Substring of the device name to filter results (optional)"
        ),
        "ble_connect" to mapOf(
            "address" to "MAC address of the BLE device, e.g. AA:BB:CC:DD:EE:FF",
            "duration_sec" to "Connection and service discovery timeout in seconds (1–30, default 10)"
        ),
        "dns_lookup" to mapOf(
            "host" to "Domain name, e.g. example.com"
        ),
        "ping" to mapOf(
            "host" to "Host or IP",
            "count" to "Number of packets 1–10 (default 4)"
        ),
        "wifi_scan" to emptyMap(),
        "network_detail" to emptyMap(),
        "nsd_discover" to mapOf(
            "service_type" to "Service type, e.g. _http._tcp. (default _http._tcp.)",
            "timeout_sec" to "How many seconds to search, 1–30 (default 5)"
        ),
        "telephony_info" to emptyMap(),
        "ir_transmit" to mapOf(
            "frequency" to "Carrier frequency in hertz, e.g. 38000",
            "pattern" to "Alternating on/off durations in microseconds separated by commas, e.g. 9000,4500,560,560"
        ),
        "usb_list" to emptyMap(),
        "usb_request_permission" to mapOf(
            "vendor_id" to "Vendor identifier (number, decimal or 0x…), e.g. 0x18d1",
            "product_id" to "Product identifier (number, decimal or 0x…), e.g. 0x4ee7"
        ),
        "usb_info" to mapOf(
            "vendor_id" to "Vendor identifier (number, decimal or 0x…)",
            "product_id" to "Product identifier (number, decimal or 0x…)"
        ),
        "vpn_status" to emptyMap(),
        "vpn_prepare" to emptyMap(),
        "sim_slots_list" to emptyMap(),
        "sim_numbers" to emptyMap(),
        "sim_set_default_sms" to mapOf(
            "subscription_id" to "SIM subscription identifier (id from sim_slots_list)"
        ),
        "esim_status" to emptyMap(),
        "battery_detail" to emptyMap(),
        "thermal_status" to emptyMap(),
        "battery_prediction" to emptyMap(),
        "power_saver_get" to emptyMap(),
        "device_idle_get" to emptyMap(),
        "battery_optimization_status" to emptyMap(),
        "battery_optimization_request" to emptyMap()
    )

    private fun params2(): Map<String, Map<String, String>> = mapOf(
        "screen_state_get" to emptyMap(),
        "alarm_set" to mapOf(
            "hour" to "Hours 0-23",
            "minute" to "Minutes 0-59",
            "message" to "Alarm label (optional)"
        ),
        "timer_set" to mapOf(
            "seconds" to "Duration in seconds",
            "message" to "Timer label (optional)"
        ),
        "text_to_speech" to mapOf(
            "text" to "What to say"
        ),
        "font_scale_set" to mapOf(
            "scale" to "Font scale 0.5–2.0"
        ),
        "flashlight_set" to mapOf(
            "on" to "Turn the flashlight on (true) or off (false)"
        ),
        "storage_volumes" to emptyMap(),
        "contact_search" to mapOf(
            "query" to "Search text by contact name"
        ),
        "contact_get" to mapOf(
            "id" to "Contact identifier"
        ),
        "contact_create" to mapOf(
            "name" to "Name of the new contact",
            "phone" to "Contact's phone number",
            "email" to "Contact's email address (optional)"
        ),
        "contact_update" to mapOf(
            "id" to "Identifier of the contact to update (from contact_search)",
            "name" to "New contact name (optional)",
            "phone" to "New phone number (optional)",
            "email" to "New email (optional)"
        ),
        "contact_delete" to mapOf(
            "id" to "Identifier of the contact to delete (from contact_search)"
        ),
        "contact_lookup_by_number" to mapOf(
            "number" to "Phone number to find a contact by"
        ),
        "contact_groups_list" to emptyMap(),
        "contact_group_create" to mapOf(
            "title" to "Title of the new group",
            "account_type" to "Group account type (optional, specify together with account_name)",
            "account_name" to "Group account name (optional, specify together with account_type)"
        ),
        "contact_group_assign" to mapOf(
            "contact_id" to "Contact (id from contact_search)",
            "contact_lookup" to "Contact's phone number (alternative to contact_id)",
            "group_id" to "Group (id from contact_groups_list)",
            "group_title" to "Group title (alternative to group_id)",
            "remove" to "true — remove from the group, false (default) — add"
        ),
        "contact_birthdays" to mapOf(
            "days" to "Window width in days (default 30)"
        ),
        "contact_photo_get" to mapOf(
            "contact_id" to "Contact (id from contact_search)"
        ),
        "contact_star_set" to mapOf(
            "contact_id" to "Contact (id from contact_search)",
            "starred" to "true — add to favorites (default), false — remove"
        ),
        "contact_batch_upsert" to mapOf(
            "names" to "Contact names separated by commas",
            "phones" to "Phones separated by commas, aligned by index with names (optional)",
            "emails" to "Email addresses separated by commas, aligned by index with names (optional)"
        ),
        "contact_merge" to mapOf(
            "contact_id1" to "First contact (id from contact_search)",
            "contact_id2" to "Second contact (id from contact_search)"
        ),
        "contact_split" to mapOf(
            "contact_id1" to "First contact (id from contact_search)",
            "contact_id2" to "Second contact (id from contact_search)"
        ),
        "contact_photo_set" to mapOf(
            "contact_id" to "Contact (id from contact_search)",
            "photo_path" to "Path to the image file (in shared storage)."
        ),
        "contact_photo_clear" to mapOf(
            "contact_id" to "Contact (id from contact_search)"
        ),
        "contact_export_vcf" to mapOf(
            "contact_id" to "Contact identifier (from contact_search); without it, all are exported",
            "path" to "Absolute path to a .vcf inside the allowed storage (optional)"
        ),
        "contact_import_vcf" to mapOf(
            "path" to "Path to the .vcf file inside the allowed storage"
        ),
        "contact_group_delete" to mapOf(
            "group_id" to "Group (id from contact_groups_list)"
        ),
        "contact_group_rename" to mapOf(
            "group_id" to "Group (id from contact_groups_list)",
            "title" to "New group name"
        ),
        "photos_list" to mapOf(
            "query" to "Text to search by file name (optional)",
            "limit" to "Maximum number of photos to return"
        ),
        "photos_delete" to mapOf(
            "ids" to "Comma-separated list of photo ids to delete, for example: 1,2,3"
        ),
        "exif_read" to mapOf(
            "path" to "Path to the image file (in shared storage or /storage/emulated/0)."
        ),
        "exif_write" to mapOf(
            "path" to "Path to the image file (in shared storage or /storage/emulated/0).",
            "tag" to "EXIF tag name: a short name such as DateTimeOriginal, or a constant such as TAG_DATETIME_ORIGINAL (case-insensitive)",
            "value" to "New tag value (string, for example 2026:09:16 12:00:00)"
        ),
        "image_thumbnail" to mapOf(
            "path" to "Path to the source image or video.",
            "destination" to "Path to save the thumbnail (PNG).",
            "size" to "Length of the longer side in pixels (default 256)."
        ),
        "image_resize" to mapOf(
            "src" to "Path to the source image.",
            "dest" to "Output path (.jpg, .png or .webp).",
            "width" to "Output width in pixels (optional).",
            "height" to "Output height in pixels (optional).",
            "maxSide" to "Length of the longer side, px.",
            "format" to "Format (by default from the dest extension, otherwise jpeg).",
            "quality" to "Quality 1-100 for jpeg/webp (default 85)."
        ),
        "image_convert" to mapOf(
            "src" to "Path to the source image.",
            "dest" to "Output path (.jpg, .png or .webp).",
            "format" to "Format (by default from the dest extension, otherwise jpeg).",
            "quality" to "Quality 1-100 for jpeg/webp (default 85).",
            "width" to "Output width in pixels (optional; the aspect ratio is preserved if only it is set).",
            "height" to "Output height in pixels (optional; the aspect ratio is preserved if only it is set).",
            "maxSide" to "Length of the longer side, px."
        ),
        "wallpaper_set" to mapOf(
            "path" to "Path to the local image file (for example, from camera_* or download_file).",
            "url" to "URL of the image on the internet - the agent downloads it itself.",
            "target" to "Where to apply: home (default), lock or both."
        ),
        "media_scan" to mapOf(
            "path" to "Path to the file (in shared storage)."
        ),
        "media_delete" to mapOf(
            "paths" to "Comma-separated file paths (in shared storage)."
        ),
        "media_trash" to mapOf(
            "paths" to "Comma-separated file paths (in shared storage)."
        ),
        "media_request_write" to mapOf(
            "paths" to "Comma-separated file paths (in shared storage)."
        ),
        "media_downloads_list" to mapOf(
            "limit" to "Maximum number of files to return (default 100)"
        ),
        "media_metadata" to mapOf(
            "path" to "Path to the audio/video file (in shared storage)."
        ),
        "media_frame_extract" to mapOf(
            "path" to "Path to the video file (in shared storage).",
            "time_ms" to "Frame moment in milliseconds (default 0)",
            "destination" to "Path to save the frame as PNG"
        ),
        "media_favorite_set" to mapOf(
            "paths" to "Comma-separated file paths (in shared storage).",
            "favorite" to "true - add to favorites (default), false - remove."
        ),
        "audio_play" to mapOf(
            "path" to "Path to the audio file (in shared storage).",
            "wait" to "Wait for playback to finish (default false)",
            "position_ms" to "Playback start position in milliseconds (default 0)"
        ),
        "audio_stop" to emptyMap(),
        "audio_mode_set" to mapOf(
            "mode" to "Audio mode: normal, in_communication, in_call or ringtone",
            "speaker" to "Turn the speakerphone on (true) or off (false) (optional)"
        ),
        "audio_stream_analysis" to mapOf(
            "duration_ms" to "Analysis window duration in milliseconds (default 3000, maximum 10000)"
        ),
        "audio_capture_app" to mapOf(
            "path" to "Path to the recording file (.wav; in shared storage).",
            "duration_ms" to "Recording duration in milliseconds (default 5000, maximum 60000)"
        ),
        "media_control_other" to mapOf(
            "action" to "Command: play, pause, next, previous, stop or toggle",
            "app" to "Filter by app package name (optional), for example com.spotify.music"
        ),
        "image_crop" to mapOf(
            "src" to "Path to the source image.",
            "dest" to "Output path for the new file.",
            "x" to "Left edge of the crop, px (default 0).",
            "y" to "Top edge of the crop, px (default 0).",
            "width" to "Crop region width in pixels.",
            "height" to "Crop region height in pixels.",
            "box" to "Alternative to width/height: fractions 0..1 \"x1,y1,x2,y2\" (e.g. 0.1,0.1,0.9,0.9)."
        ),
        "image_rotate" to mapOf(
            "src" to "Path to the source image.",
            "dest" to "Output path for the new file.",
            "angle" to "Rotation angle in degrees (e.g. 90, 180, 270 or 45). Default 0.",
            "flip" to "Mirror: none (default), h, v or both.",
            "background" to "Fill colour (#RRGGBB) for uncovered corners at an arbitrary angle."
        ),
        "image_adjust" to mapOf(
            "src" to "Path to the source image.",
            "dest" to "Output path for the new file.",
            "brightness" to "Brightness from -1 to 1 (0 = no change).",
            "contrast" to "Contrast from -1 to 1 (0 = no change).",
            "saturation" to "Saturation from -1 (grayscale) to 1 (0 = no change).",
            "sharpness" to "Sharpness from 0 to 1 (0 = off)."
        ),
        "image_watermark" to mapOf(
            "src" to "Path to the source image.",
            "dest" to "Output path for the new file.",
            "text" to "Watermark text (used first if set).",
            "image" to "Path to the watermark image (used if text is not set).",
            "position" to "Placement: top-left, top-right, bottom-left, bottom-right (default), center.",
            "size" to "Size: text size or watermark image width, px (default ~1/16 of the side).",
            "color" to "Text colour (#RRGGBB or #AARRGGBB; default white).",
            "opacity" to "Opacity from 0 to 1 (default 1).",
            "margin" to "Edge inset in pixels (default 16).",
            "tiled" to "true - repeat the watermark as a grid over the whole image."
        ),
        "image_overlay_text" to mapOf(
            "src" to "Path to the source image.",
            "dest" to "Output path for the new file.",
            "text" to "Text to draw.",
            "x" to "Horizontal anchor, px (alternative to position).",
            "y" to "Top of the text, px (alternative to position).",
            "position" to "Placement when x/y are not set: top-left, top-right, bottom-left, bottom-right, center.",
            "size" to "Font size in pixels (default ~1/20 of the side).",
            "color" to "Text colour (#RRGGBB or #AARRGGBB; default white).",
            "align" to "Alignment relative to x: left (default), center, right.",
            "opacity" to "Opacity from 0 to 1 (default 1).",
            "background" to "Background box colour (#RRGGBB or #AARRGGBB; no box if unset).",
            "padding" to "Background/placement inset in pixels (default 8)."
        ),
        "image_collage" to mapOf(
            "srcs" to "Comma- or semicolon-separated image paths.",
            "dest" to "Output path for the new file.",
            "rows" to "Number of grid rows (auto if unset).",
            "cols" to "Number of grid columns (auto if unset).",
            "spacing" to "Gap between cells and at the edges, px (default 8).",
            "background" to "Collage background colour (#RRGGBB; default black).",
            "cellWidth" to "Cell width, px (default - largest image).",
            "cellHeight" to "Cell height, px (default - largest image)."
        ),
        "screen_annotate" to mapOf(
            "src" to "Path to the source image (e.g. a screenshot).",
            "dest" to "Output path for the new file.",
            "shapes" to "JSON array of shapes. rect/ellipse: x,y,w,h; arrow: x1,y1,x2,y2; text: x,y,text,size; highlight: x,y,w,h. Common key color."
        ),
        "image_svg_render" to mapOf(
            "src" to "Path to the .svg file (alternative to asset).",
            "asset" to "Name of a bundled app asset .svg (alternative to src).",
            "dest" to "Output path for the PNG (transparent background).",
            "width" to "Output width in pixels (optional).",
            "height" to "Output height in pixels (optional)."
        ),
        "image_remove_background" to mapOf(
            "src" to "Path to the source image.",
            "dest" to "Output path for the PNG with a transparent background."
        ),
        "image_detect_objects" to mapOf(
            "path" to "Path to the image to analyse.",
            "maxResults" to "Maximum objects in the response (default 20, no more than 100)."
        )
    )

    private fun params3(): Map<String, Map<String, String>> = mapOf(
        "media_sessions_list" to mapOf(
            "app" to "Filter by app package name (optional), for example com.spotify.music"
        ),
        "sms_list" to mapOf(
            "query" to "Text to search by SMS body (optional)",
            "limit" to "Maximum number of messages to return"
        ),
        "sms_send" to mapOf(
            "phone" to "Recipient phone number",
            "text" to "Message text"
        ),
        "sms_conversations" to mapOf(
            "limit" to "Maximum number of conversations (default 50)"
        ),
        "sms_read_state" to mapOf(
            "thread_id" to "Conversation identifier (from sms_conversations)",
            "read" to "true - mark as read (default), false - as unread"
        ),
        "subscription_list" to emptyMap(),
        "sms_send_on_sim" to mapOf(
            "number" to "Recipient phone number",
            "text" to "Message text",
            "subscription_id" to "SIM subscription identifier (id from subscription_list)"
        ),
        "mms_send" to mapOf(
            "number" to "Recipient phone number",
            "content_uri" to "content:// URI of the media to send (optional if file_path is set)",
            "file_path" to "Path to the file (in shared storage; optional if content_uri is set)."
        ),
        "sms_delete" to mapOf(
            "scope" to "Deletion scope: all - all SMS; thread - messages of a single conversation (needs thread_id); number - messages with a single number (needs number)",
            "thread_id" to "Conversation identifier (from sms_conversations). Required when scope=thread",
            "number" to "Phone number. Required when scope=number",
            "confirm" to "true - confirmation of irreversible deletion. Without true, deletion is not performed"
        ),
        "camera_capture" to mapOf(
            "type" to "Capture type: photo or video (default photo)",
            "path" to "Path to the output file, for example Download/photo.jpg (optional)",
            "duration" to "Video duration in seconds (only for type=video)",
            "with_audio" to "Record audio during video capture (default true)",
            "lens" to "Camera: front (front-facing) or back (main, default)",
            "camera_id" to "Camera id from camera_list (takes precedence over lens)",
            "analyze" to "For photos: look at the shot and return a description (Vision) or offline OCR if Vision is unavailable (default true)"
        ),
        "camera_list" to emptyMap(),
        "camera_zoom" to mapOf(
            "level" to "Zoom factor as a string, for example 2.0",
            "step" to "Zoom step: in (zoom in) or out (zoom out)",
            "lens" to "Camera: front|back (default back)",
            "camera_id" to "Camera id from camera_list (takes precedence over lens)"
        ),
        "camera_manual" to mapOf(
            "iso" to "ISO sensitivity as an integer, for example 100",
            "exposure_ms" to "Shutter speed in milliseconds, for example 100",
            "focus_distance" to "Focus distance in diopters (0 = infinity)",
            "lens" to "Camera: front|back (default back)",
            "camera_id" to "Camera id from camera_list (takes precedence over lens)"
        ),
        "camera_burst" to mapOf(
            "count" to "Number of frames, from 1 to 20",
            "interval_ms" to "Pause between frames in milliseconds (optional)",
            "destination_dir" to "Destination folder (in shared storage).",
            "lens" to "Camera: front|back (default back)",
            "camera_id" to "Camera id from camera_list (takes precedence over lens)"
        ),
        "camera_video_record" to mapOf(
            "path" to "Path to the video file, for example DCIM/Meha/video.mp4",
            "with_audio" to "Record microphone audio (default true)",
            "duration_ms" to "Recording duration in milliseconds (optional)",
            "lens" to "Camera: front|back (default back)",
            "camera_id" to "Camera id from camera_list (takes precedence over lens)"
        ),
        "camera_record_stop" to emptyMap(),
        "audio_record" to mapOf(
            "duration" to "Recording duration in seconds",
            "destination" to "Optional destination file path relative to the root; default Music/Meha/audio_<time>.m4a"
        ),
        "call_log" to mapOf(
            "limit" to "Maximum number of records to return",
            "number" to "Filter by phone number (accounts for format: 8..., +7..., spaces)",
            "type" to "Filter by call type: incoming, outgoing, missed or rejected",
            "days" to "Time filter: only calls from the last N days"
        ),
        "call_start" to mapOf(
            "phone" to "Phone number to call"
        ),
        "ussd_request" to mapOf(
            "code" to "USSD/service code, for example *#06# or *100#"
        ),
        "blocked_number_ui" to mapOf(
            "number" to "Number to add on the system screen (optional)"
        ),
        "call_observe_status" to emptyMap(),
        "call_rules_list" to emptyMap(),
        "call_rules_set" to mapOf(
            "enabled" to "Master switch for auto-handling. Defaults to false - nothing is handled automatically.",
            "block_unknown" to "Reject calls from numbers not in contacts.",
            "blocked_numbers" to "Numbers to reject, comma-separated (for example +79001234567, +79007654321).",
            "allow_numbers" to "Comma-separated number whitelist: they are always allowed through and never receive an auto-reply.",
            "auto_reply_sms" to "Automatically reply by SMS to unknown numbers (at most once per number every 6 hours).",
            "auto_reply_text" to "Auto-reply text (up to 300 characters).",
            "auto_reply_when_busy" to "Send an auto-reply when another call is in progress (busy).",
            "call_back_reminder" to "Create a call-back reminder if a call from a known contact is rejected or missed."
        ),
        "app_list" to emptyMap(),
        "app_launch" to mapOf(
            "package" to "Package name of the app to launch"
        ),
        "app_uninstall" to mapOf(
            "package" to "Package name of the app to uninstall"
        ),
        "app_package_info" to mapOf(
            "package" to "App package, e.g. com.android.chrome"
        ),
        "app_is_installed" to mapOf(
            "package" to "App package, e.g. com.android.chrome"
        ),
        "app_permissions" to mapOf(
            "package" to "App package, e.g. com.android.chrome"
        ),
        "app_activities" to mapOf(
            "package" to "App package, e.g. com.android.chrome"
        ),
        "app_services" to mapOf(
            "package" to "App package, e.g. com.android.chrome"
        ),
        "app_receivers" to mapOf(
            "package" to "App package, e.g. com.android.chrome"
        ),
        "role_status" to mapOf(
            "role" to "Role: a short name (SMS, DIALER, BROWSER, HOME) or the full one (android.app.role.SMS)"
        ),
        "role_request" to mapOf(
            "role" to "Role: a short name (SMS, DIALER, BROWSER, HOME, CALL_SCREENING) or the full one (android.app.role.SMS)"
        ),
        "role_hold" to mapOf(
            "role" to "Role: a short name (SMS, DIALER, BROWSER, HOME) or the full one (android.app.role.SMS)"
        ),
        "call_screening_role_request" to emptyMap(),
        "dialer_role_request" to emptyMap(),
        "open_url" to mapOf(
            "url" to "Absolute http/https link to open in the browser"
        ),
        "app_details" to mapOf(
            "package" to "App package, e.g. com.android.chrome"
        ),
        "open_settings" to mapOf(
            "screen" to "Which screen to open: main, wifi, bluetooth, location, airplane, display, sound, battery, data, nfc, accessibility, developer, apps, all_files"
        ),
        "notification_settings_open" to mapOf(
            "package" to "App package name (optional); without it, general notification settings open"
        ),
        "app_notification_settings_open" to mapOf(
            "package" to "App package, e.g. com.android.chrome"
        ),
        "internet_panel_open" to emptyMap(),
        "location_settings_open" to emptyMap(),
        "share_text" to mapOf(
            "text" to "Text to share"
        ),
        "dial" to mapOf(
            "phone" to "Phone number to dial"
        ),
        "intent_open" to mapOf(
            "uri" to "URI, link (scheme://...) or local file path",
            "action" to "What to do: view (default) or edit",
            "mime" to "MIME type, for example application/pdf (optional)",
            "package" to "Package name of the app to open in (optional)",
            "chooser" to "Show the system app picker (default false)"
        ),
        "intent_send_to" to mapOf(
            "package" to "Package name of the recipient app",
            "text" to "Message text (optional)",
            "path" to "Path to the local attachment file (optional)",
            "mime" to "Attachment MIME type (default */*)",
            "subject" to "Message subject (optional)"
        ),
        "media_audio_list" to mapOf(
            "query" to "Filter by title (optional)",
            "limit" to "Maximum entries (optional)"
        )
    )

    private fun params4(): Map<String, Map<String, String>> = mapOf(
        "media_video_list" to mapOf(
            "query" to "Filter by title (optional)",
            "limit" to "Maximum entries (optional)"
        ),
        "settings_get" to emptyMap(),
        "self_config" to mapOf(
            "model" to "Model name",
            "endpoint" to "Endpoint address",
            "reasoning" to "off | low | medium | high"
        ),
        "sensor_list" to emptyMap(),
        "sensor_read" to mapOf(
            "type" to "Sensor type, for example accelerometer",
            "timeout_ms" to "Wait timeout, ms (500-10000)"
        ),
        "app_deep_link" to mapOf(
            "url" to "Link (scheme://...)",
            "package" to "App package (optional)"
        ),
        "app_install_apk" to mapOf(
            "path" to "Path to the local .apk (for example, from app_backup_apk or download_file)",
            "url" to "URL of the .apk: the tool downloads it and opens the installer (if path is not set)"
        ),
        "app_store_open" to mapOf(
            "package" to "Package name for the Google Play page (if query is not set)",
            "query" to "Search query in Google Play (if package is not set)"
        ),
        "app_store_search" to mapOf(
            "query" to "Search query in Google Play"
        ),
        "app_suggest_for" to mapOf(
            "format" to "A MIME (application/pdf), an extension (pdf, .docx, rar) or a general word (video, image, audio, office, archive, pdf)",
            "task" to "A short task description when format is not set (e.g. \"open a pdf\", \"edit a word document\")"
        ),
        "app_backup_apk" to mapOf(
            "package" to "App package, e.g. com.android.chrome",
            "destPath" to "Folder for the APK copies (optional; default Download/apk_backup)"
        ),
        "sms_thread" to mapOf(
            "phone" to "Phone number",
            "limit" to "Maximum messages (optional)"
        ),
        "call_log_delete" to mapOf(
            "all" to "true - delete the entire log",
            "id" to "Record identifier",
            "phone" to "Phone number; the format does not matter (match by significant digits)"
        ),
        "ui_tree" to emptyMap(),
        "screen_understand" to mapOf(
            "verbose" to "Show the raw tree and OCR (default false)",
            "attach_image" to "Attach the screenshot to the request as an image if the model supports vision; otherwise save it as a file (default false)",
            "max_chars" to "Maximum response length, 500-20000 (default 4000)"
        ),
        "ui_tap" to mapOf(
            "x" to "X coordinate (optional)",
            "y" to "Y coordinate (optional)",
            "text" to "Element text (optional)",
            "textContains" to "Text substring",
            "desc" to "Description (contentDescription)",
            "contentDescription" to "contentDescription",
            "id" to "view id (optional)",
            "resourceId" to "view id or suffix",
            "className" to "Element class",
            "index" to "Match number (1-based)."
        ),
        "ui_long_press" to mapOf(
            "x" to "X coordinate (optional)",
            "y" to "Y coordinate (optional)",
            "text" to "Element text",
            "textContains" to "Text substring",
            "desc" to "contentDescription",
            "contentDescription" to "contentDescription",
            "id" to "view id",
            "resourceId" to "view id or suffix",
            "className" to "Element class",
            "index" to "Match number (1-based)."
        ),
        "ui_swipe" to mapOf(
            "x1" to "Start X",
            "y1" to "Start Y",
            "x2" to "End X",
            "y2" to "End Y",
            "duration_ms" to "Duration, ms"
        ),
        "ui_scroll" to mapOf(
            "direction" to "down | up"
        ),
        "ui_find" to mapOf(
            "text" to "Element text (optional)",
            "textContains" to "Text substring",
            "contentDescription" to "contentDescription",
            "resourceId" to "view id or suffix",
            "className" to "Class name, for example android.widget.Button (optional)",
            "index" to "Match number (1-based).",
            "exact" to "true - exact match, false - substring (default false)",
            "limit" to "Maximum results (default 20, maximum 100)"
        ),
        "ui_wait_for_text" to mapOf(
            "text" to "Text to find (or another criterion)",
            "textContains" to "Text substring",
            "contentDescription" to "contentDescription",
            "resourceId" to "view id or suffix",
            "className" to "Element class",
            "index" to "Match number (1-based).",
            "timeoutMs" to "Timeout in ms (default 8000, maximum 30000)",
            "intervalMs" to "Polling interval in ms (default 400)",
            "exact" to "true - exact match, false - substring (default false)"
        ),
        "ui_scroll_until" to mapOf(
            "text" to "Text to find (or another criterion)",
            "textContains" to "Text substring",
            "contentDescription" to "contentDescription",
            "resourceId" to "view id or suffix",
            "className" to "Element class",
            "index" to "Match number (1-based).",
            "direction" to "down | up (default down)",
            "maxSwipes" to "Maximum swipes (default 8, maximum 30)",
            "exact" to "true - exact match, false - substring (default false)"
        ),
        "ui_type" to mapOf(
            "text" to "Text to type",
            "selectorText" to "Target field text (optional)",
            "textContains" to "Field text substring (optional)",
            "id" to "Field view id (optional)",
            "resourceId" to "Field view id, full or suffix (optional)",
            "desc" to "Field contentDescription",
            "contentDescription" to "Field contentDescription",
            "className" to "Field class (optional)",
            "index" to "Match number (1-based)."
        ),
        "key_event" to mapOf(
            "key" to "back | home | recents | notifications | quick_settings | lock | screenshot"
        ),
        "ui_copy" to emptyMap(),
        "ui_paste" to emptyMap(),
        "ui_cut" to emptyMap(),
        "ui_select_all" to emptyMap(),
        "ui_accessibility_status" to emptyMap(),
        "ui_accessibility_open_settings" to emptyMap(),
        "screen_capture" to emptyMap(),
        "screen_capture_region" to mapOf(
            "path" to "Path to the output file (.png for a frame, .mp4 for video; in shared storage).",
            "left" to "Left edge of the region in pixels (optional)",
            "top" to "Top edge of the region in pixels (optional)",
            "width" to "Region width in pixels (optional)",
            "height" to "Region height in pixels (optional)",
            "window" to "Capture a single app window (package/name). Android 14+ only; the specific window is chosen by the user in the system dialog",
            "app" to "Synonym of the window parameter (app package)",
            "duration_ms" to "If set - record a video of this duration in ms (whole screen only; maximum 60000)"
        ),
        "screen_record_start" to mapOf(
            "path" to "Path to the recording file (.mp4; in shared storage).",
            "with_audio" to "Record microphone audio along with the screen (default false)"
        ),
        "screen_record_stop" to emptyMap(),
        "screen_record_status" to emptyMap(),
        "remember_screen" to mapOf(
            "note" to "Short caption/title of the entry (optional)",
            "tags" to "Your own comma-separated tags (optional)",
            "person" to "Person's name or id to link (optional)",
            "save" to "Save to memory (default true)",
            "max_chars" to "Maximum text length, 200-20000 (default 4000)"
        ),
        "notification_list" to emptyMap(),
        "notification_open" to mapOf(
            "key" to "Notification key"
        ),
        "notification_dismiss" to mapOf(
            "key" to "Notification key"
        ),
        "notification_reply" to mapOf(
            "key" to "Notification key",
            "text" to "Reply text"
        ),
        "notification_clear_all" to emptyMap(),
        "notification_channels_list" to emptyMap(),
        "notification_channels_create" to mapOf(
            "id" to "Channel identifier, for example reminders",
            "name" to "Channel display name",
            "importance" to "Importance: low, default or high (default default)"
        ),
        "dnd_status" to emptyMap(),
        "app_usage" to mapOf(
            "days" to "Number of days 1-30 (default 1)"
        ),
        "device_lock" to emptyMap(),
        "device_admin_enable" to emptyMap(),
        "speech_to_text" to mapOf(
            "language" to "Language, e.g. ru-RU (optional)",
            "timeout_ms" to "Wait timeout, ms (2000-30000)"
        ),
        "ocr_image" to mapOf(
            "path" to "Path to the image"
        ),
        "vision_status" to emptyMap(),
        "vision_check" to emptyMap()
    )

    private fun params5(): Map<String, Map<String, String>> = mapOf(
        "tool_search" to mapOf(
            "query" to "What needs to be done, in plain words (Russian or English)"
        ),
        "selftest_run" to emptyMap(),
        "note_create" to mapOf(
            "text" to "Note text"
        ),
        "note_list" to emptyMap(),
        "note_delete" to mapOf(
            "id" to "id from note_list",
            "query" to "Substring of the note text"
        ),
        "memory_save" to mapOf(
            "text" to "What to remember, in one phrase",
            "tags" to "Keywords/synonyms separated by commas",
            "importance" to "0 | 1 | 2"
        ),
        "memory_search" to mapOf(
            "query" to "What to search for"
        ),
        "memory_list" to mapOf(
            "limit" to "How many records to show (30 by default, maximum 100)",
            "query" to "Long-term memory text substring (case-insensitive)",
            "tag" to "Substring in memory tags (case-insensitive)",
            "kind" to "Kind of records: memory (default) or note",
            "sinceDays" to "Only those created/updated in the last N days",
            "orderBy" to "recent (default) | importance"
        ),
        "memory_delete" to mapOf(
            "id" to "long-term memory record id"
        ),
        "profile_list" to emptyMap(),
        "profile_set" to mapOf(
            "text" to "The fact in one phrase (required)",
            "id" to "id of an existing record from profile_list to change it"
        ),
        "profile_forget" to mapOf(
            "id" to "record id from profile_list",
            "all" to "true — delete all profile facts"
        ),
        "opinion_set" to mapOf(
            "text" to "The opinion/stance itself in one phrase",
            "confidence" to "Confidence 0..1 (0.8 by default)",
            "supersedesId" to "id of the previous opinion that this one replaces (0 = none)",
            "tags" to "Keywords/synonyms separated by commas"
        ),
        "opinion_list" to mapOf(
            "limit" to "How many opinions to show (20 by default, maximum 50)",
            "includeRetired" to "Show the displaced opinions too (false by default)"
        ),
        "todo_add" to mapOf(
            "text" to "Task text"
        ),
        "todo_list" to emptyMap(),
        "todo_done" to mapOf(
            "id" to "task id"
        ),
        "todo_delete" to mapOf(
            "id" to "task id"
        ),
        "schedule_create" to mapOf(
            "time" to "Launch time in HH:MM format (24 hours), e.g. 08:00",
            "task" to "What the agent should do when triggered (task text/prompt)",
            "days" to "Days: daily | weekdays | weekends | a list (mon,wed,fri / пн,ср,пт). daily by default"
        ),
        "schedule_list" to emptyMap(),
        "schedule_delete" to mapOf(
            "id" to "Task (id from schedule_list)",
            "query" to "Substring of the task text to search for (if the id is unknown)"
        ),
        "schedule_toggle" to mapOf(
            "id" to "Task (id from schedule_list)",
            "query" to "Substring of the task text to search for",
            "enabled" to "true — enable, false — disable"
        ),
        "reminder_set" to mapOf(
            "text" to "Reminder text — what to remind about",
            "delay" to "In how long to remind: 30s, 5m, 2h, 1d, or a number of minutes (5). Mutually exclusive with at",
            "at" to "When to remind: epoch (seconds or milliseconds) or HH:MM (24h, the nearest occurrence). Mutually exclusive with delay",
            "chat_id" to "Optional: chat id for delivery (the current chat by default)"
        ),
        "clipboard_get" to emptyMap(),
        "clipboard_set" to mapOf(
            "text" to "Text to write to the clipboard"
        ),
        "clipboard_history_list" to mapOf(
            "limit" to "How many recent records to show (20 by default, maximum 50)."
        ),
        "clipboard_history_get" to mapOf(
            "index" to "Record number from clipboard_history_list (1 — the newest)",
            "id" to "Record identifier (alternative to index)"
        ),
        "clipboard_history_clear" to emptyMap(),
        "translate_text" to mapOf(
            "text" to "Text to translate",
            "to" to "Target language: code (ru, en, de) or name",
            "from" to "Source language (auto by default)",
            "tone" to "Desired translation style (optional): neutral, formal, colloquial, etc."
        ),
        "translate_chat" to mapOf(
            "to" to "Target language: code (ru, en, de) or name",
            "chat_id" to "Chat (id; the most recent by default).",
            "limit" to "How many recent messages to translate, 1–50 (20 by default)",
            "from" to "Source language (auto by default)"
        ),
        "translate_screen" to mapOf(
            "to" to "Target language: code (ru, en, de) or name",
            "from" to "Source language (auto by default)",
            "include_original" to "Also return the recognized screen text (false by default)"
        ),
        "chat_list" to mapOf(
            "limit" to "How many chats to return, 1–50 (20 by default)"
        ),
        "chat_search" to mapOf(
            "query" to "Text to search chat titles and messages for",
            "limit" to "How many chats to return, 1–50 (20 by default)"
        ),
        "chat_recall" to mapOf(
            "chat_id" to "Chat (id; the most recent by default).",
            "limit" to "How many recent messages to take into account, 1–100 (30 by default)"
        ),
        "chat_create" to mapOf(
            "title" to "Title of the new chat (optional; \"Новый чат\" by default)"
        ),
        "chat_rename" to mapOf(
            "chat_id" to "Chat (id). Optional: the current chat by default",
            "title" to "New chat title"
        ),
        "chat_delete" to mapOf(
            "chat_id" to "Chat (id). Optional: the current chat by default",
            "confirm" to "Deletion confirmation: must be true, otherwise the chat is not deleted"
        ),
        "chat_pin" to mapOf(
            "chat_id" to "Chat (id). Optional: the current chat by default",
            "pinned" to "true — pin (default), false — unpin"
        ),
        "chat_clear_messages" to mapOf(
            "chat_id" to "Chat (id). Optional: the current chat by default",
            "confirm" to "Clearing confirmation: must be true, otherwise the messages are not deleted"
        ),
        "chat_export" to mapOf(
            "chat_id" to "Chat (id). Optional: the current chat by default",
            "format" to "Export format: md, txt, or json (md by default)",
            "path" to "Absolute path to the export file (optional)"
        ),
        "device_info" to emptyMap(),
        "device_facts_list" to emptyMap(),
        "device_facts_get" to mapOf(
            "key" to "Fact key, for example installed_apps or device_model"
        ),
        "device_facts_refresh" to mapOf(
            "key" to "Fact key (optional); empty or all — update all facts"
        ),
        "device_facts_forget" to mapOf(
            "key" to "Fact key (optional); empty or all — clear the entire cache"
        ),
        "notification_post" to mapOf(
            "title" to "Notification title",
            "text" to "Notification text",
            "chat_id" to "Identifier of the chat that opens when the notification is tapped (optional)",
            "message_id" to "Identifier of the message to scroll to (optional)"
        ),
        "location_get" to emptyMap(),
        "location_last_known" to emptyMap(),
        "location_updates_start" to mapOf(
            "interval_sec" to "Update interval in seconds (30 by default, minimum 5)",
            "min_distance_m" to "Minimum displacement in meters for a new point (10 by default)",
            "high_accuracy" to "High accuracy (GPS); false by default — network provider"
        ),
        "location_updates_stop" to emptyMap()
    )

    private fun params6(): Map<String, Map<String, String>> = mapOf(
        "location_updates_get" to mapOf(
            "limit" to "How many recent points to return (20 by default)"
        ),
        "geofence_add" to mapOf(
            "latitude" to "Latitude of the geofence center (degrees)",
            "longitude" to "Longitude of the geofence center (degrees)",
            "radius_m" to "Geofence radius in meters (greater than 0)",
            "id" to "Geofence identifier (optional; otherwise it will be generated)",
            "expiration_hours" to "Geofence lifetime in hours (optional; otherwise indefinite)",
            "dwell" to "Report dwelling inside the zone (dwell); false by default"
        ),
        "geofence_remove" to mapOf(
            "id" to "Identifier of the geofence to delete"
        ),
        "geofence_list" to emptyMap(),
        "calendar_events" to mapOf(
            "from" to "Start of the range (epoch millis or ISO date)",
            "to" to "End of the range (epoch millis or ISO date)"
        ),
        "calendar_list" to emptyMap(),
        "calendar_instances" to mapOf(
            "from" to "Start of the period (epoch millis or ISO date)",
            "to" to "End of the period (epoch millis or ISO date)"
        ),
        "calendar_accounts_list" to emptyMap(),
        "calendar_device_events" to mapOf(
            "from" to "Start of the range (epoch millis or ISO date)",
            "to" to "End of the range (epoch millis or ISO date)"
        ),
        "calendar_create" to mapOf(
            "title" to "Event title",
            "start" to "Event start (epoch millis or ISO date)",
            "end" to "Event end (epoch millis or ISO date)",
            "reminder" to "Reminder N minutes before the event start"
        ),
        "calendar_update" to mapOf(
            "id" to "Event (id from calendar_events)",
            "title" to "New event title (optional)",
            "start" to "New start (epoch millis or ISO date, optional)",
            "end" to "New end (epoch millis or ISO date, optional)",
            "reminder" to "New reminder N minutes before the start (optional)"
        ),
        "calendar_delete" to mapOf(
            "id" to "Identifier of the event to delete (id from calendar_events)"
        ),
        "calendar_attendees_set" to mapOf(
            "event_id" to "Event (id from calendar_events)",
            "email" to "Attendee email",
            "name" to "Attendee name (optional)"
        ),
        "calendar_reminders_set" to mapOf(
            "event_id" to "Event (id from calendar_events)",
            "minutes" to "Number of minutes before the event start (can be negative — after the start)"
        ),
        "calendar_recurring_create" to mapOf(
            "title" to "Event title",
            "dtstart" to "Event start (ISO date or epoch millis)",
            "duration" to "Duration in RFC5545 format (e.g. PT1H) or a number of seconds",
            "dtend" to "Event end (ISO date or epoch millis) — an alternative to duration",
            "rrule" to "RFC5545 recurrence rule, e.g. FREQ=WEEKLY;COUNT=10",
            "calendar_id" to "Calendar identifier (id from calendar_list; optional)"
        ),
        "saf_pick_tree" to mapOf(
            "title" to "Title of the folder picker dialog (optional)."
        ),
        "saf_grants_list" to emptyMap(),
        "saf_grant_remove" to mapOf(
            "grant_id" to "Grant (id from saf_grants_list)."
        ),
        "saf_grant_test" to mapOf(
            "grant_id" to "Grant (id from saf_grants_list)."
        ),
        "saf_list" to mapOf(
            "grant_id" to "Grant (id from saf_grants_list).",
            "path" to "Path to the folder relative to the grant root (optional; empty — root)."
        ),
        "saf_read" to mapOf(
            "grant_id" to "Grant (id from saf_grants_list).",
            "path" to "Path to the file inside the grant.",
            "offset" to "Byte offset from which to start reading (optional).",
            "limit" to "How many bytes to read (optional; 1 MB by default, maximum 64 MB)."
        ),
        "saf_write" to mapOf(
            "grant_id" to "Grant (id from saf_grants_list).",
            "path" to "Path to the file inside the grant.",
            "content" to "Text content to write.",
            "overwrite" to "Overwrite the file (true by default)."
        ),
        "saf_mkdir" to mapOf(
            "grant_id" to "Grant (id from saf_grants_list).",
            "path" to "Path to the folder to create relative to the grant root."
        ),
        "saf_delete" to mapOf(
            "grant_id" to "Grant (id from saf_grants_list).",
            "path" to "Path to the file or folder relative to the grant root."
        ),
        "saf_create_document" to mapOf(
            "filename" to "File name to save.",
            "mime" to "MIME type (optional; application/octet-stream by default).",
            "content" to "File contents (optional; if specified, it will be written immediately)."
        ),
        "folder_watch_start" to mapOf(
            "path" to "Absolute path to the folder (within storage).",
            "mask" to "create | modify | delete | move | all (all by default)."
        ),
        "folder_watch_stop" to mapOf(
            "watch_id" to "Watch (id from folder_watch_list)."
        ),
        "folder_watch_list" to emptyMap(),
        "folder_watch_events" to mapOf(
            "watch_id" to "Watch (id from folder_watch_list).",
            "limit" to "How many recent events to return (100 by default)."
        ),
        "folder_watch_clear" to mapOf(
            "watch_id" to "Watch (id from folder_watch_list)."
        ),
        "saf_open_document" to mapOf(
            "mime" to "MIME type for the filter (optional; */* by default)."
        ),
        "doc_info" to mapOf(
            "path" to "Path to the document (in shared storage or /storage/emulated/0)."
        ),
        "doc_read" to mapOf(
            "path" to "Path to the document (in shared storage or /storage/emulated/0).",
            "page" to "Page for PDF/PPTX (1-based; all by default).",
            "sheet" to "Sheet for XLSX/ODS: name or 1-based index (the first by default).",
            "max_chars" to "Maximum number of characters in the output (100000 by default)."
        ),
        "doc_create" to mapOf(
            "path" to "Path to the document to create (in shared storage or /storage/emulated/0).",
            "content" to "Document text; for tables (XLSX/ODS/CSV) — rows, cells separated by a tab, `;` or `,`.",
            "format" to "Explicit format if the extension is ambiguous.",
            "title" to "Document title (metadata; optional).",
            "overwrite" to "Overwrite the file."
        ),
        "doc_edit" to mapOf(
            "path" to "Path to the existing document (in shared storage or /storage/emulated/0).",
            "mode" to "`append` — add to the end; `replace` — replace all occurrences of `find`.",
            "content" to "Text to add (append) or replace with (replace); for tables — rows, cells separated by a tab, `;` or `,`.",
            "find" to "Text to search for (required for `replace`)."
        ),
        "pdf_merge" to mapOf(
            "sources" to "Paths to the PDFs, separated by commas.",
            "destination" to "Path to the PDF to create (in shared storage or /storage/emulated/0).",
            "overwrite" to "Overwrite the file."
        ),
        "pdf_split" to mapOf(
            "source" to "Path to the source PDF (in shared storage or /storage/emulated/0).",
            "pages" to "Range, e.g. 1-3 or 5.",
            "destination" to "Path to the PDF to create (in shared storage or /storage/emulated/0).",
            "overwrite" to "Overwrite the file."
        ),
        "pdf_create" to mapOf(
            "text" to "The text from which the PDF is created.",
            "title" to "Title (metadata).",
            "path" to "Path to the PDF to create inside an allowed folder, e.g. Documents/report.pdf; the .pdf extension is added if it is missing.",
            "file_name" to "File name without a path (by default from title in Documents/Meha).",
            "overwrite" to "Overwrite the file."
        ),
        "pdf_from_markdown" to mapOf(
            "text" to "Markdown text (a simple subset is supported).",
            "title" to "Title (metadata).",
            "path" to "Path to the PDF to create inside an allowed folder, e.g. Documents/note.pdf; the .pdf extension is added if it is missing.",
            "file_name" to "File name without a path (by default from title in Documents/Meha).",
            "overwrite" to "Overwrite the file."
        ),
        "pdf_ocr" to mapOf(
            "path" to "Path to the PDF (in shared storage or /storage/emulated/0).",
            "page" to "Page number (1-based; 1 by default)."
        ),
        "pdf_rotate" to mapOf(
            "source" to "Path to the source PDF (in shared storage or /storage/emulated/0).",
            "destination" to "Path to the PDF to create (in shared storage or /storage/emulated/0).",
            "pages" to "Page range (1-based), e.g. 1-3 or 5; all pages by default.",
            "angle" to "Rotation angle: 90, 180 or 270 (clockwise).",
            "overwrite" to "Overwrite the file."
        ),
        "pdf_watermark" to mapOf(
            "source" to "Path to the source PDF (in shared storage or /storage/emulated/0).",
            "destination" to "Path to the PDF to create (in shared storage or /storage/emulated/0).",
            "text" to "Watermark text.",
            "pages" to "Page range (1-based), e.g. 1-3 or 5; all pages by default.",
            "opacity" to "Opacity from 0 to 1 (0.3 by default).",
            "font_size" to "Font size in points (48 by default).",
            "angle" to "Tilt angle in degrees (45 by default).",
            "color" to "Colour: #rrggbb, #rgb or r,g,b (grey by default).",
            "overwrite" to "Overwrite the file."
        ),
        "pdf_extract_images" to mapOf(
            "path" to "Path to the PDF (in shared storage or /storage/emulated/0).",
            "output_dir" to "Directory for the PNGs (created if needed).",
            "pages" to "Page range (1-based), e.g. 1-3 or 5; all pages by default.",
            "overwrite" to "Overwrite existing PNGs."
        ),
        "pdf_render_pages" to mapOf(
            "path" to "Path to the PDF (in shared storage or /storage/emulated/0).",
            "output_dir" to "Directory for the PNGs (created if needed).",
            "pages" to "Page range (1-based), e.g. 1-3 or 5; all pages by default.",
            "dpi" to "Render resolution in DPI (150 by default; 18–600).",
            "prefix" to "File-name prefix (page by default).",
            "overwrite" to "Overwrite existing PNGs."
        ),
        "pdf_compress" to mapOf(
            "source" to "Path to the source PDF (in shared storage or /storage/emulated/0).",
            "destination" to "Path to the PDF to create (in shared storage or /storage/emulated/0).",
            "max_image_dimension" to "Maximum image side in pixels; when set, large images are downscaled and re-encoded as JPEG.",
            "image_quality" to "JPEG quality 1–100 when downscaling images (75 by default).",
            "overwrite" to "Overwrite the file."
        ),
        "pdf_fill_form" to mapOf(
            "source" to "Path to the source PDF with the form (in shared storage or /storage/emulated/0).",
            "destination" to "Path to the PDF to create (in shared storage or /storage/emulated/0).",
            "fields" to "JSON object name→value, e.g. {\"name\": \"Ivan\", \"agree\": \"Yes\"}.",
            "flatten" to "Bake the values into the page content (make the form non-editable).",
            "overwrite" to "Overwrite the file."
        ),
        "pdf_pages" to mapOf(
            "path" to "Path to the PDF (in shared storage or /storage/emulated/0)."
        ),
        "xlsx_set_cell" to mapOf(
            "path" to "Path to the XLSX file (in shared storage or /storage/emulated/0).",
            "cell" to "Cell address, e.g. B2.",
            "sheet" to "Sheet name or index (the first by default).",
            "value" to "Value to write (a number or text)."
        ),
        "pdf_from_template" to mapOf(
            "template" to "Template (text/Markdown) with placeholders of the form {{key}}.",
            "values" to "JSON object of values of the form {\"key\": \"value\"}.",
            "title" to "Title (metadata).",
            "path" to "Path to the PDF to create, e.g. Documents/report.pdf; the .pdf extension is added if it is missing.",
            "file_name" to "File name without a path (by default from title in Documents/Meha).",
            "overwrite" to "Overwrite the file."
        ),
        "pdf_from_template_file" to mapOf(
            "template_path" to "Path to the template file, text/Markdown (in shared storage).",
            "values" to "JSON object of values of the form {\"key\": \"value\"}.",
            "title" to "Title (metadata).",
            "path" to "Path to the PDF to create; the .pdf extension is added if it is missing.",
            "file_name" to "File name without a path (by default from title in Documents/Meha).",
            "overwrite" to "Overwrite the file."
        ),
        "xlsx_set_cells" to mapOf(
            "path" to "Path to the XLSX file, e.g. Documents/table.xlsx.",
            "cells" to "JSON of cells: {\"A1\": \"value\", \"B2\": 42} or [{\"cell\": \"A1\", \"value\": \"…\"}].",
            "sheet" to "Sheet name or index (the first by default).",
            "overwrite" to "Overwrite cells that already have a value (yes by default, as in xlsx_set_cell)."
        ),
        "docx_replace" to mapOf(
            "path" to "Path to the existing DOCX, e.g. Documents/contract.docx.",
            "replacements" to "JSON object of old→new replacements: {\"old\": \"new\"}.",
            "template_values" to "JSON object of substitutions: {\"key\": \"value\"} → {{key}}.",
            "output_path" to "Write the result to a new file instead of in place.",
            "file_name" to "Copy file name (by default — Documents/Meha).",
            "overwrite" to "Overwrite the copy file."
        ),
        "docx_append" to mapOf(
            "path" to "Path to the existing DOCX, e.g. Documents/report.docx.",
            "content" to "Text to add; each line is a new paragraph.",
            "output_path" to "Write the result to a new file instead of in place.",
            "file_name" to "Copy file name (by default — Documents/Meha).",
            "overwrite" to "Overwrite the copy file."
        ),
        "pptx_create" to mapOf(
            "path" to "Path to the PPTX to create, e.g. Documents/deck.pptx.",
            "slides" to "JSON array of slides: strings or {\"title\": …, \"lines\": [...]}.",
            "overwrite" to "Overwrite the file."
        ),
        "pptx_edit" to mapOf(
            "path" to "Path to the existing PPTX, e.g. Documents/deck.pptx.",
            "mode" to "`append` — add slides; `replace` — replace text.",
            "slides" to "For append: JSON array of slides to add.",
            "slide" to "For replace: slide number (1-based; all by default).",
            "find" to "For replace: the text to find.",
            "replace" to "For replace: the replacement text."
        ),
        "odp_create" to mapOf(
            "path" to "Path to the ODP to create, e.g. Documents/deck.odp.",
            "slides" to "JSON array of slides: strings or {\"title\": …, \"lines\": [...]}.",
            "overwrite" to "Overwrite the file."
        ),
        "xlsx_style" to mapOf(
            "path" to "Path to the XLSX, e.g. Documents/table.xlsx.",
            "range" to "Cell range: A1 or A1:C3.",
            "sheet" to "Sheet name or index (the first by default).",
            "bold" to "Make the text bold.",
            "fill_color" to "Fill colour #RRGGBB.",
            "number_format" to "Number format, e.g. 0.00 or #,##0."
        ),
        "office_convert" to mapOf(
            "path" to "Path to the source document.",
            "target" to "Target format (name or extension), e.g. md, csv, json, xlsx.",
            "destination" to "Path to the file to create.",
            "sheet" to "XLSX/ODS sheet (the first by default).",
            "title" to "Document title (metadata; optional).",
            "overwrite" to "Overwrite the file."
        ),
        "office_merge" to mapOf(
            "sources" to "Paths to the source files, comma-separated.",
            "destination" to "Path to the file to create.",
            "separator" to "Separator between text parts (optional).",
            "overwrite" to "Overwrite the file."
        ),
        "office_split" to mapOf(
            "path" to "Path to the source document.",
            "output_dir" to "Directory for the results (created if needed).",
            "separator" to "Separator line for text documents (--- by default).",
            "overwrite" to "Overwrite existing files."
        ),
        "doc_tables" to mapOf(
            "path" to "Path to the table (XLSX/ODS/CSV/TSV).",
            "sheet" to "XLSX/ODS sheet (name or index; the first by default).",
            "max_rows" to "Maximum rows in the output (1000 by default)."
        ),
        "settings_system_get" to mapOf(
            "key" to "Exact name of the Settings.System key, e.g. SCREEN_BRIGHTNESS, FONT_SCALE, TIME_12_24"
        ),
        "settings_secure_get" to mapOf(
            "key" to "Exact name of the Settings.Secure key, e.g. ANDROID_ID, DEFAULT_INPUT_METHOD"
        ),
        "settings_global_get" to mapOf(
            "key" to "Exact name of the Settings.Global key, e.g. AIRPLANE_MODE_ON, WIFI_ON, ADB_ENABLED"
        ),
        "settings_known_keys" to emptyMap()
    )

    private fun params7(): Map<String, Map<String, String>> = mapOf(
        "settings_system_set" to mapOf(
            "key" to "Exact name of the Settings.System key, e.g. SCREEN_BRIGHTNESS, FONT_SCALE, TIME_12_24",
            "value" to "Value to write (a string; specify numbers as text, e.g. \"1\")"
        ),
        "settings_write_access_request" to emptyMap(),
        "dnd_set" to mapOf(
            "mode" to "One of: off, all, priority, alarms, none"
        ),
        "alarm_exact_set" to mapOf(
            "time" to "Trigger time: epoch milliseconds (e.g. 1735689600000) or HH:MM (e.g. 07:30)",
            "label" to "Alarm label (optional)",
            "chat_id" to "Chat ID to navigate to from the notification (optional; the current chat by default)"
        ),
        "alarm_exact_list" to emptyMap(),
        "alarm_exact_cancel" to mapOf(
            "id" to "Alarm ID from alarm_exact_list"
        ),
        "automation_rule_create" to mapOf(
            "name" to "Rule name, e.g. \"Charging enabled\"",
            "trigger_type" to "Trigger type (the full list is in the tool description).",
            "trigger_config" to "Trigger configuration (format depends on the type, see the tool description).",
            "action_type" to "start_chat, agent_task, or notify (see the tool description).",
            "action_config" to "Action configuration (format depends on the type, see the tool description)."
        ),
        "automation_rule_list" to emptyMap(),
        "automation_rule_toggle" to mapOf(
            "id" to "Rule ID from automation_rule_list",
            "enabled" to "true — enable, false — disable"
        ),
        "automation_rule_delete" to mapOf(
            "id" to "Rule ID from automation_rule_list"
        ),
        "events_recent" to mapOf(
            "limit" to "How many events to return (1–100, 20 by default)",
            "type" to "Filter by trigger type (values as in automation_rule_create)."
        ),
        "automation_suggestions" to mapOf(
            "limit" to "How many suggestions to return (1–20, 10 by default)"
        ),
        "automation_suggestions_simulate" to mapOf(
            "action" to "analyze | accept | dismiss (analyze by default)",
            "id" to "suggestion id; the first found one by default"
        ),
        "locale_app_set" to mapOf(
            "lang" to "Language tag, e.g. ru or en-US",
            "mode" to "system — revert to the system locale; default — set lang (default)"
        ),
        "wifi_connect_specifier" to mapOf(
            "ssid" to "Network name (SSID), e.g. HomeWiFi",
            "password" to "WPA2 password (optional; without it the network is considered open)",
            "timeout_sec" to "Wait timeout in seconds, 5–120 (30 by default)"
        ),
        "wifi_suggest_add" to mapOf(
            "ssid" to "Network name (SSID), e.g. HomeWiFi",
            "password" to "WPA2 password (optional; without it the network is considered open)"
        ),
        "wifi_direct_discover" to mapOf(
            "timeout_sec" to "How many seconds to search, 5–60 (15 by default)"
        ),
        "wifi_direct_connect" to mapOf(
            "device_address" to "MAC address of the Wi-Fi Direct peer, e.g. AA:BB:CC:DD:EE:FF",
            "wps_pin" to "WPS PIN (optional; without it PBC is used)"
        ),
        "wifi_direct_disconnect" to emptyMap(),
        "hotspot_local_start" to emptyMap(),
        "hotspot_local_stop" to emptyMap(),
        "wifi_rtt_range" to mapOf(
            "bssid" to "Access point BSSID, e.g. AA:BB:CC:DD:EE:FF (the current one by default)",
            "timeout_sec" to "Measurement timeout in seconds, 3–60 (10 by default)"
        ),
        "app_ops_self" to mapOf(
            "op" to "AppOps operation, e.g. android:fine_location (optional; otherwise a default set)"
        ),
        "shortcut_pin" to mapOf(
            "id" to "Shortcut's unique identifier, e.g. notes",
            "label" to "Shortcut label on the home screen",
            "intent_uri" to "Optional link (URI) that the shortcut opens"
        ),
        "widget_pin" to emptyMap(),
        "app_data_usage" to mapOf(
            "package" to "App package name, e.g. com.example.app",
            "days" to "Number of days 1–90 (default 7)"
        ),
        "network_data_usage" to mapOf(
            "days" to "Number of days 1–90 (default 7)"
        ),
        "notification_extras" to mapOf(
            "key" to "Notification key from notification_list (optional, but a key or package is required)",
            "package" to "Package name: the first active notification of this package is taken (optional)"
        ),
        "notification_snooze" to mapOf(
            "key" to "Notification key from notification_list",
            "minutes" to "How many minutes to snooze, 1–1440 (default 15)"
        ),
        "notifications_digest" to mapOf(
            "since" to "Window in minutes (e.g. 60) or a timestamp in ms; optional",
            "limit" to "How many recent notifications to show, 1–200 (default 30)"
        ),
        "notification_triage_simulate" to mapOf(
            "package" to "Source package name",
            "title" to "Notification title",
            "text" to "Notification text",
            "sender" to "Sender/chat (optional)",
            "group" to "Is this a group/broadcast?",
            "missed_call" to "Is this a missed call?"
        ),
        "notification_rules_list" to emptyMap(),
        "notification_rules_set" to mapOf(
            "package" to "App package, e.g. org.telegram.messenger",
            "importance" to "Importance: mute (no notifications), normal (normal), important (important)",
            "note" to "Note for the app rule, optional (up to 200 characters)",
            "remove" to "Delete the app rule; without package — delete all app rules",
            "quiet_start" to "Quiet hours start HH:MM, e.g. 23:00",
            "quiet_end" to "Quiet hours end HH:MM, e.g. 07:00 (may be earlier than the start — a window across midnight)",
            "quiet_off" to "Disable quiet hours",
            "quiet_allow_important" to "Allow important notifications through during quiet hours",
            "summary_mode" to "Summary mode: off (disabled), important_only (important only), all (all)"
        ),
        "sms_photo_rule_get" to emptyMap(),
        "sms_photo_rule_set" to mapOf(
            "enabled" to "Enable the SMS photo report (default false)",
            "reply_text_prefix" to "Reply prefix, e.g. «Фото: » (optional, up to 200 characters)",
            "cooldown_seconds" to "Per-number cooldown in seconds, 0..86400 (default 60)"
        ),
        "job_schedule" to mapOf(
            "interval_min" to "Period in minutes, minimum 15 (e.g. 30)",
            "requires_charging" to "Run only when charging (optional)",
            "requires_network" to "Run only when a network is available (optional)",
            "prompt" to "Request text for the agent, executed on firing",
            "notify_text" to "Notification text on firing (if no agent run is needed)"
        ),
        "job_list" to emptyMap(),
        "job_cancel" to mapOf(
            "id" to "Task id from job_list or from the job_schedule response"
        ),
        "sensor_subscribe" to mapOf(
            "type" to "Sensor name, e.g. accelerometer or light",
            "duration_ms" to "Window duration in milliseconds, 200–10000 (default 2000)",
            "rate" to "Sampling rate: normal | fast | game (default normal)"
        ),
        "sensor_unsubscribe" to emptyMap(),
        "sensor_trigger_subscribe" to mapOf(
            "type" to "Trigger sensor name, e.g. significant_motion",
            "duration_ms" to "Wait window in milliseconds, 1000–300000 (default 30000)"
        ),
        "health_sensors_list" to emptyMap(),
        "ui_gesture_multi" to mapOf(
            "cx" to "X coordinate of the gesture center in pixels",
            "cy" to "Y coordinate of the gesture center in pixels",
            "start_distance" to "Initial distance between the fingers in pixels",
            "end_distance" to "Final distance between the fingers in pixels (smaller — pinch, larger — spread)",
            "duration_ms" to "Gesture duration in milliseconds, 50–3000 (default 400)"
        ),
        "ui_trigger_subscribe" to mapOf(
            "action" to "start | stop | list",
            "limit" to "How many latest events to return, 1–200 (default 50)"
        ),
        "ui_macro_start" to mapOf(
            "name" to "Macro name"
        ),
        "ui_macro_stop" to emptyMap(),
        "ui_macro_list" to emptyMap(),
        "ui_macro_run" to mapOf(
            "id" to "Macro id from ui_macro_list (optional)",
            "name" to "Macro name (optional)"
        ),
        "ui_macro_delete" to mapOf(
            "id" to "Macro id (optional)",
            "name" to "Macro name (optional)"
        ),
        "screen_map_get" to mapOf(
            "app_package" to "App package name, e.g. com.android.chrome (optional; without it — a summary of all known apps)"
        )
    )

    private fun params8(): Map<String, Map<String, String>> = mapOf(
        "screen_map_list" to emptyMap(),
        "screen_map_forget" to mapOf(
            "app_package" to "App package name (optional; without it the entire map is cleared)"
        ),
        "skill_list" to emptyMap(),
        "skill_get" to mapOf(
            "id" to "Skill id from skill_list",
            "name" to "Skill name"
        ),
        "skill_save" to mapOf(
            "id" to "id of the existing skill to update (optional)",
            "name" to "Skill name, e.g. «Order a taxi»",
            "description" to "What the skill does (optional)",
            "appPackage" to "Target app package name, e.g. com.app (optional)",
            "steps" to "JSON array of steps (see the description)",
            "parameters" to "JSON array of parameters (see the description, optional)"
        ),
        "skill_delete" to mapOf(
            "id" to "Skill id (optional)",
            "name" to "Skill name"
        ),
        "skill_run" to mapOf(
            "id" to "Skill id from skill_list",
            "name" to "Skill name",
            "values" to "JSON object of parameter values, e.g. {\"address\":\"Lenina 1\"} (optional)",
            "dry_run" to "true — only check the plan without touching the screen (default false)"
        ),
        "skill_record_start" to mapOf(
            "appPackage" to "Target app package name, e.g. com.app (optional)"
        ),
        "skill_record_stop" to mapOf(
            "name" to "Skill name, e.g. «Order a taxi»",
            "description" to "What the skill does (optional)",
            "appPackage" to "Target app package name (optional)"
        ),
        "skill_export" to mapOf(
            "id" to "Skill id from skill_list",
            "name" to "Skill name",
            "all" to "true — export all skills (default false)"
        ),
        "skill_import" to mapOf(
            "json" to "Skill JSON or an array of skills (skill_export format)",
            "overwrite" to "true — replace existing skills (default false)"
        ),
        "fgs_start" to mapOf(
            "type" to "Foreground service type: dataSync | specialUse | shortService"
        ),
        "fgs_stop" to emptyMap(),
        "keep_alive_start" to mapOf(
            "label" to "Optional wake lock label (e.g. the task name)"
        ),
        "keep_alive_stop" to emptyMap(),
        "crash_log_list" to mapOf(
            "limit" to "How many recent entries to show (1–50, default 10)"
        ),
        "crash_log_clear" to emptyMap(),
        "self_diagnostics" to emptyMap(),
        "exit_reasons" to mapOf(
            "limit" to "How many recent entries to show (1–64, default 20)"
        ),
        "power_guard_status" to emptyMap(),
        "audit_export" to mapOf(
            "path" to "Export file path (in shared storage; optional)."
        ),
        "insights_list" to mapOf(
            "kind" to "Section filter: routine (recurring actions), stale_skill (unused skills), notification_load (notification load); optional",
            "limit" to "How many insights to show, 1–100 (default 20)"
        ),
        "number_osint" to mapOf(
            "number" to "Phone number in any format (+7…, 8…, 10 digits).",
            "force" to "Re-request, ignoring a fresh cache (default false)."
        ),
        "number_profile" to mapOf(
            "number" to "Phone number in any format."
        ),
        "auto_number_chat_status" to emptyMap(),
        "auto_number_chat_toggle" to mapOf(
            "enabled" to "true — enable interception and auto-chat; false — disable."
        ),
        "unknown_numbers_list" to mapOf(
            "limit" to "How many entries to show, 1–100 (default 50)"
        ),
        "messaging_index_config" to mapOf(
            "action" to "get — show the state (default); set — apply enabled/packages.",
            "enabled" to "For action=set: true — enable indexing and start the backfill; false — disable.",
            "packages" to "For action=set: messenger/email packages, comma-separated. Empty — return the default list."
        ),
        "messaging_index_app_add" to mapOf(
            "app" to "App name («MAX», «Telegram») or its package (org.telegram.messenger), required."
        ),
        "messaging_index_app_remove" to mapOf(
            "app" to "App name («MAX», «Telegram») or its package (org.telegram.messenger), required."
        ),
        "person_find" to mapOf(
            "query" to "What to search: a name, alias, phone, email, or nickname (required).",
            "channel" to "Optional channel to match against: SMS, CALL, MESSENGER, EMAIL, CHAT. Example: for SMS, a PHONE identity is preferred."
        ),
        "person_get" to mapOf(
            "person_id" to "Numeric person id, e.g. 12 (required).",
            "limit" to "How many recent interactions to show, 1–100 (default 10)."
        ),
        "person_link" to mapOf(
            "person_id" to "Numeric id of the person to link to (required).",
            "type" to "Identity type: PHONE, EMAIL, MESSENGER, or OTHER.",
            "value" to "The handle value: a phone number, email, or nickname.",
            "package" to "Optional app package for MESSENGER (the same nickname in different apps is a different identity).",
            "label" to "Optional caption, e.g. «work»."
        ),
        "person_merge" to mapOf(
            "person_id" to "Numeric id of the source person being deleted (required).",
            "into_person_id" to "Numeric id of the person being merged into (required)."
        ),
        "person_alias_set" to mapOf(
            "person_id" to "Numeric person id (required).",
            "alias" to "Alternative name, e.g. «Seryoga» (required)."
        ),
        "interaction_search" to mapOf(
            "query" to "Optional substring to search metadata/summary.",
            "person_id" to "Optional numeric person id.",
            "limit" to "How many entries to return, 1–200 (default 30)."
        ),
        "compose_message" to mapOf(
            "to" to "Recipient: the person's name/alias, phone number, or nickname (required).",
            "text" to "Message text (required).",
            "channel" to "Channel: AUTO (default), SMS, or MESSENGER."
        ),
        "schedule_message" to mapOf(
            "to" to "Recipient: the person's name/alias, phone number, or nickname (required).",
            "text" to "Message text (required).",
            "at" to "Send time: epoch millis (e.g. 1730000000000) or HH:MM (e.g. 09:30 — the nearest occurrence). Required.",
            "channel" to "Channel: AUTO (default), SMS, or MESSENGER.",
            "chat_id" to "Optional chat id for the delivery report; defaults to the current chat."
        ),
        "schedule_message_list" to emptyMap(),
        "schedule_message_cancel" to mapOf(
            "id" to "Delayed message id (see schedule_message_list)."
        ),
        "ssh_server_add" to mapOf(
            "name" to "Unique clear server name, e.g. vps-prod.",
            "host" to "Host address or name, e.g. example.com or 10.0.0.5.",
            "port" to "SSH port, default 22.",
            "user" to "Login user name.",
            "auth_type" to "PASSWORD (password) or KEY (private key).",
            "password" to "Password for auth_type=PASSWORD. Not stored in the database.",
            "key_ref" to "SAF URI of the private key for auth_type=KEY, e.g. content://com.android.providers.downloads.documents/document/…",
            "passphrase" to "Passphrase of the private key, if it is protected."
        ),
        "ssh_server_list" to emptyMap(),
        "ssh_server_remove" to mapOf(
            "server" to "Server name (ssh_server_list).",
            "server_id" to "Numeric server id (takes precedence over the name)."
        ),
        "ssh_server_rename" to mapOf(
            "server" to "Server name (ssh_server_list).",
            "server_id" to "Numeric server id (takes precedence over the name).",
            "name" to "New unique clear server name, e.g. vps-prod."
        ),
        "ssh_server_update" to mapOf(
            "server" to "Server name (ssh_server_list).",
            "server_id" to "Numeric server id (takes precedence over the name).",
            "host" to "New host address or name. Optional.",
            "port" to "New SSH port (1–65535). Optional.",
            "user" to "New login user name. Optional.",
            "secret" to "New password (PASSWORD) or key passphrase (KEY). Not stored in the database. Optional."
        ),
        "ssh_exec" to mapOf(
            "server" to "Server name (ssh_server_list).",
            "server_id" to "Numeric server id (takes precedence over the name).",
            "command" to "Shell command to run on the server.",
            "timeout_sec" to "Timeout, seconds (default 60, max 300).",
            "trust_host" to "true — remember the host key if it is not yet confirmed (after verifying the fingerprint). A changed key is always rejected.",
            "confirm_dangerous" to "Deprecated parameter: accepted for compatibility, but it no longer has any effect — destructive commands run without confirmation."
        ),
        "ssh_sftp_list" to mapOf(
            "server" to "Server name (ssh_server_list).",
            "server_id" to "Numeric server id (takes precedence over the name).",
            "path" to "Directory path on the server (default the home directory).",
            "limit" to "How many entries to show (default 200, maximum 1000).",
            "trust_host" to "Remember the host key if it is not yet confirmed.",
            "timeout_sec" to "Timeout, seconds (default 60, max 300)."
        ),
        "ssh_sftp_get" to mapOf(
            "server" to "Server name (ssh_server_list).",
            "server_id" to "Numeric server id (takes precedence over the name).",
            "remote" to "Path to the file on the server.",
            "local" to "Where to save (in shared storage).",
            "trust_host" to "Remember the host key if it is not yet confirmed.",
            "timeout_sec" to "Timeout, seconds (default 60, max 300)."
        ),
        "ssh_sftp_put" to mapOf(
            "server" to "Server name (ssh_server_list).",
            "server_id" to "Numeric server id (takes precedence over the name).",
            "local" to "Local file path relative to the file-tools root.",
            "remote" to "Destination path on the server.",
            "overwrite" to "true — allow overwriting an existing file on the server.",
            "trust_host" to "Remember the host key if it is not yet confirmed.",
            "timeout_sec" to "Timeout, seconds (default 60, max 300)."
        ),
        "repo_status" to mapOf(
            "server" to "Server name (ssh_server_list).",
            "server_id" to "Numeric server id (takes precedence over the name).",
            "dir" to "Repository path on the server (e.g. ~/project).",
            "short" to "true (default) — brief status with the branch (--short --branch); false — full git status.",
            "trust_host" to "Remember the host key if it is not yet confirmed.",
            "timeout_sec" to "Timeout, seconds (default 60, max 300)."
        )
    )

    private fun params9(): Map<String, Map<String, String>> = mapOf(
        "repo_diff" to mapOf(
            "server" to "Server name (ssh_server_list).",
            "server_id" to "Numeric server id (takes precedence over the name).",
            "dir" to "Repository path on the server (e.g. ~/project).",
            "staged" to "true — show staged changes (--staged); by default, show working-tree changes.",
            "path" to "Optional path inside the repository to limit the diff to one file or directory.",
            "trust_host" to "Remember the host key if it is not yet confirmed.",
            "timeout_sec" to "Timeout, seconds (default 60, max 300)."
        ),
        "repo_log" to mapOf(
            "server" to "Server name (ssh_server_list).",
            "server_id" to "Numeric server id (takes precedence over the name).",
            "dir" to "Repository path on the server (e.g. ~/project).",
            "limit" to "How many recent commits to show (default 20, maximum 100).",
            "trust_host" to "Remember the host key if it is not yet confirmed.",
            "timeout_sec" to "Timeout, seconds (default 60, max 300)."
        ),
        "repo_apply_patch" to mapOf(
            "server" to "Server name (ssh_server_list).",
            "server_id" to "Numeric server id (takes precedence over the name).",
            "dir" to "Repository path on the server (e.g. ~/project).",
            "patchPath" to "Path to the local patch file relative to the file-tools root, e.g. Downloads/fix.patch.",
            "checkOnly" to "true — only check applicability (git apply --check) and do not change files; default false.",
            "trust_host" to "Remember the host key if it is not yet confirmed.",
            "timeout_sec" to "Timeout, seconds (default 60, max 300)."
        ),
        "repo_run" to mapOf(
            "server" to "Server name (ssh_server_list).",
            "server_id" to "Numeric server id (takes precedence over the name).",
            "dir" to "Repository path on the server (e.g. ~/project).",
            "command" to "Shell command to run in the repository directory.",
            "timeoutMs" to "Timeout in milliseconds (default 60000, maximum 300000).",
            "trust_host" to "Remember the host key if it is not yet confirmed."
        ),
        "rag_index" to mapOf(
            "path" to "Path (in shared storage or /storage/emulated/0).",
            "recursive" to "Traverse the folder recursively (default false — one level only).",
            "force" to "Reindex even unchanged files (default false)."
        ),
        "rag_query" to mapOf(
            "query" to "A question or phrase to search the document content for, e.g. «what is said about the contract term»",
            "limit" to "How many chunks to return (default 8, maximum 50).",
            "path" to "Optional filter: search only files whose path starts with this string, e.g. Documents/contracts"
        ),
        "autofill_profile_set" to mapOf(
            "name" to "User's full name. An empty string clears exactly this field.",
            "phone" to "User's phone number. An empty string clears exactly this field.",
            "email" to "User's e-mail. An empty string clears exactly this field.",
            "clear" to "true — clear the entire autofill profile (name, phone, and e-mail)."
        ),
        "autofill_profile_get" to emptyMap(),
        "trusted_source_add" to mapOf(
            "type" to "Source type: sms, email, notification, web, or other.",
            "value" to "The source itself: a phone number for sms, an address for email, an app package name for notification, a domain for web, or an arbitrary label for other."
        ),
        "trusted_source_list" to emptyMap(),
        "trusted_source_remove" to mapOf(
            "type" to "Source type: sms, email, notification, web, or other.",
            "value" to "Source value (number, address, domain, package name, or label)."
        ),
        "undo_list" to mapOf(
            "limit" to "How many recent actions to show (default 20, maximum 50)."
        ),
        "undo_last" to emptyMap(),
        "mcp_server_add" to mapOf(
            "name" to "A short server name to manage it from the chat (e.g. «Weather»).",
            "url" to "Full MCP server URL (http:// or https://). Without a scheme, https:// is prepended.",
            "transport" to "Transport: auto (default), streamable_http, or sse.",
            "auth_header_name" to "Authorization header name, if the server requires a token (e.g. Authorization or X-Api-Key). For Authorization, a value without a space automatically gets the Bearer prefix.",
            "auth_header_value" to "Token/key value. Stored encrypted (Android Keystore) and is never returned in responses and never gets into the configuration file."
        ),
        "mcp_server_list" to emptyMap(),
        "mcp_server_remove" to mapOf(
            "name" to "Name (or id) of the server to delete."
        ),
        "mcp_server_test" to mapOf(
            "name" to "Name (or id) of the server to test."
        ),
        "objective_create" to mapOf(
            "title" to "A short objective title (up to 120 characters).",
            "goal" to "What exactly should be achieved in the end (up to 600 characters).",
            "steps" to "Optional plan: steps in order, each on a new line (you can also pass a JSON array of strings).",
            "chat_id" to "Optional: the chat id to bind to. Usually not needed — the current chat is used by default."
        ),
        "objective_list" to mapOf(
            "status" to "Optional filter: active, paused, done, or cancelled."
        ),
        "objective_get" to mapOf(
            "id" to "Objective id or its title."
        ),
        "objective_step_update" to mapOf(
            "objective" to "Objective: id or title (default — the active one).",
            "step" to "Step: id (e.g. «s2») or ordinal number (1, 2, …).",
            "status" to "New step status: pending, in_progress, done, or blocked.",
            "note" to "Optional short note: what was done or what is blocking."
        ),
        "objective_next" to mapOf(
            "objective" to "Objective: id or title (default — the active one)."
        ),
        "objective_finish" to mapOf(
            "objective" to "Objective: id or title (default — the active one)."
        ),
        "objective_cancel" to mapOf(
            "objective" to "Objective: id or title (default — the active one)."
        ),
        "objective_resume" to mapOf(
            "objective" to "Optional: objective id or title. By default — the last paused objective of the current chat."
        ),
        "objective_pause" to mapOf(
            "objective" to "Objective: id or title (default — the active one)."
        ),
        "objective_run" to mapOf(
            "objective" to "Optional: objective id or title. Without it, all suitable active objectives are started."
        ),
        "objective_stop" to mapOf(
            "objective" to "Optional: objective id or title. Without it, all running and waiting objectives are stopped."
        ),
        "objective_workers_status" to emptyMap(),
        "objective_concurrency_set" to mapOf(
            "max" to "Maximum number of simultaneously executing objectives: 1, 2, or 3."
        ),
        "recordings_list" to mapOf(
            "limit" to "How many recordings to return, 1–200 (default 20).",
            "status" to "Optional filter by transcription status: PENDING, RUNNING, DONE, FAILED, or SKIPPED."
        ),
        "recording_transcribe" to mapOf(
            "id" to "Recording id (required).",
            "language" to "Recognition language or auto (default auto).",
            "model" to "Recognition model: base or small (default base)."
        ),
        "transcript_get" to mapOf(
            "recording_id" to "Recording id (required)."
        ),
        "transcript_search" to mapOf(
            "query" to "What to search for in transcription texts (required).",
            "limit" to "How many matches to return, 1–100 (default 20)."
        ),
        "communication_search" to mapOf(
            "query" to "Search query (required).",
            "limit" to "How many entries to return, 1–100 (default 30)."
        ),
        "message_search" to mapOf(
            "query" to "What to search for in message texts (required).",
            "limit" to "How many hits to return, 1–100 (default 20).",
            "source" to "Source filter: sms, mms, messenger, email, chat, or all (default all).",
            "person" to "Person's name or numeric id for the filter (optional).",
            "semantic" to "Search by meaning if embeddings are available (default true); false — lexical search only."
        ),
        "messages_summarize" to mapOf(
            "topic" to "What exactly to summarize, in free text (optional).",
            "person" to "Person's name or numeric id for the filter (optional).",
            "source" to "Source filter: sms, mms, messenger, email, chat, or all (default all).",
            "window" to "Window: today, 7d, 30d, or all (default 30d).",
            "limit" to "How many messages to consider, 1–200 (default 40).",
            "save" to "Save the summary to memory (default false)."
        ),
        "person_brief" to mapOf(
            "person" to "Person's name or numeric id (required).",
            "days" to "Window in days, 1–365 (default 30)."
        ),
        "speaker_registry_list" to mapOf(
            "limit" to "How many speakers to return, 1–200 (default 50)."
        ),
        "speaker_name_assign" to mapOf(
            "speaker_id" to "Speaker id (required).",
            "name" to "Name to assign to the voice (optional).",
            "person" to "Person's name or numeric id to link to (optional).",
            "self" to "Mark the voice as «this is me» (true) or clear the mark (false)."
        ),
        "speaker_registry_forget" to mapOf(
            "speaker_id" to "Speaker id to delete (required)."
        ),
        "recordings_scan" to mapOf(
            "limit" to "How many audio files to scan per run, 1–1000 (default 200)."
        ),
        "recording_import" to mapOf(
            "path" to "Absolute path to an audio file or a content:// URI (required).",
            "transcribe" to "Transcribe the recording immediately (default true).",
            "language" to "Recognition language or auto (default auto)."
        ),
        "messenger_voice_capture" to mapOf(
            "duration_seconds" to "How many seconds to record, 5–300 (default 30).",
            "transcribe" to "Transcribe the recording immediately (default true).",
            "language" to "Recognition language or auto (default auto)."
        ),
        "commitments_extract" to mapOf(
            "window" to "Search window: today or Nd, e.g. 7d (default 7d).",
            "create" to "Save the found candidates as tasks (default false)."
        ),
        "device_search" to mapOf(
            "query" to "What to search for, in words: the meaning, object, or topic, e.g. «photo with a lock» or «lease contract».",
            "type" to "Limit the type: any (default), image (photos only), or document (documents only).",
            "limit" to "How many results to return (default 10, maximum 30)."
        ),
        "media_open" to mapOf(
            "uri" to "A content://… link or a file path to open (e.g. a uri from device_search).",
            "mime" to "MIME type, e.g. image/jpeg (optional; detected automatically by default)"
        )
    )

    private fun params10(): Map<String, Map<String, String>> = mapOf(
        "media_share" to mapOf(
            "uri" to "A content://… link or a file path to share (e.g. a uri from device_search).",
            "mime" to "Attachment MIME type (optional; detected automatically by default)",
            "text" to "Optional caption for the attachment"
        ),
        "audio_trim" to mapOf(
            "path" to "Path to the source audio file.",
            "dest" to "Path to the NEW result file (must not exist).",
            "start" to "Start of the fragment: seconds or «1:30»/«0:01:30» (default 0).",
            "end" to "End of the fragment in the same form as start (provide end or duration).",
            "duration" to "Fragment length in seconds (alternative to end).",
            "reencode" to "Re-encode the track to the dest format (default false — copy)."
        ),
        "audio_concat" to mapOf(
            "paths" to "Comma/semicolon-separated audio file paths, in join order.",
            "dest" to "Path to the NEW result file (must not exist).",
            "reencode" to "Re-encode while joining (default false — remux only)."
        ),
        "audio_convert" to mapOf(
            "path" to "Path to the source audio file.",
            "dest" to "Path to the NEW result file (its extension sets the format).",
            "codec" to "Explicit codec/format (optional; defaults to the dest extension).",
            "bitrate" to "Bitrate in kbps, e.g. 192 (optional).",
            "sample_rate" to "Sample rate in Hz, e.g. 44100 (optional)."
        ),
        "audio_volume" to mapOf(
            "path" to "Path to the source audio file.",
            "dest" to "Path to the NEW result file (must not exist).",
            "gain_db" to "Loudness change in dB, e.g. «6» or «-3».",
            "percent" to "Loudness in percent of the source, e.g. «150» (alternative to gain_db)."
        ),
        "audio_speed" to mapOf(
            "path" to "Path to the source audio file.",
            "dest" to "Path to the NEW result file (must not exist).",
            "factor" to "Speed multiplier, e.g. 1.5 (above 1 is faster)."
        ),
        "audio_extract_from_video" to mapOf(
            "path" to "Path to the source video file.",
            "dest" to "Path to the NEW audio file (the extension sets the format when reencoding).",
            "reencode" to "Re-encode the audio (default false — copy the track)."
        ),
        "audio_tag_write" to mapOf(
            "path" to "Path to the source audio file.",
            "dest" to "Path to the NEW result file (must not exist).",
            "title" to "Title.",
            "artist" to "Artist.",
            "album" to "Album.",
            "album_artist" to "Album artist.",
            "genre" to "Genre.",
            "track" to "Track number.",
            "date" to "Date/year.",
            "comment" to "Comment."
        ),
        "video_trim" to mapOf(
            "path" to "Path to the source video file.",
            "dest" to "Path to the NEW result file.",
            "start" to "Start: seconds or «1:30»/«0:01:30» (default 0).",
            "end" to "End in the same form as start (provide end or duration).",
            "duration" to "Fragment length in seconds (alternative to end).",
            "reencode" to "Re-encode for an exact cut (default false — copy)."
        ),
        "video_concat" to mapOf(
            "paths" to "Comma/semicolon-separated video file paths, in join order.",
            "dest" to "Path to the NEW result file (must not exist).",
            "reencode" to "Re-encode while joining (default false — remux only)."
        ),
        "video_resize" to mapOf(
            "path" to "Path to the source video file.",
            "dest" to "Path to the NEW result file.",
            "width" to "Width in pixels.",
            "height" to "Height in pixels.",
            "maxSide" to "Bound on the longer side, in pixels."
        ),
        "video_bitrate" to mapOf(
            "path" to "Path to the source video file.",
            "dest" to "Path to the NEW result file.",
            "video_bitrate" to "Video bitrate in kbps, e.g. 4000.",
            "audio_bitrate" to "Audio bitrate in kbps, e.g. 128 (optional)."
        ),
        "video_compress" to mapOf(
            "path" to "Path to the source video file.",
            "dest" to "Path to the NEW result file.",
            "crf" to "Quality CRF 18–32 (default 28).",
            "preset" to "x264 preset: ultrafast…veryslow (default medium).",
            "maxSide" to "Bound on the longer side, in pixels (optional)."
        ),
        "video_rotate" to mapOf(
            "path" to "Path to the source video file.",
            "dest" to "Path to the NEW result file.",
            "rotation" to "Rotation angle: 90, 180 or 270."
        ),
        "video_extract_audio" to mapOf(
            "path" to "Path to the source video file.",
            "dest" to "Path to the NEW audio file.",
            "reencode" to "Re-encode the audio (default false — copy the track)."
        ),
        "video_subtitles" to mapOf(
            "path" to "Path to the source video file.",
            "dest" to "Path to the NEW result file.",
            "srt" to "Path to the .srt subtitle file.",
            "mode" to "burn — burn in; mux — embed as a track (default mux)."
        ),
        "video_convert" to mapOf(
            "path" to "Path to the source video file.",
            "dest" to "Path to the NEW result file (its extension sets the container).",
            "reencode" to "Re-encode the tracks (default false — remux)."
        )
    )

}

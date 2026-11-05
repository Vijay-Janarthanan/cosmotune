package com.cosmotune.video.player.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.DoubleByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference

/**
 * The part of libmpv's client API the desktop engine uses (spec 6.3), mapped with JNA: the core (`client.h`) and the software
 * render API (`render.h`, `MPV_RENDER_API_TYPE_SW`). Written from mpv's headers; SimpMusic's `MpvLibrary.kt` (GPL-3.0,
 * `maxrave-dev/core`, `media/media-jvm/.../mpv/`) is the reference the plan names for the approach, and no code is copied
 * from it.
 *
 * Handles (`mpv_handle*`, `mpv_render_context*`) are opaque [Pointer]s. Strings are UTF-8 ([MpvNatives] loads the library with
 * that encoding). A `char**` command is an `Array<String>`: JNA ends the native array with the NULL mpv expects. Structures
 * mpv returns (`mpv_event` and what it points to) are read field by field at the offsets of [MpvEventLayout] instead of
 * through `Structure`, so the event loop does not allocate a structure per event.
 */
@Suppress("FunctionName", "LocalVariableName")
interface MpvLibrary : Library {

    // ---- client.h --------------------------------------------------------------------------------------------------------

    /** `unsigned long mpv_client_api_version(void)`: major in the high 16 bits (2 for libmpv 2.x). */
    fun mpv_client_api_version(): NativeLong

    /** `const char *mpv_error_string(int error)`. */
    fun mpv_error_string(error: Int): String?

    /** `mpv_handle *mpv_create(void)`. */
    fun mpv_create(): Pointer?

    /** `int mpv_initialize(mpv_handle *ctx)`. */
    fun mpv_initialize(ctx: Pointer): Int

    /** `void mpv_terminate_destroy(mpv_handle *ctx)`. */
    fun mpv_terminate_destroy(ctx: Pointer)

    /** `int mpv_set_option_string(mpv_handle *ctx, const char *name, const char *data)`; before [mpv_initialize]. */
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int

    /** `int mpv_command(mpv_handle *ctx, const char **args)`; `args` without the final NULL (JNA adds it). */
    fun mpv_command(ctx: Pointer, args: Array<String>): Int

    /** `int mpv_set_property_string(mpv_handle *ctx, const char *name, const char *data)`. */
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int

    /** `int mpv_get_property(mpv_handle *ctx, const char *name, mpv_format format, void *data)` for [MPV_FORMAT_DOUBLE]. */
    fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: DoubleByReference): Int

    /** `int mpv_get_property(...)` for [MPV_FORMAT_INT64]. */
    fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: LongByReference): Int

    /** `int mpv_get_property(...)` for [MPV_FORMAT_STRING]: `data` receives a `char*` to free with [mpv_free]. */
    fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: PointerByReference): Int

    /** `void mpv_free(void *data)`. */
    fun mpv_free(data: Pointer?)

    /** `int mpv_observe_property(mpv_handle *mpv, uint64_t reply_userdata, const char *name, mpv_format format)`. */
    fun mpv_observe_property(mpv: Pointer, reply_userdata: Long, name: String, format: Int): Int

    /** `int mpv_request_log_messages(mpv_handle *ctx, const char *min_level)`. */
    fun mpv_request_log_messages(ctx: Pointer, min_level: String): Int

    /** `mpv_event *mpv_wait_event(mpv_handle *ctx, double timeout)`: never null; `MPV_EVENT_NONE` on a timeout or wakeup. */
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer

    /** `void mpv_wakeup(mpv_handle *ctx)`: makes a waiting [mpv_wait_event] return. */
    fun mpv_wakeup(ctx: Pointer)

    // ---- render.h --------------------------------------------------------------------------------------------------------

    /** `int mpv_render_context_create(mpv_render_context **res, mpv_handle *mpv, mpv_render_param *params)`. */
    fun mpv_render_context_create(res: PointerByReference, mpv: Pointer, params: Pointer): Int

    /** `void mpv_render_context_set_update_callback(mpv_render_context *ctx, mpv_render_update_fn callback, void *callback_ctx)`. */
    fun mpv_render_context_set_update_callback(ctx: Pointer, callback: RenderUpdate?, callback_ctx: Pointer?)

    /** `uint64_t mpv_render_context_update(mpv_render_context *ctx)`: a set of `MPV_RENDER_UPDATE_*` flags. */
    fun mpv_render_context_update(ctx: Pointer): Long

    /** `int mpv_render_context_render(mpv_render_context *ctx, mpv_render_param *params)`. */
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int

    /** `void mpv_render_context_free(mpv_render_context *ctx)`; before the core is destroyed. */
    fun mpv_render_context_free(ctx: Pointer)

    /**
     * `typedef void (*mpv_render_update_fn)(void *cb_ctx)`: called on one of mpv's threads when a new frame is ready or the
     * picture must be redrawn. It must not call into mpv; it only wakes the render thread.
     */
    interface RenderUpdate : Callback {
        fun invoke(cbCtx: Pointer?)
    }

    @Suppress("unused")
    companion object {
        // mpv_format
        const val MPV_FORMAT_NONE = 0
        const val MPV_FORMAT_STRING = 1
        const val MPV_FORMAT_OSD_STRING = 2
        const val MPV_FORMAT_FLAG = 3
        const val MPV_FORMAT_INT64 = 4
        const val MPV_FORMAT_DOUBLE = 5
        const val MPV_FORMAT_NODE = 6

        // mpv_event_id
        const val MPV_EVENT_NONE = 0
        const val MPV_EVENT_SHUTDOWN = 1
        const val MPV_EVENT_LOG_MESSAGE = 2
        const val MPV_EVENT_GET_PROPERTY_REPLY = 3
        const val MPV_EVENT_SET_PROPERTY_REPLY = 4
        const val MPV_EVENT_COMMAND_REPLY = 5
        const val MPV_EVENT_START_FILE = 6
        const val MPV_EVENT_END_FILE = 7
        const val MPV_EVENT_FILE_LOADED = 8
        const val MPV_EVENT_CLIENT_MESSAGE = 16
        const val MPV_EVENT_VIDEO_RECONFIG = 17
        const val MPV_EVENT_AUDIO_RECONFIG = 18
        const val MPV_EVENT_SEEK = 20
        const val MPV_EVENT_PLAYBACK_RESTART = 21
        const val MPV_EVENT_PROPERTY_CHANGE = 22
        const val MPV_EVENT_QUEUE_OVERFLOW = 24
        const val MPV_EVENT_HOOK = 25

        // mpv_end_file_reason
        const val MPV_END_FILE_REASON_EOF = 0
        const val MPV_END_FILE_REASON_STOP = 2
        const val MPV_END_FILE_REASON_QUIT = 3
        const val MPV_END_FILE_REASON_ERROR = 4
        const val MPV_END_FILE_REASON_REDIRECT = 5

        // mpv_error (the ones the engine tells apart)
        const val MPV_ERROR_SUCCESS = 0
        const val MPV_ERROR_OPTION_NOT_FOUND = -5
        const val MPV_ERROR_LOADING_FAILED = -13
        const val MPV_ERROR_UNKNOWN_FORMAT = -17
        const val MPV_ERROR_NOTHING_TO_PLAY = -18

        // mpv_render_param_type
        const val MPV_RENDER_PARAM_INVALID = 0
        const val MPV_RENDER_PARAM_API_TYPE = 1
        const val MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME = 12
        const val MPV_RENDER_PARAM_SW_SIZE = 17
        const val MPV_RENDER_PARAM_SW_FORMAT = 18
        const val MPV_RENDER_PARAM_SW_STRIDE = 19
        const val MPV_RENDER_PARAM_SW_POINTER = 20

        /** `MPV_RENDER_API_TYPE_SW`. */
        const val MPV_RENDER_API_TYPE_SW = "sw"

        /** `MPV_RENDER_UPDATE_FRAME`: a new frame should be rendered. */
        const val MPV_RENDER_UPDATE_FRAME = 1L
    }
}

/**
 * Byte offsets of the fields of mpv's event structures for this platform's pointer size (C layout, natural alignment), read
 * with `Pointer.getInt`, `getLong` and `getPointer`.
 */
internal object MpvEventLayout {
    private val P = Native.POINTER_SIZE.toLong()

    // struct mpv_event { mpv_event_id event_id; int error; uint64_t reply_userdata; void *data; }
    const val EVENT_ID = 0L
    const val EVENT_ERROR = 4L
    const val EVENT_REPLY_USERDATA = 8L
    const val EVENT_DATA = 16L

    // struct mpv_event_property { const char *name; mpv_format format; void *data; }
    const val PROPERTY_NAME = 0L
    val PROPERTY_FORMAT = P
    val PROPERTY_DATA = 2 * P

    // struct mpv_event_log_message { const char *prefix; const char *level; const char *text; mpv_log_level log_level; }
    const val LOG_PREFIX = 0L
    val LOG_LEVEL = P
    val LOG_TEXT = 2 * P

    // struct mpv_event_end_file { mpv_end_file_reason reason; int error; int64_t playlist_entry_id; ... }
    const val END_FILE_REASON = 0L
    const val END_FILE_ERROR = 4L
    const val END_FILE_ENTRY = 8L

    // struct mpv_render_param { mpv_render_param_type type; void *data; }
    val RENDER_PARAM_SIZE = 2 * P
    val RENDER_PARAM_DATA = P
}

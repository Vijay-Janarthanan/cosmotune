# DSD transport and equalizer response

This source update adds a device-specific, opt-in **DoP** path for local, uncompressed DSF and DFF recordings. It follows the [DoP Open Standard 1.1](https://dsd-guide.com/sites/default/files/white-papers/DoP_openStandard_1v1.pdf): two chronological DSD bytes per channel in a 24-bit carrier, shared alternating `05`/`FA` markers, and a carrier rate equal to the DSD rate divided by 16. DSF channel blocks and bit order are handled explicitly. Compressed DST is refused on the DoP path; ordinary PCM conversion remains available with DoP disabled.

## Output scope

| Platform | Implemented output | Limits |
| --- | --- | --- |
| Windows | Exact 24-bit WASAPI exclusive carrier on the explicitly selected endpoint. A separate native entry point prevents older native libraries from ignoring strict mode. | Requires rebuilding the native library and a compatible DAC/driver. Device loss, exclusive loss or route changes stop playback; no default/shared fallback receives DoP frames. |
| Linux | Exact packed 24-bit ALSA `hw:` DoP carrier, or native ALSA DSD in exact 16/32-bit little- or big-endian words. Both paths check the accepted format and disable resampling. | The selected hardware must be free and support the selected format and source rate. PipeWire may own it. A failed direct DSD route stops playback. |
| macOS | Strict DoP uses the CoreAudio HAL on an explicitly selected USB DAC with one interleaved output stream and matching nonmixable, packed 24-bit physical and virtual formats. | Hardware validation is outstanding. DACs that only expose a mixable or float virtual format cannot use this path. |
| Android | Existing DSD/DST-to-PCM decoding and AudioTrack output remain available. | There is no userspace isochronous USB audio transport in this checkout. Descriptor/probe information does not enable one. No userspace USB, hardware-volume or native DSD claim. |

DoP support cannot be inferred from accepting a high-rate PCM format. The listener explicitly confirms the DAC manufacturer's DoP support for one selected endpoint in Audio output. Other endpoint selections are not approved automatically. DoP starts only with direct output, player volume 100% and speed 1×. Software EQ, loudness normalization, spatial processing, silence skipping, time stretching and crossfading are bypassed; changing volume, speed or the approved route stops playback. Control listening volume on the DAC. Replay after changing transport settings. Defaults and ordinary PCM processing are unchanged.

The pipeline distinguishes DoP framing, native ALSA DSD and DSD-to-PCM conversion. The transport labels record the format accepted by the OS. Confirm DAC output separately with a physical playback test.

Linux and macOS confirmations last for the session. Cosmotune rechecks the Linux sysfs identity or macOS CoreAudio device UID before opening the DAC. Linux confirmation is also cleared on device-topology changes.

## Shared equalizer additions

Android and desktop now display the same settled frequency-response calculation, including the chosen preamp compensation and the filters' bilinear frequency mapping. The graph names its reference sample rate and is not a recording spectrum. An optional **Full boost headroom** choice compensates the full existing estimated response peak instead of half; it defaults off, and the transient limiter remains active. Both Android deck processors receive the same compensated curve; desktop already has independent per-deck EQ state and retains it through handover.

This does not implement a convolution/impulse-response loader, independent arbitrary effect-rack editing, native ASIO/ALSA DSD formats or physical DAC volume controls. Those need separate input validation, transport/driver work and device testing; they are not represented by a superficial setting.

## Verification

The user's target hardware is a **Samsung Galaxy S24 Ultra with the AUDIOCULAR CS-Pro / CS46L41 USB-C DAC**. [ConceptKart's CS-Pro listing](https://conceptkart.com/products/audiocular-d08-cs-pro-cs46l41-typec-to-3-5mm-dac-dongle-demo-unit) markets PCM/DSD capability; that does not establish the USB audio descriptors, native DSD protocol, Android mixer route or successful DoP operation. An authorized USB descriptor/control inspection and actual playback/receiver-mode check are still required. The Android userspace-transport prerequisite above remains unchanged.

`DesktopDopSourceTest` checks all 65,536 payloads with both marker values through the actual 24-bit software writer, DSF stereo channel/bit ordering, seek parity, uncompressed DFF and rejection of truncated/DST inputs. `DesktopOutputFidelityTest` distinguishes preserved DoP from PCM conversion without claiming hardware verification. `DesktopEqualizerTest` compares the displayed response against filtered test tones, while `EqualizerResponseTest` checks explicit headroom and rate/boundary behavior. Compilation/test results must be recorded after the combined validation run; no native hardware is opened by these unit tests.

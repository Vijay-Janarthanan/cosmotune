`stereo-tone.m4a` is a synthetic three-second test signal generated for this repository:
440 Hz in the left channel, 660 Hz in the right, 48 kHz stereo AAC. No music or recordings are used.

Reproduce with FFmpeg:

```sh
ffmpeg -f lavfi -i 'aevalsrc=0.12*sin(2*PI*440*t)|0.12*sin(2*PI*660*t):s=48000:d=3' -c:a aac -b:a 48k -movflags +faststart stereo-tone.m4a
```

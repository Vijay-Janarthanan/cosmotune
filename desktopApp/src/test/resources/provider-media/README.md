These are original one-second 440 Hz test tones generated locally with FFmpeg. They contain no commercial recordings. The corresponding files under app/src/androidTest/assets/provider-media are byte-identical.

Reproduce from this directory (the fixed key and KID are public test values):

```sh
ffmpeg -f lavfi -i sine=frequency=440:sample_rate=44100:duration=1 -c:a aac -b:a 128k clear.m4a
ffmpeg -i clear.m4a -c:a copy -encryption_scheme cenc-aes-ctr -encryption_key 00112233445566778899aabbccddeeff -encryption_kid 0123456789abcdef0123456789abcdef encrypted.m4a
ffmpeg -f lavfi -i sine=frequency=440:sample_rate=44100:duration=1 -c:a flac -sample_fmt s32 clear.flac
ffmpeg -i clear.flac -c:a copy -strict -2 -encryption_scheme cenc-aes-ctr -encryption_key 00112233445566778899aabbccddeeff -encryption_kid 0123456789abcdef0123456789abcdef encrypted-flac.mp4
```

Tests use the committed fixtures; they do not require the FFmpeg executable to be installed.

# Beat model compatibility

Intel Mac uses ONNX Runtime 1.23.2, the last Maven version with macOS x86_64 natives. Its CPU `ConvInteger` kernel supports unsigned activation and weight tensors. The previous Beat This! export used signed weights for four convolution nodes and failed during session creation (`NOT_IMPLEMENTED: ConvInteger(10)`). This is unrelated to the recommendation ranker, which does not use ONNX.

`tools/convert-beat-model.py` rebases each of those four weight tensors and its scalar zero point by 128, from int8 to uint8. For every weight, `(w + 128) - (z + 128) = w - z`. Scales, activations, other graph nodes and learned parameters remain unchanged. The converter verifies exact centered-integer equality and validates the ONNX graph; it needs `onnx` and `numpy` only for offline conversion, not for the app. Running it on an already converted model makes no further changes.

The shared asset is used by Android and all desktop packages. `DesktopTargetRuntimeTest` loads it through each platform's filtered runtime and executes deterministic input through both beat and downbeat output heads. The release requires this test to pass on Windows, Linux, Intel Mac and Apple Silicon Mac.

For a seeded 256-frame spectrogram, the original and converted models produced bit-identical beat/downbeat outputs on Runtime 1.29.0. The converted model also produced bit-identical outputs on Runtime 1.23.2. The strengthened Windows JVM native test passed as well. These comparisons validate the representation change; they do not measure beat-tracking accuracy on music.

Kernel reference: [ONNX Runtime 1.23.2 ConvInteger CPU implementation](https://github.com/microsoft/onnxruntime/blob/v1.23.2/onnxruntime/core/providers/cpu/quantization/conv_integer.cc).

# Local framing models

These models run on decoded video frames locally through ONNX Runtime. Neither is used to establish video publisher identity.

| Model | Upstream file | SHA-256 | Licence |
|---|---|---|---|
| YuNet | `opencv/opencv_zoo`, `models/face_detection_yunet/face_detection_yunet_2026may.onnx` | `ebafce4e3c118d6554634be5c27ab333b4c047a9a8c3faf1d7cf93101c22f0f0` | MIT, `YuNet-LICENSE` |
| YOLOX-Nano | `Megvii-BaseDetection/YOLOX`, release `0.1.1rc0`, `yolox_nano.onnx` | `c789161ed43c8269fcd4e67c67eeeb4e80c622da2eb296a20bc6007bd18a0b7d` | Apache-2.0, `YOLOX-LICENSE` |

Sources: https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet and https://github.com/Megvii-BaseDetection/YOLOX/releases/tag/0.1.1rc0 . Retrieved 23 October 2026. Replacing either model requires rerunning detector/output-layout tests and changing the framing cache revision.

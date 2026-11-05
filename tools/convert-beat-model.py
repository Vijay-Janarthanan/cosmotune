"""Rebase ConvInteger weights for ONNX Runtime 1.23.2 (Intel Mac).

Requires numpy and onnx. Usage: python tools/convert-beat-model.py INPUT OUTPUT
The CPU kernel in 1.23.2 accepts uint8 weights only. Adding 128 to both
signed weights and their zero point preserves every centered integer exactly;
scales, activations, attention weights, and learned parameters are unchanged.
"""
import argparse
from pathlib import Path

import numpy as np
import onnx
from onnx import numpy_helper


def convert(model):
    initializers = {tensor.name: tensor for tensor in model.graph.initializer}
    converted = 0
    for node in model.graph.node:
        if node.op_type != "ConvInteger":
            continue
        weight = initializers[node.input[1]]
        if weight.data_type == onnx.TensorProto.UINT8:
            continue
        if weight.data_type != onnx.TensorProto.INT8 or len(node.input) < 4:
            raise ValueError(f"Unsupported quantization: {node.name}")
        zero = initializers[node.input[3]]
        values = numpy_helper.to_array(weight).astype(np.int16)
        offset = numpy_helper.to_array(zero).astype(np.int16)
        if offset.size != 1:
            raise ValueError(f"Expected per-tensor zero point: {node.name}")
        rebased = (values + 128).astype(np.uint8)
        rebased_zero = (offset + 128).astype(np.uint8)
        np.testing.assert_array_equal(values - offset, rebased.astype(np.int16) - rebased_zero.astype(np.int16))
        # Separate names avoid altering any other operator that shares a tensor.
        for position, array in ((1, rebased), (3, rebased_zero)):
            name = node.input[position] + "_uint8"
            model.graph.initializer.append(numpy_helper.from_array(array, name))
            node.input[position] = name
        converted += 1
    used = {name for node in model.graph.node for name in node.input}
    kept = [tensor for tensor in model.graph.initializer if tensor.name in used]
    del model.graph.initializer[:]
    model.graph.initializer.extend(kept)
    onnx.checker.check_model(model)
    return converted


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    model = onnx.load(args.input)
    count = convert(model)
    onnx.save(model, args.output)
    print(f"Rebased {count} convolutions; centered integer weights verified exactly")

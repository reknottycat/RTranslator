"""Read-only qualification of a Qualcomm precompiled Whisper pack (not an ASR test).

Usage: python inspect_whisper_pack.py PACK_DIRECTORY [--open-sessions BACKEND_LIBRARY]
Requires onnx. Session probe additionally needs a QNN-enabled onnxruntime and the
matching QAIRT HTP runtime on a compatible Qualcomm device. No fallback is hidden.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys


def inspect(root):
    import onnx
    meta = json.loads((root / 'metadata.json').read_text())
    if meta['model_id'] != 'whisper_small_quantized' or meta['runtime'] != 'precompiled_qnn_onnx':
        raise ValueError('Wrong model/runtime; do not reuse RTranslator CPU model filenames')
    result = {'model_id': meta['model_id'], 'precision': meta['precision'],
              'tool_versions': meta['tool_versions'], 'chipset_attributes': meta['chipset_attributes'],
              'inference_tested': False, 'components': {}}
    for name in ('encoder.onnx', 'decoder.onnx'):
        p = root / name
        graph = onnx.load(str(p), load_external_data=False).graph
        contexts = [n for n in graph.node if n.op_type == 'EPContext' and n.domain == 'com.microsoft']
        if not contexts:
            raise ValueError(f'{name}: no Qualcomm compiled context')
        for node in contexts:
            attrs = {a.name: onnx.helper.get_attribute_value(a) for a in node.attribute}
            ref = attrs.get('ep_cache_context', b'')
            if isinstance(ref, bytes): ref = ref.decode()
            if ref and not attrs.get('embed_mode', 0):
                target = (root / ref).resolve()
                if target.parent != root.resolve() or not target.is_file():
                    raise ValueError('Missing or unsafe external context path')
        def sig(values):
            out = {}
            for v in values:
                t = v.type.tensor_type
                shape = [d.dim_value if d.HasField('dim_value') else d.dim_param for d in t.shape.dim]
                if any(not isinstance(d, int) or d < 1 for d in shape):
                    raise ValueError('Unexpected dynamic/invalid dimension')
                out[v.name] = {'shape': shape, 'type': onnx.TensorProto.DataType.Name(t.elem_type)}
            return out
        result['components'][name] = {'inputs': sig(graph.input), 'outputs': sig(graph.output),
                'sha256': hashlib.sha256(p.read_bytes()).hexdigest(), 'compiled_contexts': len(contexts)}
    result['files'] = {p.name: {'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
                       for p in root.iterdir() if p.is_file()}
    return result


def open_sessions(root, backend):
    import onnxruntime as ort
    if 'QNNExecutionProvider' not in ort.get_available_providers():
        raise RuntimeError('QNNExecutionProvider absent: CPU is NOT a successful NPU test')
    sessions = []
    for component in ('encoder.onnx', 'decoder.onnx'):
        options = ort.SessionOptions()
        # ONNX wrappers contain Q/DQ nodes; those may stay on CPU. The compiled
        # EPContext itself cannot run on CPU, so failure cannot pretend acceleration.
        session = ort.InferenceSession(str(root/component), sess_options=options,
            providers=[('QNNExecutionProvider', {'backend_path': str(backend)})])
        session.disable_fallback()
        if 'QNNExecutionProvider' not in session.get_providers():
            raise RuntimeError('Session silently fell back: rejecting the probe')
        sessions.append(session)
    return {'session_load_only': True, 'providers': [s.get_providers() for s in sessions],
            'inference_tested': False}

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('pack', type=Path)
    parser.add_argument('--open-sessions', type=Path)
    args = parser.parse_args()
    try:
        report = inspect(args.pack)
        if args.open_sessions:
            report['session_probe'] = open_sessions(args.pack, args.open_sessions)
        print(json.dumps(report, indent=2))
    except Exception as exc:
        print(json.dumps({'ok': False, 'inference_tested': False, 'error': str(exc)}))
        sys.exit(1)

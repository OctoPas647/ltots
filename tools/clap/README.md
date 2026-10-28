# CLAP theme & album-name embeddings

`app/src/main/assets/clap_text.json` holds the text side of CLAP (Xenova/clap-htsat-unfused),
so the phone only runs the 34 MB audio model.

To change the themes or AI album names: edit `prompts.py`, put the model's `onnx/text_model.onnx`,
`tokenizer.json` and `tokenizer_config.json` one folder up, run `python3 embed.py`
(`pip install onnxruntime transformers`) and copy `clap_text.json` into the assets.
`feat.py` is the reference log-mel extractor the Kotlin `Clap.features` is tested against.

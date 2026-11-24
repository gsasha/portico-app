# Portico icon

A white portico with a lit amber doorway on deep blue (#1F4E8C). `portico.svg` is the master;
`make_icon.py` holds the geometry and generates everything else (the Android vector XMLs, PNG
previews). Regenerate with `python3 make_icon.py` (requires Pillow); outputs are written next to
the script. The generated Android XML is NOT picked up automatically: copy it by hand into
`app/src/main/res` (drawable/ and mipmap-anydpi-v26/) and commit it there.

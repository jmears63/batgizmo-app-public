# Third-party notices

Batgizmo application source code is licensed separately under the MIT License
(see [LICENSE](LICENSE)).

This document covers third-party material redistributed with Batgizmo, including
machine-learning model assets, native libraries, and spectrogram colour maps.

## Auto Id model assets (CC BY-NC-SA 4.0)

The following assets under `app/src/main/assets/ml/` are **not** MIT-licensed:

| Asset | Origin |
| --- | --- |
| `battybirdnet/BirdNET_GLOBAL_6K_V2.4_Embeddings_FP32.tflite` | Derived from BirdNET v2.4 (see below) |
| `battybirdnet/variants/*/BattyBirdNET-*.tflite` | BattyBirdNET regional classifiers |
| `battybirdnet/variants/*/labels.json` (and legacy `labels.txt`) | BattyBirdNET class labels |
| `birdnet/BirdNET_GLOBAL_6K_V2.4_MData_Model_FP16.tflite` | BirdNET v2.4 species-range (geo) model |
| `birdnet/variants/global-6k-v2.4/BirdNET_GLOBAL_6K_V2.4_Model_FP32.tflite` | BirdNET v2.4 end-to-end classifier |
| `birdnet/variants/global-6k-v2.4/labels.json` (and legacy `labels.txt`) | BirdNET v2.4 class labels |

These model/label materials are used under the
[Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License (CC BY-NC-SA 4.0)](https://creativecommons.org/licenses/by-nc-sa/4.0/).

In summary (not a substitute for the license):

- **Attribution** — credit the authors and link to the license and source projects.
- **NonCommercial** — do not use the material for commercial purposes.
- **ShareAlike** — if you adapt the material, distribute your adaptations under CC BY-NC-SA 4.0.
- Educational and research use is generally treated as non-commercial by the BirdNET project FAQ; still review the license for your situation.

### BattyBirdNET

Auto Id uses regional bat classifier weights and labels from
[BattyBirdNET-Analyzer](https://github.com/rdz-oss/BattyBirdNET-Analyzer)
by **R.D. Zinck**.

Please cite:

```bibtex
@misc{Zinck2023,
  author = {Zinck, R.D.},
  title = {BattyBirdNET - Bat Sound Analyzer},
  year = {2023},
  publisher = {GitHub},
  journal = {GitHub repository},
  howpublished = {\url{https://github.com/rdz-oss/BattyBirdNET-Analyzer}}
}
```

License stated by the upstream project: https://creativecommons.org/licenses/by-nc-sa/4.0/

### BirdNET

Auto Id can run the full BirdNET v2.4 FP32 TFLite classifier (48 kHz, 3 s
windows) as the `birdnet` family, using assets under
`app/src/main/assets/ml/birdnet/`. The companion FP16 species-range (MData)
model filters detections to species plausible at the session's location and
week when GPS is available.

BattyBirdNET classifiers are trained on embeddings from
[BirdNET](https://birdnet.cornell.edu/) / [BirdNET-Analyzer](https://github.com/birdnet-team/BirdNET-Analyzer)
(K. Lisa Yang Center for Conservation Bioacoustics at the Cornell Lab of Ornithology,
in collaboration with Chemnitz University of Technology).

BirdNET-Analyzer **source code** is MIT-licensed; BirdNET **models** are
CC BY-NC-SA 4.0.

Please cite:

```bibtex
@article{kahl2021birdnet,
  title={BirdNET: A deep learning solution for avian diversity monitoring},
  author={Kahl, Stefan and Wood, Connor M and Eibl, Maximilian and Klinck, Holger},
  journal={Ecological Informatics},
  volume={61},
  pages={101236},
  year={2021},
  publisher={Elsevier}
}
```

### Modifications in this repository

`BirdNET_GLOBAL_6K_V2.4_Embeddings_FP32.tflite` is adapted from the stock BirdNET
v2.4 FP32 TFLite model so that Android LiteRT exposes the penultimate embedding
tensor (`GLOBAL_AVG_POOL`) as the model output. No weights were retrained; only
TFLite output metadata was changed. See
`app/src/main/assets/ml/battybirdnet/notes.txt`.

That adaptation remains under CC BY-NC-SA 4.0 (ShareAlike).

## Android Open Source Project — USB descriptor parsing (Apache-2.0)

Batgizmo includes a copy of AOSP USB audio descriptor parsing classes under
`app/src/main/java/com/android/server/usb/descriptors/`, used to interpret
USB microphone / audio interface descriptors (via `UsbService`).

- **Origin:** Android Open Source Project (AOSP)
- **License:** Apache License, Version 2.0
- **Copyright:** Copyright (C) The Android Open Source Project (see per-file headers)

These files retain their upstream Apache-2.0 license headers. The full license
text is the same as [`TDigest.LICENSE`](app/src/main/cpp/TDigest.LICENSE)
(Apache License, Version 2.0), also available at
http://www.apache.org/licenses/LICENSE-2.0

## Bundled native libraries

These libraries are vendored under `app/src/main/cpp/` with their upstream
license files retained in-tree.

### TDigest (Apache-2.0)

Approximate quantile estimation used when computing the spectrogram noise
baseline (`nativeFindNoiseBaseline` in `pipeline.cpp`).

- **Implementation:** Derrick R. Burns —
  https://github.com/derrickburns/tdigest
- **Algorithm:** Ted Dunning —
  https://github.com/tdunning/t-digest
- **Vendored path:** [`TDigest.h`](app/src/main/cpp/TDigest.h)
- **Notice / license:** [`TDigest.NOTICE`](app/src/main/cpp/TDigest.NOTICE),
  [`TDigest.LICENSE`](app/src/main/cpp/TDigest.LICENSE)
  (Apache License, Version 2.0)

### r8brain-free-src (MIT)

High-quality audio sample-rate conversion used in the Auto Id / ML resample path.

- **Author:** Aleksey Vaneev (Voxengo)
- **Project:** https://github.com/avaneev/r8brain-free-src
- **Vendored path:** `app/src/main/cpp/r8brain-free-src-7.5/`
- **License file:** [`LICENSE`](app/src/main/cpp/r8brain-free-src-7.5/LICENSE)

Courtesy credit requested by the author:

> Sample rate converter designed by Aleksey Vaneev of Voxengo

```
MIT License

Copyright (c) 2013-2026 Aleksey Vaneev

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### kissfft (BSD-3-Clause)

FFT routines used in the native audio / spectrogram pipeline.

- **Author:** Mark Borgerding
- **Project:** https://github.com/mborgerding/kissfft
- **Vendored path:** `app/src/main/cpp/kissfft/`
- **License files:** [`COPYING`](app/src/main/cpp/kissfft/COPYING),
  [`LICENSES/BSD-3-Clause`](app/src/main/cpp/kissfft/LICENSES/BSD-3-Clause)

```
Copyright (c) 2003-2010 Mark Borgerding . All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
this list of conditions and the following disclaimer in the documentation
and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors
may be used to endorse or promote products derived from this software without
specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Spectrogram colour maps

CSV colour maps under `app/src/main/assets/` are used to colour spectrograms
and the amplitude graph. Attribution by family:

### CET maps (CC BY 4.0)

Assets: `cet-l03-256.csv`, `cet-l07-256.csv`, `cet-l08-256.csv`,
`cet-l09-256.csv`, `cet-l16-256.csv`, `cet-l20-256.csv`.

These are from Peter Kovesi’s CET perceptually uniform colour maps
(https://colorcet.com/), released under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

Please cite:

> Peter Kovesi. Good Colour Maps: How to Design Them.
> arXiv:1509.03700 [cs.GR], 2015.

### Inferno

Asset: `inferno-256.csv`.

The Inferno sequential colormap was designed by Stéfan van der Walt and
Nathaniel Smith and is widely distributed with
[matplotlib](https://matplotlib.org/)
(see matplotlib’s license agreements).

### Kindlmann and related maps

Assets: `kindlmann-256.csv`, `extended-kindlmann-256.csv`,
`black-body-256.csv`, `greyscale-256.csv`.

The Kindlmann map follows the luminance-corrected rainbow idea described by
Kindlmann, Reinhard, and Creem (IEEE Visualization, 2002). Extended /
black-body / greyscale variants are included as practical spectrogram presets;
treat them as third-party colour designs and retain this acknowledgment when
redistributing the CSV assets.

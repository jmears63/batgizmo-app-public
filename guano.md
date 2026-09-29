# GUANO fields written by Batgizmo

Namespace for app-specific fields: `BatGizmo|App`.

| Field | When written | Description |
| --- | --- | --- |
| `GUANO\|Version` | Always | GUANO format version (`1.0`). |
| `Timestamp` | Always | Local wall time when the WAV was opened. |
| `Samplerate` | When known | Capture sample rate (Hz) from the live USB connection. |
| `Make` | When known | USB device manufacturer name. |
| `Model` | When known | USB device product name. |
| `Loc Position` | When enabled and a fix exists | Latitude and longitude (`lat lon`), if “include location in file” is on. |
| `Species Auto ID` | When Auto Id detections overlap this WAV | Comma-separated scientific names (highest score first, max 10). Written for any trigger type when detections match the file’s audio. |
| `BatGizmo\|App\|DeviceModel` | Always | Phone/tablet manufacturer and model. |
| `BatGizmo\|App\|Version` | Always | App version name. |
| `BatGizmo\|App\|TriggerType` | First file of a sequence | How recording started (`Manual`, `AutoEnergy`, `AutoClassifier`, …). Continuation parts use `Continuation (…)` instead of the full settings set below. |
| `BatGizmo\|App\|PretriggerS` | First file | Pre-trigger duration (seconds). |
| `BatGizmo\|App\|PosttriggerS` | First file | Post-trigger duration (seconds); classifier mode uses the ceiled post-trigger. |
| `BatGizmo\|App\|MaxFileTimeS` | First file | Max file length (seconds), or `unlimited`. |
| `BatGizmo\|App\|AutoTriggerThresholddB` | Energy auto, first file | Auto-trigger threshold (dB). |
| `BatGizmo\|App\|AutoTriggerMinkHz` | Energy auto, first file | Auto-trigger frequency range lower bound (kHz). |
| `BatGizmo\|App\|AutoTriggerMaxkHz` | Energy auto, first file | Auto-trigger frequency range upper bound (kHz). |
| `BatGizmo\|App\|AutoIdModel` | Classifier auto, first file | Selected Auto Id model id. |
| `BatGizmo\|App\|AutoIdModelVersion` | Classifier auto, first file, when known | Model/pack version string (e.g. BYOM `version`). |
| `BatGizmo\|App\|ClassifierWindowS` | Classifier auto, first file, when known | Classifier analysis window length (seconds). |
| `BatGizmo\|App\|SpeciesCode` | Classifier auto, first file, when formable | Six-letter code from the first trigger’s top scientific name (also used in the filename). |

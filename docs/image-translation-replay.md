# Image Translation Replay

Corpus fixtures are synthetic and versioned under `src/test/resources/image-translation-replay/v1`.
No user-provided image or third-party OCR payload is committed.

Run the offline gates:

```powershell
.\mvnw.cmd -Dtest=ImageTranslationReplayCorpusTests,PolygonOverlayRendererTests,StructuredImageTranslationCodecTests test
```

Diagnostics are written to `target/image-translation-replay/`. To compare a private local image,
keep it outside the repository and run:

```powershell
.\scripts\replay-image-translation.ps1 -ImagePath 'C:\private\image.png'
```

The command writes `target/image-translation-replay/external-private-regions.png`. Never commit the
private image or generated output unless redistribution rights are confirmed.

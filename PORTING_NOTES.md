# it5260 Chess — porting notes

Source: github.com/peterosterlund2/droidfish, module `CuckooChessEngine/src/main/java/chess`
License: GPLv3 (COPYING file at repo root). Anything shipped based on this code must
also be GPLv3 and its source made available — that carries over to this project too.

Target device reality: 2.4" 240x320 keypad screen, ARM7, only a few MB of usable RAM,
no JIT (pure bytecode interpretation). Stockfish (native/JNI) can never run here —
only the pure-Java CuckooChess engine is a viable base, and even that needs both
code changes (CLDC 1.1 has no Collections/generics/java.security) and much smaller
search/memory settings than the Android version uses.

## Status

The `chess` package has 21 files total. Full list, updated as we go:

| File | Lines (orig) | Status | Notes |
|---|---|---|---|
| Piece.java | 54 | ✅ done | Used as-is, already CLDC-safe |
| Move.java | 107 | ✅ done | Removed `Comparator<Move>` (not in CLDC), removed `@Override` |
| UndoInfo.java | 27 | ✅ done | Used as-is, already CLDC-safe |
| Position.java | 645 | ✅ done | Replaced the `MessageDigest`/SHA-1 hash-key generator with a fixed-seed `java.util.Random` (same determinism, different but equally valid Zobrist constants); removed 2x `@Override` |
| MoveGen.java | 1069 | ✅ done | Turned out to need almost nothing: only real fix was `List<Move>` → `Vector` in one method (`filter`). A commented-out debug block using ArrayList/HashSet was left as-is since it's already dead code, nothing calls it |
| ComputerPlayer.java | 245 | ✅ done | `SecureRandom`→`Random`, `List`→`Vector` + enhanced-for→indexed loop, `String.isEmpty()`→`length()==0` (Java 6+, not in CLDC), `printf`→`println`, `assert`→`RuntimeException`, `Math.exp()`→precomputed lookup table (CLDC has no transcendental Math functions). Dropped `searchPosition()` (generic-typed, EPD-test-suite-only, unused by actual gameplay) |
| Player.java | 47 | ✅ done | Interface `ComputerPlayer` implements — same `List`→`Vector` fix |
| Evaluate.java | 1226 | ✅ done | Nearly clean already - `readTable()`: no try-with-resources in CLDC (Java 7+), rewritten as try/finally; also fixed an apparent upstream bug (byte array hardcoded to KPK-table size regardless of which table was being read). **Revised later**: ~45 calls to `Long.bitCount()` (Java 5+, not in CLDC) → `BitBoard.bitCount()`, plus 2 stray `Long.numberOfTrailingZeros()` → the `BitBoard` version the rest of the file already used |
| TranspositionTable.java | 338 | ✅ done | `List`/`ArrayList`→`Vector` (using addElement/elementAt, not add/get - CLDC's Vector predates those aliases), manual `Long`/`Integer` boxing (no autoboxing in CLDC), `StringBuilder`→`StringBuffer`, 2 array enhanced-for loops→indexed, `printStats()` debug printf simplified to println. **Also retuned the hash table size** — Android used `log2Size=15` (32768 entries, ~1MB); changed the call in ComputerPlayer.java to `10` (1024 entries, ~34KB), a conservative starting point to raise once real headroom is known |
| Search.java | 1308 | ✅ done | `ArrayList`→`Vector` (Listener interface + notifyPV). `Comparator`+`Arrays.sort(arr,cmp)` don't exist in CLDC — `SortByScore`/`SortByNodes` became plain static compare methods used by a hand-written insertion sort (`sortMoveInfo()`). Most `printf` calls here were *already* dead code inside `/* */` comments in the original; only 5 live ones (verbose-mode stats) needed `println` conversion. Second `Math.exp()` (in `weakPlaySkipMove`, the other half of the "weak/beginner" difficulty system) got the same lookup-table treatment as `ComputerPlayer.java`'s. **Open question, not yet resolved:** search time budget defaults to Android's 10s (`minTimeMillis=maxTimeMillis=10000` in ComputerPlayer.java). With no JIT and a much slower chip, this device will reach a far shallower depth in that same 10s — not broken, iterative deepening just gracefully returns whatever depth it got to, but the *right* number for this phone can only really be picked by timing it on real hardware/emulator once there's a working build |
| KillerTable.java | — | ⏳ pending | Small search-ordering helper, likely fine |
| History.java | — | ⏳ pending | Small search-ordering helper, likely fine |
| Book.java | — | ⏳ pending | Opening book — may need a much smaller book.txt |
| TextIO.java | — | ⏳ pending | FEN/move notation — needed for UI + save/load |
| BitBoard.java | — | ⏳ pending | Uses `long` bitmasks — fine, CLDC supports `long`; review for completeness |
| KillerTable.java | 80 | ✅ done | Scanned clean, used as-is |
| History.java | 81 | ✅ done | Scanned clean, used as-is |
| Book.java | 189 | ✅ done | `SecureRandom`→`Random`, `Map<Long,List<..>>`→`Hashtable`+`Vector` with manual `Long` boxing, the `List<Byte>` read loop replaced with `ByteArrayOutputStream` (avoids boxing every byte, more efficient too), try-with-resources→try/finally, `printf`→`println`, `StringBuilder`→`StringBuffer`. Confirmed book.bin doesn't need Zobrist-hash compatibility with the original engine — see file comment for why |
| TextIO.java | 604 | ✅ done | 4x `StringBuilder`→`StringBuffer`; one `java.util.Locale` import that was only being used to compute a newline character via `String.format` — replaced with a plain `"\n"` |
| BitBoard.java | 382 | ✅ done | Scanned clean initially, but **revised later**: added `bitCount()`, a hand-written popcount, after discovering `Long.bitCount()` is Java 5+ and not in CLDC (found while porting Evaluate.java/Game.java, both heavy users of it) |
| ChessParseError.java | 29 | ✅ done | Scanned clean, used as-is |
| Game.java | 566 | ✅ done | First `enum` in this codebase (`GameState`, 11 values) — flattened to plain `int` constants directly on `Game` since nothing outside this file referenced it. 3 parallel `List` fields → `Vector` (one of boxed `Boolean`). `Collections.reverse()` → manual in-place reverse loop. Several `String.format(Locale.US, "%-10s"/"%3d", ...)` calls actually needed their column padding preserved (feeds the move-list display) — added small `padLeft()`/`padRight()` helpers rather than dropping alignment. `Long.bitCount()` ×4 → `BitBoard.bitCount()`. **This is also where I found `Long.bitCount()` is Java 5+ and not in CLDC at all** — see the BitBoard.java and Evaluate.java rows above, both revised because of this |
| HumanPlayer.java | 71 | ❌ skipped, confirmed | Reads moves via `System.in`/`BufferedReader` — a desktop console input loop that has no equivalent on a keypad phone. Our own `Player` implementation for the human side will be a new class driven by the Phase 3 keypad UI, not an adaptation of this one |
| TreeLogger.java | 618 | ❌ skipped, confirmed | Search-tree debug/analysis logging tool, not used by actual gameplay, not needed on the phone build |

## The `chess` package is now fully accounted for — 21/21 files
**17 ported, 4 confirmed unnecessary** (Parameters.java, TwoReturnValues.java, HumanPlayer.java, TreeLogger.java — each with reasoning above). That's the end of Phase 1 (rules) + Phase 2 (AI). Phase 3 (keypad UI) and Phase 4 (MIDlet class + packaging) are still fully ahead — see the Phases section below.
| Parameters.java | — | ❌ skipped | Turned out to be a real dependency (Evaluate.java's piece values), not skippable as first guessed — but all 5 values it supplied default to 0 on Android anyway, so hardcoded that in Evaluate.java instead of porting a whole tunable-parameter/enum framework this phone build has no use for |
| TwoReturnValues.java | 30 | ❌ skipped | Confirmed unused — only call site was `searchPosition()`, already dropped from ComputerPlayer.java |
| HumanPlayer.java | — | ❔ maybe skip | Likely superseded by our own keypad input handling |
| TreeLogger.java | — | ❌ likely skip | Search-tree debug logging, not needed on the phone build |

Not being ported: all Android/GUI code (`DroidFishApp/*`, `CuckooChessApp/*`), the UCI
wrapper layer, Stockfish and every native engine — none of it applies to a J2ME target.
The it5260 build gets a brand-new LCDUI + keypad UI instead (Phase 3 below).

## Phases

1. Rules engine (`chess` package core) — ✅ **complete**
2. AI (Search/Evaluate/ComputerPlayer, retuned for device memory & speed) — ✅ **complete**
3. Keypad UI (LCDUI Canvas, cursor-based square selection, move list, menu) — ✅ **source complete**
4. Packaging (MIDlet class, .jad descriptor, preverify, jar build) — 🔶 **source/template complete, build itself not done**

### Phase 3, finished: ChessCanvas.java + ChessMIDlet.java

**Important difference from Phases 1-2:** those were *ports* - existing,
working CuckooChess code, adapted for CLDC and checked against the
original. ChessCanvas.java is **new code**, not a port of anything -
DroidFish's real UI is 100% Android Views/touch, none of it applies to a
keypad phone. There's no reference implementation to check this against,
so treat it as a first draft that needs real device/emulator testing, not
something pre-verified like the ported files.

What it does now: renders the 8x8 board (pieces as a disc + letter, for
contrast regardless of square color); a cursor moved with the D-pad or
2/4/6/8; fire/5 to pick up a piece and see its legal destinations
highlighted, fire/5 again to complete the move; a 1/2/3/4 prompt for pawn
promotion; a ComputerPlayer opponent (Black) that thinks on a background
thread so the UI doesn't freeze during its search, with input locked out
meanwhile; and 4 soft-key Commands - New Game, Undo (steps back a full
human+AI round trip), Resign, Difficulty (cycles 2s/5s/15s time budgets).

**Concurrency note, stated plainly rather than glossed over:** the AI's
background thread calls `game.processString()` directly instead of
handing the result back to a UI thread first. MIDP has no
`java.util.concurrent` and no strict single-UI-thread rule the way Android
does. Input is locked out for the whole AI turn, so there's no genuinely
concurrent *input*, just `paint()` possibly reading Position fields on
another thread while they're being updated at the very end of the AI's
move. Worst case for a hobby game is a single glitched frame, not a crash -
a real lock wasn't judged worth the complexity here, but it's a real
simplification, not an oversight.

**Still not included:** save/load, board-flip for playing Black, a
draw-offer UI (the engine supports draw claims - `Game.handleDrawCmd()` -
there's just no menu entry for it yet).

### First real compile against actual CLDC/MIDP jars: 7 errors, all fixed

This is exactly why compiling against the real stub jars (not a host JDK's
own libraries) matters - it caught things a manual read-through missed:

- **Search.java**: `private TreeLogger log = null;` - a field referencing
  the file I deliberately didn't port (search-tree debug logging). Every
  single use was already guarded `if (log != null) log.foo(...)`, and the
  only assignment was commented out in the original source - so `log` was
  always null anyway. Removed the field and all ~18 dead guarded calls.
- **Evaluate.java**: `throw new RuntimeException(e)` - the `Throwable`
  constructor isn't in this CLDC stub, only `RuntimeException(String)`
  (CLDC 1.1 predates Java's exception-chaining feature). Changed to
  `RuntimeException(e.toString())`.
- **Position.java**: `Long.toHexString()` - not in CLDC. Added a small
  manual hex-digit-by-digit converter for this one debug `toString()`.
- **TextIO.java**: `String.split()` and `String.replaceAll()` both need
  `java.util.regex`, which doesn't exist in CLDC at all - not even a
  reduced version. Added `splitOnSpace()`/`removeChar()` as hand-written
  replacements for the two places these were used (FEN parsing, stripping
  "=" from promotion notation).
- **BitBoard.java**: two internal `Long.bitCount()` calls that got missed
  earlier - I'd fixed every *other* file's usage when this was first found
  (while porting Game.java) but never re-checked BitBoard.java's own
  original code for the same pattern, only added the replacement method
  there. Now uses its own `bitCount()` directly (no `BitBoard.` qualifier
  needed from inside the same class).

Did a broader proactive scan afterward for other likely-too-new String/
Long/Integer methods (`toBinaryString`, `matches`, `contains(String)`,
etc.) across every file - came back clean, but since javac may not surface
every error in one pass (a type-resolution failure in one place can mask
further errors in the same file), the next run may still turn up a few
more. That would be normal, not a sign anything is fundamentally wrong.

`.github/workflows/build.yml` does the compile → preverify → package →
size-fill steps automatically on every push. This isn't guesswork - I found
gtrxAC/discord-j2me, a real, currently-distributed J2ME app (a Discord
client for feature phones), and pulled its actual build.sh/compile.sh/
midlets.pro from GitHub to see exactly what a working pipeline does:
JDK 8, `javac -source 1.2 -target 1.2 -bootclasspath <cldc+midp jars>`,
then ProGuard with `-microedition -target 1.2` as the actual preverify
step (not a separate tool - ProGuard does both preverification and
optional shrinking together). The CLDC 1.1 / MIDP 2.0 stub jars come from
Maven Central (`org.microemu:cldcapi11:2.0.4` and `:midpapi20:2.0.4` -
confirmed real, current coordinates, last published 2010 but that's
expected and fine, they're just empty method signatures for javac).

**Confidence level, stated honestly:** the compile step and the stub-jar
coordinates are verified against multiple independent real sources. The
ProGuard/preverify step follows a real project's real, working config
exactly - but I haven't run this specific workflow myself (no CLDC/MIDP
toolchain in this sandbox, as covered earlier), so "should work, built on
a proven pattern" is accurate; "guaranteed to work first try" is not. Most
likely first-run friction points, if any: ProGuard version differences,
or a MIDlet-Vendor/name detail worth personalizing.

### Getting this into a repo (Termux)

The whole project is bundled as a single zip for exactly this step. Once
it's on the phone (wherever downloads land, typically `~/storage/downloads`
if storage permission is set up, or `/sdcard/Download`):

```bash
cd ~
unzip /sdcard/Download/it5260chess-project.zip
cd it5260chess
git init
git add .
git commit -m "it5260 chess: full port, keypad UI, AI, build workflow"
gh repo create it5260chess --public --source=. --remote=origin --push
```

`gh repo create ... --source=. --remote=origin --push` creates the GitHub
repo and pushes in one step (needs `gh auth login` done once beforehand,
if not already). Without `gh`, the manual equivalent:

```bash
git branch -M main
git remote add origin https://github.com/<username>/it5260chess.git
git push -u origin main
```

Either way, the workflow in `.github/workflows/build.yml` runs
automatically on push. Once it finishes (Actions tab → the run → green
check), the `it5260chess-build` artifact contains `it5260chess.jar` and
`it5260chess.jad` - download that, and those two files are what go on the
phone (or into J2ME-Loader on the Realme first, per the earlier plan).

### What's still genuinely yours to do

The workflow automates compiling, preverifying, packaging, and filling in
the jar size - that's no longer manual. What's left:

1. Push to GitHub (commands above) and let the workflow run.
2. If it fails: read the Actions log, tell me what broke, I'll fix the
   workflow/source. This is the realistic remaining work, not a formality -
   see the honesty note above about confidence level.
3. Test the resulting `.jar` in J2ME-Loader on your Realme C25Y first.
4. Only once that's solid, try the real it5260 (microSD card is the likely
   install path for a phone like this, not yet confirmed for this exact
   model).
5. Still not built at all: save/load, board-flip, draw-offer UI (see
   Phase 3 notes above) - separate follow-up work, not blocking a first
   playable build.

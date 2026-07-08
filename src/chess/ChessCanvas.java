package chess;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * The on-phone chess board: an LCDUI Canvas with cursor-based (keypad)
 * navigation instead of DroidFish's touch UI, which doesn't exist here at
 * all - this whole file is new code, not a port of anything.
 *
 * Interaction model (the classic feature-phone chess pattern):
 *  - D-pad (or 2/4/6/8 as a fallback on phones without a proper D-pad)
 *    moves a cursor around the board.
 *  - Fire/select (or 5) on your own piece picks it up and highlights every
 *    square it can legally move to.
 *  - Fire/select again on a highlighted square completes the move; on the
 *    same square, cancels the selection; on a different own piece, switches
 *    the selection to that piece instead.
 *  - Promoting a pawn pops up a 4-way choice (1=Q 2=R 3=B 4=N).
 *  - Human plays White, ComputerPlayer plays Black, on a background thread
 *    so a multi-second search doesn't freeze the UI.
 *  - Soft-key Commands: New Game, Undo (steps back a full human+AI round
 *    trip), Resign, and Difficulty (cycles the AI's time budget).
 *
 * NOT included, and clearly flagged rather than silently missing: no
 * save/load, no board-flip for playing Black, no draw-offer UI.
 *
 * This is new, untested code - unlike the ported chess/ files, there is no
 * reference implementation to check it against, so treat it as a first
 * draft that will likely need real-device/emulator debugging.
 */
public class ChessCanvas extends Canvas implements CommandListener {
    private final Game game;

    private int cursorX = 4;
    private int cursorY = 1;
    private int selectedSquare = -1;             // -1 = nothing picked up

    private final int[] legalDestinations = new int[27]; // max legal targets for one piece (a queen has at most 27)
    private int numLegalDestinations = 0;

    private boolean awaitingPromotion = false;
    private int pendingFrom, pendingTo;

    private volatile boolean aiThinking = false;
    private Thread aiThread = null;
    private int difficultyLevel = 1; // 0=Easy 1=Medium 2=Hard - index into TIME_BUDGETS_MS
    private static final int[] TIME_BUDGETS_MS = { 2000, 5000, 15000 };
    private static final String[] DIFFICULTY_NAMES = { "Easy", "Medium", "Hard" };

    private String statusMessage = "";

    private static final int COLOR_BG        = 0x000000;
    private static final int COLOR_LIGHT_SQ  = 0xEEEED2;
    private static final int COLOR_DARK_SQ   = 0x769656;
    private static final int COLOR_CURSOR    = 0xFFEE00;
    private static final int COLOR_SELECTED  = 0x00CC33;
    private static final int COLOR_DEST_DOT  = 0x3388FF;
    private static final int COLOR_WHITE_PC  = 0xFFFFFF;
    private static final int COLOR_BLACK_PC  = 0x000000;
    private static final int COLOR_STATUS    = 0xFFFFFF;

    private final Command cmdNewGame  = new Command("New Game", Command.SCREEN, 1);
    private final Command cmdUndo     = new Command("Undo", Command.SCREEN, 2);
    private final Command cmdResign   = new Command("Resign", Command.SCREEN, 3);
    private final Command cmdDifficulty = new Command("Difficulty", Command.SCREEN, 4);

    public ChessCanvas() {
        // Both sides ComputerPlayer (cheap to construct - Book loading is
        // lazy, only happens on first actual getBookMove() call). Black's
        // instance is the one actually consulted, on its background thread,
        // once it's Black's turn - see maybeStartAiTurn().
        game = new Game(new ComputerPlayer(), new ComputerPlayer());
        applyDifficulty();
        addCommand(cmdNewGame);
        addCommand(cmdUndo);
        addCommand(cmdResign);
        addCommand(cmdDifficulty);
        setCommandListener(this);
        try {
            setFullScreenMode(true);
        } catch (Exception e) {
            // Not fatal if unsupported - just get a bit less board space.
        }
    }

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        int statusHeight = 22;
        int sq = Math.min(w, h - statusHeight) / 8;
        int boardPx = sq * 8;
        int bx = (w - boardPx) / 2;
        int by = 2;

        g.setColor(COLOR_BG);
        g.fillRect(0, 0, w, h);

        // --- squares ---
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                boolean light = ((x + y) % 2) == 1;
                g.setColor(light ? COLOR_LIGHT_SQ : COLOR_DARK_SQ);
                g.fillRect(bx + x * sq, by + (7 - y) * sq, sq, sq);
            }
        }

        // --- legal-destination dots (shown for the currently picked-up piece) ---
        for (int i = 0; i < numLegalDestinations; i++) {
            int dsq = legalDestinations[i];
            int dx = Position.getX(dsq);
            int dy = Position.getY(dsq);
            int r = sq / 6;
            g.setColor(COLOR_DEST_DOT);
            g.fillArc(bx + dx * sq + sq / 2 - r, by + (7 - dy) * sq + sq / 2 - r, r * 2, r * 2, 0, 360);
        }

        // --- pieces (drawn as a filled disc + letter, so contrast against
        //     the square color doesn't depend on font rendering alone) ---
        Font pieceFont = Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_LARGE);
        g.setFont(pieceFont);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int sqIdx = Position.getSquare(x, y);
                int p = game.pos.getPiece(sqIdx);
                if (p == Piece.EMPTY)
                    continue;
                boolean white = Piece.isWhite(p);
                int sx = bx + x * sq;
                int sy = by + (7 - y) * sq;
                int pad = Math.max(2, sq / 10);
                g.setColor(white ? COLOR_WHITE_PC : COLOR_BLACK_PC);
                g.fillArc(sx + pad, sy + pad, sq - 2 * pad, sq - 2 * pad, 0, 360);
                g.setColor(white ? COLOR_BLACK_PC : COLOR_WHITE_PC);
                char c = pieceChar(p);
                int cw = pieceFont.charWidth(c);
                g.drawChar(c, sx + sq / 2 - cw / 2, sy + sq / 2 - pieceFont.getHeight() / 2,
                           Graphics.TOP | Graphics.LEFT);
            }
        }

        // --- cursor ---
        g.setColor(COLOR_CURSOR);
        int cx = bx + cursorX * sq;
        int cy = by + (7 - cursorY) * sq;
        g.drawRect(cx + 1, cy + 1, sq - 3, sq - 3);
        g.drawRect(cx + 2, cy + 2, sq - 5, sq - 5);

        // --- selected-square outline ---
        if (selectedSquare >= 0) {
            int selX = Position.getX(selectedSquare);
            int selY = Position.getY(selectedSquare);
            g.setColor(COLOR_SELECTED);
            g.drawRect(bx + selX * sq + 1, by + (7 - selY) * sq + 1, sq - 3, sq - 3);
        }

        // --- status line ---
        g.setColor(COLOR_STATUS);
        g.setFont(Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL));
        String status = statusMessage;
        if (status.length() == 0) {
            String gs = game.getGameStateString();
            status = (gs.length() > 0) ? gs : (game.pos.whiteMove ? "White to move" : "Black to move");
        }
        g.drawString(status, w / 2, by + boardPx + 3, Graphics.TOP | Graphics.HCENTER);
    }

    private char pieceChar(int p) {
        switch (p) {
            case Piece.WKING:   case Piece.BKING:   return 'K';
            case Piece.WQUEEN:  case Piece.BQUEEN:  return 'Q';
            case Piece.WROOK:   case Piece.BROOK:   return 'R';
            case Piece.WBISHOP: case Piece.BBISHOP: return 'B';
            case Piece.WKNIGHT: case Piece.BKNIGHT: return 'N';
            case Piece.WPAWN:   case Piece.BPAWN:   return 'P';
            default: return ' ';
        }
    }

    protected void keyPressed(int keyCode) {
        if (aiThinking)
            return; // input locked out during the AI's background turn

        if (awaitingPromotion) {
            handlePromotionKey(keyCode);
            repaint();
            return;
        }

        int action;
        try {
            action = getGameAction(keyCode);
        } catch (IllegalArgumentException e) {
            action = 0;
        }

        // D-pad/game-action first, then 2/4/6/8 as a fallback for phones
        // with no true D-pad (a very common feature-phone convention).
        if (action == UP || keyCode == KEY_NUM2) {
            cursorY = Math.min(7, cursorY + 1);
        } else if (action == DOWN || keyCode == KEY_NUM8) {
            cursorY = Math.max(0, cursorY - 1);
        } else if (action == LEFT || keyCode == KEY_NUM4) {
            cursorX = Math.max(0, cursorX - 1);
        } else if (action == RIGHT || keyCode == KEY_NUM6) {
            cursorX = Math.min(7, cursorX + 1);
        } else if (action == FIRE || keyCode == KEY_NUM5) {
            onSelect();
        }
        repaint();
    }

    private void onSelect() {
        int sq = Position.getSquare(cursorX, cursorY);
        if (selectedSquare < 0) {
            int p = game.pos.getPiece(sq);
            if (p == Piece.EMPTY)
                return;
            if (Piece.isWhite(p) != game.pos.whiteMove)
                return; // not this side's piece
            selectedSquare = sq;
            computeLegalDestinations(sq);
            statusMessage = "";
        } else if (sq == selectedSquare) {
            selectedSquare = -1;
            numLegalDestinations = 0;
        } else if (isLegalDestination(sq)) {
            tryMakeMove(selectedSquare, sq);
        } else {
            int p = game.pos.getPiece(sq);
            if ((p != Piece.EMPTY) && (Piece.isWhite(p) == game.pos.whiteMove)) {
                selectedSquare = sq;
                computeLegalDestinations(sq);
            } else {
                selectedSquare = -1;
                numLegalDestinations = 0;
            }
        }
    }

    private boolean isLegalDestination(int sq) {
        for (int i = 0; i < numLegalDestinations; i++)
            if (legalDestinations[i] == sq)
                return true;
        return false;
    }

    private void computeLegalDestinations(int fromSq) {
        numLegalDestinations = 0;
        MoveGen.MoveList moves = new MoveGen().pseudoLegalMoves(game.pos);
        MoveGen.removeIllegal(game.pos, moves);
        for (int i = 0; i < moves.size; i++) {
            if (moves.m[i].from == fromSq) {
                legalDestinations[numLegalDestinations++] = moves.m[i].to;
            }
        }
    }

    private void tryMakeMove(int from, int to) {
        int p = game.pos.getPiece(from);
        boolean isPawn = (p == Piece.WPAWN) || (p == Piece.BPAWN);
        int toY = Position.getY(to);
        if (isPawn && ((toY == 7) || (toY == 0))) {
            pendingFrom = from;
            pendingTo = to;
            awaitingPromotion = true;
            statusMessage = "Promote: 1=Q 2=R 3=B 4=N";
            return;
        }
        completeMove(from, to, Piece.EMPTY);
    }

    private void handlePromotionKey(int keyCode) {
        boolean white = game.pos.whiteMove; // side to move, before the move is applied
        int promoteTo;
        switch (keyCode) {
            case KEY_NUM1: promoteTo = white ? Piece.WQUEEN  : Piece.BQUEEN;  break;
            case KEY_NUM2: promoteTo = white ? Piece.WROOK   : Piece.BROOK;   break;
            case KEY_NUM3: promoteTo = white ? Piece.WBISHOP : Piece.BBISHOP; break;
            case KEY_NUM4: promoteTo = white ? Piece.WKNIGHT : Piece.BKNIGHT; break;
            default:
                return; // ignore anything else while a promotion choice is pending
        }
        awaitingPromotion = false;
        completeMove(pendingFrom, pendingTo, promoteTo);
    }

    private void completeMove(int from, int to, int promoteTo) {
        Move m = new Move(from, to, promoteTo);
        String moveStr = TextIO.moveToUCIString(m);
        boolean ok = game.processString(moveStr);
        selectedSquare = -1;
        numLegalDestinations = 0;
        if (!ok) {
            statusMessage = "Illegal move";
            return;
        }
        // else: leave statusMessage empty so paint() shows the fresh
        // game/turn state (check, mate, whose move, etc), unless the AI
        // is about to start thinking, below.
        maybeStartAiTurn();
    }

    /** If it's now Black's turn and the game isn't over, start the AI's
     *  search on a background thread so the UI doesn't freeze. */
    private void maybeStartAiTurn() {
        if (game.getGameState() != Game.ALIVE)
            return;
        if (game.pos.whiteMove)
            return; // human (White) to move, nothing to do
        aiThinking = true;
        statusMessage = "Thinking...";
        repaint();
        aiThread = new Thread(new Runnable() {
            public void run() {
                runAiTurn();
            }
        });
        aiThread.start();
    }

    private void runAiTurn() {
        // NOTE: this runs on a background thread and calls game.processString()
        // directly rather than handing the result back to the UI thread first.
        // MIDP has no java.util.concurrent and no strict single-UI-thread rule
        // the way Android does, so this is a deliberate simplification: input
        // is locked out via aiThinking during the whole search (see
        // keyPressed()), so there's no *concurrent* mutation happening, just
        // paint() possibly reading Position fields on another thread while
        // they're briefly being updated at the very end of the AI's move.
        // Worst case for a hobby game like this is a single glitched frame,
        // never a crash - a real lock wasn't judged worth the complexity here.
        ComputerPlayer cp = (ComputerPlayer) game.blackPlayer;
        String cmd = cp.getCommand(game.pos, false, game.getHistory());
        game.processString(cmd);
        aiThinking = false;
        statusMessage = "";
        repaint();
    }

    public void commandAction(Command c, Displayable d) {
        if (aiThinking)
            return; // ignore menu presses mid-search too
        if (c == cmdNewGame) {
            game.handleCommand("new");
            selectedSquare = -1;
            numLegalDestinations = 0;
            awaitingPromotion = false;
            statusMessage = "";
        } else if (c == cmdUndo) {
            doUndo();
        } else if (c == cmdResign) {
            game.handleCommand("resign");
        } else if (c == cmdDifficulty) {
            difficultyLevel = (difficultyLevel + 1) % TIME_BUDGETS_MS.length;
            applyDifficulty();
            statusMessage = "Difficulty: " + DIFFICULTY_NAMES[difficultyLevel];
        }
        repaint();
    }

    private void applyDifficulty() {
        int t = TIME_BUDGETS_MS[difficultyLevel];
        game.whitePlayer.timeLimit(t, t, false);
        game.blackPlayer.timeLimit(t, t, false);
    }

    /** Steps back a full human+AI round trip so it's the human's turn
     *  again, rather than undoing just the AI's reply and immediately
     *  triggering another AI move. */
    private void doUndo() {
        if (!game.pos.whiteMove) {
            // Black (AI) to move means White's move hasn't been answered
            // yet - just undo that one human move.
            game.handleCommand("undo");
        } else {
            // White to move: undo White's last move and, if there was an
            // AI reply before it, undo that too.
            game.handleCommand("undo");
            game.handleCommand("undo");
        }
        selectedSquare = -1;
        numLegalDestinations = 0;
        awaitingPromotion = false;
        statusMessage = "";
    }
}
